# Implement Tracking Improvements from Vehicle-Detection-and-Tracking

The referenced repository implements the core concepts of the **SORT (Simple Online and Realtime Tracking)** algorithm. The current app uses a basic greedy distance/IoU approach and immediately trusts every detection. We can significantly improve stability by adopting SORT's techniques:

## Proposed Changes

1. **Track Lifecycle (`min_hits`)**:
   Currently, a track is spawned on the first frame a bounding box appears. This causes 1-frame spurious false positives (glitches) to snap onto the screen. We will add a `hitCount` property to `MagTrackTarget` and `Track`. A track will start as "Tentative" and only be passed to the UI/CaptureManager once it hits a threshold (e.g., `min_hits = 3`).

2. **Hungarian Algorithm / Optimal Matching**:
   The current code in `OnnxImageAnalyzer.kt` uses a `greedy` assignment loop. If two vehicles cross paths, the greedy loop often swaps their IDs. We can implement a simplified optimal bipartite matching logic based on the highest intersection-over-union (IoU).

### [MinosTacticalHUD]

#### [MODIFY] [TacticalHudState.kt](file:///home/damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/TacticalHudState.kt)
- Add `hitCount: Int = 1` to `MagTrackTarget` data class.

#### [MODIFY] [OnnxImageAnalyzer.kt](file:///home/damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/OnnxImageAnalyzer.kt)
- Update tracker logic to increment `hitCount` on successful assignment.
- Filter the `finalUpdatedTracks` to only send tracks with `hitCount >= 3` to the UI and CaptureManager. This acts as the `min_hits` filter from the SORT paper.

#### [MODIFY] [HUDOverlayView.kt](file:///home/damon/AndroidStudioProjects/MinosTacticalHUD/app/src/main/java/com/minos/hud/HUDOverlayView.kt)
- Introduce a similar `hitCount` check to prevent 1-frame false positive bounding boxes from flashing on screen.

## User Review Required
> [!NOTE]
> Do you want me to also write a lightweight **Kalman Filter** implementation to replace the Exponential Moving Average (EMA) velocity prediction, or should we start with just the `min_hits` lifecycle and IoU matching improvements?