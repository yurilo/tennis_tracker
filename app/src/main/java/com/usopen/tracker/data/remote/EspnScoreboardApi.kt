package com.usopen.tracker.data.remote

import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.PlayerPhotoProvider
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.Tournaments
import com.usopen.tracker.domain.usecase.DeepLinkGenerator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

open class EspnScoreboardApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val ATP_SCOREBOARD_URL = "https://site.web.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard"
        const val WTA_SCOREBOARD_URL = "https://site.web.api.espn.com/apis/site/v2/sports/tennis/wta/scoreboard"
    }

    open fun fetchScoreboard(url: String): Result<List<Match>> {
        return fetchScoreboard(url, Tournaments.ALL)
    }

    open fun fetchScoreboard(
        url: String,
        tournament: Tournament?
    ): Result<List<Match>> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .header("Referer", "https://www.espn.com/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP error: ${response.code}"))
            }

            val jsonBody = response.body?.string() ?: return Result.failure(Exception("Empty body"))
            val matches = parseScoreboardJson(jsonBody, tournament)
            Result.success(matches)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun parseScoreboardJson(
        jsonString: String,
        tournament: Tournament? = Tournaments.ALL
    ): List<Match> {
        val root = JSONObject(jsonString)
        val events = root.optJSONArray("events") ?: return emptyList()
        val matchList = mutableListOf<Match>()

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val tournamentName = event.optString("name", "Tennis Tournament")

            if (tournament != null && !tournament.matches(tournamentName)) {
                continue
            }

            val groupings = event.optJSONArray("groupings")

            if (groupings != null && groupings.length() > 0) {
                for (g in 0 until groupings.length()) {
                    val groupObj = groupings.optJSONObject(g) ?: continue
                    val groupingInfo = groupObj.optJSONObject("grouping")
                    val groupDisplayName = groupingInfo?.optString("displayName") ?: "Singles"
                    val competitions = groupObj.optJSONArray("competitions") ?: continue
                    parseCompetitions(competitions, tournamentName, groupDisplayName, matchList, tournament)
                }
            } else {
                // Fallback for direct competitions array
                val competitions = event.optJSONArray("competitions") ?: continue
                parseCompetitions(competitions, tournamentName, "Main Draw", matchList, tournament)
            }
        }
        return matchList
    }

    private fun parseCompetitions(
        competitions: JSONArray,
        tournamentName: String,
        groupingName: String,
        output: MutableList<Match>,
        tournament: Tournament? = null
    ) {
        for (j in 0 until competitions.length()) {
            val comp = competitions.optJSONObject(j) ?: continue
            val compId = comp.optString("id", "")
            if (compId.isEmpty()) continue

            val statusObj = comp.optJSONObject("status")
            val typeObj = statusObj?.optJSONObject("type")
            val stateStr = typeObj?.optString("state", "pre") ?: "pre"
            val isCompleted = typeObj?.optBoolean("completed", false) ?: false
            val statusDesc = typeObj?.optString("description", "Scheduled") ?: "Scheduled"
            val statusDetailStr = typeObj?.optString("detail", "") ?: ""

            val roundObj = comp.optJSONObject("round")
            val roundDisplayName = roundObj?.optString("displayName", "") ?: ""

            val matchState = when {
                stateStr == "in" -> MatchState.LIVE
                stateStr == "post" || isCompleted -> MatchState.FINISHED
                else -> MatchState.UPCOMING
            }

            val roundName = if (roundDisplayName.isNotEmpty()) roundDisplayName else statusDesc

            val competitorsJson = comp.optJSONArray("competitors")
            val competitors = parseCompetitors(competitorsJson, matchState, comp)

            if (competitors.size >= 2) {
                val p1 = competitors[0]
                val p2 = competitors[1]
                if (p1.isTbdOrPlaceholder || p2.isTbdOrPlaceholder) {
                    continue
                }

                val venueObj = comp.optJSONObject("venue")
                val rawCourt = venueObj?.optString("court", "")?.trim() ?: ""
                val rawFullName = venueObj?.optString("fullName", "")?.trim() ?: ""
                val courtName = rawCourt.ifEmpty {
                    if (rawFullName.isNotEmpty() && !rawFullName.equals("New York, USA", ignoreCase = true)) {
                        rawFullName
                    } else {
                        null
                    }
                }
                val defaultVenue = tournament?.defaultVenue ?: "Center Court"
                val venueName = courtName
                    ?: if (rawFullName.isNotEmpty() && !rawFullName.contains("USA", ignoreCase = true)) {
                        rawFullName
                    } else {
                        defaultVenue
                    }
                val startDate = comp.optString("startDate", "").ifEmpty { comp.optString("date", "") }
                val endDate = comp.optString("endDate", "")

                val durationFromClock = parseDurationMinutes(statusObj)
                val durationMinutes = when {
                    durationFromClock != null -> durationFromClock
                    startDate.isNotEmpty() && endDate.isNotEmpty() -> {
                        try {
                            val start = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(startDate) ?: java.time.Instant.parse(startDate)
                            val end = com.usopen.tracker.util.TimezoneFormatter.parseToInstant(endDate) ?: java.time.Instant.parse(endDate)
                            java.time.Duration.between(start, end).toMinutes().toInt().takeIf { it > 0 }
                        } catch (_: Throwable) { null }
                    }
                    else -> null
                }

                val isMatchInTiebreak = matchState == MatchState.LIVE && competitors.any {
                    it.tiebreakScores.lastOrNull() != null
                }

                val statusDetail = when {
                    isMatchInTiebreak && !statusDetailStr.contains("Tiebreak", ignoreCase = true) ->
                        if (statusDetailStr.isNotEmpty()) "$statusDesc • $statusDetailStr (Tiebreak)" else "$statusDesc • Tiebreak"
                    matchState == MatchState.LIVE && statusDetailStr.isNotEmpty() && statusDetailStr != statusDesc ->
                        "$statusDesc • $statusDetailStr"
                    matchState == MatchState.FINISHED && roundDisplayName.isNotEmpty() ->
                        "Final • $roundDisplayName"
                    matchState == MatchState.UPCOMING && statusDetailStr.isNotEmpty() && statusDetailStr != statusDesc ->
                        statusDetailStr
                    else -> statusDesc
                }

                val match = Match(
                    id = compId,
                    tournamentName = tournamentName,
                    eventGrouping = groupingName,
                    roundName = roundName,
                    state = matchState,
                    statusDetail = statusDetail,
                    player1 = p1,
                    player2 = p2,
                    venue = venueName,
                    court = courtName,
                    startTime = if (startDate.isNotEmpty()) startDate else null,
                    endTime = if (endDate.isNotEmpty()) endDate else null,
                    durationMinutes = durationMinutes,
                    espnEventId = compId
                )
                output.add(match.copy(deepLink = DeepLinkGenerator.generate(match)))
            }
        }
    }

    private fun parseDurationMinutes(statusObj: JSONObject?): Int? {
        val clock = statusObj?.optInt("clock", 0) ?: 0
        if (clock > 0) return clock / 60

        val displayClock = statusObj?.optString("displayClock", "") ?: ""
        if (displayClock.isNotEmpty() && displayClock.contains(":")) {
            val parts = displayClock.split(":")
            if (parts.size == 3) {
                val hours = parts[0].toIntOrNull() ?: 0
                val mins = parts[1].toIntOrNull() ?: 0
                val secs = parts[2].toIntOrNull() ?: 0
                return hours * 60 + mins + (if (secs >= 30) 1 else 0)
            } else if (parts.size == 2) {
                val mins = parts[0].toIntOrNull() ?: 0
                val secs = parts[1].toIntOrNull() ?: 0
                return mins + (if (secs >= 30) 1 else 0)
            }
        }
        return null
    }

    private fun parseCompetitors(
        array: JSONArray?,
        matchState: MatchState,
        comp: JSONObject? = null
    ): List<Competitor> {
        if (array == null) return emptyList()
        val list = mutableListOf<Competitor>()

        val situationObj = comp?.optJSONObject("situation")
        val situationServerId = situationObj?.optString("possession", "")
            ?.ifEmpty { situationObj.optString("service", "") }
            ?.ifEmpty { situationObj.optJSONObject("athlete")?.optString("id", "") }
            ?: ""

        for (k in 0 until array.length()) {
            val compObj = array.optJSONObject(k) ?: continue
            val id = compObj.optString("id", "$k")
            val athlete = compObj.optJSONObject("athlete")
            val roster = compObj.optJSONObject("roster")

            val displayName = athlete?.optString("displayName")
                ?: athlete?.optString("name")
                ?: roster?.optString("displayName")
                ?: roster?.optString("shortDisplayName")
                ?: compObj.optString("name", "Competitor ${k + 1}")

            val flagObj = athlete?.optJSONObject("flag") ?: compObj.optJSONObject("flag")
            val country = flagObj?.optString("alt")

            val curatedRank = compObj.optJSONObject("curatedRank")
            val seed = curatedRank?.optInt("current", 0)?.takeIf { it > 0 }
            val isWinner = compObj.optBoolean("winner", false)
            val rawServing = compObj.optBoolean("possession", false) ||
                compObj.optBoolean("service", false) ||
                compObj.optBoolean("isServing", false) ||
                (situationServerId.isNotEmpty() && situationServerId == id)

            val linescores = compObj.optJSONArray("linescores")
            val setScores = mutableListOf<Int>()
            val tiebreakScores = mutableListOf<Int?>()
            if (linescores != null) {
                for (s in 0 until linescores.length()) {
                    val ls = linescores.optJSONObject(s)
                    val value = ls?.optDouble("value", 0.0)?.toInt() ?: 0
                    val tiebreak = if (ls != null && ls.has("tiebreak")) {
                        ls.optInt("tiebreak")
                    } else null
                    setScores.add(value)
                    tiebreakScores.add(tiebreak)
                }
            }

            val lastSetTiebreak = tiebreakScores.lastOrNull()
            val rawCandidate = when {
                compObj.has("points") -> compObj.optString("points")
                compObj.has("currentScore") -> compObj.optString("currentScore")
                compObj.has("gameScore") -> compObj.optString("gameScore")
                compObj.has("score") -> compObj.optString("score")
                else -> null
            }?.trim()

            val isValidTennisPoint = rawCandidate != null && (
                rawCandidate in listOf("0", "15", "30", "40", "A", "Adv", "ADV", "Ad", "AD", "Deuce", "DEUCE") ||
                rawCandidate.startsWith("TB", ignoreCase = true) ||
                (rawCandidate.all { it.isDigit() } && (rawCandidate.toIntOrNull() ?: -1) in 0..99)
            )

            val gameScore = when {
                isValidTennisPoint -> {
                    when {
                        rawCandidate!!.equals("A", ignoreCase = true) || rawCandidate.equals("Ad", ignoreCase = true) -> "Adv"
                        rawCandidate.equals("Deuce", ignoreCase = true) -> "40"
                        else -> rawCandidate
                    }
                }
                matchState == MatchState.LIVE && lastSetTiebreak != null -> "TB $lastSetTiebreak"
                matchState == MatchState.LIVE && setScores.isNotEmpty() && setScores.last() >= 6 -> {
                    // Check if tiebreak
                    lastSetTiebreak?.let { "TB $it" } ?: if (rawServing) "40" else "30"
                }
                matchState == MatchState.LIVE -> if (rawServing) "40" else "30"
                else -> null
            }

            val athleteHeadshot = athlete?.optJSONObject("headshot")?.optString("href")
                ?: athlete?.optString("headshot")?.takeIf { it.startsWith("http") }
            val resolvedPhoto = PlayerPhotoProvider.getPhotoUrl(
                name = displayName,
                athleteId = id,
                existingUrl = athleteHeadshot
            )

            val individualPlayers = mutableListOf<Competitor>()
            val athletesArray = roster?.optJSONArray("athletes")
            if (athletesArray != null && athletesArray.length() >= 2) {
                for (a in 0 until athletesArray.length()) {
                    val athObj = athletesArray.optJSONObject(a) ?: continue
                    val athName = athObj.optString("displayName")
                        .ifEmpty { athObj.optString("fullName") }
                        .ifEmpty { athObj.optString("name") }
                    val athFlagObj = athObj.optJSONObject("flag")
                    val athCountry = athFlagObj?.optString("alt")
                    var athId = athObj.optString("id", "")
                    if (athId.isEmpty()) {
                        val links = athObj.optJSONArray("links")
                        if (links != null) {
                            for (l in 0 until links.length()) {
                                val href = links.optJSONObject(l)?.optString("href", "") ?: ""
                                val m = Regex("/id/(\\d+)").find(href)
                                if (m != null) {
                                    athId = m.groupValues[1]
                                    break
                                }
                            }
                        }
                    }
                    val athHeadshot = athObj.optJSONObject("headshot")?.optString("href")
                        ?: athObj.optString("headshot").takeIf { it.startsWith("http") }
                    val athPhoto = PlayerPhotoProvider.getPhotoUrl(
                        name = athName,
                        athleteId = athId.ifEmpty { null },
                        existingUrl = athHeadshot
                    )
                    individualPlayers.add(
                        Competitor(
                            id = athId.ifEmpty { "${id}_$a" },
                            name = athName,
                            seed = seed,
                            country = athCountry,
                            photoUrl = athPhoto
                        )
                    )
                }
            } else if (displayName.contains(" / ") || (displayName.contains("/") && !displayName.contains("http"))) {
                val parts = displayName.split(Regex("\\s*/\\s*")).filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    val countries = country?.split(Regex("\\s*/\\s*")) ?: emptyList()
                    parts.forEachIndexed { idx, pName ->
                        val trimmed = pName.trim()
                        val partnerCountry = countries.getOrNull(idx)?.trim() ?: country
                        individualPlayers.add(
                            Competitor(
                                id = "${id}_$idx",
                                name = trimmed,
                                seed = seed,
                                country = partnerCountry,
                                photoUrl = PlayerPhotoProvider.getPhotoUrl(name = trimmed)
                            )
                        )
                    }
                }
            }

            val finalCountry = country ?: if (individualPlayers.isNotEmpty()) {
                individualPlayers.mapNotNull { it.country }.distinct().joinToString("/")
            } else null

            list.add(
                Competitor(
                    id = id,
                    name = displayName,
                    seed = seed,
                    country = finalCountry,
                    isWinner = isWinner,
                    setScores = setScores,
                    tiebreakScores = tiebreakScores,
                    currentGameScore = gameScore,
                    isServing = rawServing,
                    photoUrl = resolvedPhoto,
                    players = individualPlayers
                )
            )
        }

        // Tennis Rules Enforcement for Serving:
        if (matchState != MatchState.LIVE) {
            // Rule 1: Non-live matches (UPCOMING and FINISHED) never have an active server.
            return list.map { it.copy(isServing = false) }
        }

        if (list.size >= 2) {
            val p1 = list[0]
            val p2 = list[1]
            val p1Serving = p1.isServing
            val p2Serving = p2.isServing

            val (finalP1Serving, finalP2Serving) = when {
                p1Serving && !p2Serving -> true to false
                !p1Serving && p2Serving -> false to true
                p1Serving && p2Serving -> true to false // Disambiguate if both flagged
                else -> {
                    // If neither flagged, check if in an active tiebreak with known points
                    val lastIdx1 = p1.setScores.size - 1
                    val tb1 = if (lastIdx1 >= 0) p1.tiebreakScores.getOrNull(lastIdx1) else null
                    val lastIdx2 = p2.setScores.size - 1
                    val tb2 = if (lastIdx2 >= 0) p2.tiebreakScores.getOrNull(lastIdx2) else null

                    if (tb1 != null || tb2 != null) {
                        // In Tiebreak: Server changes after point 1, then every 2 points.
                        // Completed points = tb1 + tb2.
                        val completedPoints = (tb1 ?: 0) + (tb2 ?: 0)
                        val pointMod4 = completedPoints % 4
                        if (pointMod4 == 1 || pointMod4 == 2) false to true else true to false
                    } else {
                        // Standard tennis service rotation fallback:
                        // Total games played in current set alternates server (Game 0: P1, Game 1: P2, etc.)
                        val gamesInSet = (p1.setScores.lastOrNull() ?: 0) + (p2.setScores.lastOrNull() ?: 0)
                        if (gamesInSet % 2 == 0) true to false else false to true
                    }
                }
            }

            return listOf(
                p1.copy(isServing = finalP1Serving),
                p2.copy(isServing = finalP2Serving)
            ) + list.drop(2)
        }

        return list
    }
}
