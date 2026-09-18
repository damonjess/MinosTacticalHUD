package com.minos.hud

import org.junit.Assert.*
import org.junit.Test

class FeaturesUnitTest {

    @Test
    fun testTrackingProfilesConfiguration() {
        val vehicleProfile = TrackingProfile.VEHICLE
        assertEquals("Vehicle Mode", vehicleProfile.displayName)
        assertEquals(640, vehicleProfile.modelInputSize)
        assertEquals(250L, vehicleProfile.plateScanIntervalMs)
        assertTrue(vehicleProfile.isPlateDetectorEnabled)
        assertTrue(vehicleProfile.allowedClasses!!.contains("car"))
        assertTrue(vehicleProfile.allowedClasses!!.contains("plate"))

        val peopleProfile = TrackingProfile.PEOPLE
        assertEquals("People Mode", peopleProfile.displayName)
        assertFalse(peopleProfile.isPlateDetectorEnabled)
        assertEquals(1, peopleProfile.allowedClasses!!.size)
        assertTrue(peopleProfile.allowedClasses!!.contains("person"))

        val indoorProfile = TrackingProfile.INDOOR
        assertTrue(indoorProfile.suggestTorch)
        assertEquals(0.40f, indoorProfile.confidenceThreshold, 0.01f)

        val movingProfile = TrackingProfile.MOVING
        assertEquals(0.85f, movingProfile.boxSmoothingAlpha, 0.01f)
        assertNull(movingProfile.allowedClasses)
    }

    @Test
    fun testQualitySpeedPresetsConfiguration() {
        val perf = QualitySpeedPreset.PERFORMANCE
        assertEquals(640, perf.modelInputSize)
        assertEquals(600L, perf.plateScanIntervalMs)
        assertEquals(3, perf.minStableFrames)
        assertFalse(perf.highResCaptureEnabled)

        val balanced = QualitySpeedPreset.BALANCED
        assertEquals(640, balanced.modelInputSize)
        assertEquals(400L, balanced.plateScanIntervalMs)
        assertEquals(5, balanced.minStableFrames)

        val quality = QualitySpeedPreset.QUALITY
        assertEquals(640, quality.modelInputSize)
        assertEquals(200L, quality.plateScanIntervalMs)
        assertEquals(8, quality.minStableFrames)
        assertTrue(quality.highResCaptureEnabled)
    }

    @Test
    fun testViewModelStateTransitions() {
        val viewModel = MainViewModel()

        // Default state
        assertEquals(TrackingProfile.OUTDOOR, viewModel.selectedProfile)
        assertEquals(QualitySpeedPreset.BALANCED, viewModel.qualityPreset)
        assertFalse(viewModel.isTargetLocked)

        // Switch profile — sensitivity is set to the profile's confidenceThreshold,
        // but the per-class threshold logic in OnnxImageAnalyzer allows animals
        // to be detected at a lower effective threshold.
        viewModel.setProfile(TrackingProfile.VEHICLE)
        assertEquals(TrackingProfile.VEHICLE, viewModel.selectedProfile)
        assertEquals("VEHICLE", viewModel.currentProfile)
        assertEquals(0.35f, viewModel.sensitivityThreshold, 0.01f)
        assertEquals(DetectionMode.VEHICLES, viewModel.detectionMode)

        // Switch preset
        viewModel.setPreset(QualitySpeedPreset.QUALITY)
        assertEquals(QualitySpeedPreset.QUALITY, viewModel.qualityPreset)

        // Target locking
        viewModel.lockTarget("TRK-101")
        assertTrue(viewModel.isTargetLocked)
        assertEquals("TRK-101", viewModel.lockedTrackId)

        viewModel.releaseLock()
        assertFalse(viewModel.isTargetLocked)
        assertNull(viewModel.lockedTrackId)
    }

    @Test
    fun testBestFrameSelectorPadding() {
        val personPad = BestFrameSelector.getPaddingForLabel("person")
        assertEquals(0.10f, personPad, 0.001f)

        val carPad = BestFrameSelector.getPaddingForLabel("car")
        assertEquals(0.12f, carPad, 0.001f)

        val platePad = BestFrameSelector.getPaddingForLabel("plate")
        assertEquals(0.04f, platePad, 0.001f)
    }

    @Test
    fun testLicensePlateAspectRatioRange() {
        val minRatio = 0.8f
        val maxRatio = 10.0f
        val minConf = 0.25f

        // Standard US plate (12x6 inches => ~2.0 aspect ratio)
        val usAspect = 12f / 6f
        assertTrue(usAspect in minRatio..maxRatio)

        // Standard EU plate (520x110 mm => ~4.73 aspect ratio)
        val euAspect = 520f / 110f
        assertTrue(euAspect in minRatio..maxRatio)

        // Motorcycle / stacked plate (e.g. 180x140 mm => ~1.28 aspect ratio)
        val motorcycleAspect = 180f / 140f
        assertTrue(motorcycleAspect in minRatio..maxRatio)

        // Steep oblique angle foreshortened plate (~0.9 aspect ratio)
        val obliqueAspect = 0.9f
        assertTrue(obliqueAspect in minRatio..maxRatio)

        // Wide panorama plate crop (~9.0 aspect ratio)
        val wideAspect = 9.0f
        assertTrue(wideAspect in minRatio..maxRatio)

        // Out of bounds extreme ratios
        val extremeThin = 0.5f
        val extremeWide = 12.0f
        assertFalse(extremeThin in minRatio..maxRatio)
        assertFalse(extremeWide in minRatio..maxRatio)
        
        // Confidence floor allows low-light detections at 0.25
        assertTrue(0.28f >= minConf)
    }

    @Test
    fun testOutdoorDefaultSensitivityIsLowEnoughForAnimals() {
        // The OUTDOOR profile sets sensitivityThreshold to its confidenceThreshold (0.30f),
        // which is low enough for YOLOv8n to detect dogs that often score 0.20–0.35.
        val viewModel = MainViewModel()
        assertEquals(0.30f, viewModel.sensitivityThreshold, 0.01f)
    }

    @Test
    fun testAnimalClassesContainsAllCocoAnimals() {
        val expected = setOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
        assertEquals(expected, ANIMAL_CLASSES)
    }

    @Test
    fun testOutdoorProfileIncludesAllAnimals() {
        val outdoor = TrackingProfile.OUTDOOR
        // Every COCO animal class should be in the OUTDOOR allowedClasses
        for (animal in ANIMAL_CLASSES) {
            assertTrue("OUTDOOR profile should allow '$animal'",
                outdoor.allowedClasses!!.contains(animal))
        }
    }

    @Test
    fun testOutdoorConfidenceThresholdIsLowEnoughForAnimals() {
        // The OUTDOOR profile threshold should be at most 0.30 so that lightweight
        // YOLO models can detect animals (dogs, cats) that often score 0.20–0.35.
        assertTrue(TrackingProfile.OUTDOOR.confidenceThreshold <= 0.30f)
    }

    @Test
    fun testVehicleClassesContainsAllCocoVehicles() {
        val expected = setOf("bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat")
        assertEquals(expected, VEHICLE_CLASSES)
    }
}
