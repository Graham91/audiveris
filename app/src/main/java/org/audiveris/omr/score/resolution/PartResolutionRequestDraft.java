//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                    P a r t R e s o l u t i o n R e q u e s t D r a f t                         //
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

/**
 * Draft payload metadata for one planned request.
 */
public class PartResolutionRequestDraft
{
    //~ Instance fields ----------------------------------------------------------------------------

    private final String windowId;

    private final int scoreId;

    private final int startMeasure;

    private final int endMeasure;

    private final String modelName;

    private final String instruction;

    //~ Constructors -------------------------------------------------------------------------------

    public PartResolutionRequestDraft (String windowId,
                                       int scoreId,
                                       int startMeasure,
                                       int endMeasure,
                                       String modelName,
                                       String instruction)
    {
        this.windowId = windowId;
        this.scoreId = scoreId;
        this.startMeasure = startMeasure;
        this.endMeasure = endMeasure;
        this.modelName = modelName;
        this.instruction = instruction;
    }

    //~ Methods ------------------------------------------------------------------------------------

    public int getEndMeasure ()
    {
        return endMeasure;
    }

    public String getInstruction ()
    {
        return instruction;
    }

    public String getModelName ()
    {
        return modelName;
    }

    public int getScoreId ()
    {
        return scoreId;
    }

    public int getStartMeasure ()
    {
        return startMeasure;
    }

    public String getWindowId ()
    {
        return windowId;
    }
}