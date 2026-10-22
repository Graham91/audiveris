//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                            P a r t R e s o l u t i o n S e t t i n g s                         //
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

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.constant.ConstantSet;

import java.nio.file.Path;

/**
 * Class <code>PartResolutionSettings</code> gathers user settings for AI-assisted part resolution.
 * <p>
 * API keys are session-only and are intentionally not persisted in ConstantSet values.
 */
public abstract class PartResolutionSettings
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    /** API key value kept only in current process memory. */
    private static volatile String sessionApiKey = "";

    //~ Constructors -------------------------------------------------------------------------------

    /** Not meant to be instantiated. */
    private PartResolutionSettings ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    /**
     * Report the persistent folder where part-resolution fixtures are kept.
     * <p>
     * Unlike the debug artifacts, it is not under the temp folder, so it is never cleaned up.
     *
     * @return the fixtures root folder
     */
    public static Path getFixtureFolder ()
    {
        final String value = constants.fixtureFolder.getValue().trim();

        return value.isEmpty() ? WellKnowns.CONFIG_FOLDER.resolve("part-resolution-fixtures")
                : Path.of(value);
    }

    //-----------------------------//
    // getApiEndpoint             //
    //-----------------------------//
    public static String getApiEndpoint ()
    {
        return constants.apiEndpoint.getValue();
    }

    //-----------------------------//
    // getApiKeyEnvVar            //
    //-----------------------------//
    public static String getApiKeyEnvVar ()
    {
        return constants.apiKeyEnvVar.getValue();
    }

    //-----------------------------//
    // getChunkSize               //
    //-----------------------------//
    public static int getChunkSize ()
    {
        return constants.chunkSize.getValue();
    }

    //-----------------------------//
    // getConfiguredApiKey        //
    //-----------------------------//
    /**
     * Report API key from session memory first, then from configured environment variable.
     *
     * @return API key string, perhaps empty
     */
    public static String getConfiguredApiKey ()
    {
        if ((sessionApiKey != null) && !sessionApiKey.trim().isEmpty()) {
            return sessionApiKey;
        }

        final String envName = constants.apiKeyEnvVar.getValue().trim();

        if (envName.isEmpty()) {
            return "";
        }

        final String value = System.getenv(envName);

        return (value != null) ? value.trim() : "";
    }

    //-----------------------------//
    // getModelName               //
    //-----------------------------//
    public static String getModelName ()
    {
        return constants.modelName.getValue();
    }

    //-----------------------------//
    // getTimeoutSeconds          //
    //-----------------------------//
    public static int getTimeoutSeconds ()
    {
        return constants.timeoutSeconds.getValue();
    }

    //-----------------------------//
    // getApiRequestsPerRun       //
    //-----------------------------//
    public static int getApiRequestsPerRun ()
    {
        return Math.max(1, constants.apiRequestsPerRun.getValue());
    }

    //-----------------------------//
    // getReasoningEffort         //
    //-----------------------------//
    /**
     * Report the reasoning effort to send with each request.
     *
     * @return none, low, medium, high, or an empty string when the field must be left out
     */
    public static String getReasoningEffort ()
    {
        final String value = constants.reasoningEffort.getValue().trim();

        return "omit".equalsIgnoreCase(value) ? "" : value;
    }

    //-----------------------------//
    // getTargetPartCount         //
    //-----------------------------//
    public static int getTargetPartCount ()
    {
        return constants.targetPartCount.getValue();
    }

    //-----------------------------//
    // setReasoningEffort         //
    //-----------------------------//
    public static void setReasoningEffort (String effort)
    {
        constants.reasoningEffort.setStringValue(effort.trim());
    }

    //-----------------------------//
    // isEnabled                  //
    //-----------------------------//
    public static boolean isEnabled ()
    {
        return constants.enabled.isSet();
    }

    //-----------------------------//
    // setApiEndpoint             //
    //-----------------------------//
    public static void setApiEndpoint (String endpoint)
    {
        constants.apiEndpoint.setStringValue(endpoint.trim());
    }

    //-----------------------------//
    // setApiKeyEnvVar            //
    //-----------------------------//
    public static void setApiKeyEnvVar (String envName)
    {
        constants.apiKeyEnvVar.setStringValue(envName.trim());
    }

    //-----------------------------//
    // setEnabled                 //
    //-----------------------------//
    public static void setEnabled (boolean enabled)
    {
        constants.enabled.setValue(enabled);
    }

    //-----------------------------//
    // setModelName               //
    //-----------------------------//
    public static void setModelName (String modelName)
    {
        constants.modelName.setStringValue(modelName.trim());
    }

    //-----------------------------//
    // setSessionApiKey           //
    //-----------------------------//
    public static void setSessionApiKey (String apiKey)
    {
        sessionApiKey = (apiKey != null) ? apiKey.trim() : "";
    }

    //-----------------------------//
    // setTimeoutSeconds          //
    //-----------------------------//
    public static void setTimeoutSeconds (int timeoutSeconds)
    {
        constants.timeoutSeconds.setValue(Math.max(1, timeoutSeconds));
    }

    //-----------------------------//
    // setApiRequestsPerRun       //
    //-----------------------------//
    public static void setApiRequestsPerRun (int count)
    {
        constants.apiRequestsPerRun.setValue(Math.max(1, count));
    }

    //-----------------------------//
    // setTargetPartCount         //
    //-----------------------------//
    public static void setTargetPartCount (int count)
    {
        constants.targetPartCount.setValue(Math.max(1, count));
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Boolean enabled = new Constant.Boolean(
                false,
                "Should AI-assisted part resolution be enabled?");

        private final Constant.String apiEndpoint = new Constant.String(
                "https://api.openai.com/v1/responses",
                "Endpoint URL for part resolution API calls");

        private final Constant.String modelName = new Constant.String(
                "gpt-5.1",
                "Model name used for part resolution");

        private final Constant.String apiKeyEnvVar = new Constant.String(
                "AUDIVERIS_PART_RESOLUTION_KEY",
                "Environment variable name that can provide API key");

        private final Constant.Integer timeoutSeconds = new Constant.Integer(
                "seconds",
                180,
                "HTTP timeout for one part-resolution request");

        private final Constant.Integer chunkSize = new Constant.Integer(
                "measures",
                3,
                "Sliding window size in measures");

        private final Constant.Integer apiRequestsPerRun = new Constant.Integer(
            "requests",
            1,
            "Maximum API requests to submit in one Resolve Parts run");

        private final Constant.String reasoningEffort = new Constant.String(
            "medium",
            "Reasoning effort sent to the model: none, low, medium or high. Use 'omit' for models without reasoning support");

        private final Constant.String fixtureFolder = new Constant.String(
            "",
            "Persistent folder for part-resolution fixtures (empty: <config folder>/part-resolution-fixtures)");

        private final Constant.Integer targetPartCount = new Constant.Integer(
            "parts",
            4,
            "Target number of logical parts expected in AI assignments");
    }
}