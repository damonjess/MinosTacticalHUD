package com.minos.hud

import android.graphics.Bitmap

enum class EventCategory {
    PEOPLE_VEHICLES, ANIMALS, PLATES
}

/**
 * Shared set of COCO animal class labels used across profiles, detection modes, and UI rendering.
 * Keeping this in one place prevents the per-class lists from drifting out of sync.
 */
val ANIMAL_CLASSES = setOf(
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe"
)

/** Shared set of vehicle class labels. */
val VEHICLE_CLASSES = setOf(
    "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat"
)

enum class DetectionMode(val displayName: String) {
    PEOPLE("People only"),
    VEHICLES("Cars, trucks, buses, motorcycles, bicycles"),
    ANIMALS("Animal classes"),
    PLATES("Vehicle and license-plate detection"),
    ALL("Full COCO detector"),
    CUSTOM("User-selected classes")
}

enum class TrackingStatus {
    LOCKED, SEARCHING, WEAK_TRACK, LOST, PREDICTING, MOTION_BLUR, LOW_LIGHT
}

/**
 * Feature 1: Personal tracking profiles
 */
enum class TrackingProfile(
    val displayName: String,
    val description: String,
    val modelInputSize: Int,
    val confidenceThreshold: Float,
    val detectionMode: DetectionMode,
    val allowedClasses: Set<String>?,
    val plateScanIntervalMs: Long,
    val boxSmoothingAlpha: Float,
    val captureCooldownMs: Long,
    val cropPaddingFraction: Float,
    val isPlateDetectorEnabled: Boolean,
    val suggestTorch: Boolean
) {
    VEHICLE(
        displayName = "Vehicle Mode",
        description = "Cars, trucks, motorcycles, plates. Faster tracking. Plate detector enabled.",
        modelInputSize = 640,
        confidenceThreshold = 0.35f,
        detectionMode = DetectionMode.VEHICLES,
        allowedClasses = setOf("car", "truck", "bus", "motorcycle", "bicycle", "plate"),
        plateScanIntervalMs = 250L,
        boxSmoothingAlpha = 0.80f,
        captureCooldownMs = 5000L,
        cropPaddingFraction = 0.12f,
        isPlateDetectorEnabled = true,
        suggestTorch = false
    ),
    PEOPLE(
        displayName = "People Mode",
        description = "Person detection only. Better face/body framing.",
        modelInputSize = 640,
        confidenceThreshold = 0.35f,
        detectionMode = DetectionMode.PEOPLE,
        allowedClasses = setOf("person"),
        plateScanIntervalMs = 2000L,
        boxSmoothingAlpha = 0.80f,
        captureCooldownMs = 8000L,
        cropPaddingFraction = 0.10f,
        isPlateDetectorEnabled = false,
        suggestTorch = false
    ),
    INDOOR(
        displayName = "Indoor Mode",
        description = "Person and selected objects. Torch suggestion. Lower clutter.",
        modelInputSize = 640,
        confidenceThreshold = 0.40f,
        detectionMode = DetectionMode.CUSTOM,
        allowedClasses = setOf("person", "chair", "couch", "tv", "laptop", "cell phone", "bottle", "cup", "book", "clock", "potted plant"),
        plateScanIntervalMs = 3000L,
        boxSmoothingAlpha = 0.80f,
        captureCooldownMs = 10000L,
        cropPaddingFraction = 0.10f,
        isPlateDetectorEnabled = false,
        suggestTorch = true
    ),
    OUTDOOR(
        displayName = "Outdoor Mode",
        description = "People, vehicles, animals.",
        modelInputSize = 640,
        confidenceThreshold = 0.30f,
        detectionMode = DetectionMode.ALL,
        allowedClasses = setOf("person", "car", "truck", "bus", "motorcycle", "bicycle", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "plate"),
        plateScanIntervalMs = 400L,
        boxSmoothingAlpha = 0.80f,
        captureCooldownMs = 8000L,
        cropPaddingFraction = 0.10f,
        isPlateDetectorEnabled = true,
        suggestTorch = false
    ),
    MOVING(
        displayName = "Moving Mode",
        description = "More aggressive prediction. Less smoothing. Faster response.",
        modelInputSize = 640,
        confidenceThreshold = 0.35f,
        detectionMode = DetectionMode.ALL,
        allowedClasses = null,
        plateScanIntervalMs = 300L,
        boxSmoothingAlpha = 0.85f,
        captureCooldownMs = 5000L,
        cropPaddingFraction = 0.12f,
        isPlateDetectorEnabled = true,
        suggestTorch = false
    )
}

/**
 * Feature 3: Simple quality vs speed switch
 */
enum class QualitySpeedPreset(
    val displayName: String,
    val description: String,
    val modelInputSize: Int,
    val plateScanIntervalMs: Long,
    val minCropWidth: Int,
    val minCropHeight: Int,
    val minPlateCropWidth: Int,
    val minPlateCropHeight: Int,
    val minSharpnessScore: Float,
    val minStableFrames: Int,
    val highResCaptureEnabled: Boolean
) {
    PERFORMANCE(
        displayName = "Performance",
        description = "640x640 main detector. Plate detection every 600ms. Minimal crop creation. Responsive tracking.",
        modelInputSize = 640,
        plateScanIntervalMs = 600L,
        minCropWidth = 120,
        minCropHeight = 120,
        minPlateCropWidth = 80,
        minPlateCropHeight = 25,
        minSharpnessScore = 0.25f,
        minStableFrames = 3,
        highResCaptureEnabled = false
    ),
    BALANCED(
        displayName = "Balanced",
        description = "640x640 detector. Plate detection every 400ms. Sharpest-frame selection. Normal tracking.",
        modelInputSize = 640,
        plateScanIntervalMs = 400L,
        minCropWidth = 160,
        minCropHeight = 160,
        minPlateCropWidth = 100,
        minPlateCropHeight = 30,
        minSharpnessScore = 0.35f,
        minStableFrames = 5,
        highResCaptureEnabled = true
    ),
    QUALITY(
        displayName = "Quality",
        description = "640x640 main detector. Frequent plate scans (200ms). High-resolution still capture. Larger min crop size. Strict quality.",
        modelInputSize = 640,
        plateScanIntervalMs = 200L,
        minCropWidth = 220,
        minCropHeight = 220,
        minPlateCropWidth = 140,
        minPlateCropHeight = 40,
        minSharpnessScore = 0.50f,
        minStableFrames = 8,
        highResCaptureEnabled = true
    )
}

data class DetectedEvent(
    val id: String,
    val label: String,
    val confidence: Float = 0f,
    val timestamp: String,
    val category: EventCategory,
    val detectionMode: DetectionMode = DetectionMode.ALL,
    val imageDimensions: String = "",
    val sharpnessScore: String = "Unknown",
    val framesTracked: Int = 0,
    val trackedDurationSec: Float = 0f,
    val screenPosition: String = "",
    val lowLightActive: Boolean = false,
    val gpsCoordinates: String? = null,
    val thumbnailPath: String,
    val fullImagePath: String? = null,
    val videoClipPath: String? = null
)
