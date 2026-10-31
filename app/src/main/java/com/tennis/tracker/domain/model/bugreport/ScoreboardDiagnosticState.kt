package com.tennis.tracker.domain.model.bugreport

import org.json.JSONObject

/**
 * Diagnostic snapshot of active scoreboard filter and match counts.
 */
data class ScoreboardDiagnosticState(
    val tourFilter: String = "ALL",
    val currentTournament: String = "All Tournaments",
    val matchTypeFilter: String = "ALL",
    val matchFilter: String = "ALL",
    val isOffline: Boolean = false,
    val searchActive: Boolean = false,
    val searchQuery: String? = null,
    val totalMatches: Int = 0,
    val filteredMatches: Int = 0,
    val liveMatches: Int = 0,
    val followedMatches: Int = 0
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("tour_filter", tourFilter)
        obj.put("current_tournament", currentTournament)
        obj.put("match_type_filter", matchTypeFilter)
        obj.put("match_filter", matchFilter)
        obj.put("is_offline", isOffline)
        obj.put("search_active", searchActive)
        if (!searchQuery.isNullOrBlank()) {
            obj.put("search_query", searchQuery)
        }
        obj.put("total_matches", totalMatches)
        obj.put("filtered_matches", filteredMatches)
        obj.put("live_matches", liveMatches)
        obj.put("followed_matches", followedMatches)
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): ScoreboardDiagnosticState {
            return ScoreboardDiagnosticState(
                tourFilter = json.optString("tour_filter", "ALL"),
                currentTournament = json.optString("current_tournament", "All Tournaments"),
                matchTypeFilter = json.optString("match_type_filter", "ALL"),
                matchFilter = json.optString("match_filter", "ALL"),
                isOffline = json.optBoolean("is_offline", false),
                searchActive = json.optBoolean("search_active", false),
                searchQuery = if (json.has("search_query")) json.optString("search_query") else null,
                totalMatches = json.optInt("total_matches", 0),
                filteredMatches = json.optInt("filtered_matches", 0),
                liveMatches = json.optInt("live_matches", 0),
                followedMatches = json.optInt("followed_matches", 0)
            )
        }
    }
}
