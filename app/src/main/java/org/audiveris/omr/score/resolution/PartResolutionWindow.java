//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                          P a r t R e s o l u t i o n W i n d o w                               //
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
 * One deterministic window to process during part resolution.
 */
public class PartResolutionWindow
{
    //~ Instance fields ----------------------------------------------------------------------------

    private final int scoreId;

    private final int windowIndex;

    private final int startMeasure;

    private final int endMeasure;

    private final String id;

    //~ Constructors -------------------------------------------------------------------------------

    public PartResolutionWindow (int scoreId,
                                 int windowIndex,
                                 int startMeasure,
                                 int endMeasure)
    {
        this.scoreId = scoreId;
        this.windowIndex = windowIndex;
        this.startMeasure = startMeasure;
        this.endMeasure = endMeasure;
        id = String.format("s%02d-m%04d-%04d-w%04d", scoreId, startMeasure, endMeasure, windowIndex);
    }

    //~ Methods ------------------------------------------------------------------------------------

    public int getEndMeasure ()
    {
        return endMeasure;
    }

    public String getId ()
    {
        return id;
    }

    public int getScoreId ()
    {
        return scoreId;
    }

    public int getStartMeasure ()
    {
        return startMeasure;
    }

    public int getWindowIndex ()
    {
        return windowIndex;
    }

    @Override
    public String toString ()
    {
        return id;
    }
}