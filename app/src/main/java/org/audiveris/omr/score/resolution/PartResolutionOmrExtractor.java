//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                      P a r t R e s o l u t i o n O m r E x t r a c t o r                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import org.audiveris.omr.score.PartwiseBuilder;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sig.inter.AbstractChordInter;
import org.audiveris.proxymusic.Lyric;
import org.audiveris.proxymusic.Note;
import org.audiveris.proxymusic.Pitch;
import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.StartStop;
import org.audiveris.proxymusic.Stem;
import org.audiveris.proxymusic.StemValue;
import org.audiveris.proxymusic.TextElementData;
import org.audiveris.proxymusic.Tie;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the notes of a score as OMR sees them, and the matching MusicXML export.
 * <p>
 * Both come out of one walk of the score, so every {@link PartResolutionOmrNote} has exactly one
 * exported <code>note</code> element carrying the same <code>id</code>. The AI works on the first,
 * the rebuilder cuts and pastes the second.
 */
public abstract class PartResolutionOmrExtractor
{
    private PartResolutionOmrExtractor ()
    {
    }

    /**
     * Extract the notes of a score.
     *
     * @param score the score to read
     * @return the OMR notes and the MusicXML whose notes are tagged with their ids
     * @throws Exception if the export fails
     */
    public static Extraction extract (Score score)
        throws Exception
    {
        final List<PartResolutionOmrNote> notes = new ArrayList<>();
        final ScorePartwise partwise = PartwiseBuilder.build(
                score,
                (id, note, measure, partId, pmNote) -> {
                    final AbstractChordInter chord = note.getChord();
                    final PartResolutionOmrNote n = new PartResolutionOmrNote();
                    n.id = id;
                    n.stack = measure.getStack();
                    n.staff = partId;
                    n.line = (pmNote.getStaff() != null) ? pmNote.getStaff().intValue() : 1;
                    n.pitch = pitchOf(pmNote);
                    n.grace = pmNote.getGrace() != null;
                    n.dur = n.grace ? "0" : String.valueOf(chord.getDuration());
                    n.x = (pmNote.getDefaultX() != null) ? pmNote.getDefaultX().intValue() : 0;
                    n.stem = stemOf(pmNote);
                    n.chord = "c" + chord.getId();
                    n.tie = tieOf(pmNote);
                    n.lyric = lyricOf(pmNote);
                    notes.add(n);
                });

        return new Extraction(partwise, notes);
    }

    private static String pitchOf (Note pmNote)
    {
        if (pmNote.getRest() != null) {
            return "rest";
        }

        final Pitch pitch = pmNote.getPitch();

        if (pitch == null) {
            return "unpitched";
        }

        final int alter = (pitch.getAlter() != null) ? pitch.getAlter().intValue() : 0;
        final String sign = switch (alter) {
            case -2 -> "bb";
            case -1 -> "b";
            case 1 -> "#";
            case 2 -> "##";
            default -> "";
        };

        return pitch.getStep().name() + sign + pitch.getOctave();
    }

    private static String stemOf (Note pmNote)
    {
        final Stem stem = pmNote.getStem();

        if (stem == null) {
            return null;
        }

        return (stem.getValue() == StemValue.UP) ? "up"
                : ((stem.getValue() == StemValue.DOWN) ? "down" : null);
    }

    private static String tieOf (Note pmNote)
    {
        boolean start = false;
        boolean stop = false;

        for (Tie tie : pmNote.getTie()) {
            start |= tie.getType() == StartStop.START;
            stop |= tie.getType() == StartStop.STOP;
        }

        return (start && stop) ? "start+stop" : (start ? "start" : (stop ? "stop" : null));
    }

    private static String lyricOf (Note pmNote)
    {
        for (Lyric lyric : pmNote.getLyric()) {
            for (Object obj : lyric.getElisionAndSyllabicAndText()) {
                if (obj instanceof TextElementData text) {
                    return text.getValue();
                }
            }
        }

        return null;
    }

    /** Outcome of an extraction. */
    public static class Extraction
    {
        /** The MusicXML export, every note tagged with its OMR note id. */
        public final ScorePartwise partwise;

        /** The OMR notes, in export order. */
        public final List<PartResolutionOmrNote> notes;

        Extraction (ScorePartwise partwise,
                    List<PartResolutionOmrNote> notes)
        {
            this.partwise = partwise;
            this.notes = notes;
        }
    }
}
