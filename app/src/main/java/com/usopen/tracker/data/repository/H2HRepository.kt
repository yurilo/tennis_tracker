package com.usopen.tracker.data.repository

import com.usopen.tracker.data.local.H2HDataProvider
import com.usopen.tracker.domain.model.H2HSummary
import com.usopen.tracker.domain.model.Match
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

class H2HRepository(
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
    }

    suspend fun getH2HSummary(match: Match): Result<H2HSummary> = withContext(Dispatchers.IO) {
        try {
            val (eventId, isP1Home) = findEventId(match)
            if (eventId != null) {
                val h2hUrl = "$SOFASCORE_BASE/event/$eventId/h2h"
                val request = Request.Builder()
                    .url(h2hUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.sofascore.com/")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val root = JSONObject(body)
                        val teamDuel = root.optJSONObject("teamDuel")
                        if (teamDuel != null) {
                            val homeWins = teamDuel.optInt("homeWins", 0)
                            val awayWins = teamDuel.optInt("awayWins", 0)
                            val p1Wins = if (isP1Home) homeWins else awayWins
                            val p2Wins = if (isP1Home) awayWins else homeWins

                            // Return rich summary with live team duel counts
                            val summary = H2HDataProvider.generateH2H(
                                match = match,
                                p1WinsOverride = p1Wins,
                                p2WinsOverride = p2Wins
                            )
                            return@withContext Result.success(summary)
                        }
                    }
                }
            }

            // Fall back gracefully to curated / local deterministic H2H data
            val fallbackSummary = H2HDataProvider.generateH2H(match)
            Result.success(fallbackSummary)
        } catch (t: Throwable) {
            // Even on any network or unexpected exception, provide the local summary
            try {
                Result.success(H2HDataProvider.generateH2H(match))
            } catch (inner: Throwable) {
                Result.failure(t)
            }
        }
    }

    private fun findEventId(match: Match): Pair<String?, Boolean> {
        val p1Last = extractLastName(match.player1.name)
        val p2Last = extractLastName(match.player2.name)
        if (p1Last.isEmpty() && p2Last.isEmpty()) return Pair(null, true)

        // 1. Check live matches
        searchEventsUrl("$SOFASCORE_BASE/sport/tennis/events/live", p1Last, p2Last)?.let { return it }

        // 2. Check upcoming/next scheduled events (ATP and WTA)
        searchEventsUrl("$SOFASCORE_BASE/tournament/$US_OPEN_ATP_TOURNAMENT/season/$US_OPEN_ATP_SEASON/events/next/0", p1Last, p2Last)?.let { return it }
        searchEventsUrl("$SOFASCORE_BASE/tournament/$US_OPEN_WTA_TOURNAMENT/season/$US_OPEN_WTA_SEASON/events/next/0", p1Last, p2Last)?.let { return it }

        // 3. Check finished/last events (ATP and WTA)
        searchEventsUrl("$SOFASCORE_BASE/tournament/$US_OPEN_ATP_TOURNAMENT/season/$US_OPEN_ATP_SEASON/events/last/0", p1Last, p2Last)?.let { return it }
        searchEventsUrl("$SOFASCORE_BASE/tournament/$US_OPEN_WTA_TOURNAMENT/season/$US_OPEN_WTA_SEASON/events/last/0", p1Last, p2Last)?.let { return it }

        return Pair(null, true)
    }

    private fun extractLastName(fullName: String): String {
        val tokens = fullName.trim().lowercase().split("\\s+".toRegex())
            .filter { it.isNotEmpty() && it != "jr." && it != "jr" && it != "sr." && it != "sr" && it != "ii" && it != "iii" }
        val last = tokens.lastOrNull() ?: ""
        return normalizeString(last).filter { it.isLetter() }
    }

    private fun normalizeString(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private fun searchEventsUrl(url: String, p1Last: String, p2Last: String): Pair<String, Boolean>? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val root = JSONObject(body)
                val events = root.optJSONArray("events") ?: return null

                for (i in 0 until events.length()) {
                    val ev = events.optJSONObject(i) ?: continue
                    val homeRaw = ev.optJSONObject("homeTeam")?.optString("name", "")?.lowercase() ?: ""
                    val awayRaw = ev.optJSONObject("awayTeam")?.optString("name", "")?.lowercase() ?: ""
                    val home = normalizeString(homeRaw)
                    val away = normalizeString(awayRaw)
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
}
