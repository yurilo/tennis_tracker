package com.usopen.tracker.domain.model

/**
 * Polling interval options for live scoreboard auto-refresh.
 */
enum class AutoRefreshInterval(
    val periodMillis: Long,
    val displayName: String,
    val badgeLabel: String,
    val batteryHint: String
) {
    INTERVAL_30S(
        periodMillis = 30_000L,
        displayName = "30 seconds",
        badgeLabel = "Auto 30s",
        batteryHint = "Frequent polling; higher battery and data consumption."
    ),
    INTERVAL_60S(
        periodMillis = 60_000L,
        displayName = "60 seconds",
        badgeLabel = "Auto 1m",
        batteryHint = "Recommended balance between live updates and battery efficiency."
    ),
    INTERVAL_2M(
        periodMillis = 120_000L,
        displayName = "2 minutes",
        badgeLabel = "Auto 2m",
        batteryHint = "Low battery and data impact; updates every two minutes."
    ),
    OFF(
        periodMillis = 0L,
        displayName = "Manual only (Off)",
        badgeLabel = "Manual",
        batteryHint = "Minimal battery usage; scores refresh only on pull or manual tap."
    );

    val isEnabled: Boolean
        get() = this != OFF && periodMillis > 0L

    companion object {
        val DEFAULT: AutoRefreshInterval = INTERVAL_60S

        fun fromPeriod(periodMillis: Long): AutoRefreshInterval {
            return entries.firstOrNull { it.periodMillis == periodMillis } ?: DEFAULT
        }

        fun fromNameOrDefault(name: String?): AutoRefreshInterval {
            if (name == null) return DEFAULT
            return try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                DEFAULT
            }
        }
    }
}
