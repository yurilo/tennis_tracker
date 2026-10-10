package com.usopen.tracker.data.local

import android.content.Context
import android.content.SharedPreferences
import com.usopen.tracker.data.remote.YouTubeHighlightResolver
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.usecase.DeepLinkGenerator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages in-memory and persistent storage of match data so that
 * the app, widget provider, and widget RemoteViews service share
 * the exact same live match data (including upcoming matches with ESPN IDs).
 */
object MatchCacheManager {

    private const val PREFS_NAME = "us_open_matches_cache"
    private const val KEY_CACHED_MATCHES_JSON = "cached_matches_json"
    private const val KEY_LAST_SAVED_TIME = "last_saved_timestamp"

    @Volatile
    private var memoryCache: List<Match>? = null

    /**
     * Saves the list of matches to in-memory cache and persists to SharedPreferences.
     */
    fun saveMatches(context: Context?, matches: List<Match>) {
        if (matches.isEmpty()) return
        memoryCache = matches

        val ctx = context?.applicationContext ?: return
        try {
            val jsonArray = JSONArray()
            for (match in matches) {
                jsonArray.put(matchToJson(match))
            }
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CACHED_MATCHES_JSON, jsonArray.toString())
                .putLong(KEY_LAST_SAVED_TIME, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
            // Guard against serialization failures
        }
    }

    /**
     * Updates an individual match in the cache and persists the change.
     */
    fun updateMatch(context: Context?, updatedMatch: Match) {
        val current = memoryCache ?: loadMatches(context)
        if (current.isEmpty()) return
        val updatedList = current.map { if (it.id == updatedMatch.id) updatedMatch else it }
        saveMatches(context, updatedList)
    }

    /**
     * Loads matches from memory cache, falling back to persistent SharedPreferences.
     */
    fun loadMatches(context: Context?): List<Match> {
        val inMemory = memoryCache
        if (!inMemory.isNullOrEmpty()) {
            return inMemory
        }

        val ctx = context?.applicationContext
        if (ctx != null) {
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val rawJson = prefs.getString(KEY_CACHED_MATCHES_JSON, null)
                if (!rawJson.isNullOrEmpty()) {
                    val parsed = parseMatchesJson(rawJson)
                    if (parsed.isNotEmpty()) {
                        memoryCache = parsed
                        return parsed
                    }
                }
            } catch (_: Throwable) {
                // Fall back if disk cache fails
            }
        }

