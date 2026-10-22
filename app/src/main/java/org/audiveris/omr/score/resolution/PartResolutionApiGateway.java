//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                           P a r t R e s o l u t i o n A p i G a t e w a y                      //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
package org.audiveris.omr.score.resolution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Submits window bundles to an OpenAI-compatible Responses API endpoint.
 */
public abstract class PartResolutionApiGateway
{
    private static final Logger logger = LoggerFactory.getLogger(PartResolutionApiGateway.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PartResolutionApiGateway ()
    {
    }

    /**
     * Submit a minimal hello request against the configured Responses API.
     *
     * @param endpoint       endpoint URL
     * @param model          model name
     * @param apiKey         bearer API key
     * @param timeoutSeconds HTTP timeout in seconds
     * @return ping result with HTTP status and extracted text if available
     * @throws IOException          if request I/O fails
     * @throws InterruptedException if call is interrupted
     */
    public static PingResult pingHello (String endpoint,
                                        String model,
                                        String apiKey,
                                        int timeoutSeconds)
        throws IOException, InterruptedException
    {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(
                Duration.ofSeconds(Math.max(1, timeoutSeconds))).build();

        final JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        final JsonArray input = new JsonArray();
        payload.add("input", input);

        final JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        final JsonArray content = new JsonArray();
        userMessage.add("content", content);
        final JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty(
                "text",
                "Say hello from Audiveris part-resolution diagnostics in one short sentence.");
        content.add(text);
        input.add(userMessage);

        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        final PingResult result = new PingResult();
        result.httpStatus = response.statusCode();
        result.rawResponse = response.body();

        try {
            final JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
            result.responseText = extractResponseText(root);
        } catch (Exception ex) {
            logger.debug("Ping response text extraction failed: {}", ex.toString());
        }

        return result;
    }

    public static ApiSubmitReport submitRequests (Path artifactsRoot,
                                                  String endpoint,
                                                  String model,
                                                  String apiKey,
                                                  int timeoutSeconds,
                                                  int maxRequests)
        throws IOException, InterruptedException
    {
        final ApiSubmitReport report = new ApiSubmitReport();
        report.artifactsRoot = artifactsRoot.toString();
        report.endpoint = endpoint;
        report.model = model;
        report.timeoutSeconds = Math.max(1, timeoutSeconds);
        report.maxRequests = Math.max(1, maxRequests);

        final Path windowsRoot = artifactsRoot.resolve("windows");

        if (!Files.isDirectory(windowsRoot)) {
            report.messages.add("windows folder not found: " + windowsRoot);
            writeReportFiles(artifactsRoot, report);
            return report;
        }

        final HttpClient client = HttpClient.newBuilder().connectTimeout(
                Duration.ofSeconds(Math.max(1, timeoutSeconds))).build();
        final List<Path> windowDirs = Files.list(windowsRoot).filter(Files::isDirectory).sorted(
                Comparator.comparing(p -> p.getFileName().toString())).toList();
        report.windowsSeen = windowDirs.size();

        logger.info(
                "API submission start: windows={}, maxRequests={}, endpoint={}, model={}",
                report.windowsSeen,
                report.maxRequests,
                endpoint,
                model);

        int submitted = 0;

        for (Path windowDir : windowDirs) {
            final String windowName = windowDir.getFileName().toString();

            if (submitted >= Math.max(1, maxRequests)) {
                logger.info("API throttle reached ({}). Remaining windows skipped.", maxRequests);
                report.messages.add("throttle reached after " + submitted + " submission(s)");
                break;
            }

            final Path responsePath = windowDir.resolve("response.json");

            if (Files.exists(responsePath)) {
                logger.info("API skip {}: response.json already exists", windowName);
                report.windowsSkippedExistingResponse++;
                continue;
            }

            final SubmissionBundle bundle = readBundle(windowDir);

            if (bundle == null) {
                logger.warn("API skip {}: missing/invalid window bundle", windowName);
                report.windowsSkippedInvalidBundle++;
                continue;
            }

            logger.info(
                    "API submit {}: noteKeys={}, images={}, targetPartCount={}",
                    windowName,
                    bundle.noteKeys.size(),
                    bundle.images.size(),
                    bundle.targetPartCount);

            final JsonObject payload = buildResponsesPayload(model, bundle);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();

            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Files.writeString(windowDir.resolve("api-raw-response.json"), response.body());
        report.windowsAttempted++;

        logger.info("API response {}: HTTP {}", windowName, response.statusCode());

            if ((response.statusCode() < 200) || (response.statusCode() >= 300)) {
                logger.warn(
                        "API request failed for {}: HTTP {}",
            windowName,
                        response.statusCode());
        report.windowsHttpFailed++;
                continue;
            }

            final PartResolutionMockResponseFramework.MockResponse mapped = toMockResponse(
                    response.body(),
                    bundle.windowId,
                    bundle.targetPartCount);

            if ((mapped == null) || (mapped.assignments == null) || mapped.assignments.isEmpty()) {
                logger.warn("No usable assignments in API response for {}", bundle.windowId);
                report.windowsParseFailed++;
                continue;
            }

            Files.writeString(responsePath, GSON.toJson(mapped));
            submitted++;
            report.windowsAccepted++;
            report.responsesWritten++;
            report.assignmentsReturned += mapped.assignments.size();
            logger.info(
                    "API accepted {}: assignments={} -> {}",
                    windowName,
                    mapped.assignments.size(),
                    responsePath.getFileName());
        }

        report.submitted = submitted;
        logger.info(
                "API submission done: submitted={}, accepted={}, httpFailed={}, parseFailed={}, skippedExisting={}, skippedInvalid={}",
                report.submitted,
                report.windowsAccepted,
                report.windowsHttpFailed,
                report.windowsParseFailed,
                report.windowsSkippedExistingResponse,
                report.windowsSkippedInvalidBundle);
        writeReportFiles(artifactsRoot, report);

        return report;
    }

    private static SubmissionBundle readBundle (Path windowDir)
        throws IOException
    {
        final Path noteKeysPath = windowDir.resolve("note-keys.json");
        final Path manifestPath = windowDir.resolve("manifest.json");

        if (!Files.exists(noteKeysPath) || !Files.exists(manifestPath)) {
            return null;
        }

        final Type listType = new TypeToken<List<String>>()
        {
        }.getType();
        final List<String> noteKeys = GSON.fromJson(Files.readString(noteKeysPath), listType);
        final JsonObject manifest = GSON.fromJson(Files.readString(manifestPath), JsonObject.class);

        final String windowId = manifest.get("windowId").getAsString();
        final int targetPartCount = manifest.has("targetPartCount")
                ? manifest.get("targetPartCount").getAsInt() : 4;

        final String notesJson = readNotesJson(windowDir);
        final List<ImageContent> images = readImages(windowDir, manifest);

        if ((notesJson == null) || noteKeys.isEmpty() || images.isEmpty()) {
            return null;
        }

        final SubmissionBundle bundle = new SubmissionBundle();
        bundle.windowId = windowId;
        bundle.targetPartCount = targetPartCount;
        bundle.noteKeys = noteKeys;
        bundle.notesJson = notesJson;
        bundle.images = images;

        return bundle;
    }

    private static String readNotesJson (Path windowDir)
        throws IOException
    {
        final Path path = windowDir.resolve(PartResolutionArtifactExporter.WINDOW_NOTES_NAME);

        return Files.exists(path) ? Files.readString(path) : null;
    }

    private static List<ImageContent> readImages (Path windowDir,
                                                  JsonObject manifest)
        throws IOException
    {
        final List<ImageContent> images = new ArrayList<>();

        if (!manifest.has("images")) {
            return images;
        }

        // Segment entries look like "sheet-01-system-01.png@m1-3@sheet1-sys1"
        final Map<String, String> segmentInfo = new HashMap<>();

        if (manifest.has("imageSegments")) {
            for (JsonElement s : manifest.getAsJsonArray("imageSegments")) {
                final String[] parts = s.getAsString().split("@");

                if (parts.length >= 2) {
                    segmentInfo.put(
                            parts[0],
                            "measure stacks " + parts[1].replaceFirst("^m", "")
                                    + ((parts.length > 2) ? " (" + parts[2] + ")" : ""));
                }
            }
        }

        final JsonArray arr = manifest.getAsJsonArray("images");

        for (JsonElement e : arr) {
            final String name = e.getAsString();
            final Path path = windowDir.resolve(name);

            if (!Files.exists(path)) {
                continue;
            }

            final byte[] bytes = Files.readAllBytes(path);
            final String b64 = Base64.getEncoder().encodeToString(bytes);
            final ImageContent image = new ImageContent();
            image.name = name;
            image.label = segmentInfo.getOrDefault(name, name);
            image.dataUrl = "data:image/png;base64," + b64;
            images.add(image);
        }

        return images;
    }

    static JsonObject buildResponsesPayload (String model,
                                                     SubmissionBundle bundle)
    {
        final JsonObject payload = new JsonObject();
        payload.addProperty("model", model);

        final JsonArray input = new JsonArray();
        payload.add("input", input);

        final JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        final JsonArray content = new JsonArray();
        userMessage.add("content", content);

        final JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", buildInstructionText(bundle));
        content.add(text);

        int imageIndex = 0;

        for (ImageContent image : bundle.images) {
            imageIndex++;

            final JsonObject caption = new JsonObject();
            caption.addProperty("type", "input_text");
            caption.addProperty(
                    "text",
                    "Image " + imageIndex + " of " + bundle.images.size() + ": " + image.label);
            content.add(caption);

            final JsonObject img = new JsonObject();
            img.addProperty("type", "input_image");
            img.addProperty("image_url", image.dataUrl);
            img.addProperty("detail", "high");
            content.add(img);
        }

        input.add(userMessage);

        final String effort = PartResolutionSettings.getReasoningEffort();

        if (!effort.isEmpty()) {
            final JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", effort);
            payload.add("reasoning", reasoning);
        }

        final JsonObject textCfg = new JsonObject();
        final JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        textCfg.add("format", format);
        payload.add("text", textCfg);

        return payload;
    }

    static String buildInstructionText (SubmissionBundle bundle)
    {
        final int n = bundle.targetPartCount;
        final StringBuilder sb = new StringBuilder();

        sb.append("You are separating the voices of a short excerpt of an SATB choral score.\n");
        sb.append("The score was read by OMR. It is usually written on two staves (treble and bass), ")
                .append("each holding two voices as stems-up and stems-down lines or as chords.\n");
        sb.append("Assign every note to one of ").append(n).append(" parts: partIndex 1 is the ")
                .append("highest-sounding line and partIndex ").append(n).append(" the lowest");

        if (n == 4) {
            sb.append(" (1=Soprano, 2=Alto, 3=Tenor, 4=Bass)");
        }

        sb.append(".\n\n");

        sb.append("Inputs:\n");
        sb.append("- The images show this excerpt, one image per system, measures left to right. ")
                .append("They are the reference: the note list below is only what OMR read from them ")
                .append("and may contain errors.\n");
        sb.append("- notes is that list. Each note has: id; m (measure index, as in the measures list); ")
                .append("staff (P1 is the top staff of the system, P2 the one below) and line (staff ")
                .append("index inside it); pitch (e.g. F#3, or rest); dur (written duration as a ")
                .append("fraction of a whole note, so 1/4 is a quarter); x (position in tenths from the ")
                .append("left barline of the measure: notes with the same x, on any staff, sound at the ")
                .append("same time, and a larger x is later); stem (up or down); chord (heads with the ")
                .append("same chord value share one stem); tie; lyric; grace.\n");
        sb.append("- measures lists each measure with its time signature (time) and implicit=true for ")
                .append("a pickup or a partial measure. A measure that is shorter than its time ")
                .append("signature is usually completed by the next one.\n");
        sb.append("- Voice numbers and start times are deliberately not given: OMR guesses them badly. ")
                .append("Work them out from x, dur and the images.\n\n");

        sb.append("Rules:\n");
        sb.append("- Notes that sound together on one staff belong to different parts; ")
                .append("the higher note goes to the higher part.\n");
        sb.append("- One part sings one note at a time: never give two simultaneous notes to the ")
                .append("same part. A rest goes to the part of the voice it sits in.\n");
        sb.append("- Use stem direction, vertical position, x alignment, dur and lyrics. Heads of one ")
                .append("chord may belong to different parts (for example soprano and alto sharing a ")
                .append("stem): assign each note, not each chord.\n");
        sb.append("- Check yourself: within one measure, the durations of the notes of one part ")
                .append("should add up to the measure length, and their x values should increase ")
                .append("without two of them sounding together.\n");
        sb.append("- Return every noteKey of the list exactly once, unchanged (it is the note id). ")
                .append("Do not invent keys.\n");
        sb.append("- confidence is your honest certainty from 0 to 1; use lower values where the ")
                .append("score is unclear.\n\n");

        sb.append("Return only JSON: {\"schemaVersion\": \"")
                .append(PartResolutionContract.SCHEMA_VERSION)
                .append("\", \"windowId\": <windowId>, \"targetPartCount\": ").append(n)
                .append(", \"assignments\": [{\"noteKey\": ..., \"partIndex\": 1..").append(n)
                .append(", \"confidence\": 0..1}]}\n\n");

        sb.append("windowId=").append(bundle.windowId).append('\n');
        sb.append("targetPartCount=").append(n).append('\n');
        sb.append("noteKeys=").append(new Gson().toJson(bundle.noteKeys)).append('\n');
        sb.append("notes=\n").append(bundle.notesJson);

        return sb.toString();
    }

    private static PartResolutionMockResponseFramework.MockResponse toMockResponse (String raw,
                                                                                     String windowId,
                                                                                     int targetPartCount)
    {
        try {
            final JsonObject root = GSON.fromJson(raw, JsonObject.class);
            final String text = extractResponseText(root);

            if ((text == null) || text.isBlank()) {
                return null;
            }

            final PartResolutionMockResponseFramework.MockResponse parsed = GSON.fromJson(
                    text,
                    PartResolutionMockResponseFramework.MockResponse.class);

            if (parsed != null) {
                if ((parsed.windowId == null) || parsed.windowId.isBlank()) {
                    parsed.windowId = windowId;
                }

                if (parsed.targetPartCount <= 0) {
                    parsed.targetPartCount = targetPartCount;
                }

                if (parsed.schemaVersion == null || parsed.schemaVersion.isBlank()) {
                    parsed.schemaVersion = PartResolutionContract.SCHEMA_VERSION;
                }
            }

            return parsed;
        } catch (Exception ex) {
            logger.warn("Cannot parse API response JSON: {}", ex.toString());
            return null;
        }
    }

    private static String extractResponseText (JsonObject root)
    {
        if (root == null) {
            return null;
        }

        String text = null;

        if (root.has("output_text") && root.get("output_text").isJsonPrimitive()) {
            text = root.get("output_text").getAsString();
        }

        if ((text == null) && root.has("output") && root.get("output").isJsonArray()) {
            final JsonArray output = root.getAsJsonArray("output");

            for (JsonElement itemEl : output) {
                if (!itemEl.isJsonObject()) {
                    continue;
                }

                final JsonObject item = itemEl.getAsJsonObject();

                if (!item.has("content") || !item.get("content").isJsonArray()) {
                    continue;
                }

                for (JsonElement cEl : item.getAsJsonArray("content")) {
                    if (!cEl.isJsonObject()) {
                        continue;
                    }

                    final JsonObject c = cEl.getAsJsonObject();

                    if (c.has("text") && c.get("text").isJsonPrimitive()) {
                        text = c.get("text").getAsString();
                        break;
                    }
                }

                if (text != null) {
                    break;
                }
            }
        }

        return text;
    }

    private static void writeReportFiles (Path artifactsRoot,
                                          ApiSubmitReport report)
        throws IOException
    {
        Files.writeString(artifactsRoot.resolve("api-submit-report.json"), GSON.toJson(report));
        Files.writeString(artifactsRoot.resolve("api-submit-report.txt"), report.toText());
    }

    static class SubmissionBundle
    {
        String windowId;

        int targetPartCount;

        List<String> noteKeys;

        String notesJson;

        List<ImageContent> images;
    }

    static class ImageContent
    {
        String name;

        String label;

        String dataUrl;
    }

    public static class ApiSubmitReport
    {
        public String artifactsRoot;

        public String endpoint;

        public String model;

        public int timeoutSeconds;

        public int maxRequests;

        public int windowsSeen;

        public int windowsAttempted;

        public int windowsAccepted;

        public int windowsHttpFailed;

        public int windowsParseFailed;

        public int windowsSkippedExistingResponse;

        public int windowsSkippedInvalidBundle;

        public int responsesWritten;

        public int assignmentsReturned;

        public int submitted;

        public List<String> messages = new ArrayList<>();

        public String toText ()
        {
            final StringBuilder sb = new StringBuilder();
            sb.append("endpoint=").append(endpoint).append(System.lineSeparator());
            sb.append("model=").append(model).append(System.lineSeparator());
            sb.append("timeoutSeconds=").append(timeoutSeconds).append(System.lineSeparator());
            sb.append("maxRequests=").append(maxRequests).append(System.lineSeparator());
            sb.append("windowsSeen=").append(windowsSeen).append(System.lineSeparator());
            sb.append("windowsAttempted=").append(windowsAttempted).append(System.lineSeparator());
            sb.append("windowsAccepted=").append(windowsAccepted).append(System.lineSeparator());
            sb.append("windowsHttpFailed=").append(windowsHttpFailed).append(System.lineSeparator());
            sb.append("windowsParseFailed=").append(windowsParseFailed).append(System.lineSeparator());
            sb.append("windowsSkippedExistingResponse=").append(windowsSkippedExistingResponse).append(
                    System.lineSeparator());
            sb.append("windowsSkippedInvalidBundle=").append(windowsSkippedInvalidBundle).append(
                    System.lineSeparator());
            sb.append("responsesWritten=").append(responsesWritten).append(System.lineSeparator());
            sb.append("assignmentsReturned=").append(assignmentsReturned).append(System.lineSeparator());
            sb.append("submitted=").append(submitted).append(System.lineSeparator());

            if (!messages.isEmpty()) {
                sb.append("messages=").append(System.lineSeparator());

                for (String msg : messages) {
                    sb.append("- ").append(msg).append(System.lineSeparator());
                }
            }

            return sb.toString();
        }
    }

    public static class PingResult
    {
        public int httpStatus;

        public String responseText;

        public String rawResponse;
    }
}
