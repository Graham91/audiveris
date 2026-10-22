//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                            P a r t R e s o l u t i o n S i d e c a r                           //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized sidecar object that aggregates note identities across all windows.
 */
public class PartResolutionSidecar
{
    public String schemaVersion = PartResolutionContract.SCHEMA_VERSION;

    public String book;

    public String generatedAt;

    public int targetPartCount;

    public final List<String> targetPartLabels = new ArrayList<>();

    public final Map<String, NoteRecord> notes = new LinkedHashMap<>();

    public final Map<String, WindowRecord> windows = new LinkedHashMap<>();

    public final List<String> secondPassQueue = new ArrayList<>();

    public void registerWindow (String windowId,
                                List<String> noteKeys)
    {
        final WindowRecord window = new WindowRecord();
        window.windowId = windowId;
        window.noteKeyCount = noteKeys.size();
        window.noteKeys = new ArrayList<>(noteKeys);
        windows.put(windowId, window);
    }

    public void registerNote (NoteRecord incoming)
    {
        final NoteRecord existing = notes.get(incoming.noteKey);

        if (existing == null) {
            notes.put(incoming.noteKey, incoming);
        } else {
            mergeInto(existing, incoming);
        }
    }

    public void rebuildSecondPassQueue ()
    {
        secondPassQueue.clear();

        for (NoteRecord note : notes.values()) {
            if ((note.candidates != null) && (note.candidates.size() > 1)) {
                secondPassQueue.add(note.noteKey);
            }
        }
    }

    private void mergeInto (NoteRecord target,
                            NoteRecord source)
    {
        for (String windowId : source.sourceWindows) {
            if (!target.sourceWindows.contains(windowId)) {
                target.sourceWindows.add(windowId);
            }
        }

        for (Candidate candidate : source.candidates) {
            boolean found = false;

            for (Candidate existing : target.candidates) {
                if (existing.partLabel.equals(candidate.partLabel)
                        && existing.sourceWindow.equals(candidate.sourceWindow)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                target.candidates.add(candidate);
            }
        }
    }

    public static class NoteRecord
    {
        public String noteKey;

        public String partId;

        public String measureNumber;

        public String voice;

        public String staff;

        public String duration;

        public String pitch;

        public boolean chord;

        public boolean rest;

        public String chosenPart;

        public String status = "unresolved";

        public final List<String> sourceWindows = new ArrayList<>();

        public final List<Candidate> candidates = new ArrayList<>();
    }

    public static class Candidate
    {
        public String partLabel;

        public double confidence;

        public String sourceWindow;
    }

    public static class WindowRecord
    {
        public String windowId;

        public int noteKeyCount;

        public List<String> noteKeys = new ArrayList<>();
    }
}
