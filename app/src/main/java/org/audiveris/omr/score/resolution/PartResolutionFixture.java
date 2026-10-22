//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                           P a r t R e s o l u t i o n F i x t u r e                            //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;

import org.w3c.dom.Document;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Persistent, replayable snapshot of one part-resolution run.
 * <p>
 * A fixture holds everything the XML rebuilder needs, so that the rebuilder can be re-run and
 * refined offline, without calling the AI API again:
 * <ul>
 * <li><code>fixture.json</code>: metadata (book, target labels, scores)
 * <li><code>part-resolution-sidecar.json</code>: all notes with their AI recommendations
 * <li><code>score-sNN-source.xml</code>: the original MusicXML of each score, as read by the
 * rebuilder
 * </ul>
 * Fixtures live under {@link PartResolutionSettings#getFixtureFolder()}, outside of the temp
 * folder.
 */
public abstract class PartResolutionFixture
{
    /** Metadata file name. */
    public static final String META_NAME = "fixture.json";

    /** Sidecar file name (same as in a debug run). */
    public static final String SIDECAR_NAME = "part-resolution-sidecar.json";

    /** Sub-folder receiving rebuilt outputs. */
    public static final String REBUILT_FOLDER = "rebuilt";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PartResolutionFixture ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    /**
     * Save a fixture from a live run, in the persistent fixtures folder.
     *
     * @param book          the book just processed
     * @param artifactsRoot the debug run folder holding the sidecar
     * @return the fixture folder
     * @throws Exception on failure
     */
    public static Path save (Book book,
                             Path artifactsRoot)
        throws Exception
    {
        final PartResolutionSidecar sidecar = readSidecar(artifactsRoot.resolve(SIDECAR_NAME));
        final Map<Integer, List<PartResolutionSidecar.NoteRecord>> byScore = PartResolutionRewriteApplier
                .recordsByScore(sidecar);
        final Map<Integer, Document> sources = new java.util.LinkedHashMap<>();

        for (int scoreId : byScore.keySet()) {
            for (Score score : book.getScores()) {
                if ((score.getId() != null) && (score.getId() == scoreId)) {
                    sources.put(
                            scoreId,
                            PartResolutionRewriteApplier.toDocument(
                                    PartResolutionOmrExtractor.extract(score).partwise));
                }
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("No assigned note to save in " + artifactsRoot);
        }

        final Path dir = PartResolutionSettings.getFixtureFolder().resolve(
                artifactsRoot.getFileName().toString());

        return write(dir, book.getRadix(), artifactsRoot.getFileName().toString(), sidecar, sources);
    }

    /**
     * Build a fixture from an existing debug run and the MusicXML exported from the same book.
     * <p>
     * The source must be the original (non AI-corrected) export of the book, otherwise note keys
     * will not match; the number of unmatched assignments is reported by the rebuild.
     *
     * @param runDir      existing debug run folder (with its sidecar)
     * @param sourceFile  .xml or .mxl export of the score
     * @param fixturesDir root folder receiving the fixture
     * @return the fixture folder
     * @throws Exception on failure
     */
    public static Path importRun (Path runDir,
                                  Path sourceFile,
                                  Path fixturesDir)
        throws Exception
    {
        // Re-ingest the raw responses so that a run made with older ingest rules benefits from
        // the current key repair and voting (ingestion is idempotent)
        PartResolutionMockResponseFramework.ingestResponses(
                runDir,
                readSidecar(runDir.resolve(SIDECAR_NAME)).targetPartCount);

        final PartResolutionSidecar sidecar = readSidecar(runDir.resolve(SIDECAR_NAME));
        final Map<Integer, List<PartResolutionSidecar.NoteRecord>> byScore = PartResolutionRewriteApplier
                .recordsByScore(sidecar);

        if (byScore.size() != 1) {
            throw new IllegalStateException(
                    "Import supports runs with exactly one score, found " + byScore.keySet());
        }

        final int scoreId = byScore.keySet().iterator().next();
        final Document source = readSource(sourceFile);
        final String name = runDir.getFileName().toString();

        return write(fixturesDir.resolve(name), name, name, sidecar, Map.of(scoreId, source));
    }

    /**
     * Write a fixture folder.
     *
     * @param dir       target fixture folder
     * @param book      book name
     * @param sourceRun name of the run it comes from
     * @param sidecar   full sidecar
     * @param sources   source document per score id
     * @return the fixture folder
     * @throws Exception on failure
     */
    public static Path write (Path dir,
                              String book,
                              String sourceRun,
                              PartResolutionSidecar sidecar,
                              Map<Integer, Document> sources)
        throws Exception
    {
        Files.createDirectories(dir);

        final Meta meta = new Meta();
        meta.book = book;
        meta.sourceRun = sourceRun;
        meta.savedAt = Instant.now().toString();
        meta.targetPartLabels = PartResolutionRewriteApplier.targetLabels(sidecar);

        final Map<Integer, List<PartResolutionSidecar.NoteRecord>> byScore = PartResolutionRewriteApplier
                .recordsByScore(sidecar);

        for (Map.Entry<Integer, Document> entry : sources.entrySet()) {
            final ScoreMeta sm = new ScoreMeta();
            sm.scoreId = entry.getKey();
            sm.sourceXml = "score-s" + String.format("%02d", sm.scoreId) + "-source.xml";
            sm.assignedNotes = byScore.getOrDefault(sm.scoreId, List.of()).size();
            meta.scores.add(sm);
            PartResolutionRewriteApplier.writeDocument(dir.resolve(sm.sourceXml), entry.getValue());
        }

        Files.writeString(dir.resolve(SIDECAR_NAME), GSON.toJson(sidecar));
        Files.writeString(dir.resolve(META_NAME), GSON.toJson(meta));

        return dir;
    }

    /**
     * Rebuild the corrected MusicXML of every score of a fixture, without any API call.
     *
     * @param fixtureDir fixture folder
     * @param outDir     folder receiving outputs (null: <code>rebuilt</code> inside the fixture)
     * @return the textual report
     * @throws Exception on failure
     */
    public static String rebuild (Path fixtureDir,
                                  Path outDir)
        throws Exception
    {
        final Path out = (outDir != null) ? outDir : fixtureDir.resolve(REBUILT_FOLDER);
        final Meta meta = GSON.fromJson(Files.readString(fixtureDir.resolve(META_NAME)), Meta.class);
        final PartResolutionSidecar sidecar = readSidecar(fixtureDir.resolve(SIDECAR_NAME));
        final PartResolutionMockResponseFramework.IngestReport vote =
            new PartResolutionMockResponseFramework.IngestReport();
        // Settle overlapping-window disagreements with the current voting rules
        PartResolutionMockResponseFramework.voteOnNotes(sidecar, vote);

        final List<String> labels = meta.targetPartLabels.isEmpty()
                ? PartResolutionRewriteApplier.targetLabels(sidecar) : meta.targetPartLabels;
        final Map<Integer, List<PartResolutionSidecar.NoteRecord>> byScore = PartResolutionRewriteApplier
                .recordsByScore(sidecar);

        Files.createDirectories(out);

        final StringBuilder sb = new StringBuilder();
        sb.append("Fixture ").append(fixtureDir).append(" (book ").append(meta.book).append(")\n");
        sb.append("Target parts: ").append(labels).append('\n');
        sb.append("Vote: disputedNotes=").append(vote.disputedNotes).append(", tiedNotes=")
                .append(vote.tiedNotes).append('\n');

        for (ScoreMeta sm : meta.scores) {
            final Document source;

            try (InputStream is = Files.newInputStream(fixtureDir.resolve(sm.sourceXml))) {
                source = PartResolutionRewriteApplier.readDocument(is);
            }

            final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                    source,
                    byScore.getOrDefault(sm.scoreId, List.of()),
                    labels);

            sb.append("\nScore #").append(sm.scoreId).append(": notes=").append(result.totalNotes)
                    .append(", aiAssigned=").append(result.aiAssignedNotes)
                    .append(", defaulted=").append(result.defaultedNotes)
                    .append(", moved=").append(result.movedNotes)
                    .append(", unmatchedAssignments=").append(result.unmatchedAssignments)
                    .append(", barMismatches=").append(result.barMismatches)
                    .append(", notesPerPart=").append(result.notesPerPart).append('\n');
            result.warnings.forEach(w -> sb.append("  warning: ").append(w).append('\n'));
            result.errors.forEach(e -> sb.append("  error: ").append(e).append('\n'));

            if (result.document != null) {
                final String stem = "score-s" + String.format("%02d", sm.scoreId) + "-rebuilt";
                PartResolutionRewriteApplier.writeDocument(out.resolve(stem + ".xml"), result.document);
                PartResolutionRewriteApplier.writeMxl(
                        out.resolve(stem + ".mxl"),
                        stem + ".xml",
                        result.document);
                sb.append("  written: ").append(out.resolve(stem + ".xml")).append('\n');
                sb.append("  written: ").append(out.resolve(stem + ".mxl")).append('\n');
            }
        }

        final String text = sb.toString();
        Files.writeString(out.resolve("rebuild-report.txt"), text);

        return text;
    }

    /**
     * List the fixture folders of a root, newest first.
     *
     * @param root fixtures root
     * @return fixture folders
     * @throws Exception on failure
     */
    public static List<Path> list (Path root)
        throws Exception
    {
        if (!Files.isDirectory(root)) {
            return new ArrayList<>();
        }

        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(p -> Files.exists(p.resolve(META_NAME)))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /**
     * Describe a fixture in one line.
     *
     * @param dir fixture folder
     * @return description
     * @throws Exception on failure
     */
    public static String describe (Path dir)
        throws Exception
    {
        final Meta meta = GSON.fromJson(Files.readString(dir.resolve(META_NAME)), Meta.class);
        final StringBuilder sb = new StringBuilder(dir.getFileName().toString());

        for (ScoreMeta sm : meta.scores) {
            sb.append("  score#").append(sm.scoreId).append(" assigned=").append(sm.assignedNotes);
        }

        return sb.toString();
    }

    private static PartResolutionSidecar readSidecar (Path path)
        throws Exception
    {
        final PartResolutionSidecar sidecar = GSON.fromJson(
                Files.readString(path),
                PartResolutionSidecar.class);

        if ((sidecar == null) || (sidecar.notes == null)) {
            throw new IllegalStateException("Empty sidecar: " + path);
        }

        return sidecar;
    }

    private static Document readSource (Path file)
        throws Exception
    {
        if (file.getFileName().toString().toLowerCase().endsWith(".mxl")) {
            try (ZipFile zip = new ZipFile(file.toFile())) {
                final var entries = zip.entries();

                while (entries.hasMoreElements()) {
                    final ZipEntry entry = entries.nextElement();
                    final String name = entry.getName();

                    if (!entry.isDirectory() && name.toLowerCase().endsWith(".xml")
                            && !name.startsWith("META-INF")) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            return PartResolutionRewriteApplier.readDocument(is);
                        }
                    }
                }
            }

            throw new IllegalStateException("No MusicXML entry in " + file);
        }

        try (InputStream is = Files.newInputStream(file)) {
            return PartResolutionRewriteApplier.readDocument(is);
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    /** Fixture metadata. */
    static class Meta
    {
        String schemaVersion = PartResolutionContract.SCHEMA_VERSION;

        String book;

        String sourceRun;

        String savedAt;

        List<String> targetPartLabels = new ArrayList<>();

        List<ScoreMeta> scores = new ArrayList<>();
    }

    /** Per-score metadata. */
    static class ScoreMeta
    {
        int scoreId;

        String sourceXml;

        int assignedNotes;
    }
}
