package com.tennis.tracker.domain.model.bugreport

import org.json.JSONObject

/**
 * Diagnostic snapshot of user application preferences and sync policies.
 */
data class AppSettingsSnapshot(
    val autoRefreshInterval: String = "INTERVAL_60S",
    val matchRetention: String = "HOURS_24",
    val widgetSyncMode: String = "ADAPTIVE",
    val defaultTournament: String = "ALL",
    val telemetryEnabled: Boolean = true,
    val hasCustomEndpoint: Boolean = false
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("auto_refresh_interval", autoRefreshInterval)
        obj.put("match_retention", matchRetention)
        obj.put("widget_sync_mode", widgetSyncMode)
        obj.put("default_tournament", defaultTournament)
        obj.put("telemetry_enabled", telemetryEnabled)
        obj.put("has_custom_endpoint", hasCustomEndpoint)
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): AppSettingsSnapshot {
            return AppSettingsSnapshot(
                autoRefreshInterval = json.optString("auto_refresh_interval", "INTERVAL_60S"),
                matchRetention = json.optString("match_retention", "HOURS_24"),
                widgetSyncMode = json.optString("widget_sync_mode", "ADAPTIVE"),
                defaultTournament = json.optString("default_tournament", "ALL"),
                telemetryEnabled = json.optBoolean("telemetry_enabled", true),
                hasCustomEndpoint = json.optBoolean("has_custom_endpoint", false)
            )
        }
    }
}
