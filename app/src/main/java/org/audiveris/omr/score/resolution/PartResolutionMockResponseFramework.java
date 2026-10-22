//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                P a r t R e s o l u t i o n M o c k R e s p o n s e F r a m e w o r k         //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline response scaffold, validator and ingester for part-resolution integration.
 */
public abstract class PartResolutionMockResponseFramework
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Confidence gap under which two equally supported parts count as a tie. */
    static final double TIE_MARGIN = 0.01;

    private PartResolutionMockResponseFramework ()
    {
    }

    public static int bootstrapTemplates (Path artifactsRoot,
                                          int targetPartCount)
        throws IOException
    {
        final Path windowsRoot = artifactsRoot.resolve("windows");

        if (!Files.isDirectory(windowsRoot)) {
            return 0;
        }

        int count = 0;

        for (Path windowDir : Files.list(windowsRoot).filter(Files::isDirectory).toList()) {
            writeTemplateForWindow(windowDir, targetPartCount);
            count++;
        }

        return count;
    }

    public static void writeTemplateForWindow (Path windowDir,
                                               int targetPartCount)
        throws IOException
    {
        final Path noteKeysPath = windowDir.resolve("note-keys.json");

        if (!Files.exists(noteKeysPath)) {
            return;
        }

        final Type listType = new TypeToken<List<String>>()
        {
        }.getType();
        final List<String> noteKeys = GSON.fromJson(Files.readString(noteKeysPath), listType);

        final MockResponse response = new MockResponse();
        response.schemaVersion = PartResolutionContract.SCHEMA_VERSION;
        response.windowId = windowDir.getFileName().toString();
        response.targetPartCount = Math.max(1, targetPartCount);

        for (String noteKey : noteKeys) {
            final Assignment a = new Assignment();
            a.noteKey = noteKey;
            a.partIndex = 1;
            a.confidence = 0.5;
            response.assignments.add(a);
        }

        Files.writeString(windowDir.resolve("response-template.json"), GSON.toJson(response));
    }

    public static ValidationResult validateResponse (Path responseJsonPath,
                                                     Path sidecarJsonPath,
                                                     int targetPartCount)
        throws IOException
    {
        final PartResolutionSidecar sidecar = GSON.fromJson(
                Files.readString(sidecarJsonPath),
                PartResolutionSidecar.class);
        final MockResponse response = GSON.fromJson(Files.readString(responseJsonPath), MockResponse.class);

        return validateResponse(response, sidecar, targetPartCount);
    }

    public static IngestReport ingestResponses (Path artifactsRoot,
                                                int targetPartCount)
        throws IOException
    {
        final IngestReport report = new IngestReport();
        final List<AppliedAssignment> appliedAssignments = new ArrayList<>();
        final Path sidecarPath = artifactsRoot.resolve("part-resolution-sidecar.json");

        if (!Files.exists(sidecarPath)) {
            report.errors.add("Missing sidecar: " + sidecarPath);
            return report;
        }

        final Path windowsRoot = artifactsRoot.resolve("windows");

        if (!Files.isDirectory(windowsRoot)) {
            report.errors.add("Missing windows folder: " + windowsRoot);
            return report;
        }

        final PartResolutionSidecar sidecar = GSON.fromJson(
                Files.readString(sidecarPath),
                PartResolutionSidecar.class);

        final List<Path> windowDirs = Files.list(windowsRoot).filter(Files::isDirectory).sorted(
                Comparator.comparing(p -> p.getFileName().toString())).toList();
        report.windowsSeen = windowDirs.size();

        for (Path windowDir : windowDirs) {
            final Path responsePath = windowDir.resolve("response.json");

            if (!Files.exists(responsePath)) {
                continue;
            }

            report.windowsWithResponses++;
            final MockResponse response = GSON.fromJson(Files.readString(responsePath), MockResponse.class);
            final String windowName = windowDir.getFileName().toString();

            for (String msg : repairNoteKeys(response, sidecar, windowName)) {
                report.keysRepaired++;
                report.warnings.add(windowName + ": " + msg);
            }

            final ValidationResult vr = validateResponse(response, sidecar, targetPartCount);

            for (String warn : vr.warnings) {
                report.warnings.add(windowName + ": " + warn);
            }

            if (!vr.valid) {
                report.windowsRejected++;

                for (String err : vr.errors) {
                    report.errors.add(windowDir.getFileName() + ": " + err);
                }

                continue;
            }

            final String sourceWindow = (response.windowId != null && !response.windowId.isBlank())
                    ? response.windowId : windowDir.getFileName().toString();

            for (Assignment a : response.assignments) {
                final PartResolutionSidecar.NoteRecord note = sidecar.notes.get(a.noteKey);

                if (note == null) {
                    continue;
                }

                final String partLabel = resolvePartLabel(sidecar, a.partIndex);
                upsertCandidate(note, partLabel, a.confidence, sourceWindow);

                final String priorChosenPart = note.chosenPart;
                final String sourcePartId = note.partId;

                if ((note.chosenPart == null) || note.chosenPart.isBlank()) {
                    note.chosenPart = partLabel;
                    note.status = "resolved";
                }
                // Disagreements between overlapping windows are settled by voteOnNotes()

                final AppliedAssignment applied = new AppliedAssignment();
                applied.windowId = sourceWindow;
                applied.noteKey = a.noteKey;
                applied.sourcePartId = sourcePartId;
                applied.chosenPartBefore = priorChosenPart;
                applied.chosenPartAfter = note.chosenPart;
                applied.partIndex = a.partIndex;
                applied.confidence = a.confidence;
                applied.statusAfter = note.status;
                appliedAssignments.add(applied);

                report.notesTouched++;

                if ("resolved".equals(note.status)) {
                    report.notesResolved++;
                }

                if ((sourcePartId != null) && !sourcePartId.isBlank()
                        && (note.chosenPart != null) && !note.chosenPart.isBlank()
                        && !sourcePartId.equalsIgnoreCase(note.chosenPart)) {
                    report.partChangesFromSource++;
                }

                report.assignmentsApplied++;
            }

            report.windowsAccepted++;
        }

        voteOnNotes(sidecar, report);
        sidecar.rebuildSecondPassQueue();
        report.secondPassQueueSize = sidecar.secondPassQueue.size();
        Files.writeString(sidecarPath, GSON.toJson(sidecar));

        Files.writeString(artifactsRoot.resolve("response-ingest-report.json"), GSON.toJson(report));
        Files.writeString(artifactsRoot.resolve("response-ingest-report.txt"), report.toText());
        Files.writeString(
            artifactsRoot.resolve("applied-assignments.json"),
            GSON.toJson(appliedAssignments));

        return report;
    }

    private static ValidationResult validateResponse (MockResponse response,
                                                      PartResolutionSidecar sidecar,
                                                      int targetPartCount)
    {
        final ValidationResult vr = new ValidationResult();

        if (response == null) {
            vr.errors.add("Response is empty or invalid JSON.");
            return vr;
        }

        if (response.assignments == null) {
            vr.errors.add("Response has no assignments array.");
            return vr;
        }

        final Set<String> knownKeys = new LinkedHashSet<>(sidecar.notes.keySet());
        final int maxPart = Math.max(1, targetPartCount);
        int unknown = 0;

        for (Assignment a : response.assignments) {
            if ((a.noteKey == null) || a.noteKey.isBlank()) {
                vr.errors.add("Assignment has blank noteKey.");
                continue;
            }

            if (!knownKeys.contains(a.noteKey)) {
                unknown++;
                vr.warnings.add("Unknown noteKey ignored: " + a.noteKey);
                continue;
            }

            if ((a.partIndex < 1) || (a.partIndex > maxPart)) {
                vr.errors.add("Out-of-range partIndex for " + a.noteKey + ": " + a.partIndex);
            }

            if ((a.confidence < 0.0) || (a.confidence > 1.0)) {
                vr.errors.add("Out-of-range confidence for " + a.noteKey + ": " + a.confidence);
            }
        }

        // A few unusable keys must not throw away a whole window, but mostly-wrong output is rejected
        if ((unknown > 0) && ((unknown * 2) > response.assignments.size())) {
            vr.errors.add(
                    unknown + " of " + response.assignments.size() + " noteKeys are unknown.");
        }

        vr.valid = vr.errors.isEmpty();
        return vr;
    }

    /**
     * Fix keys the model altered slightly (typically the <code>d=</code> duration field).
     * <p>
     * An unknown key is replaced by the one known key of the same window that matches it once the
     * duration field is ignored. Ambiguous or absent matches are left untouched.
     *
     * @return one message per repaired key
     */
    static List<String> repairNoteKeys (MockResponse response,
                                        PartResolutionSidecar sidecar,
                                        String windowName)
    {
        final List<String> messages = new ArrayList<>();

        if ((response == null) || (response.assignments == null)) {
            return messages;
        }

        final String windowId = ((response.windowId != null) && !response.windowId.isBlank())
                ? response.windowId : windowName;
        final PartResolutionSidecar.WindowRecord window = (sidecar.windows != null)
                ? sidecar.windows.get(windowId) : null;
        final java.util.Collection<String> candidates = ((window != null) && (window.noteKeys != null)
                && !window.noteKeys.isEmpty()) ? window.noteKeys : sidecar.notes.keySet();

        final java.util.Map<String, List<String>> byLooseKey = new java.util.HashMap<>();

        for (String key : candidates) {
            byLooseKey.computeIfAbsent(looseKey(key), k -> new ArrayList<>()).add(key);
        }

        for (Assignment a : response.assignments) {
            if ((a.noteKey == null) || sidecar.notes.containsKey(a.noteKey)) {
                continue;
            }

            final List<String> matches = byLooseKey.get(looseKey(a.noteKey));

            if ((matches != null) && (matches.size() == 1)) {
                messages.add("Repaired noteKey " + a.noteKey + " -> " + matches.get(0));
                a.noteKey = matches.get(0);
            }
        }

        return messages;
    }

    /**
     * Settle each note from the candidates of all the windows that saw it.
     * <p>
     * The part proposed by most windows wins; on equal counts the greater summed confidence wins.
     * Only a practical tie (confidence gap below {@link #TIE_MARGIN}) leaves the note ambiguous.
     */
    static void voteOnNotes (PartResolutionSidecar sidecar,
                             IngestReport report)
    {
        report.notesResolved = 0;

        for (PartResolutionSidecar.NoteRecord note : sidecar.notes.values()) {
            if ((note.candidates == null) || note.candidates.isEmpty()) {
                continue;
            }

            final java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
            final java.util.Map<String, double[]> sums = new java.util.LinkedHashMap<>();

            for (PartResolutionSidecar.Candidate c : note.candidates) {
                if ((c == null) || (c.partLabel == null)) {
                    continue;
                }

                counts.computeIfAbsent(c.partLabel, k -> new int[1])[0]++;
                sums.computeIfAbsent(c.partLabel, k -> new double[1])[0] += c.confidence;
            }

            String best = null;
            String second = null;

            for (String label : counts.keySet()) {
                if ((best == null) || beats(label, best, counts, sums)) {
                    second = best;
                    best = label;
                } else if ((second == null) || beats(label, second, counts, sums)) {
                    second = label;
                }
            }

            if (best == null) {
                continue;
            }

            note.chosenPart = best;

            if (second == null) {
                note.status = "resolved";
            } else {
                report.disputedNotes++;
                final boolean sameCount = counts.get(best)[0] == counts.get(second)[0];
                final double gap = sums.get(best)[0] - sums.get(second)[0];

                if (sameCount && (Math.abs(gap) < TIE_MARGIN)) {
                    note.status = "ambiguous";
                    report.tiedNotes++;
                } else {
                    note.status = "resolved";
                }
            }

            if ("resolved".equals(note.status)) {
                report.notesResolved++;
            }
        }
    }

    private static boolean beats (String a,
                                  String b,
                                  java.util.Map<String, int[]> counts,
                                  java.util.Map<String, double[]> sums)
    {
        final int ca = counts.get(a)[0];
        final int cb = counts.get(b)[0];

        if (ca != cb) {
            return ca > cb;
        }

        return sums.get(a)[0] > sums.get(b)[0];
    }

    /** Note key without its duration and voice fields (the model alters them now and then). */
    private static String looseKey (String key)
    {
        return key.replaceFirst("\\|v=[^|]*", "").replaceFirst("\\|d=[^|]*", "");
    }

    private static String resolvePartLabel (PartResolutionSidecar sidecar,
                                            int partIndex)
    {
        if ((sidecar.targetPartLabels != null) && (partIndex >= 1)
                && (partIndex <= sidecar.targetPartLabels.size())) {
            return sidecar.targetPartLabels.get(partIndex - 1);
        }

        return "P" + partIndex;
    }

    private static void upsertCandidate (PartResolutionSidecar.NoteRecord note,
                                         String partLabel,
                                         double confidence,
                                         String sourceWindow)
    {
        for (PartResolutionSidecar.Candidate c : note.candidates) {
            if (partLabel.equals(c.partLabel) && sourceWindow.equals(c.sourceWindow)) {
                c.confidence = confidence;
                return;
            }
        }

        final PartResolutionSidecar.Candidate candidate = new PartResolutionSidecar.Candidate();
        candidate.partLabel = partLabel;
        candidate.confidence = confidence;
        candidate.sourceWindow = sourceWindow;
        note.candidates.add(candidate);
    }

    public static class MockResponse
    {
        public String schemaVersion;

        public String windowId;

        public int targetPartCount;

        public List<Assignment> assignments = new ArrayList<>();
    }

    public static class Assignment
    {
        public String noteKey;

        public int partIndex;

        public double confidence;
    }

    public static class ValidationResult
    {
        public boolean valid;

        public List<String> errors = new ArrayList<>();

        public List<String> warnings = new ArrayList<>();
    }

    public static class IngestReport
    {
        public int windowsSeen;

        public int windowsWithResponses;

        public int windowsAccepted;

        public int windowsRejected;

        public int assignmentsApplied;

        public int notesTouched;

        public int notesResolved;

        public int partChangesFromSource;

        public int secondPassQueueSize;

        public int keysRepaired;

        public int disputedNotes;

        public int tiedNotes;

        public List<String> warnings = new ArrayList<>();

        public List<String> errors = new ArrayList<>();

        public String toText ()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("windowsSeen=").append(windowsSeen).append(System.lineSeparator());
            sb.append("windowsWithResponses=").append(windowsWithResponses).append(
                    System.lineSeparator());
            sb.append("windowsAccepted=").append(windowsAccepted).append(System.lineSeparator());
            sb.append("windowsRejected=").append(windowsRejected).append(System.lineSeparator());
            sb.append("assignmentsApplied=").append(assignmentsApplied).append(System.lineSeparator());
                sb.append("notesTouched=").append(notesTouched).append(System.lineSeparator());
                sb.append("notesResolved=").append(notesResolved).append(System.lineSeparator());
                sb.append("partChangesFromSource=").append(partChangesFromSource).append(
                    System.lineSeparator());
            sb.append("secondPassQueueSize=").append(secondPassQueueSize).append(
                    System.lineSeparator());

            sb.append("keysRepaired=").append(keysRepaired).append(System.lineSeparator());
            sb.append("disputedNotes=").append(disputedNotes).append(System.lineSeparator());
            sb.append("tiedNotes=").append(tiedNotes).append(System.lineSeparator());

            if (!warnings.isEmpty()) {
                sb.append("warnings=").append(System.lineSeparator());

                for (String warn : warnings) {
                    sb.append("- ").append(warn).append(System.lineSeparator());
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

    public static class AppliedAssignment
    {
        public String windowId;

        public String noteKey;

        public String sourcePartId;

        public String chosenPartBefore;

        public String chosenPartAfter;

        public int partIndex;

        public double confidence;

        public String statusAfter;
    }
}
