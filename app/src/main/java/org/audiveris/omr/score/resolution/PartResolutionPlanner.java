//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                           P a r t R e s o l u t i o n P l a n n e r                            //
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

import org.audiveris.omr.score.Page;
import org.audiveris.omr.score.Score;
import org.audiveris.omr.sheet.Book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds deterministic sliding windows for AI part-resolution processing.
 */
public abstract class PartResolutionPlanner
{
    //~ Constructors -------------------------------------------------------------------------------

    private PartResolutionPlanner ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //-----------------//
    // planForBook    //
    //-----------------//
    public static List<PartResolutionPlan> planForBook (Book book,
                                                         int chunkSize)
    {
        if ((book == null) || book.getScores().isEmpty()) {
            return Collections.emptyList();
        }

        final List<PartResolutionPlan> plans = new ArrayList<>();

        for (Score score : book.getScores()) {
            final Integer id = score.getId();
            final int scoreId = (id != null) ? id : (plans.size() + 1);
            final int totalMeasures = computeScoreMeasureCount(score);

            plans.add(planForTotalMeasures(scoreId, totalMeasures, chunkSize));
        }

        return plans;
    }

    //------------------------//
    // planForTotalMeasures  //
    //------------------------//
    public static PartResolutionPlan planForTotalMeasures (int scoreId,
                                                            int totalMeasures,
                                                            int chunkSize)
    {
        final int safeChunk = Math.max(1, chunkSize);
        final int safeMeasureCount = Math.max(0, totalMeasures);
        final List<PartResolutionWindow> windows = new ArrayList<>();

        if (safeMeasureCount > 0) {
            if (safeMeasureCount <= safeChunk) {
                windows.add(new PartResolutionWindow(scoreId, 1, 1, safeMeasureCount));
            } else {
                int index = 1;

                for (int start = 1; start <= (safeMeasureCount - safeChunk + 1); start++) {
                    final int end = start + safeChunk - 1;
                    windows.add(new PartResolutionWindow(scoreId, index++, start, end));
                }
            }
        }

        return new PartResolutionPlan(scoreId, safeMeasureCount, safeChunk, windows);
    }

    //---------------------------//
    // computeScoreMeasureCount //
    //---------------------------//
    public static int computeScoreMeasureCount (Score score)
    {
        if (score == null) {
            return 0;
        }

        int total = 0;

        for (Page page : score.getPages()) {
            total += Math.max(0, page.getMeasureCount());
        }

        return total;
    }
}