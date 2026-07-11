package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Size
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
import androidx.compose.ui.graphics.Path
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
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private enum class Screen { HUD, TACTICAL_MAP }

class MainActivity : ComponentActivity() {

    private var ortSession: OrtSession? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    // State managed by Compose
    private var isScanning by mutableStateOf(true)
    private var fpsValue by mutableStateOf(0)
    private var inferenceValue by mutableStateOf(0L)
    private var activePanel by mutableStateOf("GEOLOG")
    private var panelVisible by mutableStateOf(true)
    private var motionArrayOn by mutableStateOf(true)
    private var autoTargetLock by mutableStateOf(true)
    private var digitalZoom by mutableStateOf(8f)
    private var motionSensitivity by mutableStateOf(36f)
    private var sensitivityThreshold by mutableStateOf(0.35f)
    private var showDossier by mutableStateOf(false)
    private var isYoloBoxesEnabled by mutableStateOf(true)
    private var maxDetections by mutableStateOf(15)
    private var autoMag by mutableStateOf(false)
    private var eyeMode by mutableIntStateOf(4)
    private var selectedTarget by mutableStateOf<MagTrackTarget?>(null)
    private var currentScreen by mutableStateOf(Screen.HUD)

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
        loadONNXModel("yolov8n.onnx")

