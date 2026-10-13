package com.tennis.tracker.domain.telemetry

import org.json.JSONObject

/**
 * Data model representing an anonymous telemetry heartbeat event.
 * Contains zero personal identifiable information (PII).
 */
data class TelemetryEvent(
    val eventType: String,
    val anonymousId: String,
    val sessionId: String,
    val appVersion: String,
    val versionCode: Int,
    val osVersion: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val locale: String,
    val timestamp: String,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("eventType", eventType)
            put("anonymousId", anonymousId)
            put("sessionId", sessionId)
            put("appVersion", appVersion)
            put("versionCode", versionCode)
            put("osVersion", osVersion)
            put("deviceManufacturer", deviceManufacturer)
            put("deviceModel", deviceModel)
            put("locale", locale)
            put("timestamp", timestamp)
            if (metadata.isNotEmpty()) {
                val metaObj = JSONObject()
                metadata.forEach { (k, v) -> metaObj.put(k, v) }
                put("metadata", metaObj)
            }
        }
    }
}
