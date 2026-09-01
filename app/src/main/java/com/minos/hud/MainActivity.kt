package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ai.onnxruntime.*
import org.osmdroid.config.Configuration
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

private enum class Screen { HUD, TACTICAL_MAP, EVENT_LOG }

class MainActivity : ComponentActivity() {

    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    // Pre-allocated reusable buffers to eliminate Garbage Collection churn
    private val modelInputSize = 640
    private val tensorBuffer = FloatBuffer.allocate(1 * 3 * modelInputSize * modelInputSize)
    private val pixelArray = IntArray(modelInputSize * modelInputSize)
    private val letterboxBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var cleanFrameBuffer: ByteBuffer? = null

    // State managed by Compose
    private var isScanning by mutableStateOf(true)
    private var fpsValue by mutableStateOf(0)
    private var inferenceValue by mutableStateOf(0L)
    private var activePanel by mutableStateOf("GEOLOG")
    private var panelVisible by mutableStateOf(true)
    private var motionArrayOn by mutableStateOf(true)
    private var autoTargetLock by mutableStateOf(true)
    private var digitalZoom by mutableStateOf(1.0f)
    private var sensitivityThreshold by mutableStateOf(0.35f)
    private var showDossier by mutableStateOf(false)
    private var isYoloBoxesEnabled by mutableStateOf(false)
    private var maxDetections by mutableStateOf(15)
    private var autoMag by mutableStateOf(false)
    private var selectedTarget by mutableStateOf<MagTrackTarget?>(null)
    private var currentScreen by mutableStateOf(Screen.HUD)

    private var isCaptureOn by mutableStateOf(false)
    private var currentProfile by mutableStateOf("OUTDOOR")
    private var exposureValue by mutableFloatStateOf(0f)
    private var isTorchEnabled by mutableStateOf(false)

    private lateinit var licensePlateDetector: LicensePlateDetector
    private lateinit var captureManager: CaptureManager

    private var lastFpsUpdateTime = 0L
    private var frameCount = 0
    private var hudOverlay: HUDOverlayView? = null
    private var previewView by mutableStateOf<PreviewView?>(null)

    // Simple Tracker State
    private var nextTrackId = 1
    private val activeTracks = mutableListOf<MagTrackTarget>()

    private val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
        "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
        "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
        "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
        "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val trackedTargets = mutableStateListOf<MagTrackTarget>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        setupHighPerformanceMode()

        cameraExecutor = Executors.newSingleThreadExecutor()
        ortEnv = OrtEnvironment.getEnvironment()
        loadONNXModel("yolov8n.onnx")

        licensePlateDetector = LicensePlateDetector(this)
        captureManager = CaptureManager(this)

        EventRepository.loadEvents(this)

        setContent {
            TacticalHudTheme {
                when (currentScreen) {
                    Screen.HUD -> MainContent()
                    Screen.TACTICAL_MAP -> TacticalMapScreen(onBack = { currentScreen = Screen.HUD })
                    Screen.EVENT_LOG -> EventLogScreen(onBack = { currentScreen = Screen.HUD })
                }
            }
        }

