package com.usopen.tracker.domain.model

import androidx.compose.ui.graphics.Color

/**
 * Domain abstraction representing the court surface material.
 */
enum class CourtSurface(
    val displayName: String,
    val icon: String,
    val colorHex: Long
) {
    HARD("Hard Court", "🟦", 0xFF0284C7),
    CLAY("Clay Court", "🟧", 0xFFEA580C),
    GRASS("Grass Court", "🟩", 0xFF16A34A),
    CARPET("Carpet/Indoor", "🟪", 0xFF6366F1);

    val color: Color
        get() = Color(colorHex)

    companion object {
        fun fromName(name: String?): CourtSurface {
            if (name.isNullOrBlank()) return HARD
            val clean = name.trim().lowercase()
            return when {
                clean.contains("clay") || clean.contains("terre") -> CLAY
                clean.contains("grass") || clean.contains("gazon") -> GRASS
                clean.contains("carpet") || clean.contains("indoor") -> CARPET
                else -> HARD
            }
        }
    }
}
