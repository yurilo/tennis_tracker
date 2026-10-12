package com.usopen.tracker.domain.model

/**
 * Immutable aggregated user settings domain model.
 */
data class AppSettings(
    val autoRefreshInterval: AutoRefreshInterval = AutoRefreshInterval.INTERVAL_60S,
    val matchRetention: MatchRetentionPolicy = MatchRetentionPolicy.HOURS_24,
    val widgetSyncMode: WidgetSyncMode = WidgetSyncMode.ADAPTIVE,
    val defaultTournament: StartupTournamentTier = StartupTournamentTier.ALL,
    val telemetryEnabled: Boolean = true,
    val telemetryEndpoint: String = DEFAULT_TELEMETRY_ENDPOINT
) {
    companion object {
        const val DEFAULT_TELEMETRY_ENDPOINT = "https://telemetry.tennis-tracker.app/api/heartbeat"

        val DEFAULT: AppSettings = AppSettings()
    }
}
