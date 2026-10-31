package com.tennis.tracker.domain.model.bugreport

import org.json.JSONObject

/**
 * Diagnostic contextual metadata captured when reporting an issue from a specific match.
 */
data class MatchContextSnapshot(
    val matchId: String,
    val tournamentName: String,
    val tournamentId: String? = null,
    val eventGrouping: String = "Men's Singles",
    val effectiveTour: String = "ATP",
    val effectiveCategory: String = "GRAND_SLAM",
    val state: String = "LIVE",
    val court: String? = null,
    val venue: String? = null,
    val roundName: String? = null,
    val statusDetail: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("match_id", matchId)
        obj.put("tournament_name", tournamentName)
        if (!tournamentId.isNullOrBlank()) {
            obj.put("tournament_id", tournamentId)
        }
        obj.put("event_grouping", eventGrouping)
        obj.put("effective_tour", effectiveTour)
        obj.put("effective_category", effectiveCategory)
        obj.put("state", state)
        if (!court.isNullOrBlank()) {
            obj.put("court", court)
        }
        if (!venue.isNullOrBlank()) {
            obj.put("venue", venue)
        }
        if (!roundName.isNullOrBlank()) {
            obj.put("round_name", roundName)
        }
        if (!statusDetail.isNullOrBlank()) {
            obj.put("status_detail", statusDetail)
        }
        return obj
    }

    companion object {
        fun fromJson(json: JSONObject): MatchContextSnapshot {
            return MatchContextSnapshot(
                matchId = json.optString("match_id", ""),
                tournamentName = json.optString("tournament_name", ""),
                tournamentId = if (json.has("tournament_id")) json.optString("tournament_id") else null,
                eventGrouping = json.optString("event_grouping", "Men's Singles"),
                effectiveTour = json.optString("effective_tour", "ATP"),
                effectiveCategory = json.optString("effective_category", "GRAND_SLAM"),
                state = json.optString("state", "LIVE"),
                court = if (json.has("court")) json.optString("court") else null,
                venue = if (json.has("venue")) json.optString("venue") else null,
                roundName = if (json.has("round_name")) json.optString("round_name") else null,
                statusDetail = if (json.has("status_detail")) json.optString("status_detail") else null
            )
        }
    }
}
