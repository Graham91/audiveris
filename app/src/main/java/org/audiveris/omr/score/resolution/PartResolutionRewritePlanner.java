//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                     P a r t R e s o l u t i o n R e w r i t e P l a n n e r                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds rewrite-stage artifacts from sidecar assignments.
 * <p>
 * This first rewrite phase is non-destructive: it computes and records
 * planned note-level part changes and discrepancy checks for later writeback.
 */
public abstract class PartResolutionRewritePlanner
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PartResolutionRewritePlanner ()
    {
    }

    public static RewriteReport buildRewriteArtifacts (Path artifactsRoot,
                                                       int windowsWithResponses)
        throws IOException
    {
        final RewriteReport report = new RewriteReport();
        report.schemaVersion = PartResolutionContract.SCHEMA_VERSION;
        report.artifactsRoot = artifactsRoot.toString();
        report.windowsWithResponses = windowsWithResponses;

        final Path sidecarPath = artifactsRoot.resolve("part-resolution-sidecar.json");

        if (!Files.exists(sidecarPath)) {
            report.errors.add("Missing sidecar: " + sidecarPath);
            writeArtifacts(artifactsRoot, report);
            return report;
        }

        final PartResolutionSidecar sidecar = GSON.fromJson(
                Files.readString(sidecarPath),
                PartResolutionSidecar.class);

        if (sidecar == null || sidecar.notes == null) {
            report.errors.add("Sidecar content is empty.");
            writeArtifacts(artifactsRoot, report);
            return report;
        }

        report.totalNotes = sidecar.notes.size();

        for (PartResolutionSidecar.NoteRecord note : sidecar.notes.values()) {
            if (note == null || note.noteKey == null || note.noteKey.isBlank()) {
                continue;
            }

            final String chosen = trimToNull(note.chosenPart);
            final boolean ambiguous = isAmbiguous(note);

            if (chosen == null) {
                report.unresolvedNotes++;
                continue;
            }

            if (ambiguous) {
                report.ambiguousNotes++;
                continue;
            }

            report.resolvedNotes++;

            final String sourcePart = trimToNull(note.partId);

            if ((sourcePart != null) && sourcePart.equalsIgnoreCase(chosen)) {
                report.noOpAssignments++;
                continue;
            }

            final RewriteChange change = new RewriteChange();
            change.scoreId = parseScoreIdFromWindows(note.sourceWindows);
            change.noteKey = note.noteKey;
            change.sourcePartId = sourcePart;
            change.targetPartId = chosen;
            change.status = note.status;
            change.bestConfidence = bestConfidenceForChosen(note, chosen);
            change.sourceWindows = new ArrayList<>(note.sourceWindows);
            report.changes.add(change);
        }

        report.actionableChanges = report.changes.size();

        if (windowsWithResponses <= 1) {
            report.discrepancyGateMode = "informational-single-window";
            report.discrepancyGateTriggered = false;
        } else {
            report.discrepancyGateMode = "tolerant-multi-window";
            // Only block when ties are widespread (over 5% of the assigned notes)
            final int assigned = report.resolvedNotes + report.ambiguousNotes;
            report.discrepancyGateTriggered = (assigned > 0)
                    && ((report.ambiguousNotes * 20) > assigned);
        }

        writeArtifacts(artifactsRoot, report);

        return report;
    }

    private static void writeArtifacts (Path artifactsRoot,
                                        RewriteReport report)
        throws IOException
    {
        Files.writeString(artifactsRoot.resolve("rewrite-plan.json"), GSON.toJson(report));
        Files.writeString(artifactsRoot.resolve("rewrite-plan.txt"), report.toText());
    }

    static boolean isAmbiguous (PartResolutionSidecar.NoteRecord note)
    {
        if (note == null) {
            return false;
        }

        if ("ambiguous".equalsIgnoreCase(trimToEmpty(note.status))) {
            return true;
        }

        // Disagreements between windows are settled by the ingest vote and reflected in the status
        return false;
    }

    private static double bestConfidenceForChosen (PartResolutionSidecar.NoteRecord note,
                                                   String chosen)
    {
        if ((note == null) || (note.candidates == null) || note.candidates.isEmpty()
                || (chosen == null)) {
            return 0.0;
        }

        double best = 0.0;

        for (PartResolutionSidecar.Candidate c : note.candidates) {
            if ((c != null) && chosen.equals(c.partLabel)) {
                best = Math.max(best, c.confidence);
            }
        }

        return best;
    }

    private static String trimToNull (String value)
    {
        if (value == null) {
            return null;
        }

        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty (String value)
    {
        return (value == null) ? "" : value.trim();
    }

    static Integer parseScoreIdFromWindows (List<String> sourceWindows)
    {
        if (sourceWindows == null) {
            return null;
        }

        for (String windowId : sourceWindows) {
            if (windowId == null) {
                continue;
            }

            final String trimmed = windowId.trim();

            if (!trimmed.startsWith("s")) {
                continue;
            }

            final int dash = trimmed.indexOf('-');

            if (dash <= 1) {
                continue;
            }

            final String digits = trimmed.substring(1, dash);

            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                // Try next source window.
            }
        }

        return null;
    }

    public static class RewriteReport
    {
        public String schemaVersion;

        public String artifactsRoot;

        public int windowsWithResponses;

        public int totalNotes;

        public int resolvedNotes;

        public int unresolvedNotes;

        public int ambiguousNotes;

        public int noOpAssignments;

        public int actionableChanges;

        public String discrepancyGateMode;

        public boolean discrepancyGateTriggered;

        public List<RewriteChange> changes = new ArrayList<>();

        public List<String> errors = new ArrayList<>();

        public String toText ()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("schemaVersion=").append(schemaVersion).append(System.lineSeparator());
            sb.append("windowsWithResponses=").append(windowsWithResponses).append(
                    System.lineSeparator());
            sb.append("totalNotes=").append(totalNotes).append(System.lineSeparator());
            sb.append("resolvedNotes=").append(resolvedNotes).append(System.lineSeparator());
            sb.append("unresolvedNotes=").append(unresolvedNotes).append(System.lineSeparator());
            sb.append("ambiguousNotes=").append(ambiguousNotes).append(System.lineSeparator());
            sb.append("noOpAssignments=").append(noOpAssignments).append(System.lineSeparator());
            sb.append("actionableChanges=").append(actionableChanges).append(System.lineSeparator());
            sb.append("discrepancyGateMode=").append(discrepancyGateMode).append(
                    System.lineSeparator());
            sb.append("discrepancyGateTriggered=").append(discrepancyGateTriggered).append(
                    System.lineSeparator());

            if (!errors.isEmpty()) {
                sb.append("errors=").append(System.lineSeparator());

                for (String err : errors) {
                    sb.append("- ").append(err).append(System.lineSeparator());
                }
            }

            return sb.toString();
        }
    }

    public static class RewriteChange
    {
        public Integer scoreId;

        public String noteKey;

        public String sourcePartId;

        public String targetPartId;

        public String status;

        public double bestConfidence;

        public List<String> sourceWindows = new ArrayList<>();
    }
}