        requestRequiredPermissions()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startHighPerformanceCamera()
        } else {
            finish()
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startHighPerformanceCamera()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    @Composable
    fun MainContent() {
        val context = LocalContext.current

        LaunchedEffect(previewView) {
            if (previewView != null && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startHighPerformanceCamera()
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            AndroidView(
                factory = { ctx ->
                    HUDOverlayView(ctx, null).also {
                        it.magTrackTargets = trackedTargets
                        it.isYoloBoxesEnabled = isYoloBoxesEnabled
                        it.sensitivityThreshold = sensitivityThreshold
                        hudOverlay = it
                    }
                },
                update = {
                    it.isYoloBoxesEnabled = isYoloBoxesEnabled
                    it.sensitivityThreshold = sensitivityThreshold
                },
                modifier = Modifier.fillMaxSize().clickable {
                    showDossier = true
                    isScanning = false
                }
            )

            CaptureHUD(
                onLogsClick = { currentScreen = Screen.EVENT_LOG },
                onSettingsClick = {
                    activePanel = "SETTINGS"
                    panelVisible = true
                }
            )

            if (showDossier) DossierOverlay()
            
            if (panelVisible) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { panelVisible = false }) {
                    Card(
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.9f).fillMaxHeight(0.8f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF05101A)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF00FF9D))
                    ) {
                        SettingsContent()
                    }
                }
            }

            selectedTarget?.let { target ->
                LargeTargetViewer(target) { selectedTarget = null }
            }
        }
    }

    @Composable
    fun CaptureHUD(onLogsClick: () -> Unit, onSettingsClick: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MINOS HUD",
                    color = Color(0xFF00FF9D),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Row {
                    Button(
                        onClick = onLogsClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Text("LOGS ${EventRepository.events.size}", color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSettingsClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Text("SET", color = Color.White)
                    }
                }
            }

            // Status
            Text(
                text = "LIVE  •  ${trackedTargets.size} TARGETS  •  ${if(isScanning) "Scanning" else "Paused"}",
                color = Color(0xFF00A8FF),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Profile Selection
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(text = "PROFILE  •  $currentProfile", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    ProfileButton("INDOOR", currentProfile == "INDOOR") { currentProfile = "INDOOR" }
                    Spacer(modifier = Modifier.width(8.dp))
                    ProfileButton("OUTDOOR", currentProfile == "OUTDOOR") { currentProfile = "OUTDOOR" }
                    Spacer(modifier = Modifier.width(8.dp))
                    ProfileButton("MOVING", currentProfile == "MOVING") { currentProfile = "MOVING" }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(16.dp)
            ) {
                Text(
                    text = "LOW LIGHT — ENABLE TORCH",
                    color = Color(0xFFD4AF37),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { 
                            isTorchEnabled = !isTorchEnabled
                            cameraControl?.enableTorch(isTorchEnabled)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if(isTorchEnabled) Color(0xFF00FF9D) else Color.Transparent),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color.Gray),
                        modifier = Modifier.width(100.dp)
                    ) {
                        Text("TORCH", color = if(isTorchEnabled) Color.Black else Color.White)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "${digitalZoom}x", color = Color(0xFF00FF9D), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "EXP ${exposureValue.toInt()}", color = Color.Gray, fontSize = 12.sp)
                    Slider(
                        value = exposureValue,
                        onValueChange = { 
                            exposureValue = it
                            cameraControl?.setExposureCompensationIndex(it.toInt())
                        },
                        valueRange = -4f..4f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF00FF9D), activeTrackColor = Color(0xFF00FF9D))
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${trackedTargets.size} TARGETS  •  ${EventRepository.events.size} SAVED  •  CAPTURE ${if(isCaptureOn) "ON" else "OFF"}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { isCaptureOn = !isCaptureOn },
                        colors = ButtonDefaults.buttonColors(containerColor = if(isCaptureOn) Color(0xFF008544) else Color(0xFF004422)),
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Text("CAPTURE", color = Color.White, fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { isScanning = !isScanning },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Text(if(isScanning) "PAUSE" else "RESUME", color = Color.White, fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onLogsClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Text("LOGS", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun ProfileButton(text: String, active: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = if(active) Color.Transparent else Color.Transparent),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, if(active) Color(0xFF00FF9D) else Color.Gray),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(text = text, color = if(active) Color(0xFF00FF9D) else Color.Gray)
        }
    }

    @Composable
    fun SettingsContent() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .padding(14.dp)
        ) {
            Text(
                "SYSTEM SENSITIVITY // SCANNER CALIBRATION",
                color = Color(0xFF00E5FF),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SCANNER SENSITIVITY",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${(sensitivityThreshold * 100).toInt()}%",
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            Slider(
                value = sensitivityThreshold,
                onValueChange = { sensitivityThreshold = it },
                valueRange = 0.05f..0.95f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00FF66),
                    activeTrackColor = Color(0xFF00FF66),
                    inactiveTrackColor = Color(0xFF00FF66).copy(alpha = 0.2f)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RENDER YOLO BOUNDING BOXES",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isYoloBoxesEnabled,
                    onCheckedChange = { isYoloBoxesEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF66),
                        checkedTrackColor = Color(0xFF00FF66).copy(alpha = 0.5f)
                    )
                )
            }
        }
    }

    @Composable
    fun GeologContent() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Text(
                "TRACKING + SENSOR OVERLAYS",
                color = Color(0xFFFFA500),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TerminalActionButton(
                    text = "MOTION ARRAY\n${if (motionArrayOn) "ON" else "OFF"}",
                    active = motionArrayOn,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) { motionArrayOn = !motionArrayOn }
                TerminalActionButton(
                    text = "AUTO TARGET\n${if (autoTargetLock) "LOCK" else "UNLOCK"}",
                    active = autoTargetLock,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) { autoTargetLock = !autoTargetLock }
                TerminalActionButton(
                    text = "RADAR SWEEP\nON",
                    active = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                "DIGITAL CAMERA ZOOM: ${digitalZoom.toInt()}",
                color = Color(0xFF00FF66),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            Slider(
                value = digitalZoom,
                onValueChange = {
                    digitalZoom = it
                    cameraControl?.setZoomRatio(it)
                },
                valueRange = 1f..16f,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF00FF66))
            )
        }
    }

    @Composable
    fun GpsTacticalMapContent() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(Color(0xFF02080F))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("GPS SENSOR INTERFACE // STANDBY", color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    fun LargeTargetViewer(target: MagTrackTarget, onClose: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6050C14))
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, Color(0xFF00FF66))
                    .background(Color(0xFF030708))
                    .padding(16.dp)
            ) {
                Text(
                    "PANOPTICORE // TARGET VIEWER",
                    color = Color(0xFF00FF66),
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Box(modifier = Modifier.fillMaxWidth().height(300.dp).border(1.dp, Color(0xFFFFA500).copy(alpha = 0.5f))) {
                    Image(
                        bitmap = target.crop?.asImageBitmap() ?: ImageBitmap(1, 1),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                TerminalActionButton(
                    text = "CLOSE VIEWER",
                    active = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClose
                )
            }
        }
    }

    @Composable
    fun DossierOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF205080E))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            TerminalActionButton(
                text = "INITIALIZE NEW SCAN SEQUENCE",
                active = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                showDossier = false
                isScanning = true
            }
        }
    }

    private fun setupHighPerformanceMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        window.attributes.screenBrightness = 1.0f
    }

    private fun loadONNXModel(modelName: String) {
        try {
            ortSession?.close()
            val env = ortEnv ?: return
            assets.open(modelName).use { input ->
                ortSession = env.createSession(input.readBytes())
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startHighPerformanceCamera() {
        val view = previewView ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        runOnUiThread { updateFps() }
                        if (isScanning) {
                            runDetection(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
            } catch (e: Exception) { e.printStackTrace() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun runDetection(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 1. Extract Bitmap safely
        val rawBitmap = try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val bmp = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            if (rowStride == imageProxy.width * pixelStride) {
                bmp.copyPixelsFromBuffer(buffer)
            } else {
                val rowSize = imageProxy.width * pixelStride
                if (cleanFrameBuffer == null || cleanFrameBuffer?.capacity() != rowSize * imageProxy.height) {
                    cleanFrameBuffer = ByteBuffer.allocateDirect(rowSize * imageProxy.height)
                }
                val clean = cleanFrameBuffer!!
                clean.rewind()
                val rowBytes = ByteArray(rowSize)
                for (y in 0 until imageProxy.height) {
                    buffer.position(y * rowStride)
                    buffer.get(rowBytes)
                    clean.put(rowBytes)
                }
                clean.rewind()
                bmp.copyPixelsFromBuffer(clean)
            }
            bmp
        } catch (e: Exception) {
            imageProxy.close()
            return
        }
        imageProxy.close()

        // 2. Rotate Bitmap according to camera sensor rotation
        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotBmp = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            rawBitmap.recycle()
            rotBmp
        } else {
            rawBitmap
        }

        // Notify HUD Overlay of the upright camera aspect ratio for accurate scaling
        hudOverlay?.setCameraSourceDimensions(rotatedBitmap.width, rotatedBitmap.height)

        // 3. Preprocess with Aspect-Ratio Preserving Letterboxing
        val letterboxInfo = preprocessLetterbox(rotatedBitmap)
        val env = ortEnv ?: return

        try {
            val session = ortSession ?: return
            val inputTensor = OnnxTensor.createTensor(env, tensorBuffer, longArrayOf(1, 3, modelInputSize.toLong(), modelInputSize.toLong()))
            val inputs = mapOf("images" to inputTensor)
            val outputs = session.run(inputs)

            if (outputs != null) {
                val targets = postProcess(outputs, rotatedBitmap, letterboxInfo)
                runOnUiThread {
                    hudOverlay?.updateTargets(targets)
                    updateMagTrackTargets(targets)
                    inferenceValue = System.currentTimeMillis() - startTime
                    updateFps()
                }
            }
            inputTensor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            rotatedBitmap.recycle()
        }
    }

    private data class LetterboxInfo(val scale: Float, val padX: Float, val padY: Float)

    private fun preprocessLetterbox(bitmap: Bitmap): LetterboxInfo {
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = min(modelInputSize / srcW, modelInputSize / srcH)
        val dstW = srcW * scale
        val dstH = srcH * scale
        val padX = (modelInputSize - dstW) / 2f
        val padY = (modelInputSize - dstH) / 2f

        letterboxCanvas.drawColor(android.graphics.Color.BLACK)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(padX, padY)
        }
        letterboxCanvas.drawBitmap(bitmap, matrix, letterboxPaint)

        tensorBuffer.rewind()
        letterboxBitmap.getPixels(pixelArray, 0, modelInputSize, 0, 0, modelInputSize, modelInputSize)

        val totalPixels = modelInputSize * modelInputSize
        for (i in 0 until totalPixels) {
            val pixel = pixelArray[i]
            tensorBuffer.put(i, ((pixel shr 16) and 0xFF) / 255f)
            tensorBuffer.put(i + totalPixels, ((pixel shr 8) and 0xFF) / 255f)
            tensorBuffer.put(i + 2 * totalPixels, (pixel and 0xFF) / 255f)
        }
        tensorBuffer.rewind()

        return LetterboxInfo(scale, padX, padY)
    }

    private fun postProcess(
        outputs: OrtSession.Result,
        sourceBitmap: Bitmap,
        info: LetterboxInfo
    ): List<YoloTarget> {
        val outputTensor = outputs.get(0) as OnnxTensor
        val buffer = outputTensor.floatBuffer
        val shape = outputTensor.info.shape
        val numElements = shape[2].toInt()
        val numChannels = shape[1].toInt()
        val candidateTargets = mutableListOf<YoloTarget>()

        val srcW = sourceBitmap.width.toFloat()
        val srcH = sourceBitmap.height.toFloat()

        for (i in 0 until numElements) {
            var maxScore = 0f
            var maxClassId = -1
            for (c in 4 until numChannels) {
                val score = buffer.get(c * numElements + i)
                if (score > maxScore) {
                    maxScore = score
                    maxClassId = c - 4
                }
            }

            if (maxScore >= sensitivityThreshold) {
                val cx = buffer.get(i)
                val cy = buffer.get(numElements + i)
                val w = buffer.get(2 * numElements + i)
                val h = buffer.get(3 * numElements + i)

                // Unletterbox back to normalized camera frame coordinates [0.0..1.0]
                val xMin = ((cx - w / 2f) - info.padX) / (srcW * info.scale)
                val yMin = ((cy - h / 2f) - info.padY) / (srcH * info.scale)
                val xMax = ((cx + w / 2f) - info.padX) / (srcW * info.scale)
                val yMax = ((cy + h / 2f) - info.padY) / (srcH * info.scale)

                candidateTargets.add(
                    YoloTarget(
                        id = "TGT-${candidateTargets.size}",
                        label = labels.getOrNull(maxClassId) ?: "unknown",
                        confidence = maxScore,
                        xMin = xMin.coerceIn(0f, 1f),
                        yMin = yMin.coerceIn(0f, 1f),
                        xMax = xMax.coerceIn(0f, 1f),
                        yMax = yMax.coerceIn(0f, 1f)
                    )
                )
            }
        }

        val nmsSelected = nms(candidateTargets)
        return nmsSelected.take(maxDetections).map { target ->
            val left = (target.xMin * sourceBitmap.width).toInt()
            val top = (target.yMin * sourceBitmap.height).toInt()
            val w = ((target.xMax - target.xMin) * sourceBitmap.width).toInt().coerceAtLeast(1)
            val h = ((target.yMax - target.yMin) * sourceBitmap.height).toInt().coerceAtLeast(1)
            val crop = try { Bitmap.createBitmap(sourceBitmap, left, top, w, h) } catch (e: Exception) { null }
            target.copy(label = getTacticalLabel(target.label), crop = crop)
        }
    }

    // Adaptive tracking algorithm for fast-moving vehicles
    private fun updateMagTrackTargets(targets: List<YoloTarget>) {
        if (autoMag && targets.isNotEmpty()) {
            val bestTarget = targets.maxByOrNull { it.confidence }
            if (bestTarget != null && bestTarget.confidence > 0.7f && digitalZoom < 2f) {
                digitalZoom = 2f
                cameraControl?.setZoomRatio(2f)
            }
        }

        val updatedTracks = mutableListOf<MagTrackTarget>()
        val unassignedTargets = targets.toMutableList()

        activeTracks.forEach { track ->
            // Match based on a combination of Euclidean distance + IoU
            val bestMatch = unassignedTargets.minByOrNull { yolo ->
                val targetCenterX = (yolo.xMin + yolo.xMax) / 2f
                val targetCenterY = (yolo.yMin + yolo.yMax) / 2f
                val dx = track.relX - targetCenterX
                val dy = track.relY - targetCenterY
                dx * dx + dy * dy
            }

            if (bestMatch != null) {
                val targetCenterX = (bestMatch.xMin + bestMatch.xMax) / 2f
                val targetCenterY = (bestMatch.yMin + bestMatch.yMax) / 2f
                val dx = track.relX - targetCenterX
                val dy = track.relY - targetCenterY
                val distSq = dx * dx + dy * dy

                // Fast vehicle tolerance threshold: 0.16 (covers up to 40% screen traversal per frame)
                if (distSq < 0.16f) {
                    unassignedTargets.remove(bestMatch)
                    // Apply exponential moving average (EMA) smoothing for position lock stability
                    val smoothedX = track.relX * 0.3f + targetCenterX * 0.7f
                    val smoothedY = track.relY * 0.3f + targetCenterY * 0.7f

                    updatedTracks.add(
                        track.copy(
                            relX = smoothedX,
                            relY = smoothedY,
                            coordinateLabel = "X:${(bestMatch.xMin * 100).toInt()} Y:${(bestMatch.yMin * 100).toInt()} Z:${(bestMatch.confidence * 100).toInt()}%",
                            crop = bestMatch.crop ?: track.crop
                        )
                    )
                }
            }
        }

        // Add newly identified targets
        unassignedTargets.take(4 - updatedTracks.size).forEach { yolo ->
            val cx = (yolo.xMin + yolo.xMax) / 2f
            val cy = (yolo.yMin + yolo.yMax) / 2f
            val label = yolo.label
            
            updatedTracks.add(
                MagTrackTarget(
                    id = "TRACK-${nextTrackId++ % 1000}",
                    trackLabel = "AUTO MAG-TRACK // $label",
                    coordinateLabel = "X:${(yolo.xMin * 100).toInt()} Y:${(yolo.yMin * 100).toInt()} Z:${(yolo.confidence * 100).toInt()}%",
                    relX = cx,
                    relY = cy,
                    crop = yolo.crop
                )
            )
        }

        activeTracks.clear()
        activeTracks.addAll(updatedTracks)
        trackedTargets.clear()
        trackedTargets.addAll(updatedTracks)
        hudOverlay?.magTrackTargets = updatedTracks

        // Unified Capture Logic
        if (isCaptureOn) {
            updatedTracks.forEach { track ->
                val rawLabel = track.trackLabel.replace("AUTO MAG-TRACK // ", "")
                val category = when (rawLabel.lowercase()) {
                    "person", "car", "bus", "truck", "motorcycle" -> EventCategory.PEOPLE_VEHICLES
                    "dog", "cat", "bird", "horse", "sheep", "cow" -> EventCategory.ANIMALS
                    else -> null
                }

                if (category != null && track.coordinateLabel.contains("Z:")) {
                    // Extract confidence from coordinateLabel "X:... Y:... Z:NN%"
                    val confidence = track.coordinateLabel.substringAfter("Z:").substringBefore("%").toIntOrNull() ?: 0
                    
                    if (confidence > 45) {
                        track.crop?.let { bitmap ->
                            val score = BestFrameSelector.calculateScore(bitmap, 0f, 0f, 1f, 1f)
                            captureManager.processDetection(track.id, rawLabel, category, bitmap, score)
                            
                            // License Plate Sub-Detection
                            if (rawLabel.lowercase() in listOf("car", "bus", "truck")) {
                                val plate = licensePlateDetector.detectPlate(bitmap)
                                if (plate != null) {
                                    EventRepository.saveEvent(this, "PLATE FOUND // ${rawLabel.uppercase()}", EventCategory.PLATES, bitmap)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdateTime >= 1000) {
            fpsValue = frameCount
            frameCount = 0
            lastFpsUpdateTime = now
        }
    }

    private fun getTacticalLabel(baseLabel: String): String {
        val randomId = (1000..9999).random()
        val label = baseLabel.uppercase()
        return when {
            label == "PERSON" -> "BIO-SIGN // SUBJECT-${randomId}"
            label in listOf("BICYCLE", "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK", "BOAT") ->
                "MOBILE VEHICLE // ${label}-${randomId}"
            else -> "${label} // UNIT-${randomId}"
        }
    }

    private fun nms(boxes: MutableList<YoloTarget>): List<YoloTarget> {
        boxes.sortByDescending { it.confidence }
        val selected = mutableListOf<YoloTarget>()
        val active = BooleanArray(boxes.size) { true }
        for (i in boxes.indices) {
            if (active[i]) {
                selected.add(boxes[i])
                for (j in i + 1 until boxes.size) {
                    if (active[j] && iou(boxes[i], boxes[j]) > 0.45f) active[j] = false
                }
            }
        }
        return selected
    }

    private fun iou(a: YoloTarget, b: YoloTarget): Float {
        val areaA = (a.xMax - a.xMin) * (a.yMax - a.yMin)
        val areaB = (b.xMax - b.xMin) * (b.yMax - b.yMin)
        val intersectionArea = max(0f, min(a.xMax, b.xMax) - max(a.xMin, b.xMin)) *
                max(0f, min(a.yMax, b.yMax) - max(a.yMin, b.yMin))
        return intersectionArea / (areaA + areaB - intersectionArea)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        licensePlateDetector.close()
    }
}

@Composable
fun TacticalHudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00FF66),
            background = Color(0xFF030708),
            surface = Color(0xFF050C14)
        ),
        content = content
    )
}
