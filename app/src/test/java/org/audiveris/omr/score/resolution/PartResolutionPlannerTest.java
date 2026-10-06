/*
 * Copyright © Audiveris 2026. All rights reserved.
 * This software is released under the GNU General Public License.
 */
package org.audiveris.omr.score.resolution;

import org.audiveris.omr.util.BaseTestCase;

import org.junit.Test;

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

        assertEquals(3, plan.getWindowCount());
        assertEquals("s03-m0001-0003-w0001", plan.getWindows().get(0).getId());
        assertEquals("s03-m0002-0004-w0002", plan.getWindows().get(1).getId());
        assertEquals("s03-m0003-0005-w0003", plan.getWindows().get(2).getId());
    }

    @Test
    public void testNoMeasures ()
    {
        PartResolutionPlan plan = PartResolutionPlanner.planForTotalMeasures(1, 0, 3);

        assertEquals(0, plan.getWindowCount());
    }
}