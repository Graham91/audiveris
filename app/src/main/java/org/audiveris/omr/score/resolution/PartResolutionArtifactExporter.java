//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                  P a r t R e s o l u t i o n A r t i f a c t E x p o r t e r                 //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.score.resolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sig.inter.AbstractTimeInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Exports local debug artifacts for part-resolution windows.
 */
public abstract class PartResolutionArtifactExporter
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(PartResolutionArtifactExporter.class);

    /** Name of the file holding the OMR notes of a window. */
    public static final String WINDOW_NOTES_NAME = "notes.json";

    private static final int IMAGE_MARGIN = 20;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** For files sent to the AI as they are. */
    private static final Gson COMPACT_GSON = new Gson();

    //~ Constructors -------------------------------------------------------------------------------

    private PartResolutionArtifactExporter ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //----------------------//
    // exportDebugArtifacts //
    //----------------------//
    /**
     * Export all local artifacts needed to inspect outgoing AI payloads.
     *
     * @param book   current book
     * @param plans  planned windows
     * @param drafts request drafts (used for per-window manifest)
     * @return root folder that contains generated artifacts
     * @throws Exception if export fails
     */
    public static Path exportDebugArtifacts (Book book,
                                             List<PartResolutionPlan> plans,
                                             List<PartResolutionRequestDraft> drafts)
        throws Exception
    {
        final String radix = sanitize(book.getRadix());
        final Path root = WellKnowns.TEMP_FOLDER.resolve("part-resolution-debug").resolve(
                radix + "-" + STAMP.format(LocalDateTime.now()));
        final Path windowsRoot = root.resolve("windows");

        Files.createDirectories(windowsRoot);

        final PartResolutionSidecar sidecar = new PartResolutionSidecar();
        sidecar.book = book.getRadix();
        sidecar.generatedAt = STAMP.format(LocalDateTime.now());
        sidecar.targetPartCount = PartResolutionSettings.getTargetPartCount();

        for (int i = 1; i <= sidecar.targetPartCount; i++) {
            sidecar.targetPartLabels.add("P" + i);
        }

        final Map<String, PartResolutionRequestDraft> draftByWindowId = new LinkedHashMap<>();
        final List<WindowManifestContract> windowContracts = new ArrayList<>();

        for (PartResolutionRequestDraft draft : drafts) {
            draftByWindowId.put(draft.getWindowId(), draft);
        }

        int windowCount = 0;

        for (PartResolutionPlan plan : plans) {
            final Score score = findScore(book, plan.getScoreId());

            if (score == null) {
                logger.warn("Cannot find score #{} for artifact export.", plan.getScoreId());

                continue;
            }

            final List<StackRef> stackRefs = buildMeasureIndex(score);
            final List<PartResolutionOmrNote> scoreNotes = readScoreNotes(score, stackRefs);

            for (PartResolutionWindow window : plan.getWindows()) {
                final WindowManifestContract contract = exportWindowArtifacts(
                        windowsRoot,
                        score,
                        window,
                        stackRefs,
                        scoreNotes,
                        draftByWindowId.get(window.getId()),
                        sidecar);

                if (contract != null) {
                    windowContracts.add(contract);
                }

                windowCount++;
            }
        }

        final String summary = "book=" + book.getRadix() + System.lineSeparator() + "windows="
                + windowCount + System.lineSeparator() + "generatedAt=" + STAMP.format(
                        LocalDateTime.now()) + System.lineSeparator();
        Files.writeString(root.resolve("manifest.txt"), summary);

        final RootManifestContract rootContract = new RootManifestContract(
            PartResolutionContract.SCHEMA_VERSION,
            book.getRadix(),
            STAMP.format(LocalDateTime.now()),
            windowCount,
            windowContracts);
        Files.writeString(root.resolve("manifest.json"), GSON.toJson(rootContract));

        sidecar.rebuildSecondPassQueue();
        Files.writeString(root.resolve("part-resolution-sidecar.json"), GSON.toJson(sidecar));

        return root;
    }

    //-----------------------//
    // exportWindowArtifacts //
    //-----------------------//
    private static WindowManifestContract exportWindowArtifacts (Path windowsRoot,
                                                                 Score score,
                                                                 PartResolutionWindow window,
                                                                 List<StackRef> stackRefs,
                                                                 List<PartResolutionOmrNote> scoreNotes,
                                                                 PartResolutionRequestDraft draft,
                                                                 PartResolutionSidecar sidecar)
        throws Exception
    {
        final Path windowDir = windowsRoot.resolve(window.getId());
        Files.createDirectories(windowDir);

        final List<StackRef> selectedRefs = selectMeasureRange(
                stackRefs,
                window.getStartMeasure(),
                window.getEndMeasure());

        if (selectedRefs.isEmpty()) {
            return null;
        }

        final List<ImageSegment> imageSegments = writeWindowImages(windowDir, selectedRefs);
        final List<PartResolutionOmrNote> windowNotes = new ArrayList<>();

        for (PartResolutionOmrNote n : scoreNotes) {
            if ((n.m >= window.getStartMeasure()) && (n.m <= window.getEndMeasure())) {
                windowNotes.add(n);
            }
        }

        Files.writeString(
                windowDir.resolve(WINDOW_NOTES_NAME),
                COMPACT_GSON.toJson(buildWindowNotes(window.getId(), selectedRefs, windowNotes)));

        final List<String> noteKeys = PartResolutionSidecarBuilder.registerWindowNotes(
                sidecar,
                windowNotes,
                window.getId());
        Files.writeString(windowDir.resolve("note-keys.json"), GSON.toJson(noteKeys));

        final WindowManifestContract contract = buildWindowManifestContract(
            score,
            window,
            draft,
            imageSegments,
            selectedRefs,
            noteKeys.size(),
            "note-keys.json");
        writeWindowManifest(windowDir, contract);

        return contract;
    }

    //-------------------//
    // writeWindowImage  //
    //-------------------//
    private static List<ImageSegment> writeWindowImages (Path windowDir,
                                                         List<StackRef> selectedRefs)
        throws IOException
    {
        final List<ImageSegment> segmentsMeta = new ArrayList<>();
        final List<List<StackRef>> segments = splitIntoSegments(selectedRefs);
        final Map<String, Integer> nameCounters = new LinkedHashMap<>();

        for (List<StackRef> refs : segments) {
            final int sheetNumber = refs.get(0).sheet.getStub().getNumber();
            final int systemNumber = refs.get(0).system.getId();
            final Rectangle rect = buildImageRect(refs);

            if (rect == null) {
                continue;
            }

            final Sheet sheet = refs.get(0).sheet;
            final BufferedImage image = sheet.getPicture().getImageRectangle(rect);
            final String key = sheetNumber + "-" + systemNumber;
            final int segmentIndex = nameCounters.merge(key, 1, Integer::sum);
            final String name = (segmentIndex == 1)
                    ? String.format("sheet-%02d-system-%02d.png", sheetNumber, systemNumber)
                    : String.format(
                            "sheet-%02d-system-%02d-part-%02d.png",
                            sheetNumber,
                            systemNumber,
                            segmentIndex);
            final Path target = windowDir.resolve(name);

            ImageIO.write(image, "png", target.toFile());

            final int startMeasure = refs.get(0).measureNumber;
            final int endMeasure = refs.get(refs.size() - 1).measureNumber;
            segmentsMeta.add(
                    new ImageSegment(
                            name,
                            sheetNumber,
                            systemNumber,
                            startMeasure,
                            endMeasure));
        }

        return segmentsMeta;
    }

    //-------------------//
    // buildImageRect    //
    //-------------------//
    private static Rectangle buildImageRect (List<StackRef> refs)
    {
        if (refs.isEmpty()) {
            return null;
        }

        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        final Sheet sheet = refs.get(0).sheet;

        for (StackRef ref : refs) {
            final Rectangle systemBounds = ref.system.getBounds();

            if (systemBounds == null) {
                continue;
            }

            left = Math.min(left, ref.stack.getLeft());
            right = Math.max(right, ref.stack.getRight());
            top = Math.min(top, systemBounds.y);
            bottom = Math.max(bottom, systemBounds.y + systemBounds.height - 1);
        }

        if (left > right || top > bottom) {
            return null;
        }

        final int x0 = clamp(left - IMAGE_MARGIN, 0, sheet.getWidth() - 1);
        final int y0 = clamp(top - IMAGE_MARGIN, 0, sheet.getHeight() - 1);
        final int x1 = clamp(right + IMAGE_MARGIN, 0, sheet.getWidth() - 1);
        final int y1 = clamp(bottom + IMAGE_MARGIN, 0, sheet.getHeight() - 1);

        return new Rectangle(x0, y0, (x1 - x0) + 1, (y1 - y0) + 1);
    }

    //-------------------//
    // splitIntoSegments //
    //-------------------//
    /**
     * Split refs by contiguous sheet+system runs so windows crossing line breaks
     * produce one image per visible system.
     */
    private static List<List<StackRef>> splitIntoSegments (List<StackRef> refs)
    {
        final List<List<StackRef>> segments = new ArrayList<>();

        if (refs.isEmpty()) {
            return segments;
        }

        List<StackRef> current = new ArrayList<>();
        StackRef previous = null;

        for (StackRef ref : refs) {
            final boolean sameSegment = (previous != null) && (previous.sheet == ref.sheet)
                    && (previous.system == ref.system);

            if (!sameSegment && !current.isEmpty()) {
                segments.add(current);
                current = new ArrayList<>();
            }

            current.add(ref);
            previous = ref;
        }

        if (!current.isEmpty()) {
            segments.add(current);
        }

        return segments;
    }

    //----------------//
    // readScoreNotes //
    //----------------//
    /**
     * Read all the OMR notes of a score, each given the index of its measure.
     */
    private static List<PartResolutionOmrNote> readScoreNotes (Score score,
                                                               List<StackRef> stackRefs)
        throws Exception
    {
        final Map<MeasureStack, Integer> indexByStack = new IdentityHashMap<>();

        for (StackRef ref : stackRefs) {
            indexByStack.put(ref.stack, ref.measureNumber);
        }

        final List<PartResolutionOmrNote> notes = new ArrayList<>();

        for (PartResolutionOmrNote n : PartResolutionOmrExtractor.extract(score).notes) {
            final Integer index = indexByStack.get(n.stack);

            if (index != null) {
                n.m = index;
                notes.add(n);
            }
        }

        notes.sort(
                Comparator.comparingInt((PartResolutionOmrNote n) -> n.m).thenComparing(
                        n -> n.staff).thenComparingInt(n -> n.line).thenComparingInt(n -> n.x));

        return notes;
    }

    //------------------//
    // buildWindowNotes //
    //------------------//
    private static WindowNotes buildWindowNotes (String windowId,
                                                 List<StackRef> refs,
                                                 List<PartResolutionOmrNote> notes)
    {
        final WindowNotes wn = new WindowNotes();
        wn.windowId = windowId;

        for (StackRef ref : refs) {
            final MeasureInfo mi = new MeasureInfo();
            mi.m = ref.measureNumber;
            mi.implicit = ref.stack.isImplicit();
            final AbstractTimeInter time = ref.stack.getCurrentTimeSignature();

            if (time != null) {
                mi.time = time.getNumerator() + "/" + time.getDenominator();
            }

            wn.measures.add(mi);
        }

        wn.notes = notes;

        return wn;
    }

    //---------------------//
    // writeWindowManifest //
    //---------------------//
    private static WindowManifestContract buildWindowManifestContract (Score score,
                                                                       PartResolutionWindow window,
                                                                       PartResolutionRequestDraft draft,
                                                                       List<ImageSegment> imageSegments,
                                                                       List<StackRef> selectedRefs,
                                                                       int noteKeyCount,
                                                                       String noteKeysFile)
    {
        final int stackCount = selectedRefs.size();
        final int imageSegmentCount = imageSegments.size();
        final boolean splitImage = imageSegmentCount > 1;
        int implicitCount = 0;
        final List<String> pageIds = new ArrayList<>();
        final List<String> imageNames = new ArrayList<>();
        final List<String> segmentDescriptors = new ArrayList<>();

        for (ImageSegment segment : imageSegments) {
            imageNames.add(segment.name);
            segmentDescriptors.add(
                    segment.name + "@m" + segment.startMeasure + "-" + segment.endMeasure
                            + "@sheet" + segment.sheetNumber + "-sys" + segment.systemNumber);
        }

        for (StackRef ref : selectedRefs) {
            if (ref.stack.isImplicit()) {
                implicitCount++;
            }

            pageIds.add(ref.stack.getPageId());
        }

        return new WindowManifestContract(
            PartResolutionContract.SCHEMA_VERSION,
            window.getId(),
            score.getId(),
            window.getStartMeasure(),
            window.getEndMeasure(),
            "measure-stack",
            stackCount,
            implicitCount,
            pageIds,
            imageSegmentCount,
            splitImage,
            splitImage ? "cross-system-or-page" : "single-system",
            imageNames,
            segmentDescriptors,
                noteKeyCount,
                noteKeysFile,
            (draft != null) ? draft.getModelName() : null,
            (draft != null) ? draft.getInstruction() : null);
        }

        //---------------------//
        // writeWindowManifest //
        //---------------------//
        private static void writeWindowManifest (Path windowDir,
                             WindowManifestContract contract)
        throws IOException
        {
        final StringBuilder sb = new StringBuilder();
        sb.append("windowId=").append(contract.windowId).append(System.lineSeparator());
        sb.append("scoreId=").append(contract.scoreId).append(System.lineSeparator());
        sb.append("startMeasure=").append(contract.startMeasure).append(System.lineSeparator());
        sb.append("endMeasure=").append(contract.endMeasure).append(System.lineSeparator());
        sb.append("windowMeasureUnit=").append(contract.windowMeasureUnit).append(System.lineSeparator());
        sb.append("stackCount=").append(contract.stackCount).append(System.lineSeparator());
        sb.append("implicitStackCount=").append(contract.implicitStackCount).append(
            System.lineSeparator());
        sb.append("stackPageIds=").append(String.join(",", contract.stackPageIds)).append(
            System.lineSeparator());
        sb.append("imageSegmentCount=").append(contract.imageSegmentCount).append(
            System.lineSeparator());
        sb.append("splitImage=").append(contract.splitImage).append(System.lineSeparator());
        sb.append("splitTrigger=").append(contract.splitTrigger).append(System.lineSeparator());
        sb.append("imageSegments=").append(String.join(";", contract.imageSegments)).append(
            System.lineSeparator());
        sb.append("images=").append(String.join(",", contract.images)).append(System.lineSeparator());
        sb.append("noteKeyCount=").append(contract.noteKeyCount).append(System.lineSeparator());
        sb.append("noteKeysFile=").append(contract.noteKeysFile).append(System.lineSeparator());

        if (contract.model != null) {
            sb.append("model=").append(contract.model).append(System.lineSeparator());
        }

        if (contract.instruction != null) {
            sb.append("instruction=").append(contract.instruction).append(System.lineSeparator());
        }

        Files.writeString(windowDir.resolve("manifest.txt"), sb.toString());
        Files.writeString(windowDir.resolve("manifest.json"), GSON.toJson(contract));
    }

    //-------------------//
    // buildMeasureIndex //
    //-------------------//
    private static List<StackRef> buildMeasureIndex (Score score)
    {
        final List<StackRef> refs = new ArrayList<>();
        int index = 1;

        for (Page page : score.getPages()) {
            final Sheet sheet = page.getSheet();

            for (SystemInfo system : page.getSystems()) {
                for (MeasureStack stack : system.getStacks()) {
                    if (stack.isCautionary()) {
                        continue;
                    }

                    refs.add(new StackRef(index++, sheet, system, stack));
                }
            }
        }

        return refs;
    }

    //--------------------//
    // selectMeasureRange //
    //--------------------//
    private static List<StackRef> selectMeasureRange (List<StackRef> refs,
                                                      int startMeasure,
                                                      int endMeasure)
    {
        final List<StackRef> selected = new ArrayList<>();

        for (StackRef ref : refs) {
            if ((ref.measureNumber >= startMeasure) && (ref.measureNumber <= endMeasure)) {
                selected.add(ref);
            }
        }

        return selected;
    }

    //-----------//
    // findScore //
    //-----------//
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

    //-------//
    // clamp //
    //-------//
    private static int clamp (int value,
                              int min,
                              int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    //----------//
    // sanitize //
    //----------//
    private static String sanitize (String text)
    {
        if ((text == null) || text.isBlank()) {
            return "book";
        }

        return text.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // WindowNotes //
    //-----------//
    /** What the AI is told about the music of a window. */
    private static class WindowNotes
    {
        String windowId;

        final List<MeasureInfo> measures = new ArrayList<>();

        List<PartResolutionOmrNote> notes;
    }

    private static class MeasureInfo
    {
        int m;

        boolean implicit;

        String time;
    }

    //----------//
    // StackRef //
    //----------//
    private static class StackRef
    {
        final int measureNumber;

        final Sheet sheet;

        final SystemInfo system;

        final MeasureStack stack;

        StackRef (int measureNumber,
                  Sheet sheet,
                  SystemInfo system,
                  MeasureStack stack)
        {
            this.measureNumber = measureNumber;
            this.sheet = sheet;
            this.system = system;
            this.stack = stack;
        }
    }

    //--------------//
    // ImageSegment //
    //--------------//
    private static class ImageSegment
    {
        final String name;

        final int sheetNumber;

        final int systemNumber;

        final int startMeasure;

        final int endMeasure;

        ImageSegment (String name,
                      int sheetNumber,
                      int systemNumber,
                      int startMeasure,
                      int endMeasure)
        {
            this.name = name;
            this.sheetNumber = sheetNumber;
            this.systemNumber = systemNumber;
            this.startMeasure = startMeasure;
            this.endMeasure = endMeasure;
        }
    }

    //----------------------//
    // RootManifestContract //
    //----------------------//
    private static class RootManifestContract
    {
        final String schemaVersion;

        final String book;

        final String generatedAt;

        final int windows;

        final List<WindowManifestContract> windowManifests;

        RootManifestContract (String schemaVersion,
                              String book,
                              String generatedAt,
                              int windows,
                              List<WindowManifestContract> windowManifests)
        {
            this.schemaVersion = schemaVersion;
            this.book = book;
            this.generatedAt = generatedAt;
            this.windows = windows;
            this.windowManifests = (windowManifests != null) ? windowManifests
                    : Collections.emptyList();
        }
    }

    //------------------------//
    // WindowManifestContract //
    //------------------------//
    private static class WindowManifestContract
    {
        final String schemaVersion;

        final String windowId;

        final Integer scoreId;

        final int startMeasure;

        final int endMeasure;

        final String windowMeasureUnit;

        final int stackCount;

        final int implicitStackCount;

        final List<String> stackPageIds;

        final int imageSegmentCount;

        final boolean splitImage;

        final String splitTrigger;

        final List<String> images;

        final List<String> imageSegments;

        final int noteKeyCount;

        final String noteKeysFile;

        final String model;

        final String instruction;

        WindowManifestContract (String schemaVersion,
                                String windowId,
                                Integer scoreId,
                                int startMeasure,
                                int endMeasure,
                                String windowMeasureUnit,
                                int stackCount,
                                int implicitStackCount,
                                List<String> stackPageIds,
                                int imageSegmentCount,
                                boolean splitImage,
                                String splitTrigger,
                                List<String> images,
                                List<String> imageSegments,
                                int noteKeyCount,
                                String noteKeysFile,
                                String model,
                                String instruction)
        {
            this.schemaVersion = schemaVersion;
            this.windowId = windowId;
            this.scoreId = scoreId;
            this.startMeasure = startMeasure;
            this.endMeasure = endMeasure;
            this.windowMeasureUnit = windowMeasureUnit;
            this.stackCount = stackCount;
            this.implicitStackCount = implicitStackCount;
            this.stackPageIds = (stackPageIds != null) ? stackPageIds : Collections.emptyList();
            this.imageSegmentCount = imageSegmentCount;
            this.splitImage = splitImage;
            this.splitTrigger = splitTrigger;
            this.images = (images != null) ? images : Collections.emptyList();
            this.imageSegments = (imageSegments != null) ? imageSegments : Collections.emptyList();
            this.noteKeyCount = noteKeyCount;
            this.noteKeysFile = noteKeysFile;
            this.model = model;
            this.instruction = instruction;
        }
    }
}
