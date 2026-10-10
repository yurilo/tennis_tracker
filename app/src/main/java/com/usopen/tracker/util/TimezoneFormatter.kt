package com.usopen.tracker.util

import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utility for formatting and converting match start times and status detail text
 * from UTC or tournament Eastern Time (EDT) into the device's system set timezone.
 */
object TimezoneFormatter {

    val NEW_YORK_ZONE: ZoneId = ZoneId.of("America/New_York")

    /**
     * Parses any ISO date-time string (with or without seconds, e.g. "2026-09-05T15:30Z",
     * "2026-09-05T15:30:00Z", "2026-09-05T15:30") into a UTC ZonedDateTime.
     */
    fun parseToZonedDateTime(isoString: String?): ZonedDateTime? {
        if (isoString.isNullOrBlank()) return null
        val trimmed = isoString.trim()

        // 1. Try ISO_DATE_TIME (handles "2026-09-05T15:30Z", "2026-09-05T15:30:00Z", etc.)
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME)
        } catch (_: Throwable) {}

        // 2. Try Instant.parse
        try {
            return Instant.parse(trimmed).atZone(ZoneOffset.UTC)
        } catch (_: Throwable) {}

        // 3. Try Local ISO date-time assuming UTC
        try {
            val ldt = LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            return ldt.atZone(ZoneOffset.UTC)
        } catch (_: Throwable) {}

        // 4. Try appending ":00Z" or "Z" if missing offset
        try {
            if (!trimmed.endsWith("Z", ignoreCase = true) && !trimmed.contains("+") && trimmed.contains("T")) {
                val withZ = if (trimmed.count { it == ':' } == 1) "$trimmed:00Z" else "${trimmed}Z"
                return ZonedDateTime.parse(withZ, DateTimeFormatter.ISO_DATE_TIME)
            }
        } catch (_: Throwable) {}

        return null
    }

    /**
     * Converts an ISO date-time string to an Instant.
     */
    fun parseToInstant(isoString: String?): Instant? {
        return parseToZonedDateTime(isoString)?.toInstant()
    }

    /**
     * Formats an ISO-8601 UTC timestamp string (e.g. "2026-09-04T21:00Z", "2026-09-04T21:00:00Z")
     * into the target timezone with format "MMM d • h:mm a z" (or 24-hour "MMM d • HH:mm z").
     */
    fun formatIsoToLocal(
        startTimeIso: String?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String? {
        if (startTimeIso.isNullOrBlank()) return null
        val zdt = parseToZonedDateTime(startTimeIso) ?: return null
        return try {
            val localZdt = zdt.withZoneSameInstant(zoneId)
            val pattern = if (is24Hour) "MMM d • HH:mm z" else "MMM d • h:mm a z"
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            formatter.format(localZdt)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Converts a status detail string containing an Eastern Time mention
     * (e.g. "Tonight • 9:00 PM EDT" or "Sat, September 5th at 11:30 AM EDT") to the target timezone.
     */
    fun convertEdtStringToLocal(
        text: String?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String? {
        if (text.isNullOrBlank()) return text
        val regex = Regex("""(\d{1,2}):(\d{2})\s*(AM|PM|am|pm)\s*(?:EDT|EST|ET)""")
        val match = regex.find(text) ?: return text

        return try {
            val hour12 = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            val ampm = match.groupValues[3].uppercase()
            val hour24 = when {
                ampm == "PM" && hour12 < 12 -> hour12 + 12
                ampm == "AM" && hour12 == 12 -> 0
                else -> hour12
            }

            val nyZdt = ZonedDateTime.now(NEW_YORK_ZONE)
                .withHour(hour24)
                .withMinute(minute)
                .withSecond(0)
                .withNano(0)

            val localZdt = nyZdt.withZoneSameInstant(zoneId)
            val timePattern = if (is24Hour) "HH:mm z" else "h:mm a z"
            val formatter = DateTimeFormatter.ofPattern(timePattern, Locale.getDefault())
            val formattedTime = formatter.format(localZdt)

            text.replace(match.value, formattedTime)
        } catch (_: Throwable) {
            text
        }
    }

    /**
     * Derives the display status badge text for a match in the target timezone.
     */
    fun getDisplayStatus(
        match: Match,
        zoneId: ZoneId = ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String {
        val duration = match.getFormattedDuration()
        return when (match.state) {
            MatchState.LIVE -> {
                if (duration != null) "LIVE • $duration" else match.statusDetail
            }
            MatchState.FINISHED -> {
                val base = if (match.statusDetail.startsWith("Final", ignoreCase = true)) "FINAL" else match.statusDetail
                if (duration != null) "$base • $duration" else match.statusDetail
            }
            MatchState.UPCOMING -> {
                formatIsoToLocal(match.startTime, zoneId, is24Hour)
                    ?: convertEdtStringToLocal(match.statusDetail, zoneId, is24Hour)
                    ?: match.statusDetail.takeIf { it.isNotBlank() }
                    ?: "UPCOMING"
            }
        }
    }

    /**
     * Formats an epoch millisecond timestamp into a human-readable time string
     * in the target timezone (e.g. "5:04 PM" or "17:04").
     */
    fun formatEpochTime(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        is24Hour: Boolean = false
    ): String {
        return try {
            val zdt = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
            val pattern = if (is24Hour) "HH:mm" else "h:mm a"
            DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(zdt)
        } catch (_: Throwable) {
            ""
        }
    }
}

