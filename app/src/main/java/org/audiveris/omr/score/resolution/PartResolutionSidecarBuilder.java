//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                      P a r t R e s o l u t i o n S i d e c a r B u i l d e r                   //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the notes of a window in the sidecar.
 */
public abstract class PartResolutionSidecarBuilder
{
    private PartResolutionSidecarBuilder ()
    {
    }

    /**
     * Register the OMR notes of one window.
     * <p>
     * The note key is the OMR note id, which the AI cannot alter by accident and which also
     * tags the matching note of the exported MusicXML.
     *
     * @param sidecar  the sidecar to update
     * @param notes    the OMR notes of the window
     * @param windowId the window id
     * @return the note keys of the window
     */
    public static List<String> registerWindowNotes (PartResolutionSidecar sidecar,
                                                    List<PartResolutionOmrNote> notes,
                                                    String windowId)
    {
        final List<String> noteKeys = new ArrayList<>();

        for (PartResolutionOmrNote n : notes) {
            final PartResolutionSidecar.NoteRecord record = new PartResolutionSidecar.NoteRecord();
            record.noteKey = n.id;
            record.partId = n.staff;
            record.measureNumber = Integer.toString(n.m);
            record.staff = Integer.toString(n.line);
            record.duration = n.dur;
            record.pitch = n.pitch;
            record.rest = "rest".equals(n.pitch);
            record.sourceWindows.add(windowId);

            sidecar.registerNote(record);
            noteKeys.add(n.id);
        }

        sidecar.registerWindow(windowId, noteKeys);

        return noteKeys;
    }
}
