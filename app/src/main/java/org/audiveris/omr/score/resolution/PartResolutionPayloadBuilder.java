//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                  P a r t R e s o l u t i o n P a y l o a d B u i l d e r                       //
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds request drafts from planned windows.
 */
public abstract class PartResolutionPayloadBuilder
{
    //~ Constructors -------------------------------------------------------------------------------

    private PartResolutionPayloadBuilder ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //-------------//
    // buildDrafts //
    //-------------//
    public static List<PartResolutionRequestDraft> buildDrafts (List<PartResolutionPlan> plans,
                                                                 String modelName)
    {
        if ((plans == null) || plans.isEmpty()) {
            return Collections.emptyList();
        }

        final List<PartResolutionRequestDraft> drafts = new ArrayList<>();

        for (PartResolutionPlan plan : plans) {
            for (PartResolutionWindow window : plan.getWindows()) {
                drafts.add(
                        new PartResolutionRequestDraft(
                                window.getId(),
                                plan.getScoreId(),
                                window.getStartMeasure(),
                                window.getEndMeasure(),
                                modelName,
                                instructionFor(window)));
            }
        }

        return drafts;
    }

    //----------------//
    // instructionFor //
    //----------------//
    private static String instructionFor (PartResolutionWindow window)
    {
        return "Resolve SATB part assignment for score window " + window.getId()
                + " (measures " + window.getStartMeasure() + "-" + window.getEndMeasure()
                + "). Use the staff image and matching MusicXML slice to produce per-note part mapping with confidence.";
    }
}