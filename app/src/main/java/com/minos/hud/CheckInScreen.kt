package com.minos.hud

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Placeholder for the state class mentioned in the user's snippet
data class CheckInUiState(
    val status: String = "IDLE"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    capturedBitmap: Bitmap?,
    uiState: CheckInUiState,
    onCapturePhotoClick: () -> Unit,
    onRetryClick: () -> Unit,
    onManagePeopleClick: () -> Unit,
    onViewMapClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sherlock Face Search") },
                actions = {
                    // Tactical Map entry action button
                    IconButton(onClick = onViewMapClick) {
                        Icon(Icons.Default.Place, contentDescription = "Tactical Map")
                    }
                    IconButton(onClick = onManagePeopleClick) {
                        Icon(Icons.Default.Person, contentDescription = "Manage People")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Text("Check-In Hub Content Placeholder")
            // Captured bitmap and UI state would be used here in the full implementation
            if (capturedBitmap != null) {
                 Text("Bitmap captured")
            }
            Text("Status: ${uiState.status}")
            
            Button(onClick = onCapturePhotoClick) { Text("Capture Photo") }
            Button(onClick = onRetryClick) { Text("Retry") }
        }
    }
}