        setContent {
            TacticalHudTheme {
                when (currentScreen) {
                    Screen.HUD -> MainContent(onViewMapClick = { currentScreen = Screen.TACTICAL_MAP })
                    Screen.TACTICAL_MAP -> TacticalMapScreen(onBack = { currentScreen = Screen.HUD })
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
    fun MainContent(onViewMapClick: () -> Unit) {
        val context = LocalContext.current
        
        LaunchedEffect(previewView) {
            if (previewView != null && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startHighPerformanceCamera()
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF010408))) {
            IsolatedHeaderTerminalBlock(
                currentFps = fpsValue,
                inferenceTimeMs = inferenceValue.toInt(),
                onViewMapClick = onViewMapClick,
                onSettingsClick = { 
                    activePanel = "SETTINGS"
                    panelVisible = true
                }
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).also { previewView = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                AndroidView(
                    factory = { context ->
                        HUDOverlayView(context, null).also {
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

                MagTrackWindows()
            }

            TerminalPanel(onViewMapClick = onViewMapClick)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            selectedTarget?.let { target ->
                LargeTargetViewer(target) { selectedTarget = null }
            }

            if (showDossier) DossierOverlay()
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
                    "MAX TARGET TRACKS",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${maxDetections}",
                    color = Color(0xFF00FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            
            Slider(
                value = maxDetections.toFloat(),
                onValueChange = { maxDetections = it.toInt() },
                valueRange = 1f..50f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00FF66),
                    activeTrackColor = Color(0xFF00FF66),
                    inactiveTrackColor = Color(0xFF00FF66).copy(alpha = 0.2f)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AUTO MAGNIFICATION",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoMag,
                    onCheckedChange = { autoMag = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF66),
                        checkedTrackColor = Color(0xFF00FF66).copy(alpha = 0.5f)
                    )
                )
            }

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

            Spacer(modifier = Modifier.weight(1f))

            TerminalActionButton(
                text = "CLOSE SETTINGS",
                active = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                panelVisible = false
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "DETECTION CATALOG // SUPPORTED SIGNATURES",
                color = Color(0xFF00FF66).copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp).border(1.dp, Color(0xFF00FF66).copy(alpha = 0.2f))) {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier.verticalScroll(scrollState).padding(8.dp)) {
                    labels.chunked(3).forEach { rowLabels ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            rowLabels.forEach { label ->
                                Text(
                                    text = "> ${label.uppercase()}",
                                    color = Color(0xFF00FF66).copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MagTrackWindows() {
        Box(modifier = Modifier.fillMaxSize()) {
            trackedTargets.take(eyeMode).forEachIndexed { index, target ->
                val alignment = when (index) {
                    0 -> Alignment.TopStart
                    1 -> Alignment.TopEnd
                    2 -> Alignment.BottomStart
                    else -> Alignment.BottomEnd
                }
                val padding = when (index) {
                    0 -> Modifier.padding(start = 16.dp, top = 140.dp)
                    1 -> Modifier.padding(end = 16.dp, top = 140.dp)
                    2 -> Modifier.padding(start = 16.dp, bottom = 280.dp)
                    else -> Modifier.padding(end = 16.dp, bottom = 280.dp)
                }
                SubCropTargetWindow(
                    target = target,
                    modifier = padding
                        .align(alignment)
                        .clickable { selectedTarget = target }
                )
            }
        }
    }

    @Composable
    fun TerminalPanel(onViewMapClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xED050C14))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp)
                    .background(Color(0xFF02080F))
            ) {
                PanelTabButton(
                    text = "GEOLOG PANEL",
                    active = activePanel == "GEOLOG" && panelVisible,
                    modifier = Modifier.weight(1f)
                ) {
                    activePanel = "GEOLOG"
                    panelVisible = true
                }
                PanelTabButton(
                    text = "GPS MAP",
                    active = activePanel == "GPS" && panelVisible,
                    modifier = Modifier.weight(1f)
                ) {
                    onViewMapClick()
                }
                PanelTabButton(
                    text = "SETTINGS",
                    active = activePanel == "SETTINGS" && panelVisible,
                    modifier = Modifier.weight(1f)
                ) {
                    activePanel = "SETTINGS"
                    panelVisible = true
                }
                PanelTabButton(
                    text = "TOOLS CLOSE",
                    active = !panelVisible,
                    modifier = Modifier.weight(1f)
                ) {
                    panelVisible = false
                }
            }

            if (panelVisible) {
                when (activePanel) {
                    "GEOLOG" -> GeologContent()
                    "SETTINGS" -> SettingsContent()
                    else -> GpsTacticalMapContent()
                }
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
                    modifier = Modifier.width(130.dp).padding(end = 8.dp)
                ) { motionArrayOn = !motionArrayOn }
                TerminalActionButton(
                    text = "AUTO TARGET\n${if (autoTargetLock) "LOCK" else "UNLOCK"}",
                    active = autoTargetLock,
                    modifier = Modifier.width(130.dp).padding(end = 8.dp)
                ) { autoTargetLock = !autoTargetLock }
                TerminalActionButton(
                    text = "RADAR SWEEP\nON",
                    active = true,
                    modifier = Modifier.width(130.dp)
                )
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TerminalActionButton(
                    text = "AUTO MAG\n${if (autoMag) "ACTIVE" else "OFF"}",
                    active = autoMag,
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) { autoMag = !autoMag }
                
                TerminalActionButton(
                    text = "2 EYES\nMAG",
                    active = eyeMode == 2,
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) { 
                    eyeMode = 2
                    digitalZoom = 2.0f
                    cameraControl?.setZoomRatio(2.0f)
                }
                
                TerminalActionButton(
                    text = "4 EYES\nMAG",
                    active = eyeMode == 4,
                    modifier = Modifier.weight(1f)
                ) { 
                    eyeMode = 4
                    digitalZoom = 4.0f
                    cameraControl?.setZoomRatio(4.0f)
                }
            }

            Text(
                "VISUAL RENDER + TARGET VIEW",
                color = Color(0xFFFFA500),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TerminalActionButton(text = "RENDER\nCLASSIC", active = false, modifier = Modifier.weight(1f).padding(end = 6.dp))
                TerminalActionButton(text = "TARGET\nBIG", active = true, modifier = Modifier.weight(1f).padding(end = 6.dp))
                TerminalActionButton(text = "TARGET\nNORMAL", active = false, modifier = Modifier.weight(1f))
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .padding(14.dp)
        ) {
            Text(
                "TACTICAL GPS MAPPING // SIGNAL TRACE",
                color = Color(0xFF00E5FF),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f))
                .background(Color(0xFF02080F))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val neonCyan = Color(0xFF00E5FF)
                    val matrixGreen = Color(0xFF00FF66)
                    val alertAmber = Color(0xFFFFA500)

                    for (i in 0..10) {
                        val x = i * w / 10f
                        val y = i * h / 10f
                        drawLine(neonCyan.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, h))
                        drawLine(neonCyan.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y))
                    }

                    trackedTargets.forEach { target ->
                        val mapX = target.relX * w
                        val mapY = target.relY * h
                        val pulse = (System.currentTimeMillis() % 1000) / 1000f
                        drawCircle(alertAmber.copy(alpha = 1f - pulse), radius = 10f + pulse * 15f, center = Offset(mapX, mapY), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                        drawCircle(alertAmber, radius = 4f, center = Offset(mapX, mapY))
                        drawLine(alertAmber.copy(alpha = 0.4f), center, Offset(mapX, mapY), 1f)
                    }

                    drawCircle(neonCyan.copy(alpha = 0.15f), radius = w * 0.25f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    drawCircle(neonCyan.copy(alpha = 0.1f), radius = w * 0.45f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                }
                
                Column(modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp)) {
                    Text("MAP_SCALE: 1:500m", color = Color(0xFF00FF66), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("GPS_LOCK: STABLE [45.89\u00b0N]", color = Color(0xFF00E5FF), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("ACTIVE_NODES: ${trackedTargets.size}", color = Color(0xFFFFA500), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
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
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(target.trackLabel, color = Color(0xFF00FF66), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(target.coordinateLabel, color = Color(0xFFFFA500), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                
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
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "PANOPTICORE // TARGET DOSSIER",
                    color = Color(0xFF00E5FF),
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

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
            val env = OrtEnvironment.getEnvironment()
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
        val bitmap = try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            if (rowStride == imageProxy.width * pixelStride) {
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                val rowSize = imageProxy.width * pixelStride
                val cleanBuffer = java.nio.ByteBuffer.allocateDirect(rowSize * imageProxy.height)
                val rowBytes = ByteArray(rowSize)
                for (y in 0 until imageProxy.height) {
                    buffer.position(y * rowStride)
                    buffer.get(rowBytes)
                    cleanBuffer.put(rowBytes)
                }
                cleanBuffer.rewind()
                bitmap.copyPixelsFromBuffer(cleanBuffer)
            }
            bitmap
        } catch (e: Exception) {
            imageProxy.close()
            return
        }
        imageProxy.close()

        val inputTensor = preprocessBitmap(bitmap)

        try {
            val session = ortSession ?: return
            val inputs = mapOf("images" to inputTensor)
            val outputs = session.run(inputs)

            if (outputs != null) {
                val targets = postProcess(outputs, bitmap)
                runOnUiThread {
                    hudOverlay?.updateTargets(targets)
                    updateMagTrackTargets(targets)
                    inferenceValue = System.currentTimeMillis() - startTime
                    updateFps()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputTensor.close()
        }
    }

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
            val bestMatch = unassignedTargets.minByOrNull { yolo ->
                val dx = track.relX - (yolo.xMin + yolo.xMax) / 2f
                val dy = track.relY - (yolo.yMin + yolo.yMax) / 2f
                dx * dx + dy * dy
            }
            if (bestMatch != null) {
                val dx = track.relX - (bestMatch.xMin + bestMatch.xMax) / 2f
                val dy = track.relY - (bestMatch.yMin + bestMatch.yMax) / 2f
                if (dx * dx + dy * dy < 0.05f) {
                    unassignedTargets.remove(bestMatch)
                    updatedTracks.add(track.copy(
                        relX = (bestMatch.xMin + bestMatch.xMax) / 2f,
                        relY = (bestMatch.yMin + bestMatch.yMax) / 2f,
                        coordinateLabel = "X${(bestMatch.xMin * 100).toInt()} Y${(bestMatch.yMin * 100).toInt()} Z:${(bestMatch.confidence * 100).toInt()}%",
                        crop = bestMatch.crop ?: track.crop
                    ))
                }
            }
        }

        unassignedTargets.take(4 - updatedTracks.size).forEach { yolo ->
            updatedTracks.add(MagTrackTarget(
                id = "TRACK-${nextTrackId++ % 1000}",
                trackLabel = "AUTO MAG-TRACK // ${yolo.label}",
                coordinateLabel = "X${(yolo.xMin * 100).toInt()} Y${(yolo.yMin * 100).toInt()} Z:${(yolo.confidence * 100).toInt()}%",
                relX = (yolo.xMin + yolo.xMax) / 2f,
                relY = (yolo.yMin + yolo.yMax) / 2f,
                crop = yolo.crop
            ))
        }

        activeTracks.clear()
        activeTracks.addAll(updatedTracks)
        trackedTargets.clear()
        trackedTargets.addAll(updatedTracks)
        hudOverlay?.magTrackTargets = updatedTracks
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

    private fun preprocessBitmap(bitmap: Bitmap): OnnxTensor {
        val env = OrtEnvironment.getEnvironment()
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val tensorData = FloatBuffer.allocate(1 * 3 * 640 * 640)
        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        for (i in 0 until 640 * 640) {
            val pixel = pixels[i]
            tensorData.put(i, ((pixel shr 16) and 0xFF) / 255f)
            tensorData.put(i + 640 * 640, ((pixel shr 8) and 0xFF) / 255f)
            tensorData.put(i + 2 * 640 * 640, (pixel and 0xFF) / 255f)
        }
        tensorData.rewind()
        return OnnxTensor.createTensor(env, tensorData, longArrayOf(1, 3, 640, 640))
    }

    private fun postProcess(outputs: OrtSession.Result, bitmap: Bitmap): List<YoloTarget> {
        val outputTensor = outputs.get(0) as OnnxTensor
        val buffer = outputTensor.floatBuffer
        val shape = outputTensor.info.shape
        val numElements = shape[2].toInt()
        val numChannels = shape[1].toInt()
        val candidateTargets = mutableListOf<YoloTarget>()
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
                val xMin = (cx - w / 2f) / 640f
                val yMin = (cy - h / 2f) / 640f
                val xMax = (cx + w / 2f) / 640f
                val yMax = (cy + h / 2f) / 640f
                candidateTargets.add(YoloTarget(
                    id = "TGT-${candidateTargets.size}",
                    label = labels.getOrNull(maxClassId) ?: "unknown",
                    confidence = maxScore,
                    xMin = xMin, yMin = yMin, xMax = xMax, yMax = yMax
                ))
            }
        }
        val nmsSelected = nms(candidateTargets)
        return nmsSelected.take(maxDetections).map { target ->
            val left = (target.xMin.coerceIn(0f, 1f) * bitmap.width).toInt()
            val top = (target.yMin.coerceIn(0f, 1f) * bitmap.height).toInt()
            val w = ((target.xMax - target.xMin).coerceIn(0f, 1f) * bitmap.width).toInt().coerceAtLeast(1)
            val h = ((target.yMax - target.yMin).coerceIn(0f, 1f) * bitmap.height).toInt().coerceAtLeast(1)
            val crop = try { Bitmap.createBitmap(bitmap, left, top, w, h) } catch (e: Exception) { null }
            target.copy(label = getTacticalLabel(target.label), crop = crop)
        }
    }

    private fun getTacticalLabel(baseLabel: String): String {
        val randomId = (1000..9999).random()
        val label = baseLabel.uppercase()
        return when {
            label == "PERSON" -> "BIO-SIGN DETECTED // SUBJECT-${randomId}"
            
            label in listOf("BICYCLE", "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK", "BOAT") -> 
                "MOBILE ASSET // CLASS-DELTA-${randomId}"
            
            label in listOf("BIRD", "CAT", "DOG", "HORSE", "SHEEP", "COW", "ELEPHANT", "BEAR", "ZEBRA", "GIRAFFE") -> 
                "NON-HUMAN LIFEFORM // SPECIES-${randomId}"
            
            label in listOf("TV", "LAPTOP", "MOUSE", "REMOTE", "KEYBOARD", "CELL PHONE") -> 
                "SIGINT SOURCE // OMEGA-${randomId}"
            
            label in listOf("MICROWAVE", "OVEN", "TOASTER", "REFRIGERATOR", "SINK", "HAIR DRIER", "TOOTHBRUSH") -> 
                "DOMESTIC APPLIANCE // GRID-NODE-${randomId}"
            
            label in listOf("BOTTLE", "WINE GLASS", "CUP", "FORK", "KNIFE", "SPOON", "BOWL") -> 
                "UTILITY TOOL // TYPE-B-${randomId}"
            
            label in listOf("BANANA", "APPLE", "SANDWICH", "ORANGE", "BROCCOLI", "CARROT", "HOT DOG", "PIZZA", "DONUT", "CAKE") -> 
                "BIOLOGICAL CONSUMABLE // SAMPLE-${randomId}"
            
            label in listOf("CHAIR", "COUCH", "POTTED PLANT", "BED", "DINING TABLE", "TOILET") -> 
                "FURNITURE COMPONENT // STATIC-${randomId}"
            
            label in listOf("BACKPACK", "UMBRELLA", "HANDBAG", "TIE", "SUITCASE") -> 
                "EQUIPMENT LOADOUT // KIT-${randomId}"
            
            label in listOf("FRISBEE", "SKIS", "SNOWBOARD", "SPORTS BALL", "KITE", "BASEBALL BAT", "BASEBALL GLOVE", "SKATEBOARD", "SURFBOARD", "TENNIS RACKET") -> 
                "KINETIC APPARATUS // MODULE-${randomId}"
            
            label in listOf("TRAFFIC LIGHT", "FIRE HYDRANT", "STOP SIGN", "PARKING METER", "BENCH") -> 
                "URBAN INFRASTRUCTURE // NODE-${randomId}"
            
            label in listOf("BOOK", "CLOCK", "VASE", "SCISSORS", "TEDDY BEAR") -> 
                "MISC OBJECT // UNKNOWN-CAT-${randomId}"
            
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
        val intersectionArea = maxOf(0f, minOf(a.xMax, b.xMax) - maxOf(a.xMin, b.xMin)) * maxOf(0f, minOf(a.yMax, b.yMax) - maxOf(a.yMin, b.yMin))
        return intersectionArea / (areaA + areaB - intersectionArea)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
    }
}

@Composable
fun TacticalHudTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF66), background = Color(0xFF030708), surface = Color(0xFF050C14)), content = content)
}