        return emptyList()
    }

    /**
     * Returns all known matches from local cache.
     * Ensures any followed match that has been synced from the network is found.
     */
    fun getAllKnownMatches(context: Context?): List<Match> {
        return loadMatches(context)
    }

    /**
     * Clears in-memory and persistent cache (useful for testing or full reset).
     */
    fun clearCache(context: Context?) {
        memoryCache = null
        context?.applicationContext?.let { ctx ->
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    // --- JSON Serialization & Deserialization ---

    fun matchToJson(match: Match): JSONObject {
        val obj = JSONObject()
        obj.put("id", match.id)
        obj.put("tournamentName", match.tournamentName)
        obj.put("eventGrouping", match.eventGrouping)
        obj.put("roundName", match.roundName)
        obj.put("state", match.state.name)
        obj.put("statusDetail", match.statusDetail)
        obj.put("venue", match.venue)
        if (match.court != null) obj.put("court", match.court)
        if (match.startTime != null) obj.put("startTime", match.startTime)
        if (match.endTime != null) obj.put("endTime", match.endTime)
        if (match.durationMinutes != null) obj.put("durationMinutes", match.durationMinutes)
        if (match.espnEventId != null) obj.put("espnEventId", match.espnEventId)
        if (match.espnWatchId != null) obj.put("espnWatchId", match.espnWatchId)
        if (match.youtubeVideoId != null) obj.put("youtubeVideoId", match.youtubeVideoId)
        obj.put("player1", competitorToJson(match.player1))
        obj.put("player2", competitorToJson(match.player2))
        return obj
    }

    fun jsonToMatch(obj: JSONObject): Match {
        val stateStr = obj.optString("state", "UPCOMING")
        val state = try {
            MatchState.valueOf(stateStr)
        } catch (_: Throwable) {
            MatchState.UPCOMING
        }

        val p1Obj = obj.optJSONObject("player1")
        val p2Obj = obj.optJSONObject("player2")

        val match = Match(
            id = obj.optString("id", ""),
            tournamentName = obj.optString("tournamentName", "US Open"),
            eventGrouping = obj.optString("eventGrouping", "Singles"),
            roundName = obj.optString("roundName", ""),
            state = state,
            statusDetail = obj.optString("statusDetail", ""),
            player1 = if (p1Obj != null) jsonToCompetitor(p1Obj) else Competitor(id = "p1", name = "Unknown"),
            player2 = if (p2Obj != null) jsonToCompetitor(p2Obj) else Competitor(id = "p2", name = "Unknown"),
            venue = obj.optString("venue", "Arthur Ashe Stadium"),
            court = obj.optString("court", "").takeIf { it.isNotEmpty() },
            startTime = obj.optString("startTime", "").takeIf { it.isNotEmpty() },
            endTime = obj.optString("endTime", "").takeIf { it.isNotEmpty() },
            durationMinutes = if (obj.has("durationMinutes")) obj.optInt("durationMinutes") else null,
            espnEventId = obj.optString("espnEventId", "").takeIf { it.isNotEmpty() },
            espnWatchId = obj.optString("espnWatchId", "").takeIf { it.isNotEmpty() },
            youtubeVideoId = obj.optString("youtubeVideoId", "").takeIf { it.isNotEmpty() }
        )
        val withDeepLink = match.copy(deepLink = DeepLinkGenerator.generate(match))
        return if (state == MatchState.FINISHED) {
            withDeepLink.copy(youtubeDeepLink = YouTubeHighlightResolver.generateDeepLink(withDeepLink))
        } else {
            withDeepLink
        }
    }

    fun competitorToJson(comp: Competitor): JSONObject {
        val obj = JSONObject()
        obj.put("id", comp.id)
        obj.put("name", comp.name)
        if (comp.seed != null) obj.put("seed", comp.seed)
        if (comp.country != null) obj.put("country", comp.country)
        obj.put("isWinner", comp.isWinner)
        obj.put("isServing", comp.isServing)
        if (comp.currentGameScore != null) obj.put("currentGameScore", comp.currentGameScore)
        if (comp.photoUrl != null) obj.put("photoUrl", comp.photoUrl)

        val setScoresArray = JSONArray()
        comp.setScores.forEach { setScoresArray.put(it) }
        obj.put("setScores", setScoresArray)

        val tbScoresArray = JSONArray()
        comp.tiebreakScores.forEach { tb ->
            tbScoresArray.put(tb ?: -1)
        }
        obj.put("tiebreakScores", tbScoresArray)
        return obj
    }

    fun jsonToCompetitor(obj: JSONObject): Competitor {
        val setScores = mutableListOf<Int>()
        val setScoresArray = obj.optJSONArray("setScores")
        if (setScoresArray != null) {
            for (i in 0 until setScoresArray.length()) {
                setScores.add(setScoresArray.optInt(i, 0))
            }
        }

        val tiebreakScores = mutableListOf<Int?>()
        val tbScoresArray = obj.optJSONArray("tiebreakScores")
        if (tbScoresArray != null) {
            for (i in 0 until tbScoresArray.length()) {
                val tbVal = tbScoresArray.optInt(i, -1)
                tiebreakScores.add(if (tbVal >= 0) tbVal else null)
            }
        }

        return Competitor(
            id = obj.optString("id", ""),
            name = obj.optString("name", "Unknown"),
            seed = if (obj.has("seed")) obj.optInt("seed").takeIf { it > 0 } else null,
            country = obj.optString("country", "").takeIf { it.isNotEmpty() },
            isWinner = obj.optBoolean("isWinner", false),
            setScores = setScores,
            tiebreakScores = tiebreakScores,
            currentGameScore = obj.optString("currentGameScore", "").takeIf { it.isNotEmpty() },
            isServing = obj.optBoolean("isServing", false),
            photoUrl = obj.optString("photoUrl", "").takeIf { it.isNotEmpty() }
        )
    }

    fun parseMatchesJson(jsonString: String): List<Match> {
        return try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<Match>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                try {
                    list.add(jsonToMatch(obj))
                } catch (_: Throwable) {}
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
