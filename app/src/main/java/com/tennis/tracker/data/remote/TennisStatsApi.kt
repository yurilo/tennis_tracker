package com.tennis.tracker.data.remote

import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.PeriodStats
import com.tennis.tracker.domain.model.StatGroup
import com.tennis.tracker.domain.model.StatItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

open class TennisStatsApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val SOFASCORE_BASE = "https://api.sofascore.com/api/v1"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private const val US_OPEN_ATP_TOURNAMENT = "196855"
        private const val US_OPEN_ATP_SEASON = "85956"
        private const val US_OPEN_WTA_TOURNAMENT = "196857"
        private const val US_OPEN_WTA_SEASON = "85958"
        private val TOKEN_DELIMITER_REGEX = Regex("[\\s/.-]+")
    }

    open fun fetchStatistics(match: Match): Result<MatchStatistics> {
        if (match.state == MatchState.UPCOMING) {
            return Result.failure(NoSuchElementException("Match statistics will become available once the match begins."))
        }
        return try {
            val (eventId, isP1Home) = findEventId(match)
            if (eventId != null) {
                val stats = fetchEventStatistics(eventId, match, isP1Home)
                if (stats != null && stats.periods.isNotEmpty()) {
                    return Result.success(stats)
                }
            }
            Result.failure(NoSuchElementException("Match statistics are not available for this match yet."))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun findEventId(match: Match): Pair<String?, Boolean> {
        val p1Last = match.player1.name.split(" ").lastOrNull()?.lowercase() ?: ""
        val p2Last = match.player2.name.split(" ").lastOrNull()?.lowercase() ?: ""
        if (p1Last.isEmpty() && p2Last.isEmpty()) return Pair(null, true)

        try {
            searchEventsUrl("$SOFASCORE_BASE/sport/tennis/events/live", p1Last, p2Last)?.let {
                return it
            }

            searchEventsUrl("$SOFASCORE_BASE/tournament/$US_OPEN_ATP_TOURNAMENT/season/$US_OPEN_ATP_SEASON/events/last/0", p1Last, p2Last)?.let {
                return it
            }
            searchEventsUrl("$SOFASCORE_BASE/tournament/$US_OPEN_WTA_TOURNAMENT/season/$US_OPEN_WTA_SEASON/events/last/0", p1Last, p2Last)?.let {
                return it
            }

            val queryName = p1Last.ifEmpty { p2Last }
            val encoded = URLEncoder.encode(queryName, "UTF-8")
            val searchUrl = "$SOFASCORE_BASE/search/all?q=$encoded"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 403) {
                return Pair(null, true)
            }
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val root = JSONObject(body)
                    val results = root.optJSONArray("results")
                    if (results != null) {
                        for (j in 0 until results.length()) {
                            val res = results.optJSONObject(j) ?: continue
                            val entity = res.optJSONObject("entity") ?: continue
                            val teamId = entity.optString("id", "")
                            if (teamId.isNotEmpty()) {
                                searchEventsUrl("$SOFASCORE_BASE/team/$teamId/events/last/0", p1Last, p2Last)?.let {
                                    return it
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return Pair(null, true)
    }

    private fun searchEventsUrl(url: String, p1Last: String, p2Last: String): Pair<String, Boolean>? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 403) {
                throw java.io.IOException("Remote stats provider returned HTTP 403 Forbidden")
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val events = root.optJSONArray("events") ?: return null

                for (i in 0 until events.length()) {
                    val ev = events.optJSONObject(i) ?: continue
                    val home = ev.optJSONObject("homeTeam")?.optString("name", "")?.lowercase() ?: ""
                    val away = ev.optJSONObject("awayTeam")?.optString("name", "")?.lowercase() ?: ""
                    val eventId = ev.optString("id", "")
                    if (eventId.isEmpty()) continue

                    val p1InHome = p1Last.isNotEmpty() && home.contains(p1Last)
                    val p1InAway = p1Last.isNotEmpty() && away.contains(p1Last)
                    val p2InHome = p2Last.isNotEmpty() && home.contains(p2Last)
                    val p2InAway = p2Last.isNotEmpty() && away.contains(p2Last)

                    if (p1InHome && p2InAway) {
                        return Pair(eventId, true)
                    } else if (p2InHome && p1InAway) {
                        return Pair(eventId, false)
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun fetchEventStatistics(eventId: String, match: Match, isP1Home: Boolean): MatchStatistics? {
        val statsUrl = "$SOFASCORE_BASE/event/$eventId/statistics"
        val request = Request.Builder()
            .url(statsUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.sofascore.com/")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null

        return parseStatisticsJson(body, match, isP1Home)
    }

    fun parseStatisticsJson(jsonString: String, match: Match, isP1Home: Boolean = true): MatchStatistics? {
        val root = JSONObject(jsonString)
        val statisticsArray = root.optJSONArray("statistics") ?: return null
        if (statisticsArray.length() == 0) return null

        val periods = mutableListOf<PeriodStats>()

        for (p in 0 until statisticsArray.length()) {
            val periodObj = statisticsArray.optJSONObject(p) ?: continue
            val periodName = periodObj.optString("period", "ALL")

            val displayLabel = when (periodName) {
                "ALL" -> "Match"
                "1ST" -> "Set 1"
                "2ND" -> "Set 2"
                "3RD" -> "Set 3"
                "4TH" -> "Set 4"
                "5TH" -> "Set 5"
                else -> periodName
            }

            val groupsArray = periodObj.optJSONArray("groups") ?: continue
            val groupsList = mutableListOf<StatGroup>()

            for (g in 0 until groupsArray.length()) {
                val groupObj = groupsArray.optJSONObject(g) ?: continue
                val groupName = groupObj.optString("groupName", "")
                val itemsArray = groupObj.optJSONArray("statisticsItems") ?: continue

                val itemsList = mutableListOf<StatItem>()
                for (it in 0 until itemsArray.length()) {
                    val itemObj = itemsArray.optJSONObject(it) ?: continue
                    val itemName = itemObj.optString("name", "")

                    val rawHome = itemObj.optString("home", "0")
                    val rawAway = itemObj.optString("away", "0")

                    val p1Display = if (isP1Home) rawHome else rawAway
                    val p2Display = if (isP1Home) rawAway else rawHome

                    val homeVal = itemObj.optDouble("homeValue", Double.NaN)
                    val awayVal = itemObj.optDouble("awayValue", Double.NaN)

                    val p1Val = if (isP1Home) homeVal else awayVal
                    val p2Val = if (isP1Home) awayVal else homeVal

                    val ratio = if (!p1Val.isNaN() && !p2Val.isNaN() && (p1Val + p2Val) > 0.0) {
                        (p1Val / (p1Val + p2Val)).toFloat().coerceIn(0.05f, 0.95f)
                    } else {
                        // Attempt to calculate from display strings (e.g. 75% vs 65%)
                        val p1Num = p1Display.filter { it.isDigit() }.toIntOrNull()?.toFloat() ?: 0f
                        val p2Num = p2Display.filter { it.isDigit() }.toIntOrNull()?.toFloat() ?: 0f
                        if (p1Num + p2Num > 0f) (p1Num / (p1Num + p2Num)).coerceIn(0.05f, 0.95f) else 0.5f
                    }

                    itemsList.add(
                        StatItem(
                            name = itemName,
                            homeDisplay = p1Display,
                            awayDisplay = p2Display,
                            homeRatio = ratio
                        )
                    )
                }

                if (itemsList.isNotEmpty()) {
                    groupsList.add(StatGroup(groupName = groupName, items = itemsList))
                }
            }

            if (groupsList.isNotEmpty()) {
                periods.add(PeriodStats(period = periodName, label = displayLabel, groups = groupsList))
            }
        }

            return if (periods.isNotEmpty()) {
            MatchStatistics(
                matchId = match.id,
                player1Name = match.player1.name,
                player2Name = match.player2.name,
                periods = periods
            )
        } else null
    }

    open fun fetchLiveGameScores(matches: List<Match>): Map<String, Pair<String, String>> {
        val liveMatches = matches.filter { it.state == MatchState.LIVE }
        if (liveMatches.isEmpty()) return emptyMap()

        return try {
            val request = Request.Builder()
                .url("$SOFASCORE_BASE/sport/tennis/events/live")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyMap()
            val body = response.body?.string() ?: return emptyMap()
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return emptyMap()

            val scoreMap = mutableMapOf<String, Pair<String, String>>()
            if (liveMatches.isEmpty()) return emptyMap()

            data class MatchTokens(
                val match: Match,
                val p1Tokens: List<String>,
                val p2Tokens: List<String>
            )

            val indexedMatches = liveMatches.map { m ->
                MatchTokens(
                    match = m,
                    p1Tokens = extractPlayerTokens(m.player1.name),
                    p2Tokens = extractPlayerTokens(m.player2.name)
                )
            }

            // Pre-index matches by their player tokens for O(1) candidate lookup
            val tokenToMatches = mutableMapOf<String, MutableList<MatchTokens>>()
            for (mt in indexedMatches) {
                for (token in (mt.p1Tokens + mt.p2Tokens)) {
                    if (token.isNotEmpty()) {
                        tokenToMatches.getOrPut(token) { mutableListOf() }.add(mt)
                    }
                }
            }

            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val homePoint = ev.optJSONObject("homeScore")?.optString("point", "") ?: ""
                val awayPoint = ev.optJSONObject("awayScore")?.optString("point", "") ?: ""
                if (homePoint.isEmpty() && awayPoint.isEmpty()) continue

                val homeTeam = ev.optJSONObject("homeTeam")?.optString("name", "")?.lowercase() ?: ""
                val awayTeam = ev.optJSONObject("awayTeam")?.optString("name", "")?.lowercase() ?: ""

                // Lookup candidate matches via indexed player tokens map
                val eventTokens = (homeTeam.split(TOKEN_DELIMITER_REGEX) + awayTeam.split(TOKEN_DELIMITER_REGEX))
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }

                val candidates = mutableSetOf<MatchTokens>()
                for (token in eventTokens) {
                    tokenToMatches[token]?.let { candidates.addAll(it) }
                }

                // Fallback for substring matching in concatenated names
                if (candidates.isEmpty()) {
                    for ((token, targetMatches) in tokenToMatches) {
                        if (homeTeam.contains(token) || awayTeam.contains(token)) {
                            candidates.addAll(targetMatches)
                        }
                    }
                }

                for (mt in candidates) {
                    if (scoreMap.containsKey(mt.match.id)) continue

                    val p1InHome = mt.p1Tokens.any { it.isNotEmpty() && homeTeam.contains(it) }
                    val p1InAway = mt.p1Tokens.any { it.isNotEmpty() && awayTeam.contains(it) }
                    val p2InHome = mt.p2Tokens.any { it.isNotEmpty() && homeTeam.contains(it) }
                    val p2InAway = mt.p2Tokens.any { it.isNotEmpty() && awayTeam.contains(it) }

                    if (p1InHome && p2InAway) {
                        scoreMap[mt.match.id] = normalizePoint(homePoint) to normalizePoint(awayPoint)
                    } else if (p2InHome && p1InAway) {
                        scoreMap[mt.match.id] = normalizePoint(awayPoint) to normalizePoint(homePoint)
                    }
                }
            }
            scoreMap
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun extractPlayerTokens(name: String): List<String> {
        return name.split("/").mapNotNull { part ->
            part.trim().split(" ").lastOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun normalizePoint(point: String): String {
        val trimmed = point.trim()
        return when {
            trimmed.equals("A", ignoreCase = true) || trimmed.equals("Ad", ignoreCase = true) -> "Adv"
            trimmed.equals("Deuce", ignoreCase = true) -> "40"
            trimmed.isNotEmpty() -> trimmed
            else -> "0"
        }
    }
}
