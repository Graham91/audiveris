/*
 * Copyright © Audiveris 2026. All rights reserved.
 * This software is released under the GNU General Public License.
 */
package org.audiveris.omr.score.resolution;

import org.audiveris.omr.util.BaseTestCase;

import org.junit.Test;

import java.util.List;

/**
 * Tests for payload draft generation.
 */
public class PartResolutionPayloadBuilderTest
        extends BaseTestCase
{
    @Test
    public void testDraftCountMatchesWindowCount ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(4, 5, 3);
        List<PartResolutionRequestDraft> drafts = PartResolutionPayloadBuilder.buildDrafts(
                List.of(plan),
                "gpt-5.1");

        assertEquals(3, drafts.size());
    }

    @Test
    public void testFirstDraftFields ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(2, 3, 3);
        List<PartResolutionRequestDraft> drafts = PartResolutionPayloadBuilder.buildDrafts(
                List.of(plan),
                "gpt-5.1");

        PartResolutionRequestDraft first = drafts.get(0);
        assertEquals("s02-m0001-0003-w0001", first.getWindowId());
        assertEquals(2, first.getScoreId());
        assertEquals(1, first.getStartMeasure());
        assertEquals(3, first.getEndMeasure());
        assertEquals("gpt-5.1", first.getModelName());
    }
}