/*
 * Copyright © Audiveris 2026. All rights reserved.
 * This software is released under the GNU General Public License.
 */
package org.audiveris.omr.score.resolution;

import org.audiveris.omr.util.BaseTestCase;

import org.junit.Test;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Tests for deterministic sliding window planning.
 */
public class PartResolutionPlannerTest
        extends BaseTestCase
{
    @Test
    public void testExactChunkSize ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(1, 3, 3);

        assertEquals(1, plan.getWindowCount());
        assertEquals("s01-m0001-0003-w0001", plan.getWindows().get(0).getId());
    }

    @Test
    public void testShorterThanChunk ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(2, 2, 3);

        assertEquals(1, plan.getWindowCount());
        assertEquals(1, plan.getWindows().get(0).getStartMeasure());
        assertEquals(2, plan.getWindows().get(0).getEndMeasure());
    }

    @Test
    public void testSlidingWindows ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(3, 5, 3);

        assertEquals(2, plan.getWindowCount());
        assertEquals("s03-m0001-0003-w0001", plan.getWindows().get(0).getId());
        assertEquals("s03-m0003-0005-w0002", plan.getWindows().get(1).getId());
    }

    @Test
    public void testSlidingWindowsKeepTail ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(3, 6, 3);

        assertEquals(3, plan.getWindowCount());
        assertEquals("s03-m0001-0003-w0001", plan.getWindows().get(0).getId());
        assertEquals("s03-m0003-0005-w0002", plan.getWindows().get(1).getId());
        assertEquals("s03-m0004-0006-w0003", plan.getWindows().get(2).getId());
    }

    @Test
    public void testNoMeasures ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(1, 0, 3);

        assertEquals(0, plan.getWindowCount());
    }

    private static final String TWO_STAFF_SATB = "<score-partwise version=\"4.0.3\">"
            + "<part-list>"
            + "<score-part id=\"P1\"><part-name>Voice</part-name></score-part>"
            + "<score-part id=\"P2\"><part-name>Voice</part-name></score-part>"
            + "</part-list>"
            + "<part id=\"P1\"><measure number=\"1\">"
            + "<attributes><divisions>2</divisions><clef><sign>G</sign><line>2</line></clef></attributes>"
            + "<note><pitch><step>G</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
            + "<backup><duration>2</duration></backup>"
            + "<note><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><voice>2</voice></note>"
            + "</measure></part>"
            + "<part id=\"P2\"><measure number=\"1\">"
            + "<attributes><divisions>2</divisions><clef><sign>F</sign><line>4</line></clef></attributes>"
            + "<note><pitch><step>B</step><octave>3</octave></pitch><duration>2</duration><voice>1</voice></note>"
            + "<backup><duration>2</duration></backup>"
            + "<note><pitch><step>G</step><octave>2</octave></pitch><duration>2</duration><voice>2</voice></note>"
            + "</measure></part>"
            + "</score-partwise>";

    private static Document parse (String xml)
        throws Exception
    {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static PartResolutionSidecar.NoteRecord record (String key,
                                                            String chosen)
    {
        final PartResolutionSidecar.NoteRecord r = new PartResolutionSidecar.NoteRecord();
        r.noteKey = key;
        r.chosenPart = chosen;
        r.status = "resolved";
        return r;
    }

    private static Element part (Document doc,
                                 String id)
    {
        final NodeList parts = doc.getElementsByTagName("part");

        for (int i = 0; i < parts.getLength(); i++) {
            final Element e = (Element) parts.item(i);

            if (id.equals(e.getAttribute("id"))) {
                return e;
            }
        }

        return null;
    }

    @Test
    public void testTwoPartsRebuiltAsFour ()
        throws Exception
    {
        final List<PartResolutionSidecar.NoteRecord> records = new ArrayList<>();
        records.add(record("p=P1|m=1|i=0|n=1|v=1|s=1|d=2|pc=G04|c=0", "P1"));
        records.add(record("p=P1|m=1|i=0|n=2|v=2|s=1|d=2|pc=E04|c=0", "P2"));
        records.add(record("p=P2|m=1|i=0|n=1|v=1|s=1|d=2|pc=B03|c=0", "P3"));
        records.add(record("p=P2|m=1|i=0|n=2|v=2|s=1|d=2|pc=G02|c=0", "P4"));

        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(TWO_STAFF_SATB),
                records,
                List.of("P1", "P2", "P3", "P4"));

        assertTrue(result.errors.toString(), result.errors.isEmpty());
        assertEquals(4, result.document.getElementsByTagName("part").getLength());
        assertEquals(4, result.document.getElementsByTagName("score-part").getLength());
        assertEquals(4, result.aiAssignedNotes);
        assertEquals(0, result.defaultedNotes);
        assertEquals(3, result.movedNotes);

        for (String id : new String[]
        { "P1", "P2", "P3", "P4" }) {
            final Element p = part(result.document, id);
            assertNotNull(id, p);
            assertEquals(id, 1, p.getElementsByTagName("note").getLength());
            assertEquals(id, 0, p.getElementsByTagName("backup").getLength());
            assertEquals("1", p.getElementsByTagName("voice").item(0).getTextContent());
        }

        // Tenor and bass come from the bass staff, so they keep its clef
        assertEquals("F", part(result.document, "P3").getElementsByTagName("sign").item(0).getTextContent());
        assertEquals("G", part(result.document, "P2").getElementsByTagName("sign").item(0).getTextContent());

        // Four parts are named after the voices
        final NodeList names = result.document.getElementsByTagName("part-name");
        assertEquals("Soprano", names.item(0).getTextContent());
        assertEquals("Alto", names.item(1).getTextContent());
        assertEquals("Tenor", names.item(2).getTextContent());
        assertEquals("Bass", names.item(3).getTextContent());
    }

    @Test
    public void testChordSplitAcrossParts ()
        throws Exception
    {
        final String xml = "<score-partwise version=\"4.0.3\"><part-list>"
                + "<score-part id=\"P1\"><part-name>Voice</part-name></score-part></part-list>"
                + "<part id=\"P1\"><measure number=\"1\">"
                + "<attributes><divisions>2</divisions></attributes>"
                + "<note><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
                + "<note><chord/><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
                + "<note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
                + "</measure></part></score-partwise>";

        final List<PartResolutionSidecar.NoteRecord> records = new ArrayList<>();
        records.add(record("p=P1|m=1|i=0|n=1|v=1|s=1|d=2|pc=E04|c=0", "P1"));
        records.add(record("p=P1|m=1|i=0|n=2|v=1|s=1|d=2|pc=C04|c=1", "P2"));
        records.add(record("p=P1|m=1|i=0|n=3|v=1|s=1|d=2|pc=D04|c=0", "P2"));

        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(xml),
                records,
                List.of("P1", "P2"));

        assertTrue(result.errors.toString(), result.errors.isEmpty());

        final Element p1 = part(result.document, "P1");
        assertEquals(1, p1.getElementsByTagName("note").getLength());

        // C was a chord member in P1 but opens the line in P2, followed by D
        final Element p2 = part(result.document, "P2");
        assertEquals(2, p2.getElementsByTagName("note").getLength());
        assertEquals(0, p2.getElementsByTagName("chord").getLength());
        assertEquals(0, p2.getElementsByTagName("forward").getLength());
        assertEquals(0, p2.getElementsByTagName("backup").getLength());

        // P1 only has one note at time 0, then nothing: no filler needed
        assertEquals(0, p1.getElementsByTagName("backup").getLength());
    }

    @Test
    public void testMissingAssignmentsStayInSourcePart ()
        throws Exception
    {
        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(TWO_STAFF_SATB),
                new ArrayList<>(),
                List.of("P1", "P2", "P3", "P4"));

        assertTrue(result.errors.isEmpty());
        assertEquals(4, result.defaultedNotes);
        assertEquals(0, result.movedNotes);
        assertEquals(4, result.document.getElementsByTagName("part").getLength());
        assertFalse(result.warnings.isEmpty());
    }

    @Test
    public void testMultiStaffSourceIsRejected ()
        throws Exception
    {
        final String xml = TWO_STAFF_SATB.replaceFirst(
                "<divisions>2</divisions>",
                "<divisions>2</divisions><staves>2</staves>");

        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(xml),
                new ArrayList<>(),
                List.of("P1", "P2"));

        assertNull(result.document);
        assertFalse(result.errors.isEmpty());
    }

    @Test
    public void testIngestRepairsAlteredDurationInKey ()
        throws Exception
    {
        final PartResolutionSidecar sidecar = new PartResolutionSidecar();
        sidecar.targetPartCount = 4;
        sidecar.targetPartLabels.addAll(List.of("P1", "P2", "P3", "P4"));

        final String[] keys = {
            "p=P2|m=1|i=0|n=1|v=1|s=1|d=2|pc=E03|c=0",
            "p=P2|m=1|i=0|n=3|v=1|s=1|d=1|pc=E03|c=0",
            "p=P2|m=1|i=0|n=4|v=1|s=1|d=1|pc=D03|c=0" };

        for (String key : keys) {
            final PartResolutionSidecar.NoteRecord r = record(key, null);
            r.sourceWindows.add("s01-m0001-0003-w0001");
            sidecar.notes.put(key, r);
        }

        sidecar.registerWindow("s01-m0001-0003-w0001", List.of(keys));

        final java.nio.file.Path root = java.nio.file.Files.createTempDirectory("part-ingest");
        final java.nio.file.Path win = root.resolve("windows").resolve("s01-m0001-0003-w0001");
        java.nio.file.Files.createDirectories(win);
        java.nio.file.Files.writeString(
                root.resolve("part-resolution-sidecar.json"),
                new com.google.gson.Gson().toJson(sidecar));

        // The model wrote d=2 instead of d=1 for the two eighth notes
        final String response = "{\"windowId\":\"s01-m0001-0003-w0001\",\"targetPartCount\":4,\"assignments\":["
                + "{\"noteKey\":\"" + keys[0] + "\",\"partIndex\":3,\"confidence\":0.9},"
                + "{\"noteKey\":\"p=P2|m=1|i=0|n=3|v=1|s=1|d=2|pc=E03|c=0\",\"partIndex\":3,\"confidence\":0.9},"
                + "{\"noteKey\":\"p=P2|m=1|i=0|n=4|v=1|s=1|d=2|pc=D03|c=0\",\"partIndex\":4,\"confidence\":0.9}]}";
        java.nio.file.Files.writeString(win.resolve("response.json"), response);

        final PartResolutionMockResponseFramework.IngestReport report = PartResolutionMockResponseFramework
                .ingestResponses(root, 4);

        assertEquals(report.errors.toString(), 1, report.windowsAccepted);
        assertEquals(0, report.windowsRejected);
        assertEquals(2, report.keysRepaired);
        assertEquals(3, report.assignmentsApplied);

        final PartResolutionSidecar after = new com.google.gson.Gson().fromJson(
                java.nio.file.Files.readString(root.resolve("part-resolution-sidecar.json")),
                PartResolutionSidecar.class);
        assertEquals("P4", after.notes.get(keys[2]).chosenPart);
    }

    private static final String ID_SOURCE = "<score-partwise version=\"4.0.3\"><part-list>"
            + "<score-part id=\"P1\"><part-name>Voice</part-name></score-part></part-list>"
            + "<part id=\"P1\"><measure number=\"1\">"
            + "<attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time></attributes>"
            + "<note id=\"a\" default-x=\"10\"><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
            + "<note id=\"b\" default-x=\"60\"><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice></note>"
            + "<backup><duration>4</duration></backup>"
            // voice 2 is deliberately mis-timed: later note first, with a wrong onset
            + "<note id=\"d\" default-x=\"60\"><pitch><step>A</step><octave>3</octave></pitch><duration>2</duration><voice>2</voice></note>"
            + "<note id=\"c\" default-x=\"10\"><pitch><step>G</step><octave>3</octave></pitch><duration>2</duration><voice>2</voice></note>"
            + "</measure></part></score-partwise>";

    @Test
    public void testIdKeysAndHorizontalOrderRepairSourceTiming ()
        throws Exception
    {
        final List<PartResolutionSidecar.NoteRecord> records = new ArrayList<>();
        records.add(record("a", "P1"));
        records.add(record("b", "P1"));
        records.add(record("c", "P2"));
        records.add(record("d", "P2"));

        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(ID_SOURCE),
                records,
                List.of("P1", "P2"));

        assertTrue(result.errors.toString(), result.errors.isEmpty());
        assertEquals(4, result.aiAssignedNotes);
        assertEquals(0, result.defaultedNotes);
        assertEquals(0, result.barMismatches);

        final Element p2 = part(result.document, "P2");
        final NodeList steps = p2.getElementsByTagName("step");
        assertEquals("G", steps.item(0).getTextContent());
        assertEquals("A", steps.item(1).getTextContent());
        assertEquals(0, p2.getElementsByTagName("forward").getLength());
        assertEquals(0, p2.getElementsByTagName("backup").getLength());
    }

    @Test
    public void testBarMismatchIsReported ()
        throws Exception
    {
        final List<PartResolutionSidecar.NoteRecord> records = new ArrayList<>();
        records.add(record("a", "P1"));
        records.add(record("b", "P1"));
        records.add(record("c", "P1"));
        records.add(record("d", "P2"));

        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(ID_SOURCE),
                records,
                List.of("P1", "P2"));

        assertTrue(result.errors.toString(), result.errors.isEmpty());
        assertTrue(result.barMismatches >= 1);
        assertFalse(result.warnings.isEmpty());
    }

    @Test
    public void testDynamicsOrnamentsAndDirectionsAreDropped ()
        throws Exception
    {
        final String xml = "<score-partwise version=\"4.0.3\"><part-list>"
                + "<score-part id=\"P1\"><part-name>Voice</part-name></score-part></part-list>"
                + "<part id=\"P1\"><measure number=\"1\">"
                + "<attributes><divisions>1</divisions></attributes>"
                + "<direction placement=\"below\"><direction-type><dynamics><f/></dynamics></direction-type></direction>"
                + "<note id=\"a\" default-x=\"10\"><pitch><step>C</step><alter>1</alter><octave>4</octave></pitch>"
                + "<duration>2</duration><voice>1</voice><accidental>sharp</accidental>"
                + "<tie type=\"start\"/><notations><tied type=\"start\"/><slur type=\"start\" number=\"1\"/>"
                + "<ornaments><trill-mark/></ornaments><articulations><accent/></articulations>"
                + "<fermata/></notations></note>"
                + "<note id=\"b\" default-x=\"60\"><pitch><step>C</step><alter>1</alter><octave>4</octave></pitch>"
                + "<duration>2</duration><voice>1</voice><tie type=\"stop\"/>"
                + "<notations><ornaments><trill-mark/></ornaments></notations></note>"
                + "</measure></part></score-partwise>";

        final List<PartResolutionSidecar.NoteRecord> records = new ArrayList<>();
        records.add(record("a", "P1"));
        records.add(record("b", "P1"));

        final PartResolutionScoreRebuilder.Result result = PartResolutionScoreRebuilder.rebuild(
                parse(xml),
                records,
                List.of("P1"));

        assertTrue(result.errors.toString(), result.errors.isEmpty());

        final Element p1 = part(result.document, "P1");
        assertEquals(0, p1.getElementsByTagName("direction").getLength());
        assertEquals(0, p1.getElementsByTagName("dynamics").getLength());
        assertEquals(0, p1.getElementsByTagName("trill-mark").getLength());
        assertEquals(0, p1.getElementsByTagName("articulations").getLength());
        assertEquals(0, p1.getElementsByTagName("fermata").getLength());

        // Pitch, accidental, tie and slur survive; a note left with no notations loses the tag
        assertEquals(1, p1.getElementsByTagName("slur").getLength());
        assertEquals(1, p1.getElementsByTagName("tied").getLength());
        assertEquals(2, p1.getElementsByTagName("tie").getLength());
        assertEquals(1, p1.getElementsByTagName("accidental").getLength());
        assertEquals(2, p1.getElementsByTagName("alter").getLength());
        assertEquals(1, p1.getElementsByTagName("notations").getLength());
    }

    @Test
    public void testIngestStillRejectsMostlyUnknownKeys ()
        throws Exception
    {
        final PartResolutionSidecar sidecar = new PartResolutionSidecar();
        sidecar.targetPartCount = 4;
        final String key = "p=P1|m=1|i=0|n=1|v=1|s=1|d=2|pc=G04|c=0";
        sidecar.notes.put(key, record(key, null));
        sidecar.registerWindow("s01-m0001-0003-w0001", List.of(key));

        final java.nio.file.Path root = java.nio.file.Files.createTempDirectory("part-ingest");
        final java.nio.file.Path win = root.resolve("windows").resolve("s01-m0001-0003-w0001");
        java.nio.file.Files.createDirectories(win);
        java.nio.file.Files.writeString(
                root.resolve("part-resolution-sidecar.json"),
                new com.google.gson.Gson().toJson(sidecar));
        java.nio.file.Files.writeString(
                win.resolve("response.json"),
                "{\"windowId\":\"s01-m0001-0003-w0001\",\"assignments\":["
                        + "{\"noteKey\":\"nonsense-1\",\"partIndex\":1,\"confidence\":0.9},"
                        + "{\"noteKey\":\"nonsense-2\",\"partIndex\":1,\"confidence\":0.9},"
                        + "{\"noteKey\":\"" + key + "\",\"partIndex\":1,\"confidence\":0.9}]}");

        final PartResolutionMockResponseFramework.IngestReport report = PartResolutionMockResponseFramework
                .ingestResponses(root, 4);

        assertEquals(1, report.windowsRejected);
        assertEquals(0, report.assignmentsApplied);
    }

    @Test
    public void testOverlappingWindowsAreSettledByVote ()
        throws Exception
    {
        final PartResolutionSidecar sidecar = new PartResolutionSidecar();
        sidecar.targetPartCount = 4;
        sidecar.targetPartLabels.addAll(List.of("P1", "P2", "P3", "P4"));

        final String disputed = "p=P1|m=3|i=0|n=6|v=1|s=1|d=1|pc=G04|c=0";
        final String tied = "p=P1|m=3|i=0|n=7|v=1|s=1|d=1|pc=A04|c=0";
        final String voiceAltered = "p=P2|m=2|i=0|n=2|v=2|s=1|d=2|pc=B02|c=0";

        for (String key : new String[] { disputed, tied, voiceAltered }) {
            sidecar.notes.put(key, record(key, null));
        }

        sidecar.registerWindow("w2", List.of(disputed, tied, voiceAltered));
        sidecar.registerWindow("w3", List.of(disputed, tied));

        final java.nio.file.Path root = java.nio.file.Files.createTempDirectory("part-vote");
        java.nio.file.Files.createDirectories(root.resolve("windows").resolve("w2"));
        java.nio.file.Files.createDirectories(root.resolve("windows").resolve("w3"));
        java.nio.file.Files.writeString(
                root.resolve("part-resolution-sidecar.json"),
                new com.google.gson.Gson().toJson(sidecar));

        // w2 says P2 (0.97) for the disputed note, w3 says P1 (0.99): higher confidence wins.
        // The tied note gets the same confidence from both windows.
        // The model wrote v=1 where the voice is 2.
        java.nio.file.Files.writeString(
                root.resolve("windows").resolve("w2").resolve("response.json"),
                "{\"windowId\":\"w2\",\"assignments\":["
                        + "{\"noteKey\":\"" + disputed + "\",\"partIndex\":2,\"confidence\":0.97},"
                        + "{\"noteKey\":\"" + tied + "\",\"partIndex\":1,\"confidence\":0.9},"
                        + "{\"noteKey\":\"p=P2|m=2|i=0|n=2|v=1|s=1|d=2|pc=B02|c=0\",\"partIndex\":4,\"confidence\":0.9}]}");
        java.nio.file.Files.writeString(
                root.resolve("windows").resolve("w3").resolve("response.json"),
                "{\"windowId\":\"w3\",\"assignments\":["
                        + "{\"noteKey\":\"" + disputed + "\",\"partIndex\":1,\"confidence\":0.99},"
                        + "{\"noteKey\":\"" + tied + "\",\"partIndex\":2,\"confidence\":0.9}]}");

        final PartResolutionMockResponseFramework.IngestReport report = PartResolutionMockResponseFramework
                .ingestResponses(root, 4);

        assertEquals(2, report.windowsAccepted);
        assertEquals(1, report.keysRepaired);
        assertEquals(2, report.disputedNotes);
        assertEquals(1, report.tiedNotes);

        final PartResolutionSidecar after = new com.google.gson.Gson().fromJson(
                java.nio.file.Files.readString(root.resolve("part-resolution-sidecar.json")),
                PartResolutionSidecar.class);
        assertEquals("P1", after.notes.get(disputed).chosenPart);
        assertEquals("resolved", after.notes.get(disputed).status);
        assertEquals("ambiguous", after.notes.get(tied).status);
        assertEquals("P4", after.notes.get(voiceAltered).chosenPart);

        // One tie out of three assigned notes is above the 5% threshold, so this plan is gated
        final PartResolutionRewritePlanner.RewriteReport plan = PartResolutionRewritePlanner
                .buildRewriteArtifacts(root, 2);
        assertEquals(1, plan.ambiguousNotes);
        assertTrue(plan.discrepancyGateTriggered);
    }

    @Test
    public void testFixtureRoundTrip ()
        throws Exception
    {
        final PartResolutionSidecar sidecar = new PartResolutionSidecar();
        sidecar.targetPartCount = 4;
        sidecar.targetPartLabels.addAll(List.of("P1", "P2", "P3", "P4"));

        final String[][] rows = {
            { "p=P1|m=1|i=0|n=1|v=1|s=1|d=2|pc=G04|c=0", "P1" },
            { "p=P1|m=1|i=0|n=2|v=2|s=1|d=2|pc=E04|c=0", "P2" },
            { "p=P2|m=1|i=0|n=1|v=1|s=1|d=2|pc=B03|c=0", "P3" },
            { "p=P2|m=1|i=0|n=2|v=2|s=1|d=2|pc=G02|c=0", "P4" } };

        for (String[] row : rows) {
            final PartResolutionSidecar.NoteRecord r = record(row[0], row[1]);
            r.sourceWindows.add("s01-w001");
            sidecar.notes.put(r.noteKey, r);
        }

        final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("part-fixture");
        final java.nio.file.Path fixture = PartResolutionFixture.write(
                dir.resolve("fx"),
                "test-book",
                "run",
                sidecar,
                java.util.Map.of(1, parse(TWO_STAFF_SATB)));

        final String report = PartResolutionFixture.rebuild(fixture, null);

        assertTrue(report, report.contains("aiAssigned=4"));
        assertTrue(report, report.contains("unmatchedAssignments=0"));

        final java.nio.file.Path xml = fixture.resolve("rebuilt").resolve("score-s01-rebuilt.xml");
        assertTrue(java.nio.file.Files.exists(xml));
        assertTrue(java.nio.file.Files.exists(fixture.resolve("rebuilt").resolve("score-s01-rebuilt.mxl")));

        try (java.io.InputStream is = java.nio.file.Files.newInputStream(xml)) {
            assertEquals(
                    4,
                    PartResolutionRewriteApplier.readDocument(is).getElementsByTagName("part").getLength());
        }

        assertEquals(1, PartResolutionFixture.list(dir).size());
    }
}