# Implementation Plan - Performance and Photo Quality Fixes

This plan implements three specific fixes in `MainActivity.kt` to improve model performance and photo quality, and updates `BestFrameSelector.kt` to restore sharpness evaluation.

## User Review Required

> [!IMPORTANT]
> The `CaptureManager.processDetection` method signature is changing to include a sharpness score. I will also update the call site in `MainActivity.kt` to calculate and pass this score, ensuring the project remains compilable and functional.

## Proposed Changes

### [Component Name] - Core HUD Logic

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/MainActivity.kt)
- Change `modelInputSize` from 640 to 320 for performance.
- Update `postProcess` to add 40% contextual padding to crops.
- Remove EMA smoothing in `updateMagTrackTargets` for instant snapping.
- Update `captureManager.processDetection` call to pass a sharpness score calculated via `BestFrameSelector.calculateScore`.

#### [MODIFY] [BestFrameSelector.kt](file:///C:/Users/Damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/BestFrameSelector.kt)
- Replace the `CaptureManager` class with a new version that performs sharpness evaluation and holds the best frame before committing to storage.

## Verification Plan

### Automated Tests
- Gradle build to verify compilation.
- `gradle_build(":app:assembleDebug")`

### Manual Verification
- Deploy to device and verify:
    - Faster FPS due to 320px model input.
    - Padded crops in the event logs and target viewer.
    - Zero-lag bounding box snapping.
    - Improved photo quality in logs (best frame selection).
