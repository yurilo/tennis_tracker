package com.usopen.tracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.usopen.tracker.domain.model.AppSettings
import com.usopen.tracker.domain.model.AutoRefreshInterval
import com.usopen.tracker.domain.model.MatchRetentionPolicy
import com.usopen.tracker.domain.model.StartupTournamentTier
import com.usopen.tracker.domain.model.WidgetSyncMode
import com.usopen.tracker.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe persistent implementation of [SettingsRepository] backed by [SharedPreferences]
 * and reactive [MutableStateFlow].
 *
 * Provides atomic cold startup loading and immediate in-memory emissions upon preference mutations.
 */
class SettingsRepositoryImpl(
    private val prefs: SharedPreferences
) : SettingsRepository {

    private val lock = Any()

    private val _settings = MutableStateFlow(loadInitialSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun getSettings(): AppSettings {
        return _settings.value
    }

    override suspend fun updateAutoRefreshInterval(interval: AutoRefreshInterval) {
        synchronized(lock) {
            prefs.edit().putString(KEY_AUTO_REFRESH_INTERVAL, interval.name).apply()
            _settings.value = _settings.value.copy(autoRefreshInterval = interval)
        }
    }

    override suspend fun updateMatchRetention(policy: MatchRetentionPolicy) {
        synchronized(lock) {
            prefs.edit().putString(KEY_MATCH_RETENTION, policy.name).apply()
            _settings.value = _settings.value.copy(matchRetention = policy)
        }
    }

    override suspend fun updateWidgetSyncMode(mode: WidgetSyncMode) {
        synchronized(lock) {
            prefs.edit().putString(KEY_WIDGET_SYNC_MODE, mode.name).apply()
            _settings.value = _settings.value.copy(widgetSyncMode = mode)
        }
    }

    override suspend fun updateDefaultTournament(tier: StartupTournamentTier) {
        synchronized(lock) {
            prefs.edit().putString(KEY_DEFAULT_TOURNAMENT, tier.name).apply()
            _settings.value = _settings.value.copy(defaultTournament = tier)
        }
    }

    override suspend fun updateTelemetryEnabled(enabled: Boolean) {
        synchronized(lock) {
            prefs.edit().putBoolean(KEY_TELEMETRY_ENABLED, enabled).apply()
            _settings.value = _settings.value.copy(telemetryEnabled = enabled)
        }
    }

    override suspend fun updateTelemetryEndpoint(endpoint: String) {
        val cleanUrl = endpoint.trim()
        val finalUrl = if (cleanUrl.isNotEmpty()) {
            require(cleanUrl.startsWith("https://", ignoreCase = true)) {
                "Only secure HTTPS endpoints are permitted"
            }
            cleanUrl
        } else {
            AppSettings.DEFAULT_TELEMETRY_ENDPOINT
        }

        synchronized(lock) {
            if (finalUrl == AppSettings.DEFAULT_TELEMETRY_ENDPOINT) {
                prefs.edit().remove(KEY_TELEMETRY_ENDPOINT).apply()
            } else {
                prefs.edit().putString(KEY_TELEMETRY_ENDPOINT, finalUrl).apply()
            }
            _settings.value = _settings.value.copy(telemetryEndpoint = finalUrl)
        }
    }

    override suspend fun resetToDefaults() {
        synchronized(lock) {
            prefs.edit().clear().apply()
            _settings.value = AppSettings.DEFAULT
        }
    }

    private fun loadInitialSettings(): AppSettings {
        val autoRefresh = AutoRefreshInterval.fromNameOrDefault(
            prefs.getString(KEY_AUTO_REFRESH_INTERVAL, null)
        )
        val retention = MatchRetentionPolicy.fromNameOrDefault(
            prefs.getString(KEY_MATCH_RETENTION, null)
        )
        val widgetSync = WidgetSyncMode.fromNameOrDefault(
            prefs.getString(KEY_WIDGET_SYNC_MODE, null)
        )
        val defaultTour = StartupTournamentTier.fromNameOrDefault(
            prefs.getString(KEY_DEFAULT_TOURNAMENT, null)
        )
        val telemetryEnabled = prefs.getBoolean(KEY_TELEMETRY_ENABLED, true)

        val rawEndpoint = prefs.getString(KEY_TELEMETRY_ENDPOINT, null)?.trim()
        val endpoint = if (!rawEndpoint.isNullOrEmpty() && rawEndpoint.startsWith("https://", ignoreCase = true)) {
            rawEndpoint
        } else {
            AppSettings.DEFAULT_TELEMETRY_ENDPOINT
        }

        return AppSettings(
            autoRefreshInterval = autoRefresh,
            matchRetention = retention,
            widgetSyncMode = widgetSync,
            defaultTournament = defaultTour,
            telemetryEnabled = telemetryEnabled,
            telemetryEndpoint = endpoint
        )
    }

    companion object {
        const val PREFS_NAME = "tennis_tracker_app_settings"

        const val KEY_AUTO_REFRESH_INTERVAL = "key_auto_refresh_interval"
        const val KEY_MATCH_RETENTION = "key_match_retention"
        const val KEY_WIDGET_SYNC_MODE = "key_widget_sync_mode"
        const val KEY_DEFAULT_TOURNAMENT = "key_default_tournament"
        const val KEY_TELEMETRY_ENABLED = "key_telemetry_enabled"
        const val KEY_TELEMETRY_ENDPOINT = "key_telemetry_endpoint"

        @Volatile
        private var instance: SettingsRepositoryImpl? = null

        fun getInstance(context: Context): SettingsRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepositoryImpl(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }

        fun getInstance(prefs: SharedPreferences): SettingsRepositoryImpl {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepositoryImpl(prefs).also { instance = it }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }
}
