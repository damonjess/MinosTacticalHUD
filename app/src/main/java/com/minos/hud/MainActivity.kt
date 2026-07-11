package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
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
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private var sensitivityThreshold by mutableStateOf(0.50f)
    private var showDossier by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var isYoloBoxesEnabled by mutableStateOf(true)
    private var selectedTarget by mutableStateOf<MagTrackTarget?>(null)

    private var lastFpsUpdateTime = 0L
    private var frameCount = 0
    private var hudOverlay: HUDOverlayView? = null
    private var previewView: PreviewView? = null

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
        enableEdgeToEdge()
        setupHighPerformanceMode()

        cameraExecutor = Executors.newSingleThreadExecutor()
        loadONNXModel("yolov8n.onnx")

        setContent {
            TacticalHudTheme {
                MainContent()
            }
        }

        requestCameraPermission()
    }

    @Composable
    fun MainContent() {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF010408))) {
            // 1. Top Telemetry (Absolute Isolated Block)
            IsolatedHeaderTerminalBlock(
                currentFps = fpsValue,
                inferenceTimeMs = inferenceValue.toInt()
            )

            // 2. Center Viewport (Camera + HUD Overlay)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 2a. Camera Preview
                AndroidView(
                    factory = { context ->
                        PreviewView(context).also { previewView = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2b. HUD Overlay
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

                // 2c. Floating Mag-Track Windows
                MagTrackWindows()
            }

            // 3. Bottom Terminal Panel
            TerminalPanel()
        }

        // Global Overlays (Large Viewers, Settings, etc.)
        Box(modifier = Modifier.fillMaxSize()) {
            // 4. Large Target Viewer (Panopticore Feature)
            selectedTarget?.let { target ->
                LargeTargetViewer(target) { selectedTarget = null }
            }

            // 5. Global Dialog Overlays
            if (showDossier) DossierOverlay()
            if (showSettings) SettingsOverlay()
        }
    }

    @Composable
    fun MagTrackWindows() {
        Box(modifier = Modifier.fillMaxSize()) {
            trackedTargets.forEachIndexed { index, target ->
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
    fun TerminalPanel() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xED050C14))
        ) {
            // Tabs
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
                    text = "GPS TACTICAL MAP",
                    active = activePanel == "GPS" && panelVisible,
                    modifier = Modifier.weight(1f)
                ) {
                    activePanel = "GPS"
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
                if (activePanel == "GEOLOG") {
                    GeologContent()
                } else {
                    GpsTacticalMapContent()
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                TerminalActionButton(
                    text = "MOTION ARRAY\n${if (motionArrayOn) "ON" else "OFF"}",
                    active = motionArrayOn,
                    modifier = Modifier.width(130.dp).padding(end = 8.dp)
                ) {
                    motionArrayOn = !motionArrayOn
                }
                TerminalActionButton(
                    text = "AUTO TARGET\n${if (autoTargetLock) "LOCK" else "UNLOCK"}",
                    active = autoTargetLock,
                    modifier = Modifier.width(130.dp).padding(end = 8.dp)
                ) {
                    autoTargetLock = !autoTargetLock
                }
                TerminalActionButton(
                    text = "RADAR SWEEP\nON",
                    active = true,
                    modifier = Modifier.width(130.dp)
                )
            }

            Text(
                "VISUAL RENDER + TARGET VIEW",
                color = Color(0xFFFFA500),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
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
                // --- TACTICAL VECTOR MAP ---
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val neonCyan = Color(0xFF00E5FF)
                    val matrixGreen = Color(0xFF00FF66)
                    val alertAmber = Color(0xFFFFA500)

                    // 1. Digital Grid Infrastructure
                    for (i in 0..10) {
                        val x = i * w / 10f
                        val y = i * h / 10f
                        drawLine(neonCyan.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, h))
                        drawLine(neonCyan.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y))
                    }

                    // 2. Coordinate Axis Markers
                    for (i in 1..9) {
                        val x = i * w / 10f
                        val y = i * h / 10f
                        // Lat/Long style labels (mock)
                        // Note: Using native canvas for text if needed, but here we can just draw ticks
                        drawLine(neonCyan.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, 10f), 2f)
                        drawLine(neonCyan.copy(alpha = 0.3f), Offset(0f, y), Offset(10f, y), 2f)
                    }

                    // 3. Simulated Topographical Contours
                    val contourPath = Path().apply {
                        moveTo(w * 0.2f, h * 0.1f)
                        quadraticBezierTo(w * 0.4f, h * 0.3f, w * 0.1f, h * 0.6f)
                        quadraticBezierTo(w * 0.3f, h * 0.8f, w * 0.6f, h * 0.7f)
                        quadraticBezierTo(w * 0.9f, h * 0.9f, w * 0.8f, h * 0.4f)
                        quadraticBezierTo(w * 0.7f, h * 0.2f, w * 0.2f, h * 0.1f)
                    }
                    drawPath(contourPath, neonCyan.copy(alpha = 0.1f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))

                    // 4. Center "Home" Node (Subject Zero)
                    drawCircle(matrixGreen, radius = 6f, center = center)
                    drawCircle(matrixGreen.copy(alpha = 0.3f), radius = 12f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    
                    // 5. Dynamic Target Blips (Mapped from tracked objects)
                    trackedTargets.forEach { target ->
                        // Map 3D screen space to 2D top-down map space (simulated)
                        val mapX = target.relX * w
                        val mapY = target.relY * h
                        
                        // Pulse effect for targets
                        val pulse = (System.currentTimeMillis() % 1000) / 1000f
                        
                        drawCircle(alertAmber.copy(alpha = 1f - pulse), radius = 10f + pulse * 15f, center = Offset(mapX, mapY), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                        drawCircle(alertAmber, radius = 4f, center = Offset(mapX, mapY))
                        
                        // Identification line
                        drawLine(alertAmber.copy(alpha = 0.4f), center, Offset(mapX, mapY), 1f)
                    }

                    // 6. Perimeter Range Rings
                    drawCircle(neonCyan.copy(alpha = 0.15f), radius = w * 0.25f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    drawCircle(neonCyan.copy(alpha = 0.1f), radius = w * 0.45f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                }
                
                // Map Legend / Data Readout
                Column(modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp)) {
                    Text("MAP_SCALE: 1:500m", color = Color(0xFF00FF66), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    Text("GPS_LOCK: STABLE [45.89°N]", color = Color(0xFF00E5FF), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
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
                .clickable { onClose() }
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
                
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .border(1.dp, Color(0xFFFFA500).copy(alpha = 0.5f))
                ) {
                    Image(
                        bitmap = target.crop?.asImageBitmap() 
                            ?: ImageBitmap(1, 1),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Scanline Overlay for the crop
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val h = size.height
                        val w = size.width
                        for (i in 0 until h.toInt() step 4) {
                            drawLine(Color.Black.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, i.toFloat()), androidx.compose.ui.geometry.Offset(w, i.toFloat()))
                        }
                    }

                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.5f))) {
                        Text("MAG: 8.0x", color = Color(0xFF00FF66), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("RES: 720p", color = Color(0xFF00FF66), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(target.trackLabel, color = Color(0xFF00FF66), fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(target.coordinateLabel, color = Color(0xFFFFA500), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text("OBJ_ID: ${target.id}", color = Color(0xFF00E5FF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    "ANALYSIS: " + "0x" + (100000..999999).random().toString(16).uppercase() + " " + (100000..999999).random().toString(16).uppercase(),
                    color = Color(0xFF00FF66).copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                
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
                .clickable { /* consume clicks */ }
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

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(2.dp))
                        .padding(2.dp)
                ) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Last target",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                TerminalActionButton(
                    text = "INITIALIZE NEW SCAN SEQUENCE",
                    active = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    showDossier = false
                    isScanning = true
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0x800D1527))
                        .padding(12.dp)
                ) {
                    Text(
                        "--> AWAITING INTERCEPT BIOMETRIC DATA...\n--> CHOOSE CAPTURE PATTERN TO INITIATE LENS SEARCH.",
                        color = Color(0xFF00E5FF),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    "RESET SCAN MATRIX",
                    color = Color(0xFFFF3366),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .clickable {
                            showDossier = false
                            isScanning = true
                        }
                )
            }
        }
    }

    @Composable
    fun SettingsOverlay() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20D1527))
                .clickable { /* consume clicks */ }
                .padding(24.dp)
        ) {
            Column {
                Text(
                    "PANOPTIC ENGINE SETTINGS",
                    color = Color(0xFF00E5FF),
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Text(
                        "RENDER YOLO BOUNDING BOXES",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isYoloBoxesEnabled,
                        onCheckedChange = { isYoloBoxesEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00FF66),
                            checkedTrackColor = Color(0x4000FF66)
                        )
                    )
                }

                HorizontalDivider(color = Color(0x3300E5FF), modifier = Modifier.padding(bottom = 16.dp))

                Column {
                    Row {
                        Text(
                            "SCANNER SENSITIVITY THRESHOLD",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(sensitivityThreshold * 100).toInt()}%",
                            color = Color(0xFF00FF66),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = sensitivityThreshold,
                        onValueChange = { sensitivityThreshold = it },
                        valueRange = 0.10f..0.95f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00FF66),
                            activeTrackColor = Color(0xFF00FF66)
                        )
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                TerminalActionButton(
                    text = "APPLY CONFIGURATION",
                    active = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    showSettings = false
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

        val layoutParams = window.attributes
        layoutParams.screenBrightness = 1.0f
        window.attributes = layoutParams
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startHighPerformanceCamera() else finish()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 
            PackageManager.PERMISSION_GRANTED) {
            startHighPerformanceCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadONNXModel(modelName: String) {
        try {
            ortSession?.close()
            val env = OrtEnvironment.getEnvironment()
            assets.open(modelName).use { input ->
                ortSession = env.createSession(input.readBytes())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startHighPerformanceCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            @Suppress("DEPRECATION")
            val preview = Preview.Builder()
                .setTargetResolution(Size(1080, 1920))
                .build().also {
                    it.setSurfaceProvider(previewView?.surfaceProvider)
                }

            @Suppress("DEPRECATION")
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isScanning) {
                            runDetection(imageProxy)
                        } else {
                            updateFps()
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun runDetection(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()
        val bitmap = imageProxy.toBitmapCustom()
        val inputTensor = preprocessBitmap(bitmap)

        try {
            val inputs = mapOf("images" to inputTensor)
            val outputs = ortSession?.run(inputs)

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
        val updatedTracks = mutableListOf<MagTrackTarget>()
        val unassignedTargets = targets.toMutableList()

        // 1. Try to update existing tracks based on proximity
        activeTracks.forEach { track ->
            val bestMatch = unassignedTargets.minByOrNull { yolo ->
                val dx = track.relX - (yolo.xMin + yolo.xMax) / 2f
                val dy = track.relY - (yolo.yMin + yolo.yMax) / 2f
                dx * dx + dy * dy
            }

            if (bestMatch != null) {
                val distSq = let {
                    val dx = track.relX - (bestMatch.xMin + bestMatch.xMax) / 2f
                    val dy = track.relY - (bestMatch.yMin + bestMatch.yMax) / 2f
                    dx * dx + dy * dy
                }

                if (distSq < 0.05f) { // Threshold for "same" object
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

        // 2. Create new tracks for remaining targets (up to limit)
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

    private fun ImageProxy.toBitmapCustom(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val buffer = planes[0].buffer
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun preprocessBitmap(bitmap: Bitmap): OnnxTensor {
        val env = OrtEnvironment.getEnvironment()
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val tensorData = FloatBuffer.allocate(1 * 3 * 640 * 640)
        tensorData.rewind()

        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        for (i in 0 until 640 * 640) {
            val pixel = pixels[i]
            tensorData.put(i, ((pixel shr 16) and 0xFF) / 255f)
            tensorData.put(i + 640 * 640, ((pixel shr 8) and 0xFF) / 255f)
            tensorData.put(i + 2 * 640 * 640, (pixel and 0xFF) / 255f)
        }

        tensorData.rewind()
        val shape = longArrayOf(1, 3, 640, 640)
        return OnnxTensor.createTensor(env, tensorData, shape)
    }

    private fun postProcess(outputs: OrtSession.Result, bitmap: Bitmap): List<YoloTarget> {
        val outputTensor = outputs.get(0) as OnnxTensor
        val buffer = outputTensor.floatBuffer
        val shape = outputTensor.info.shape // [1, 84, 8400]
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
                val cx = buffer.get(0 * numElements + i)
                val cy = buffer.get(1 * numElements + i)
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
                    xMin = xMin,
                    yMin = yMin,
                    xMax = xMax,
                    yMax = yMax,
                    crop = null // Crop extracted after NMS
                ))
            }
        }

        val nmsSelected = nms(candidateTargets)
        
        // Finalize top targets with tactical labels and crops
        return nmsSelected.take(10).map { target ->
            val finalCrop = try {
                val left = (target.xMin.coerceIn(0f, 1f) * bitmap.width).toInt()
                val top = (target.yMin.coerceIn(0f, 1f) * bitmap.height).toInt()
                val width = ((target.xMax - target.xMin).coerceIn(0f, 1f) * bitmap.width).toInt().coerceAtLeast(1)
                val height = ((target.yMax - target.yMin).coerceIn(0f, 1f) * bitmap.height).toInt().coerceAtLeast(1)
                
                if (left + width <= bitmap.width && top + height <= bitmap.height) {
                    Bitmap.createBitmap(bitmap, left, top, width, height)
                } else null
            } catch (e: Exception) { null }

            target.copy(
                label = getTacticalLabel(target.label),
                crop = finalCrop
            )
        }
    }

    private fun getTacticalLabel(baseLabel: String): String {
        val upper = baseLabel.uppercase()
        val randomId = (1000..9999).random()
        return when (upper) {
            "PERSON" -> "BIO-SIGN DETECTED // SUBJECT-${randomId}"
            "BICYCLE", "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK", "BOAT" -> 
                "VEHICLE INTERCEPT // CLASS-DELTA-${randomId}"
            "CELL PHONE", "LAPTOP", "TV", "REMOTE", "KEYBOARD", "MOUSE" -> 
                "ELECTRONIC SIGINT // OMEGA-${randomId}"
            "BOTTLE", "CUP", "FORK", "KNIFE", "SPOON" -> 
                "RESOURCE LOCATED // ITEM-${randomId}"
            "CHAIR", "COUCH", "BED", "DINING TABLE" -> 
                "STRUCTURAL ASSET // AREA-${randomId}"
            else -> "${upper} // UNIT-${randomId}"
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
                    if (active[j]) {
                        if (iou(boxes[i], boxes[j]) > 0.45f) {
                            active[j] = false
                        }
                    }
                }
            }
        }
        return selected
    }

    private fun iou(a: YoloTarget, b: YoloTarget): Float {
        val areaA = (a.xMax - a.xMin) * (a.yMax - a.yMin)
        val areaB = (b.xMax - b.xMin) * (b.yMax - b.yMin)

        val intersectionLeft = maxOf(a.xMin, b.xMin)
        val intersectionTop = maxOf(a.yMin, b.yMin)
        val intersectionRight = minOf(a.xMax, b.xMax)
        val intersectionBottom = minOf(a.yMax, b.yMax)

        val intersectionWidth = maxOf(0f, intersectionRight - intersectionLeft)
        val intersectionHeight = maxOf(0f, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionWidth * intersectionHeight

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
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00FF66),
            background = Color(0xFF030708),
            surface = Color(0xFF050C14)
        ),
        content = content
    )
}
