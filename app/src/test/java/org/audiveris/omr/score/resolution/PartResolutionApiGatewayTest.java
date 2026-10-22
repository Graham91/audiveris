/*
 * Copyright © Audiveris 2026. All rights reserved.
 * This software is released under the GNU General Public License.
 */
package org.audiveris.omr.score.resolution;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.audiveris.omr.util.BaseTestCase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the request sent to the model.
 */
public class PartResolutionApiGatewayTest
        extends BaseTestCase
{
    private static PartResolutionApiGateway.SubmissionBundle bundle (int imageCount)
    {
        final PartResolutionApiGateway.SubmissionBundle b =
                new PartResolutionApiGateway.SubmissionBundle();
        b.windowId = "s01-m0001-0003-w0001";
        b.targetPartCount = 4;
        b.noteKeys = List.of("s1n4971");
        b.notesJson = "{\"windowId\":\"s01-m0001-0003-w0001\",\"notes\":[]}";
        b.images = new ArrayList<>();

        for (int i = 1; i <= imageCount; i++) {
            final PartResolutionApiGateway.ImageContent image =
                    new PartResolutionApiGateway.ImageContent();
            image.name = "sheet-01-system-0" + i + ".png";
            image.label = "measure stacks " + i + "-" + (i + 2) + " (sheet1-sys" + i + ")";
            image.dataUrl = "data:image/png;base64,AAAA";
            b.images.add(image);
        }

        return b;
    }

    @Test
    public void testInstructionHasRealLineBreaks ()
    {
        final String text = PartResolutionApiGateway.buildInstructionText(bundle(1));

        assertFalse("literal backslash-n found", text.contains("\\n"));
        assertTrue(text.contains("\nwindowId=s01-m0001-0003-w0001\n"));
        assertTrue(text.contains("\nnoteKeys=[\"s1n4971\"]\n"));
        assertTrue(text.contains("\nnotes=\n{\"windowId\""));
        assertFalse(text.contains("chunkXml"));
        assertTrue(text.contains("1=Soprano, 2=Alto, 3=Tenor, 4=Bass"));
        assertTrue(text.contains("JSON"));
    }

    @Test
    public void testPayloadCarriesLabelledHighDetailImages ()
    {
        final JsonObject payload = PartResolutionApiGateway.buildResponsesPayload(
                "gpt-5.1",
                bundle(2));

        final JsonArray content = payload.getAsJsonArray("input").get(0).getAsJsonObject()
                .getAsJsonArray("content");

        // instruction, then caption + image for each of the two images
        assertEquals(5, content.size());
        assertEquals("input_text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(
                "Image 1 of 2: measure stacks 1-3 (sheet1-sys1)",
                content.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("input_image", content.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("high", content.get(2).getAsJsonObject().get("detail").getAsString());
        assertEquals(
                "Image 2 of 2: measure stacks 2-4 (sheet1-sys2)",
                content.get(3).getAsJsonObject().get("text").getAsString());
        assertEquals(
                "data:image/png;base64,AAAA",
                content.get(4).getAsJsonObject().get("image_url").getAsString());
    }

    @Test
    public void testReasoningEffortIsSentUnlessOmitted ()
    {
        final String previous = PartResolutionSettings.getReasoningEffort();

        try {
            PartResolutionSettings.setReasoningEffort("high");
            JsonObject payload = PartResolutionApiGateway.buildResponsesPayload("m", bundle(1));
            assertEquals(
                    "high",
                    payload.getAsJsonObject("reasoning").get("effort").getAsString());

            PartResolutionSettings.setReasoningEffort("omit");
            payload = PartResolutionApiGateway.buildResponsesPayload("m", bundle(1));
            assertFalse(payload.has("reasoning"));
        } finally {
            PartResolutionSettings.setReasoningEffort(previous.isEmpty() ? "omit" : previous);
        }
    }
}
