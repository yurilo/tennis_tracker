package com.tennis.tracker.domain.repository

import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.WidgetSyncMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface providing reactive observable state and persistence operations for [AppSettings].
 */
interface SettingsRepository {
    /**
     * Observable [StateFlow] emitting the latest [AppSettings] state to collectors.
     */
    val settings: StateFlow<AppSettings>

    /**
     * Synchronously returns the current [AppSettings] snapshot.
     */
    fun getSettings(): AppSettings

    /**
     * Persists and emits an updated [AutoRefreshInterval].
     */
    suspend fun updateAutoRefreshInterval(interval: AutoRefreshInterval)

    /**
     * Persists and emits an updated [MatchRetentionPolicy].
     */
    suspend fun updateMatchRetention(policy: MatchRetentionPolicy)

    /**
     * Persists and emits an updated [WidgetSyncMode].
     */
    suspend fun updateWidgetSyncMode(mode: WidgetSyncMode)

    /**
     * Persists and emits an updated [StartupTournamentTier].
     */
    suspend fun updateDefaultTournament(tier: StartupTournamentTier)

    /**
     * Persists and emits an updated anonymous telemetry opt-in flag.
     */
    suspend fun updateTelemetryEnabled(enabled: Boolean)

    /**
     * Persists and emits an updated custom telemetry endpoint URL.
     * Enforces secure HTTPS protocol or reverts to default if blank.
     */
    suspend fun updateTelemetryEndpoint(endpoint: String)

    /**
     * Restores all settings back to canonical defaults and emits the updated state.
     */
    suspend fun resetToDefaults()
}
