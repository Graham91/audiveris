# AI-Assisted Part Resolution Plan

## Objective
Add a post-OMR workflow that improves SATB part assignment by combining:
- Audiveris MusicXML export (symbolic data)
- Staff screenshots in 3-measure sliding windows (visual context)
- Cloud AI API reasoning

The workflow is non-destructive: original export remains unchanged.

## Confirmed Product Decisions
- Payload to AI: screenshot + matching MusicXML measures.
- API mode: direct cloud API from Audiveris.
- Chunking: 3-measure sliding windows (overlap).
- Outputs: corrected `.mxl` + sidecar mapping file.

## Current Implementation Status
### Implemented scaffold
- New settings class:
  - `app/src/main/java/org/audiveris/omr/score/resolution/PartResolutionSettings.java`
  - Stores endpoint/model/timeout/chunk defaults in `ConstantSet`
  - Keeps API key session-only (memory) and supports env var lookup
- New action entry point:
  - `resolveParts` in `app/src/main/java/org/audiveris/omr/sheet/ui/BookActions.java`
  - Registered in `app/res/system-actions.xml`
  - Background task scaffold validates settings and logs status
- Preferences integration:
  - New `PartResolutionPane` in `app/src/main/java/org/audiveris/omr/ui/action/Preferences.java`
  - Endpoint/model/env-var editable
  - Session API key prompt button
- Resource keys added:
  - `BookActions.properties`, `BookActions_fr.properties`
  - `Preferences.properties`, `Preferences_fr.properties`

### Not implemented yet
- Screenshot extraction per measure window
- MusicXML window slicing and request payload building
- HTTP client and response parsing
- Overlap merge/conflict policy
- Corrected MXL writer and sidecar serializer

## Planned Next Steps
1. Implement measure window generator (3-measure sliding windows with deterministic IDs).
2. Add staff image capture bound to each window ID.
3. Implement API client with timeout/retry/backoff.
4. Define strict request/response schemas for part decisions and confidence.
5. Merge overlapping window outputs and produce sidecar audit file.
6. Write corrected MXL while preserving original export file.
7. Add unit and integration tests for windowing, merge rules, and output integrity.

## Security Notes
- API key is not persisted in constants/config files.
- Session key is in-memory only.
- Optional environment variable support is available via settings.

## Handoff Status (2026-10-05)
- Build status: passing for targeted resolution tests.
- Command used:
  - `./gradlew :app:compileJava :app:test --tests org.audiveris.omr.score.resolution.PartResolutionPlannerTest --tests org.audiveris.omr.score.resolution.PartResolutionPayloadBuilderTest`
- Runtime status: app boots with Java 25 and `:app:run`.
- Implemented user-visible behavior:
  - New `Book -> Resolve parts (AI)` action.
  - New `Preferences -> Part resolution` pane.
  - Dry-run planning logs with deterministic window IDs.
  - Dry-run request draft generation logs.

## Next Session Start Point
1. Implement screenshot extraction per planned window.
2. Bind each screenshot to matching MusicXML measure slice.
3. Add JSON payload serializer for API call contracts.
4. Add sidecar writer scaffold to capture original/proposed/final mapping.
