package com.minos.hud

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class TacticalFaceAnalyzer(
    private val onFacesDetected: (List<Face>, width: Int, height: Int) -> Unit
) : ImageAnalysis.Analyzer {

    // Configure the detector for high-performance speed and tracking status modes
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(options)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            
            detector.process(image)
                .addOnSuccessListener { faces ->
                    // Pass detected face bounds up to the interface layer
                    onFacesDetected(faces, imageProxy.width, imageProxy.height)
                }
                .addOnFailureListener {
                    // Fail silently or log debugging streams
                }
                .addOnCompleteListener {
                    // Crucial: Close the proxy frame to release memory for the next incoming image
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
