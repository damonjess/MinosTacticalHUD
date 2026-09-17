package com.minos.hud

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TacticalMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val detectedEvents = EventRepository.events
    
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Maintain a persistent programmatic reference to the raw MapView instance
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var myLocationOverlayInstance by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    // Request permissions on-the-fly when map screen is opened if not yet granted
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Dynamically attach and activate location overlay when permission is granted
    LaunchedEffect(hasLocationPermission, mapViewInstance) {
        val map = mapViewInstance
        if (hasLocationPermission && map != null && myLocationOverlayInstance == null) {
            val locationProvider = GpsMyLocationProvider(context)
            val myLocationOverlay = MyLocationNewOverlay(locationProvider, map).apply {
                enableMyLocation()
                isDrawAccuracyEnabled = true
                runOnFirstFix {
                    val fix = myLocation
                    if (fix != null) {
                        map.post {
                            map.controller.animateTo(fix, 15.0, 1000L)
                        }
                    }
                }
            }
            map.overlays.add(myLocationOverlay)
            myLocationOverlayInstance = myLocationOverlay
            map.invalidate()
        }
    }

    // Lifecycle management for the Map and Location Overlay
    DisposableEffect(mapViewInstance, myLocationOverlayInstance) {
        val map = mapViewInstance
        val overlay = myLocationOverlayInstance
        
        map?.onResume()
        overlay?.enableMyLocation()
        
        onDispose {
            overlay?.disableMyLocation()
            overlay?.disableFollowLocation()
            map?.onPause()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LIVE GEOGRAPHIC TACTICAL MAP", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF020A02),
                    titleContentColor = Color(0xFF00FF00),
                    navigationIconContentColor = Color(0xFF00FF00)
                )
            )
        },
        floatingActionButton = {
            // Tactical HUD "Locate Me" Button
            FloatingActionButton(
                onClick = {
                    if (!hasLocationPermission) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        val overlay = myLocationOverlayInstance
                        val map = mapViewInstance
                        if (overlay != null && map != null) {
                            val myLocation = overlay.myLocation
                            if (myLocation != null) {
                                map.controller.animateTo(myLocation, 16.0, 800L)
                            } else {
                                // If no fix, ensure location is enabled and tell it to follow next fix
                                overlay.enableMyLocation()
                                overlay.enableFollowLocation()
                                map.controller.setZoom(14.0)
                            }
                        }
                    }
                },
                containerColor = Color(0xFF051A05),
                contentColor = Color(0xFF00FF00),
                shape = CircleShape,
                modifier = Modifier.padding(bottom = 200.dp) // Offset above the bottom sheet
            ) {
                Icon(Icons.Default.Place, contentDescription = "Locate Device Position")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF020A02)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // Tactical OpenStreetMap Framework Wrap
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setMultiTouchControls(true)
                            isTilesScaledToDpi = true
                            
                            // 1. TACTICAL COLOR INVERSION MATRIX (Converts base map to glowing dark cyber grid)
                            val inverseMatrix = ColorMatrix(floatArrayOf(
                                -1.0f,  0.0f,  0.0f, 0.0f, 255f, // R
                                 0.0f, -1.0f,  0.0f, 0.0f, 255f, // G
                                 0.0f,  0.0f, -1.0f, 0.0f, 255f, // B
                                 0.0f,  0.0f,  0.0f, 1.0f,   0f  // A
                            ))
                            
                            // Matrix shift adjustments to tint land masses deep green/black
                            val tintMatrix = ColorMatrix()
                            tintMatrix.setScale(0.0f, 0.5f, 0.0f, 1.0f) 
                            inverseMatrix.postConcat(tintMatrix)
                            
                            // Apply style filters to map canvas pipeline layers
                            overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(inverseMatrix))

                            // Base camera orientation default anchors
                            controller.setZoom(6.0)
                            controller.setCenter(GeoPoint(51.5074, -0.1278))

                            // TARGET BLIP NODES
                            detectedEvents.forEachIndexed { index, event ->
                                val seed = event.id.hashCode()
                                val latOffset = ((seed xor (seed shr 16)) and 0xFFFF) / 65535f * 1.5 - 0.75
                                val lonOffset = ((seed xor (seed shl 13)) and 0xFFFF) / 65535f * 1.5 - 0.75
                                
                                val marker = Marker(this).apply {
                                    position = GeoPoint(51.5074 + latOffset, -0.1278 + lonOffset)
                                    title = "EVENT: ${event.label.uppercase()}"
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                }
                                overlays.add(marker)
                            }

                            mapViewInstance = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { mapView ->
                        mapView.invalidate()
                    }
                )

                if (!hasLocationPermission) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.Red),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .clickable {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                    ) {
                        Text(
                            "GPS PERMISSION DENIED // TAP TO ENABLE POSITIONING",
                            color = Color.Red,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Bottom Cyber Manifest Sheet Index
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF051605)),
                shape = AbsoluteRoundedCornerShape(0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GEOGRAPHIC TARGET INDEX [COUNT: ${detectedEvents.size}]",
                        color = Color(0xFF00FF00),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (detectedEvents.isEmpty()) {
                        Text("NO TARGET DATA CHANNELS ACTIVE", color = Color(0xFF004400), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    } else {
                        LazyColumn {
                            items(detectedEvents) { target ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "> OBJ: ${target.label.uppercase()}",
                                        color = Color(0xFF00FF00),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "SIG-HASH: ${target.id.take(8).uppercase()}",
                                        color = Color(0xFF00AA00),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
