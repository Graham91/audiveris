//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                     P a r t R e s o l u t i o n R e w r i t e A p p l i e r                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.audiveris.omr.OMR;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;
import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.mxl.Mxl;
import org.audiveris.proxymusic.mxl.RootFile;
import org.audiveris.proxymusic.util.Marshalling;

import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Applies rewrite-plan changes onto cloned MusicXML and emits corrected artifacts.
 */
public abstract class PartResolutionRewriteApplier
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PartResolutionRewriteApplier ()
    {
    }

    public static RewriteApplyReport apply (Book book,
                                            Path artifactsRoot,
                                            PartResolutionRewritePlanner.RewriteReport rewritePlan)
        throws Exception
    {
        final RewriteApplyReport report = new RewriteApplyReport();
        report.schemaVersion = PartResolutionContract.SCHEMA_VERSION;
        report.artifactsRoot = artifactsRoot.toString();

        if (rewritePlan == null) {
            report.errors.add("Missing rewrite plan.");
            writeReport(artifactsRoot, report);
            return report;
        }

        report.discrepancyGateMode = rewritePlan.discrepancyGateMode;
        report.discrepancyGateTriggered = rewritePlan.discrepancyGateTriggered;
        report.plannedActionableChanges = rewritePlan.actionableChanges;

        if (rewritePlan.discrepancyGateTriggered) {
            report.skippedByGate = true;
            report.errors.add("Discrepancy gate triggered; rewrite apply skipped.");
            writeReport(artifactsRoot, report);
            return report;
        }

        final Path sidecarPath = artifactsRoot.resolve("part-resolution-sidecar.json");

        if (!Files.exists(sidecarPath)) {
            report.errors.add("Missing sidecar: " + sidecarPath);
            writeReport(artifactsRoot, report);
            return report;
        }

        final PartResolutionSidecar sidecar = GSON.fromJson(
                Files.readString(sidecarPath),
                PartResolutionSidecar.class);

        if ((sidecar == null) || (sidecar.notes == null)) {
            report.errors.add("Sidecar content is empty.");
            writeReport(artifactsRoot, report);
            return report;
        }

        final List<String> labels = targetLabels(sidecar);
        final Map<Integer, List<PartResolutionSidecar.NoteRecord>> byScore = recordsByScore(sidecar);

        if (byScore.isEmpty()) {
            writeReport(artifactsRoot, report);
            return report;
        }

        final Path outRoot = artifactsRoot.resolve("corrected");
        Files.createDirectories(outRoot);

        for (Map.Entry<Integer, List<PartResolutionSidecar.NoteRecord>> entry : byScore.entrySet()) {
            final int scoreId = entry.getKey();
            final ScoreApplyReport scoreReport = new ScoreApplyReport();
            scoreReport.scoreId = scoreId;
            scoreReport.plannedChanges = entry.getValue().size();
            report.scores.add(scoreReport);

            final Score score = findScore(book, scoreId);

            if (score == null) {
                scoreReport.errors.add("Score not found in book.");
                report.errors.add("Score #" + scoreId + " not found in book.");
                continue;
            }

            final Document source = toDocument(PartResolutionOmrExtractor.extract(score).partwise);
            final PartResolutionScoreRebuilder.Result rebuilt = PartResolutionScoreRebuilder.rebuild(
                    source,
                    entry.getValue(),
                    labels);

            scoreReport.totalNotes = rebuilt.totalNotes;
            scoreReport.aiAssignedNotes = rebuilt.aiAssignedNotes;
            scoreReport.defaultedNotes = rebuilt.defaultedNotes;
            scoreReport.applied = rebuilt.movedNotes;
            scoreReport.notesPerPart.putAll(rebuilt.notesPerPart);
            scoreReport.warnings.addAll(rebuilt.warnings);
            scoreReport.errors.addAll(rebuilt.errors);

            if (rebuilt.document == null) {
                report.errors.add("Score #" + scoreId + " not rebuilt: " + rebuilt.errors);
                continue;
            }

            final String stem = "score-s" + String.format("%02d", scoreId) + "-corrected";
            final Path scoreDir = outRoot.resolve("score-s" + String.format("%02d", scoreId));
            Files.createDirectories(scoreDir);

            final Path xmlPath = scoreDir.resolve(stem + OMR.SCORE_EXTENSION);
            final Path mxlPath = scoreDir.resolve(stem + OMR.COMPRESSED_SCORE_EXTENSION);

            writeDocument(xmlPath, rebuilt.document);
            writeMxl(mxlPath, stem + OMR.SCORE_EXTENSION, rebuilt.document);

            scoreReport.correctedXml = xmlPath.toString();
            scoreReport.correctedMxl = mxlPath.toString();
            report.totalApplied += scoreReport.applied;
        }

        writeReport(artifactsRoot, report);

        return report;
    }

    /**
     * Report the target part labels of a sidecar (P1..Pn when none is recorded).
     */
    static List<String> targetLabels (PartResolutionSidecar sidecar)
    {
        final List<String> labels = new ArrayList<>();

        if (sidecar.targetPartLabels != null) {
            labels.addAll(sidecar.targetPartLabels);
        }

        if (labels.isEmpty()) {
            for (int i = 1; i <= sidecar.targetPartCount; i++) {
                labels.add("P" + i);
            }
        }

        return labels;
    }

    /**
     * Group the records carrying an assignment per score.
     * <p>
     * Note keys only identify a note within one score, hence the grouping.
     */
    static Map<Integer, List<PartResolutionSidecar.NoteRecord>> recordsByScore (
            PartResolutionSidecar sidecar)
    {
        final Map<Integer, List<PartResolutionSidecar.NoteRecord>> byScore = new LinkedHashMap<>();

        if (sidecar.notes == null) {
            return byScore;
        }

        for (PartResolutionSidecar.NoteRecord record : sidecar.notes.values()) {
            final Integer scoreId = PartResolutionRewritePlanner.parseScoreIdFromWindows(
                    record.sourceWindows);

            if ((scoreId != null) && (trimToNull(record.chosenPart) != null)) {
                byScore.computeIfAbsent(scoreId, k -> new ArrayList<>()).add(record);
            }
        }

        return byScore;
    }

    static Document toDocument (ScorePartwise partwise)
        throws Exception
    {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Marshalling.marshal(partwise, bos, true, 2);
        final byte[] xmlBytes = bos.toByteArray();

        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setXIncludeAware(false);
        dbf.setValidating(false);
        configureSafeXmlParsing(dbf);

        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
    }

    /**
     * Parse an XML file (without loading any external DTD).
     */
    static Document readDocument (java.io.InputStream is)
        throws Exception
    {
        final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setXIncludeAware(false);
        dbf.setValidating(false);
        configureSafeXmlParsing(dbf);

        return dbf.newDocumentBuilder().parse(is);
    }

    static void writeDocument (Path xmlPath,
                                       Document doc)
        throws Exception
    {
        Files.createDirectories(xmlPath.getParent());

        try (OutputStream os = Files.newOutputStream(xmlPath)) {
            writeDocumentToStream(doc, os);
        }
    }

    static void writeMxl (Path mxlPath,
                                  String entryName,
                                  Document doc)
        throws Exception
    {
        Files.createDirectories(mxlPath.getParent());

        try (OutputStream os = Files.newOutputStream(mxlPath)) {
            final Mxl.Output mxl = new Mxl.Output(os);
            final OutputStream zos = mxl.getOutputStream();
            mxl.addEntry(new RootFile(entryName, RootFile.MUSICXML_MEDIA_TYPE));
            writeDocumentToStream(doc, zos);
            mxl.close();
        }
    }

    private static void writeDocumentToStream (Document doc,
                                               OutputStream os)
        throws Exception
    {
        final TransformerFactory tf = TransformerFactory.newInstance();
        final Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(doc), new StreamResult(os));
    }

    private static void configureSafeXmlParsing (DocumentBuilderFactory dbf)
    {
        safeSetFeature(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        safeSetFeature(dbf, "http://xml.org/sax/features/external-general-entities", false);
        safeSetFeature(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        safeSetFeature(dbf, "http://javax.xml.XMLConstants/feature/secure-processing", true);
    }

    private static void safeSetFeature (DocumentBuilderFactory dbf,
                                        String feature,
                                        boolean value)
    {
        try {
            dbf.setFeature(feature, value);
        } catch (Exception ignored) {
            // Feature availability depends on JAXP implementation.
        }
    }

    private static String trimToNull (String value)
    {
        if (value == null) {
            return null;
        }

        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Score findScore (Book book,
                                    int scoreId)
    {
        for (Score score : book.getScores()) {
            final Integer id = score.getId();

            if ((id != null) && (id == scoreId)) {
                return score;
            }
        }

        return null;
    }

    private static void writeReport (Path artifactsRoot,
                                     RewriteApplyReport report)
        throws IOException
    {
        Files.writeString(artifactsRoot.resolve("rewrite-apply-report.json"), GSON.toJson(report));
        Files.writeString(artifactsRoot.resolve("rewrite-apply-report.txt"), report.toText());
    }

    public static class RewriteApplyReport
    {
        public String schemaVersion;

        public String artifactsRoot;

        public String discrepancyGateMode;

        public boolean discrepancyGateTriggered;

        public boolean skippedByGate;

        public int plannedActionableChanges;

        public int totalApplied;

        public List<ScoreApplyReport> scores = new ArrayList<>();

        public List<String> errors = new ArrayList<>();

        public String toText ()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("schemaVersion=").append(schemaVersion).append(System.lineSeparator());
            sb.append("discrepancyGateMode=").append(discrepancyGateMode).append(
                    System.lineSeparator());
            sb.append("discrepancyGateTriggered=").append(discrepancyGateTriggered).append(
                    System.lineSeparator());
            sb.append("skippedByGate=").append(skippedByGate).append(System.lineSeparator());
            sb.append("plannedActionableChanges=").append(plannedActionableChanges).append(
                    System.lineSeparator());
            sb.append("totalApplied=").append(totalApplied).append(System.lineSeparator());

            for (ScoreApplyReport score : scores) {
                sb.append("score#").append(score.scoreId)
                        .append(": notes=").append(score.totalNotes)
                        .append(", aiAssigned=").append(score.aiAssignedNotes)
                        .append(", defaulted=").append(score.defaultedNotes)
                        .append(", moved=").append(score.applied)
                        .append(System.lineSeparator());
                sb.append("  notesPerPart=").append(score.notesPerPart).append(
                        System.lineSeparator());
                if (score.correctedXml != null) {
                    sb.append("  correctedXml=").append(score.correctedXml).append(
                            System.lineSeparator());
                }
                if (score.correctedMxl != null) {
                    sb.append("  correctedMxl=").append(score.correctedMxl).append(
                            System.lineSeparator());
                }
                if (!score.warnings.isEmpty()) {
                    sb.append("  warnings=").append(score.warnings).append(
                            System.lineSeparator());
                }
                if (!score.errors.isEmpty()) {
                    sb.append("  errors=").append(score.errors).append(System.lineSeparator());
                }
            }

            if (!errors.isEmpty()) {
                sb.append("errors=").append(System.lineSeparator());

                for (String err : errors) {
                    sb.append("- ").append(err).append(System.lineSeparator());
                }
            }

            return sb.toString();
        }
    }

    public static class ScoreApplyReport
    {
        public int scoreId;

        public int plannedChanges;

        /** Notes whose output part differs from the part they came from. */
        public int applied;

        public int totalNotes;

        public int aiAssignedNotes;

        public int defaultedNotes;

        public String correctedXml;

        public String correctedMxl;

        public Map<String, Integer> notesPerPart = new LinkedHashMap<>();

        public List<String> warnings = new ArrayList<>();

        public List<String> errors = new ArrayList<>();
    }
}
