package com.tennis.tracker.data.repository

import com.tennis.tracker.data.local.H2HDataProvider
import com.tennis.tracker.data.remote.TennisStatsApi
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.H2HSummary
import com.tennis.tracker.domain.model.HistoricalMatch
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class H2HRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val statsApi: TennisStatsApi = TennisStatsApi(client)
) {
    companion object {
        private const val FLASHSCORE_FEED_BASE = "https://local-global.flashscore.ninja/2/x/feed"
        private const val FLASHSCORE_FSIGN = "SW9D1eZo"
        private const val SOFASCORE_BASE = "https://api.sofascore.com/api/v1"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    suspend fun getH2HSummary(match: Match): Result<H2HSummary> = withContext(Dispatchers.IO) {
        try {
            // 1. Primary live/historical H2H source: Flashscore H2H feed
            statsApi.findFlashscoreMatchId(match)?.let { (fsMatchId, _) ->
                val fsH2H = fetchFlashscoreH2H(fsMatchId, match)
                if (fsH2H != null) {
                    return@withContext Result.success(fsH2H)
                }
            }

            // 2. Secondary fallback: Sofascore H2H endpoint
            if (System.currentTimeMillis() >= TennisStatsApi.sofaScoreBlockedUntil) {
                val (eventId, isP1Home) = findSofascoreEventId(match)
                if (eventId != null) {
                    val h2hUrl = "$SOFASCORE_BASE/event/$eventId/h2h"
                    val request = Request.Builder()
                        .url(h2hUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", "https://www.sofascore.com/")
                        .build()

                    client.newCall(request).execute().use { response ->
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
                }
            }

            // 3. Fall back to local H2H data provider
            val fallbackSummary = H2HDataProvider.generateH2H(match)
            Result.success(fallbackSummary)
        } catch (t: Throwable) {
            try {
                Result.success(H2HDataProvider.generateH2H(match))
            } catch (_: Throwable) {
                Result.failure(t)
            }
        }
    }

    private fun fetchFlashscoreH2H(fsMatchId: String, match: Match): H2HSummary? {
        return try {
            val url = "$FLASHSCORE_FEED_BASE/df_hh_1_$fsMatchId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-fsign", FLASHSCORE_FSIGN)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                if (body.isBlank()) return null
                parseFlashscoreH2H(body, match, fsMatchId)
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun parseFlashscoreH2H(
        feedText: String,
        match: Match,
        currentFsMatchId: String? = null
    ): H2HSummary? {
        if (feedText.isBlank() || !feedText.contains("~KB÷")) return null

        // Extract the first "Head-to-head matches" section (which corresponds to "All surfaces")
        val h2hSection = feedText.split("~KB÷")
            .drop(1)
            .firstOrNull { it.startsWith("Head-to-head matches") }
            ?.substringBefore("~KA÷")
            ?: return null

        val p1Name = match.player1.name
        val p2Name = match.player2.name
        val parsedMatches = mutableListOf<HistoricalMatch>()

        if (!h2hSection.contains("~KO÷")) {
            val rawItems = h2hSection.split("~KC÷").drop(1)
            for (rawItem in rawItems) {
                val tsSeconds = rawItem.substringBefore("¬").trim().toLongOrNull() ?: continue
                val kpId = extractFlashscoreField(rawItem, "KP") ?: "h2h_$tsSeconds"
                val surfaceRaw = (extractFlashscoreField(rawItem, "KD") ?: "hard").lowercase()
                val tournamentRaw = extractFlashscoreField(rawItem, "KF") ?: "ATP/WTA Tour"
                val countryRaw = extractFlashscoreField(rawItem, "KH") ?: ""
                val kjRaw = extractFlashscoreField(rawItem, "KJ") ?: ""
                val kkRaw = extractFlashscoreField(rawItem, "KK") ?: ""
                val fhClean = extractFlashscoreField(rawItem, "FH") ?: kjRaw.removePrefix("*")
                val fkClean = extractFlashscoreField(rawItem, "FK") ?: kkRaw.removePrefix("*")
                val klScore = extractFlashscoreField(rawItem, "KL") ?: ""

                val fhIsP1 = statsApi.sideMatchesCompetitor(statsApi.normalizeText(fhClean), p1Name)
                val fkIsP1 = statsApi.sideMatchesCompetitor(statsApi.normalizeText(fkClean), p1Name)

                val kjWon = kjRaw.startsWith("*")
                val kkWon = kkRaw.startsWith("*")

                val winnerName = when {
                    kjWon -> if (fhIsP1) p1Name else p2Name
                    kkWon -> if (fkIsP1) p1Name else p2Name
                    else -> if (fhIsP1) p1Name else p2Name
                }

                val surface = when {
                    surfaceRaw.contains("clay") -> CourtSurface.CLAY
                    surfaceRaw.contains("grass") -> CourtSurface.GRASS
                    surfaceRaw.contains("carpet") -> CourtSurface.CARPET
                    else -> CourtSurface.HARD
                }

                val localDate = Instant.ofEpochSecond(tsSeconds).atZone(ZoneOffset.UTC).toLocalDate()
                val formattedSets = if (klScore.contains(":")) {
                    val parts = klScore.split(":")
                    val fhSets = parts.getOrNull(0)?.trim() ?: "0"
                    val fkSets = parts.getOrNull(1)?.trim() ?: "0"
                    val p1Sets = if (fhIsP1) fhSets else fkSets
                    val p2Sets = if (fhIsP1) fkSets else fhSets
                    "$p1Sets-$p2Sets Sets"
                } else {
                    klScore.ifBlank { "Final" }
                }

                val isThisCurrentMatch = match.state == MatchState.FINISHED &&
                    (kpId == currentFsMatchId || abs(ChronoUnit.DAYS.between(localDate, LocalDate.now())) <= 2L)

                if (match.state != MatchState.FINISHED && kpId == currentFsMatchId) {
                    continue
                }

                parsedMatches.add(
                    HistoricalMatch(
                        id = if (isThisCurrentMatch) "current_${match.id}" else "fs_$kpId",
                        date = localDate.toString(),
                        year = localDate.year,
                        tournamentName = if (isThisCurrentMatch && match.tournamentName.isNotBlank()) {
                            match.tournamentName
                        } else {
                            tournamentRaw
                        },
                        venue = if (isThisCurrentMatch && match.venue.isNotBlank()) {
                            match.venue
                        } else if (countryRaw.isNotBlank()) {
                            "$tournamentRaw, $countryRaw"
                        } else {
                            tournamentRaw
                        },
                        city = tournamentRaw,
                        round = if (isThisCurrentMatch && match.roundName.isNotBlank()) match.roundName else "Tour Match",
                        surface = surface,
                        player1Name = p1Name,
                        player2Name = p2Name,
                        winnerName = if (isThisCurrentMatch) {
                            if (match.player1.isWinner) p1Name else p2Name
                        } else {
                            winnerName
                        },
                        score = if (isThisCurrentMatch) formatMatchSetScore(match).ifEmpty { formattedSets } else formattedSets,
                        matchDuration = if (isThisCurrentMatch) match.getFormattedDuration() else null,
                        isCurrentMatch = isThisCurrentMatch
                    )
                )
            }
        }

        // If the current match is finished and wasn't already present in parsedMatches, prepend it
        if (match.state == MatchState.FINISHED && parsedMatches.none { it.isCurrentMatch }) {
            val currentYear = Year.now().value
            val currentDate = LocalDate.now().toString()
            val winnerName = if (match.player1.isWinner) p1Name else p2Name
            parsedMatches.add(
                0,
                HistoricalMatch(
                    id = "current_${match.id}",
                    date = currentDate,
                    year = currentYear,
                    tournamentName = match.tournamentName.ifBlank { "Tennis Tournament $currentYear" },
                    venue = match.venue.ifBlank { "Center Court" },
                    city = match.venue.substringBefore(",").ifBlank { "International" },
                    round = match.roundName.ifBlank { "Final" },
                    surface = CourtSurface.HARD,
                    player1Name = p1Name,
                    player2Name = p2Name,
                    winnerName = winnerName,
                    score = formatMatchSetScore(match).ifEmpty { "Final" },
                    matchDuration = match.getFormattedDuration(),
                    isCurrentMatch = true
                )
            )
        }

        val sortedMatches = parsedMatches.sortedWith(
            compareByDescending<HistoricalMatch> { it.isCurrentMatch }
                .thenByDescending { it.date }
        )

        var p1Wins = 0
        var p2Wins = 0
        var hardP1 = 0
        var hardP2 = 0
        var clayP1 = 0
        var clayP2 = 0
        var grassP1 = 0
        var grassP2 = 0
        var gsP1 = 0
        var gsP2 = 0

        for (m in sortedMatches) {
            val isP1 = m.isWinner(p1Name)
            if (isP1) {
                p1Wins++
                when (m.surface) {
                    CourtSurface.HARD -> hardP1++
                    CourtSurface.CLAY -> clayP1++
                    CourtSurface.GRASS -> grassP1++
                    CourtSurface.CARPET -> hardP1++
                }
                if (isGrandSlam(m.tournamentName)) gsP1++
            } else {
                p2Wins++
                when (m.surface) {
                    CourtSurface.HARD -> hardP2++
                    CourtSurface.CLAY -> clayP2++
                    CourtSurface.GRASS -> grassP2++
                    CourtSurface.CARPET -> hardP2++
                }
                if (isGrandSlam(m.tournamentName)) gsP2++
            }
        }

        return H2HSummary(
            player1 = match.player1,
            player2 = match.player2,
            p1Wins = p1Wins,
            p2Wins = p2Wins,
            hardWinsP1 = hardP1,
            hardWinsP2 = hardP2,
            clayWinsP1 = clayP1,
            clayWinsP2 = clayP2,
            grassWinsP1 = grassP1,
            grassWinsP2 = grassP2,
            grandSlamWinsP1 = gsP1,
            grandSlamWinsP2 = gsP2,
            matches = sortedMatches
        )
    }

    private fun formatMatchSetScore(match: Match): String {
        return buildString {
            for (i in 0 until maxOf(match.player1.setScores.size, match.player2.setScores.size)) {
                val s1 = match.player1.setScores.getOrNull(i) ?: 0
                val s2 = match.player2.setScores.getOrNull(i) ?: 0
                if (i > 0) append(", ")
                append("$s1-$s2")
                val tb = match.player1.tiebreakScores.getOrNull(i) ?: match.player2.tiebreakScores.getOrNull(i)
                if (tb != null) {
                    append("($tb)")
                }
            }
        }
    }

    private fun isGrandSlam(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("us open") || n.contains("wimbledon") ||
            n.contains("roland garros") || n.contains("french open") ||
            n.contains("australian open")
    }

    private fun extractFlashscoreField(block: String, key: String): String? {
        val prefix = "$key÷"
        var searchFrom = 0
        while (searchFrom < block.length) {
            val idx = block.indexOf(prefix, searchFrom)
            if (idx == -1) return null
            if (idx == 0 || block[idx - 1] == '¬' || block[idx - 1] == '~') {
                val valueStart = idx + prefix.length
                val valueEnd = block.indexOf('¬', valueStart).let { if (it == -1) block.length else it }
                return block.substring(valueStart, valueEnd)
            }
            searchFrom = idx + 1
        }
        return null
    }

    private fun findSofascoreEventId(match: Match): Pair<String?, Boolean> {
        val p1Last = extractLastName(match.player1.name)
        val p2Last = extractLastName(match.player2.name)
        if (p1Last.isEmpty() && p2Last.isEmpty()) return Pair(null, true)

        searchSofascoreEventsUrl("$SOFASCORE_BASE/sport/tennis/events/live", p1Last, p2Last)?.let { return it }
        return Pair(null, true)
    }

    private fun extractLastName(fullName: String): String {
        val tokens = fullName.substringBefore("/").trim().lowercase().split("\\s+".toRegex())
            .filter { it.isNotEmpty() && it != "jr." && it != "jr" && it != "sr." && it != "sr" && it != "ii" && it != "iii" }
        val last = tokens.lastOrNull() ?: ""
        return normalizeString(last).filter { it.isLetter() }
    }

    private fun normalizeString(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    private fun searchSofascoreEventsUrl(url: String, p1Last: String, p2Last: String): Pair<String, Boolean>? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            client.newCall(request).execute().use { response ->
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
            }
        } catch (_: Throwable) {}
        return null
    }
}
