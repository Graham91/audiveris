//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                          P a r t R e s o l u t i o n O m r N o t e                             //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import org.audiveris.omr.sheet.rhythm.MeasureStack;

/**
 * One note (head or rest) as read by OMR, in the form handed to the AI.
 * <p>
 * Only what is on the page is described: pitch, written duration, horizontal position, stem and
 * lyric. The voice number and the start time that OMR infers (and sometimes gets wrong) are
 * deliberately left out.
 */
public class PartResolutionOmrNote
{
    /** Stable identity, "s&lt;sheet&gt;n&lt;inter id&gt;"; also the <code>id</code> of the exported note. */
    public String id;

    /** 1-based index of the measure in the score (the numbering used by windows). */
    public int m;

    /** Staff the note is written on, as the id of its source part (P1 is the top staff). */
    public String staff;

    /** Staff index within the source part, 1 when the part has a single staff. */
    public int line = 1;

    /** Pitch such as "F#3", "Bb2", or "rest". */
    public String pitch;

    /** Written duration as a fraction of a whole note, such as "1/4"; "0" for a grace note. */
    public String dur;

    /** Abscissa in tenths from the left barline of the measure; equal values are simultaneous. */
    public int x;

    /** Stem direction, "up", "down", or null when there is no stem. */
    public String stem;

    /** Identity of the chord: notes with the same value share one stem. */
    public String chord;

    /** True for a grace note. */
    public boolean grace;

    /** "start", "stop" or "start+stop" when the note is tied, else null. */
    public String tie;

    /** Lyric syllable attached to the note, if any. */
    public String lyric;

    /** The containing stack, used to find the measure index; not part of the JSON. */
    public transient MeasureStack stack;
}
