package com.minos.hud

import android.content.Context
import android.graphics.Bitmap
import java.io.File

data class EnrolledFace(
    val id: String,
    val name: String,
    val image: Bitmap? = null
)

object EnrolledFaceStore {
    fun getAll(context: Context): List<EnrolledFace> {
        // Return a mock list for now to support the TacticalMapScreen
        return listOf(
            EnrolledFace("id1", "Subject Alpha"),
            EnrolledFace("id2", "Subject Beta"),
            EnrolledFace("id3", "Infiltrator Gamma"),
            EnrolledFace("id4", "Unit Delta")
        )
    }
}

enum class EventCategory {
    PEOPLE_VEHICLES, ANIMALS, PLATES
}

data class DetectedEvent(
    val id: String,
    val label: String,
    val timestamp: String,
    val category: EventCategory,
    val thumbnailPath: String,
    val fullImagePath: String? = null
)
