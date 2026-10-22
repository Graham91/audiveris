//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                  P a r t R e s o l u t i o n S c o r e R e b u i l d e r                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Rebuilds a partwise MusicXML score from scratch, with one output part per target label.
 * <p>
 * The source document is only read, never modified. Every note of the source is given a target
 * part (the AI assignment when there is a valid one, otherwise the source part it already lives
 * in) and the output parts are written one at a time:
 * <ul>
 * <li>each output part is a single voice: its notes of a measure are put in left-to-right order
 * (the <code>default-x</code> of the notes) and each starts where the previous one ends, so the
 * start times OMR inferred for the voices, which can be wrong, are not used,
 * <li>chords whose members go to different parts are split (the first member kept in a part
 * becomes the chord base there),
 * <li>a part whose notes do not add up to the measure is reported in {@link Result#warnings}.
 * </ul>
 * A note is identified by the <code>id</code> attribute of the exported note; sources without ids
 * (older fixtures) are matched on a key made of the note content.
 * <p>
 * A target part is never silently mapped onto another one: when the source cannot be rebuilt
 * safely, {@link Result#errors} is filled and no document is produced.
 */
public abstract class PartResolutionScoreRebuilder
{
    /** Maximum number of bar mismatches listed individually in the warnings. */
    private static final int MAX_BAR_WARNINGS = 20;

    /** Names given to four target parts, top line first. */
    private static final String[] SATB_NAMES = { "Soprano", "Alto", "Tenor", "Bass" };

    private static final String[] SATB_ABBREVIATIONS = { "S.", "A.", "T.", "B." };

    private PartResolutionScoreRebuilder ()
    {
    }

    /**
     * Rebuild the score.
     *
     * @param source       the partwise document of the current score (read only)
     * @param records      sidecar note records relevant for this score
     * @param targetLabels ordered target labels; output part ids are P1..Pn in this order
     * @return the rebuilt document (null if errors) and statistics
     * @throws Exception if the output document cannot be created
     */
    public static Result rebuild (Document source,
                                  Collection<PartResolutionSidecar.NoteRecord> records,
                                  List<String> targetLabels)
        throws Exception
    {
        final Result result = new Result();

        if ((targetLabels == null) || targetLabels.isEmpty()) {
            result.errors.add("No target part labels defined.");
            return result;
        }

        final int n = targetLabels.size();
        final Element root = source.getDocumentElement();
        final List<SourcePart> sources = readSources(root);

        if (sources.isEmpty()) {
            result.errors.add("Source score has no parts.");
            return result;
        }

        final int measureCount = sources.get(0).measures.size();

        for (SourcePart sp : sources) {
            if (sp.measures.size() != measureCount) {
                result.errors.add(
                        "Source parts have different measure counts (" + sp.id + " has "
                                + sp.measures.size() + ", expected " + measureCount + ").");
                return result;
            }
        }

        checkSupported(sources, result);

        if (!result.errors.isEmpty()) {
            return result;
        }

        // Which target does each note go to?
        final Map<String, Integer> labelIndex = new LinkedHashMap<>();

        for (int i = 0; i < n; i++) {
            final String label = trimToNull(targetLabels.get(i));

            if (label != null) {
                labelIndex.putIfAbsent(label, i);
            }
        }

        final Map<String, Integer> chosenByKey = new HashMap<>();

        for (PartResolutionSidecar.NoteRecord r : records) {
            if ((r == null) || (r.noteKey == null)) {
                continue;
            }

            final String chosen = trimToNull(r.chosenPart);

            if ((chosen == null) || PartResolutionRewritePlanner.isAmbiguous(r)) {
                continue;
            }

            final Integer idx = labelIndex.get(chosen);

            if (idx == null) {
                result.unknownLabelNotes++;
                continue;
            }

            chosenByKey.put(r.noteKey, idx);
        }

        final Set<String> matched = new HashSet<>();

        for (int s = 0; s < sources.size(); s++) {
            final SourcePart sp = sources.get(s);

            for (SourceMeasure sm : sp.measures) {
                for (Item it : sm.items) {
                    if (it.kind != Kind.NOTE) {
                        continue;
                    }

                    result.totalNotes++;
                    final Integer idx = chosenByKey.get(it.key);

                    if (idx != null) {
                        it.target = idx;
                        result.aiAssignedNotes++;
                        matched.add(it.key);
                    } else {
                        it.target = labelIndex.getOrDefault(sp.id, Math.min(s, n - 1));
                        result.defaultedNotes++;
                    }

                    if (!outputId(it.target).equals(sp.id)) {
                        result.movedNotes++;
                    }
                }

                markChordSplits(sm);
            }
        }

        result.unmatchedAssignments = chosenByKey.size() - matched.size();

        // Home source part of each target (supplies clef, key, time, instrument)
        final int[][] counts = new int[n][sources.size()];

        for (int t = 0; t < n; t++) {
            result.notesPerPart.put(outputId(t), 0);
        }

        for (int s = 0; s < sources.size(); s++) {
            final SourcePart sp = sources.get(s);

            for (SourceMeasure sm : sp.measures) {
                for (Item it : sm.items) {
                    if (it.kind == Kind.NOTE) {
                        counts[it.target][s]++;
                        result.notesPerPart.merge(outputId(it.target), 1, Integer::sum);
                    }
                }
            }
        }

        final int[] home = new int[n];

        for (int t = 0; t < n; t++) {
            int best = -1;
            int bestCount = 0;

            for (int s = 0; s < sources.size(); s++) {
                if (counts[t][s] > bestCount) {
                    best = s;
                    bestCount = counts[t][s];
                }
            }

            home[t] = (best >= 0) ? best : Math.min(t, sources.size() - 1);

            if (bestCount == 0) {
                result.warnings.add("Target part " + outputId(t) + " received no notes.");
            }
        }

        if (result.defaultedNotes > 0) {
            result.warnings.add(
                    result.defaultedNotes + " of " + result.totalNotes
                            + " notes had no usable AI assignment and stayed in their default part.");
        }

        if (result.unknownLabelNotes > 0) {
            result.warnings.add(
                    result.unknownLabelNotes + " assignments used a label that is not a target part.");
        }

        if (result.unmatchedAssignments > 0) {
            result.warnings.add(
                    result.unmatchedAssignments
                            + " assignments did not match any note of the current score.");
        }

        // Longest content of each measure, used for empty parts
        final int[] measureDuration = new int[measureCount];

        for (SourcePart sp : sources) {
            for (int k = 0; k < measureCount; k++) {
                measureDuration[k] = Math.max(measureDuration[k], sp.measures.get(k).endTime);
            }
        }

        // Output document
        final Document out = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        final Element outRoot = out.createElement(root.getTagName());
        copyAttributes(root, outRoot);
        out.appendChild(outRoot);

        for (Element child : childElements(root, null)) {
            final String tag = child.getTagName();

            if ("part-list".equals(tag)) {
                outRoot.appendChild(buildPartList(out, sources, home, n));
            } else if (!"part".equals(tag)) {
                outRoot.appendChild(out.importNode(child, true));
            }
        }

        final int[][] sums = new int[n][measureCount];

        for (int t = 0; t < n; t++) {
            final Element part = out.createElement("part");
            part.setAttribute("id", outputId(t));

            for (int k = 0; k < measureCount; k++) {
                part.appendChild(
                        buildMeasure(out, t, k, sources, home, measureDuration[k], sums[t]));
            }

            outRoot.appendChild(part);
        }

        checkBars(sources.get(0), sums, result);

        result.document = out;

        return result;
    }

    /** Output part id for the target at provided index. */
    public static String outputId (int targetIndex)
    {
        return "P" + (targetIndex + 1);
    }

    //~ Reading ------------------------------------------------------------------------------------

    private static List<SourcePart> readSources (Element root)
    {
        final Map<String, Element> scoreParts = new HashMap<>();

        for (Element partList : childElements(root, "part-list")) {
            for (Element sp : childElements(partList, "score-part")) {
                scoreParts.put(sp.getAttribute("id"), sp);
            }
        }

        final List<SourcePart> list = new ArrayList<>();

        for (Element part : childElements(root, "part")) {
            final SourcePart sp = new SourcePart();
            sp.id = part.getAttribute("id");
            sp.scorePart = scoreParts.get(sp.id);

            for (Element measure : childElements(part, "measure")) {
                sp.measures.add(scanMeasure(sp, measure));
            }

            list.add(sp);
        }

        return list;
    }

    private static SourceMeasure scanMeasure (SourcePart sp,
                                              Element measure)
    {
        final SourceMeasure sm = new SourceMeasure();
        sm.element = measure;

        final String implicit = "yes".equalsIgnoreCase(measure.getAttribute("implicit")) ? "1" : "0";
        int cursor = 0;
        int ordinal = 0;
        Item lastBase = null;
        final List<Item> pending = new ArrayList<>(); // Non-note items waiting for the next note

        for (Element el : childElements(measure, null)) {
            switch (el.getTagName()) {
                case "note" -> {
                    ordinal++;
                    final boolean rest = hasChild(el, "rest");
                    final boolean chordFlag = hasChild(el, "chord");
                    final Item it = new Item(Kind.NOTE, el, sp);
                    it.voice = childText(el, "voice", "1");
                    it.duration = hasChild(el, "grace") ? 0 : intChild(el, "duration", 0);
                    it.chord = chordFlag && (lastBase != null);
                    it.x = parseX(el.getAttribute("default-x"));

                    final String id = el.getAttribute("id");
                    it.key = !id.isBlank() ? id.trim()
                            : ("p=" + sp.id + "|m=" + measure.getAttribute("number") + "|i=" + implicit
                                    + "|n=" + ordinal + "|v=" + it.voice + "|s="
                                    + childText(el, "staff", "1") + "|d="
                                    + childText(el, "duration", "0") + "|pc="
                                    + (rest ? "rest" : pitchToken(el)) + "|c="
                                    + (chordFlag ? "1" : "0"));

                    for (Item p : pending) {
                        p.anchor = it;
                    }

                    pending.clear();

                    if (it.chord) {
                        it.onset = lastBase.onset;
                        it.chordBase = lastBase;
                    } else {
                        it.onset = cursor;
                        cursor += it.duration;
                        lastBase = it;
                    }

                    sm.endTime = Math.max(sm.endTime, cursor);
                    sm.items.add(it);
                }
                case "backup" -> {
                    cursor -= intChild(el, "duration", 0);
                    lastBase = null;
                }
                case "forward" -> {
                    cursor += intChild(el, "duration", 0);
                    sm.endTime = Math.max(sm.endTime, cursor);
                    lastBase = null;
                }
                case "attributes" -> addTimed(sm, Kind.ATTRIBUTES, el, sp, cursor, pending);
                case "print" -> addTimed(sm, Kind.PRINT, el, sp, cursor, null);
                case "barline" -> addTimed(
                        sm,
                        "left".equals(el.getAttribute("location")) ? Kind.BARLINE_LEFT
                                : Kind.BARLINE_RIGHT,
                        el,
                        sp,
                        cursor,
                        null);
                default -> {
                    // Directions (dynamics, words, wedges...), harmony, sound: not kept
                }
            }
        }

        return sm;
    }

    /**
     * Add a non-note item. At the start of the measure it stays there; later it follows the
     * note it precedes in the source (set when that note is read).
     */
    private static void addTimed (SourceMeasure sm,
                                  Kind kind,
                                  Element el,
                                  SourcePart sp,
                                  int cursor,
                                  List<Item> pending)
    {
        final Item it = new Item(kind, el, sp);
        it.onset = cursor;
        it.atStart = (cursor == 0);
        sm.items.add(it);

        if ((pending != null) && !it.atStart) {
            pending.add(it);
        }
    }

    private static Double parseX (String text)
    {
        if ((text == null) || text.isBlank()) {
            return null;
        }

        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void checkSupported (List<SourcePart> sources,
                                        Result result)
    {
        final Set<String> divisions = new HashSet<>();

        for (SourcePart sp : sources) {
            for (SourceMeasure sm : sp.measures) {
                for (Item it : sm.items) {
                    if (it.kind != Kind.ATTRIBUTES) {
                        continue;
                    }

                    if (intChild(it.element, "staves", 1) > 1) {
                        result.errors.add(
                                "Part " + sp.id + " has several staves; rebuild supports "
                                        + "single-staff source parts only.");
                        return;
                    }

                    final String div = childText(it.element, "divisions", null);

                    if (div != null) {
                        divisions.add(div);
                    }
                }
            }
        }

        if (divisions.size() > 1) {
            result.errors.add(
                    "Source parts use different divisions " + divisions
                            + "; moving notes between parts would alter durations.");
        }
    }

    /**
     * For each target, the first note it gets out of a chord group becomes the chord base there
     * (so it advances time and loses its chord flag); later ones stay chord members.
     */
    private static void markChordSplits (SourceMeasure sm)
    {
        final Map<Item, Set<Integer>> seen = new HashMap<>();

        for (Item it : sm.items) {
            if (it.kind != Kind.NOTE) {
                continue;
            }

            final Item base = it.chord ? it.chordBase : it;
            final boolean first = seen.computeIfAbsent(base, b -> new HashSet<>()).add(it.target);
            it.advances = first && !hasChild(it.element, "grace");
            it.dropChord = first && it.chord;
        }
    }

    //~ Writing ------------------------------------------------------------------------------------

    private static Element buildPartList (Document out,
                                          List<SourcePart> sources,
                                          int[] home,
                                          int n)
    {
        final Element partList = out.createElement("part-list");

        for (int t = 0; t < n; t++) {
            final SourcePart src = sources.get(home[t]);
            final String id = outputId(t);
            final Element scorePart;

            if (src.scorePart != null) {
                scorePart = (Element) out.importNode(src.scorePart, true);
                renameIds(scorePart, src.id, id, t);
            } else {
                scorePart = out.createElement("score-part");
                final Element name = out.createElement("part-name");
                name.setTextContent(id);
                scorePart.appendChild(name);
            }

            scorePart.setAttribute("id", id);

            for (String tag : new String[]
            { "part-name", "part-abbreviation" }) {
                final Element e = firstChild(scorePart, tag);

                if (n == SATB_NAMES.length) {
                    final String name = "part-name".equals(tag) ? SATB_NAMES[t] : SATB_ABBREVIATIONS[t];

                    if (e != null) {
                        e.setTextContent(name);
                        e.removeAttribute("print-object");
                    } else if ("part-name".equals(tag)) {
                        scorePart.insertBefore(textElement(out, tag, name), scorePart.getFirstChild());
                    }
                } else if ((e != null) && !e.getTextContent().isBlank()) {
                    e.setTextContent(e.getTextContent().trim() + " " + (t + 1));
                }
            }

            partList.appendChild(scorePart);
        }

        return partList;
    }

    private static void renameIds (Element scorePart,
                                   String oldId,
                                   String newId,
                                   int targetIndex)
    {
        final NodeList all = scorePart.getElementsByTagName("*");

        for (int i = 0; i < all.getLength(); i++) {
            final Element e = (Element) all.item(i);
            final String id = e.getAttribute("id");

            if (id.startsWith(oldId + "-")) {
                e.setAttribute("id", newId + id.substring(oldId.length()));
            }

            if ("midi-channel".equals(e.getTagName())) {
                e.setTextContent(Integer.toString((targetIndex % 16) + 1));
            }
        }
    }

    private static Element buildMeasure (Document out,
                                         int t,
                                         int k,
                                         List<SourcePart> sources,
                                         int[] home,
                                         int restDuration,
                                         int[] sums)
    {
        final SourceMeasure homeMeasure = sources.get(home[t]).measures.get(k);
        final Element om = out.createElement("measure");
        copyAttributes(homeMeasure.element, om);

        final List<Item> lead = new ArrayList<>();
        final List<Item> trail = new ArrayList<>();
        final List<Item> startItems = new ArrayList<>(); // Attributes at measure start
        final List<Item> laterItems = new ArrayList<>(); // Attributes that follow a note
        final List<Item> notes = new ArrayList<>();

        for (Item it : homeMeasure.items) {
            switch (it.kind) {
                case PRINT, BARLINE_LEFT -> lead.add(it);
                case BARLINE_RIGHT -> trail.add(it);
                case ATTRIBUTES -> (it.atStart ? startItems : laterItems).add(it);
                default -> {
                }
            }
        }

        for (int s = 0; s < sources.size(); s++) {
            for (Item it : sources.get(s).measures.get(k).items) {
                if ((it.kind == Kind.NOTE) && (it.target == t)) {
                    notes.add(it);
                }
            }
        }

        // Chord groups: the notes of one source chord that this part keeps
        final Map<Item, Group> groupByBase = new LinkedHashMap<>();
        boolean useX = true;

        for (Item it : notes) {
            groupByBase.computeIfAbsent(it.chord ? it.chordBase : it, b -> new Group()).members.add(
                    it);
            useX &= (it.x != null);
        }

        // Left to right when every note has an abscissa, else in source time order
        final List<Group> groups = new ArrayList<>(groupByBase.values());

        for (Group g : groups) {
            g.key = useX ? g.members.stream().mapToDouble(m -> m.x).min().getAsDouble()
                    : g.members.get(0).onset;

            for (Item m : g.members) {
                if (m.advances) {
                    g.duration = Math.max(g.duration, m.duration);
                }
            }
        }

        groups.sort(Comparator.comparingDouble((Group g) -> g.key)); // Stable

        int cursor = 0;

        for (Group g : groups) {
            cursor += g.duration;
        }

        sums[k] = cursor;

        // Items that follow a note go just before the first group at or after that note
        final boolean byX = useX;
        laterItems.sort(Comparator.comparingDouble((Item i) -> anchorKey(i, byX)));

        for (Item it : lead) {
            om.appendChild(out.importNode(it.element, true));
        }

        for (Item it : startItems) {
            om.appendChild(out.importNode(it.element, true));
        }

        int next = 0;

        for (Group g : groups) {
            while ((next < laterItems.size()) && (anchorKey(laterItems.get(next), byX) <= g.key)) {
                om.appendChild(out.importNode(laterItems.get(next++).element, true));
            }

            for (Item it : g.members) {
                om.appendChild(copyNote(out, it));
            }
        }

        while (next < laterItems.size()) {
            om.appendChild(out.importNode(laterItems.get(next++).element, true));
        }

        if (groups.isEmpty() && (restDuration > 0)) {
            om.appendChild(measureRest(out, restDuration));
        }

        for (Item it : trail) {
            om.appendChild(out.importNode(it.element, true));
        }

        return om;
    }

    private static double anchorKey (Item it,
                                     boolean byX)
    {
        if (it.anchor == null) {
            return Double.POSITIVE_INFINITY; // After the last note
        }

        if (!byX) {
            return it.anchor.onset;
        }

        return (it.anchor.x != null) ? it.anchor.x : Double.POSITIVE_INFINITY;
    }

    private static Element copyNote (Document out,
                                     Item it)
    {
        final Element copy = (Element) out.importNode(it.element, true);
        final Element voice = firstChild(copy, "voice");

        if (voice != null) {
            voice.setTextContent("1"); // One voice per part
        }

        stripMarkings(copy);

        if (it.dropChord) {
            final Element chord = firstChild(copy, "chord");

            if (chord != null) {
                copy.removeChild(chord);
            }
        }

        return copy;
    }

    /**
     * Keep only what a singer's line needs: pitch, accidental, tie, slur (and tuplet brackets,
     * which belong to the rhythm). Ornaments such as trills, articulations, fermatas and
     * dynamics are removed.
     */
    private static void stripMarkings (Element note)
    {
        note.removeAttribute("dynamics");
        note.removeAttribute("end-dynamics");

        for (Element notations : childElements(note, "notations")) {
            for (Element child : childElements(notations, null)) {
                final String tag = child.getTagName();

                if (!"tied".equals(tag) && !"slur".equals(tag) && !"tuplet".equals(tag)) {
                    notations.removeChild(child);
                }
            }

            if (childElements(notations, null).isEmpty()) {
                note.removeChild(notations);
            }
        }
    }

    private static Element measureRest (Document out,
                                        int duration)
    {
        final Element note = out.createElement("note");
        final Element rest = out.createElement("rest");
        rest.setAttribute("measure", "yes");
        note.appendChild(rest);
        note.appendChild(textElement(out, "duration", Integer.toString(duration)));
        note.appendChild(textElement(out, "voice", "1"));

        return note;
    }

    private static Element textElement (Document out,
                                        String tag,
                                        String text)
    {
        final Element e = out.createElement(tag);
        e.setTextContent(text);

        return e;
    }

    //~ Checking -----------------------------------------------------------------------------------

    /**
     * Report the parts whose notes do not fill a measure.
     * <p>
     * Measures with a different length are legitimate when they pair up with a neighbour to make
     * one bar (a hymn bar split by a line break, or a pickup completed by the last measure).
     */
    private static void checkBars (SourcePart first,
                                   int[][] sums,
                                   Result result)
    {
        final Integer[] expected = expectedDurations(first);
        final int count = first.measures.size();
        int reported = 0;

        for (int t = 0; t < sums.length; t++) {
            for (int k = 0; k < count; k++) {
                final int sum = sums[t][k];

                if ((sum == 0) || (expected[k] == null) || (sum == expected[k])) {
                    continue;
                }

                if (((k + 1) < count) && ((sum + sums[t][k + 1]) == expected[k])) {
                    continue;
                }

                if ((k > 0) && ((sums[t][k - 1] + sum) == expected[k])) {
                    continue;
                }

                if ((k == (count - 1)) && ((sum + sums[t][0]) == expected[k])) {
                    continue;
                }

                result.barMismatches++;

                if (reported < MAX_BAR_WARNINGS) {
                    reported++;
                    result.warnings.add(
                            outputId(t) + " measure " + (k + 1) + " (number "
                                    + first.measures.get(k).element.getAttribute("number")
                                    + "): notes add up to " + sum + " of " + expected[k]
                                    + " divisions.");
                }
            }
        }

        if (result.barMismatches > reported) {
            result.warnings.add(
                    (result.barMismatches - reported) + " more parts/measures do not fill the bar.");
        }
    }

    /** Expected length of each measure in divisions, null for a pickup or when unknown. */
    private static Integer[] expectedDurations (SourcePart first)
    {
        final Integer[] expected = new Integer[first.measures.size()];
        Integer divisions = null;
        Integer beats = null;
        Integer beatType = null;

        for (int k = 0; k < expected.length; k++) {
            final SourceMeasure sm = first.measures.get(k);

            for (Item it : sm.items) {
                if (it.kind != Kind.ATTRIBUTES) {
                    continue;
                }

                final Integer d = tryInt(childText(it.element, "divisions", null));

                if (d != null) {
                    divisions = d;
                }

                final Element time = firstChild(it.element, "time");

                if (time != null) {
                    beats = tryInt(childText(time, "beats", null));
                    beatType = tryInt(childText(time, "beat-type", null));
                }
            }

            if (!"yes".equalsIgnoreCase(sm.element.getAttribute("implicit")) && (divisions != null)
                    && (beats != null) && (beatType != null) && (beatType > 0)) {
                final int whole = divisions * 4 * beats;

                if ((whole % beatType) == 0) {
                    expected[k] = whole / beatType;
                }
            }
        }

        return expected;
    }

    private static Integer tryInt (String text)
    {
        if (text == null) {
            return null;
        }

        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    //~ Helpers ------------------------------------------------------------------------------------

    private static List<Element> childElements (Element parent,
                                                String tag)
    {
        final List<Element> list = new ArrayList<>();
        final NodeList children = parent.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            final Node node = children.item(i);

            if ((node instanceof Element e) && ((tag == null) || tag.equals(e.getTagName()))) {
                list.add(e);
            }
        }

        return list;
    }

    private static Element firstChild (Element parent,
                                       String tag)
    {
        final List<Element> list = childElements(parent, tag);
        return list.isEmpty() ? null : list.get(0);
    }

    private static boolean hasChild (Element parent,
                                     String tag)
    {
        return firstChild(parent, tag) != null;
    }

    private static String childText (Element parent,
                                     String tag,
                                     String fallback)
    {
        final Element e = firstChild(parent, tag);

        if (e == null) {
            return fallback;
        }

        final String text = e.getTextContent();
        return ((text != null) && !text.isBlank()) ? text.trim() : fallback;
    }

    private static int intChild (Element parent,
                                 String tag,
                                 int fallback)
    {
        try {
            return Integer.parseInt(childText(parent, tag, Integer.toString(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String pitchToken (Element note)
    {
        final Element pitch = firstChild(note, "pitch");

        if (pitch == null) {
            return "?";
        }

        return childText(pitch, "step", "?") + childText(pitch, "alter", "0")
                + childText(pitch, "octave", "?");
    }

    private static void copyAttributes (Element from,
                                        Element to)
    {
        final NamedNodeMap attrs = from.getAttributes();

        for (int i = 0; i < attrs.getLength(); i++) {
            to.setAttribute(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
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

    //~ Inner classes ------------------------------------------------------------------------------

    /** Outcome of a rebuild. */
    public static class Result
    {
        /** The rebuilt document, null if errors. */
        public Document document;

        public int totalNotes;

        /** Notes that got a valid AI assignment. */
        public int aiAssignedNotes;

        /** Notes without usable assignment, left in their default part. */
        public int defaultedNotes;

        /** Notes now in a part whose id differs from their source part id. */
        public int movedNotes;

        public int unknownLabelNotes;

        /** Assignments that matched no note in the current score. */
        public int unmatchedAssignments;

        /** Part measures whose notes do not fill the bar (and do not pair up with a neighbour). */
        public int barMismatches;

        public final Map<String, Integer> notesPerPart = new LinkedHashMap<>();

        public final List<String> warnings = new ArrayList<>();

        public final List<String> errors = new ArrayList<>();
    }

    private enum Kind
    {
        NOTE,
        ATTRIBUTES,
        PRINT,
        BARLINE_LEFT,
        BARLINE_RIGHT
    }

    private static final class SourcePart
    {
        String id;

        Element scorePart;

        final List<SourceMeasure> measures = new ArrayList<>();
    }

    private static final class SourceMeasure
    {
        Element element;

        final List<Item> items = new ArrayList<>();

        int endTime;
    }

    private static final class Item
    {
        final Kind kind;

        final Element element;

        final SourcePart part;

        int onset;

        int duration;

        boolean chord;

        boolean advances;

        boolean dropChord;

        String voice = "1";

        String key;

        int target;

        Item chordBase;

        /** Abscissa of a note (default-x), null when unknown. */
        Double x;

        /** True for a non-note item found at the start of its source measure. */
        boolean atStart;

        /** The note a later non-note item precedes in the source, null when it ends the measure. */
        Item anchor;

        Item (Kind kind,
              Element element,
              SourcePart part)
        {
            this.kind = kind;
            this.element = element;
            this.part = part;
        }
    }

    /** The notes of one source chord that one output part keeps. */
    private static final class Group
    {
        final List<Item> members = new ArrayList<>();

        double key;

        int duration;
    }
}
