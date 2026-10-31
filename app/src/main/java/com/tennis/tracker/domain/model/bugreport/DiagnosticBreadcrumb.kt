package com.tennis.tracker.domain.model.bugreport

import org.json.JSONObject
import java.time.Instant

/**
 * Represents a single non-PII diagnostic breadcrumb event.
 */
data class DiagnosticBreadcrumb(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * ISO-8601 UTC formatted representation of timestamp.
     */
    val isoTimestamp: String
        get() = runCatching { Instant.ofEpochMilli(timestamp).toString() }
            .getOrDefault("1970-01-01T00:00:00Z")

    /**
     * Serializes this breadcrumb to a [JSONObject].
     */
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("timestamp", timestamp)
        obj.put("iso_timestamp", isoTimestamp)
        obj.put("tag", tag)
        obj.put("message", message)
        if (metadata.isNotEmpty()) {
            val metaObj = JSONObject()
            for ((key, value) in metadata) {
                metaObj.put(key, value)
            }
            obj.put("metadata", metaObj)
        }
        return obj
    }

    companion object {
        /**
         * Deserializes a breadcrumb from a [JSONObject].
         */
        fun fromJson(json: JSONObject): DiagnosticBreadcrumb {
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val tag = json.optString("tag", "")
            val message = json.optString("message", "")
            val metadata = mutableMapOf<String, String>()
            val metaObj = json.optJSONObject("metadata")
            if (metaObj != null) {
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    metadata[key] = metaObj.optString(key, "")
                }
            }
            return DiagnosticBreadcrumb(
                timestamp = timestamp,
                tag = tag,
                message = message,
                metadata = metadata
            )
        }
    }
}
