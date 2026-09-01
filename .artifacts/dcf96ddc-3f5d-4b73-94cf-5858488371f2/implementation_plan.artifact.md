# Performance Optimization Plan for Bounding Boxes

This plan implements the strategies suggested to speed up bounding box detection and tracking, including hardware acceleration, lower input resolution, UI smoothing, and tracker momentum adjustments.

## User Review Required

> [!IMPORTANT]
> The model input resolution is being reduced from 640x640 to 320x320. This will significantly increase performance but may slightly reduce detection accuracy for very small or distant objects.

## Proposed Changes

### Core Logic & Inference

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/MainActivity.kt)
- **Strategy 1 (Hardware Acceleration):** Update `loadONNXModel` to enable NNAPI in `OrtSession.SessionOptions`.
- **Strategy 3 (Lower Input Resolution):** Change `modelInputSize` from 640 to 320 to reduce computational load.
- **Strategy 4 (Tracker Momentum):** Tweak EMA weights in `updateMagTrackTargets` (from 0.7/0.3 to 0.8/0.2) to make tracking more responsive to fast-moving objects.

### UI & Rendering

#### [MODIFY] [HUDOverlayView.kt](file:///C:/Users/Damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/HUDOverlayView.kt)
- **Strategy 2 (UI Smoothing):** Implement an Exponential Moving Average (EMA) for the bounding box coordinates within the `onDraw` method to provide "gliding" animations instead of choppy jumps. This will use `postInvalidateOnAnimation()` to maintain a smooth 60 FPS visual experience even if inference is slower.

## Verification Plan

### Automated Tests
- Build and run the app to ensure no crashes occur after changing the model input size and enabling NNAPI.

### Manual Verification
- **Performance:** Observe the "INF" (Inference Time) value in the HUD. It should drop significantly (e.g., from >100ms to <40ms).
- **Smoothness:** Observe the bounding boxes. They should "glide" smoothly between positions rather than snapping instantly.
- **Tracking:** Test with moving objects to ensure the "AUTO MAG-TRACK" stays locked on effectively with the new momentum settings.
