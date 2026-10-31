package com.tennis.tracker.domain.model.bugreport

import com.tennis.tracker.data.diagnostic.BugReportPayloadFormatter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Complete, privacy-safe diagnostic telemetry bundle captured at the time of issue reporting.
 */
data class DiagnosticSnapshot(
    val appVersion: String = "1.0.25",
    val versionCode: Int = 26,
    val formFactor: String = "PHONE",
    val screenWidthBucket: String = "COMPACT",
    val screenWidthDp: Int = 412,
    val screenHeightDp: Int = 915,
    val osVersion: String = "unknown",
    val apiLevel: Int = 0,
    val deviceModel: String = "unknown",
    val deviceManufacturer: String = "unknown",
    val scoreboardState: ScoreboardDiagnosticState = ScoreboardDiagnosticState(),
    val settingsSnapshot: AppSettingsSnapshot = AppSettingsSnapshot(),
    val matchContext: MatchContextSnapshot? = null,
    val breadcrumbs: List<DiagnosticBreadcrumb> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val isoTimestamp: String
        get() = runCatching { Instant.ofEpochMilli(timestamp).toString() }
            .getOrDefault("1970-01-01T00:00:00Z")

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("app_version", appVersion)
        obj.put("version_code", versionCode)
        obj.put("form_factor", formFactor)
        obj.put("screen_width_bucket", screenWidthBucket)
        obj.put("screen_width_dp", screenWidthDp)
        obj.put("screen_height_dp", screenHeightDp)
        obj.put("os_version", osVersion)
        obj.put("api_level", apiLevel)
        obj.put("device_model", deviceModel)
        obj.put("device_manufacturer", deviceManufacturer)
        obj.put("timestamp", isoTimestamp)
        obj.put("captured_at", timestamp)

        obj.put("scoreboard_state", scoreboardState.toJson())
        obj.put("settings_snapshot", settingsSnapshot.toJson())

        if (matchContext != null) {
            obj.put("match_context", matchContext.toJson())
        } else {
            obj.put("match_context", JSONObject.NULL)
        }

        val breadcrumbsArray = JSONArray()
        for (crumb in breadcrumbs) {
            breadcrumbsArray.put(crumb.toJson())
        }
        obj.put("breadcrumbs", breadcrumbsArray)

        return obj
    }

    /**
     * Formats human-readable Markdown summary tables.
     */
    fun toFormattedMarkdown(): String {
        return BugReportPayloadFormatter.formatMarkdownSummary(this)
    }

    /**
     * Formats complete GitHub issue body containing user description, Markdown summary,
     * and delimited machine-readable JSON block.
     */
    fun toFullIssueBody(userReport: BugReport): String {
        return BugReportPayloadFormatter.formatFullIssueBody(userReport, this)
    }

    companion object {
        fun fromJson(json: JSONObject): DiagnosticSnapshot {
            val breadcrumbsList = mutableListOf<DiagnosticBreadcrumb>()
            val crumbsArray = json.optJSONArray("breadcrumbs")
            if (crumbsArray != null) {
                for (i in 0 until crumbsArray.length()) {
                    val crumbObj = crumbsArray.optJSONObject(i)
                    if (crumbObj != null) {
                        breadcrumbsList.add(DiagnosticBreadcrumb.fromJson(crumbObj))
                    }
                }
            }

            val matchContextObj = json.optJSONObject("match_context")
            val parsedMatchContext = if (matchContextObj != null && !json.isNull("match_context")) {
                MatchContextSnapshot.fromJson(matchContextObj)
            } else {
                null
            }

            val scoreboardObj = json.optJSONObject("scoreboard_state")
            val scoreboard = if (scoreboardObj != null) {
                ScoreboardDiagnosticState.fromJson(scoreboardObj)
            } else {
                ScoreboardDiagnosticState()
            }

            val settingsObj = json.optJSONObject("settings_snapshot")
            val settings = if (settingsObj != null) {
                AppSettingsSnapshot.fromJson(settingsObj)
            } else {
                AppSettingsSnapshot()
            }

            return DiagnosticSnapshot(
                appVersion = json.optString("app_version", "1.0.25"),
                versionCode = json.optInt("version_code", 26),
                formFactor = json.optString("form_factor", "PHONE"),
                screenWidthBucket = json.optString("screen_width_bucket", "COMPACT"),
                screenWidthDp = json.optInt("screen_width_dp", 412),
                screenHeightDp = json.optInt("screen_height_dp", 915),
                osVersion = json.optString("os_version", "unknown"),
                apiLevel = json.optInt("api_level", 0),
                deviceModel = json.optString("device_model", "unknown"),
                deviceManufacturer = json.optString("device_manufacturer", "unknown"),
                scoreboardState = scoreboard,
                settingsSnapshot = settings,
                matchContext = parsedMatchContext,
                breadcrumbs = breadcrumbsList,
                timestamp = json.optLong("captured_at", System.currentTimeMillis())
            )
        }
    }
}
