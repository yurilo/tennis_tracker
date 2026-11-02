package com.tennis.tracker.data.remote

import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.PeriodStats
import com.tennis.tracker.domain.model.StatGroup
import com.tennis.tracker.domain.model.StatItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class TennisStatsApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val FLASHSCORE_FEED_BASE = "https://local-global.flashscore.ninja/2/x/feed"
        private const val FLASHSCORE_SEARCH_BASE = "https://s.livesport.services/api/v2/search"
        private const val FLASHSCORE_FSIGN = "SW9D1eZo"
        private const val SOFASCORE_BASE = "https://api.sofascore.com/api/v1"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private val TOKEN_DELIMITER_REGEX = Regex("[\\s/.-]+")
        private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9\\s]")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PERCENT_REGEX = Regex("(\\d+(?:\\.\\d+)?)\\s*%")
        private val FRACTION_REGEX = Regex("(\\d+)\\s*/\\s*(\\d+)")
        private val NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)")
        private val IGNORED_NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv")

        @Volatile
        var sofaScoreBlockedUntil: Long = 0L
        private const val BLOCKED_COOLDOWN_MS = 15 * 60 * 1000L // 15 minutes

        // Cache resolved Flashscore match IDs: match.id -> Pair(flashscoreMatchId, isP1Home)
        private val matchIdCache = ConcurrentHashMap<String, Pair<String, Boolean>>()
    }

    private val liveScoreClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    open fun fetchStatistics(match: Match): Result<MatchStatistics> {
        if (match.state == MatchState.UPCOMING) {
            return Result.failure(NoSuchElementException("Match statistics will become available once the match begins."))
        }
        return try {
            // 1. Primary live/historical court telemetry provider: Flashscore feed
            findFlashscoreMatchId(match)?.let { (fsMatchId, isP1Home) ->
                val fsStats = fetchFlashscoreStatistics(fsMatchId, match, isP1Home)
                if (fsStats != null && fsStats.periods.isNotEmpty()) {
                    return Result.success(fsStats)
                }
            }

            // 2. Secondary fallback provider: Sofascore API (if not blocked by 403 challenge)
            if (System.currentTimeMillis() >= sofaScoreBlockedUntil) {
                val (eventId, isP1Home) = findSofascoreEventId(match)
                if (eventId != null) {
                    val stats = fetchSofascoreEventStatistics(eventId, match, isP1Home)
                    if (stats != null && stats.periods.isNotEmpty()) {
                        return Result.success(stats)
                    }
                }
            }

            Result.failure(NoSuchElementException("Match statistics are not available for this match yet."))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    internal fun findFlashscoreMatchId(match: Match): Pair<String, Boolean>? {
        matchIdCache[match.id]?.let { return it }

        val p1Name = match.player1.name
        val p2Name = match.player2.name
        if (p1Name.isBlank() || p2Name.isBlank()) return null

        // 1. If LIVE, check today's live/daily feed first for fastest resolution
        if (match.state == MatchState.LIVE) {
            searchFlashscoreFeedUrl("$FLASHSCORE_FEED_BASE/f_2_0_2_en_1", p1Name, p2Name)?.let {
                matchIdCache[match.id] = it
                return it
            }
        }

        // 2. Search player profile feeds via Flashscore Search API
        val candidateQueries = buildPlayerSearchQueries(p1Name, p2Name)
        for (query in candidateQueries) {
            val players = searchFlashscorePlayers(query)
            for ((playerId, countryId) in players) {
                val feedsToTry = if (match.state == MatchState.UPCOMING) {
                    listOf(
                        "$FLASHSCORE_FEED_BASE/pf_2_${countryId}_${playerId}_0_2_en_1",
                        "$FLASHSCORE_FEED_BASE/pr_2_${countryId}_${playerId}_0_2_en_1"
                    )
                } else {
                    listOf(
                        "$FLASHSCORE_FEED_BASE/pr_2_${countryId}_${playerId}_0_2_en_1",
                        "$FLASHSCORE_FEED_BASE/pf_2_${countryId}_${playerId}_0_2_en_1"
                    )
                }
                for (feedUrl in feedsToTry) {
                    searchFlashscoreFeedUrl(feedUrl, p1Name, p2Name)?.let { found ->
                        matchIdCache[match.id] = found
                        return found
                    }
                }
            }
        }

        // 3. Fallback: check today's and yesterday's global tennis feeds
        val dayOffsets = if (match.state == MatchState.UPCOMING) listOf(0, 1) else listOf(0, -1)
        for (offset in dayOffsets) {
            searchFlashscoreFeedUrl("$FLASHSCORE_FEED_BASE/f_2_${offset}_2_en_1", p1Name, p2Name)?.let {
                matchIdCache[match.id] = it
                return it
            }
        }

        return null
    }

    private fun buildPlayerSearchQueries(p1Name: String, p2Name: String): List<String> {
        val queries = mutableListOf<String>()
        for (competitorName in listOf(p1Name, p2Name)) {
            val partners = competitorName.split("/").map { it.trim() }.filter { it.isNotEmpty() }
            // Prefer partner with longer surname first to avoid common surnames in doubles
            val sortedPartners = partners.sortedByDescending { partner ->
                partnerTokens(partner).firstOrNull()?.length ?: 0
            }
            for (partner in sortedPartners) {
                val tokens = partnerTokens(partner)
                tokens.firstOrNull()?.let { surname ->
                    if (surname.length >= 2 && surname !in queries) {
                        queries.add(surname)
                    }
                }
            }
        }
        return queries.take(3)
    }

    private fun searchFlashscorePlayers(query: String): List<Pair<String, Int>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$FLASHSCORE_SEARCH_BASE?q=$encoded&lang-id=1&type-ids=1,2,3,4&sport-ids=2"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val arr = JSONArray(body)
                val results = mutableListOf<Pair<String, Int>>()
                for (i in 0 until arr.length().coerceAtMost(5)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val countryId = obj.optJSONObject("defaultCountry")?.optInt("id", 0) ?: 0
                    if (id.isNotEmpty() && countryId > 0) {
                        results.add(id to countryId)
                    }
                }
                results
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun searchFlashscoreFeedUrl(url: String, p1Name: String, p2Name: String): Pair<String, Boolean>? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("x-fsign", FLASHSCORE_FSIGN)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                findMatchInFlashscoreFeed(body, p1Name, p2Name)
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun findMatchInFlashscoreFeed(feedText: String, p1Name: String, p2Name: String): Pair<String, Boolean>? {
        val blocks = feedText.split("~AA÷")
        if (blocks.size <= 1) return null

        for (i in 1 until blocks.size) {
            val block = blocks[i]
            val matchId = block.substringBefore("¬").trim()
            if (matchId.isEmpty()) continue

            val homeRaw = extractFlashscoreField(block, "AE") ?: continue
            val awayRaw = extractFlashscoreField(block, "AF") ?: continue

            val homeNorm = normalizeText(homeRaw)
            val awayNorm = normalizeText(awayRaw)

            if (sideMatchesCompetitor(homeNorm, p1Name) && sideMatchesCompetitor(awayNorm, p2Name)) {
                return matchId to true
            }
            if (sideMatchesCompetitor(homeNorm, p2Name) && sideMatchesCompetitor(awayNorm, p1Name)) {
                return matchId to false
            }
        }
        return null
    }

    private fun fetchFlashscoreStatistics(
        fsMatchId: String,
        match: Match,
        isP1Home: Boolean
    ): MatchStatistics? {
        val url = "$FLASHSCORE_FEED_BASE/df_st_1_$fsMatchId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-fsign", FLASHSCORE_FSIGN)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (body.isBlank()) return null
            return parseFlashscoreStatistics(body, match, isP1Home)
        }
    }

    fun parseFlashscoreStatistics(
        feedText: String,
        match: Match,
        isP1Home: Boolean = true
    ): MatchStatistics? {
        if (feedText.isBlank() || !feedText.contains("SE÷")) return null

        val rawPeriods = feedText.split("SE÷").drop(1)
        if (rawPeriods.isEmpty()) return null

        val periods = mutableListOf<PeriodStats>()

        for (rawPeriod in rawPeriods) {
            val periodTitle = rawPeriod.substringBefore("¬").trim()
            if (periodTitle.isEmpty()) continue

            val (periodCode, displayLabel) = when {
                periodTitle.equals("Match", ignoreCase = true) -> "ALL" to "Match"
                periodTitle.equals("Set 1", ignoreCase = true) -> "1ST" to "Set 1"
                periodTitle.equals("Set 2", ignoreCase = true) -> "2ND" to "Set 2"
                periodTitle.equals("Set 3", ignoreCase = true) -> "3RD" to "Set 3"
                periodTitle.equals("Set 4", ignoreCase = true) -> "4TH" to "Set 4"
                periodTitle.equals("Set 5", ignoreCase = true) -> "5TH" to "Set 5"
                else -> periodTitle.uppercase() to periodTitle
            }

            val rawGroups = rawPeriod.split("~SF÷").drop(1)
            val groupsList = mutableListOf<StatGroup>()

            for (rawGroup in rawGroups) {
                val groupName = rawGroup.substringBefore("¬").trim()
                if (groupName.isEmpty()) continue

                val rawItems = rawGroup.split("~SG÷").drop(1)
                val itemsList = mutableListOf<StatItem>()

                for (rawItem in rawItems) {
                    val itemName = rawItem.substringBefore("¬").trim()
                    val rawHome = extractFlashscoreField(rawItem, "SH") ?: continue
                    val rawAway = extractFlashscoreField(rawItem, "SI") ?: continue

                    val p1Display = if (isP1Home) rawHome else rawAway
                    val p2Display = if (isP1Home) rawAway else rawHome
                    val ratio = computeStatRatio(p1Display, p2Display)

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
                periods.add(PeriodStats(period = periodCode, label = displayLabel, groups = groupsList))
            }
        }

        return if (periods.isNotEmpty()) {
            MatchStatistics(
                matchId = match.id,
                player1Name = match.player1.name,
                player2Name = match.player2.name,
                periods = periods
            )
        } else {
            null
        }
    }

    internal fun computeStatRatio(p1Display: String, p2Display: String): Float {
        val p1Val = extractComparableMetricValue(p1Display)
        val p2Val = extractComparableMetricValue(p2Display)
        return if (p1Val + p2Val > 0.0) {
            (p1Val / (p1Val + p2Val)).toFloat().coerceIn(0.05f, 0.95f)
        } else {
            0.5f
        }
    }

    private fun extractComparableMetricValue(display: String): Double {
        val trimmed = display.trim()
        // 1. Prefer percentage when present (e.g. "80% (49/61)" or "48/68 (71%)" or "70%")
        PERCENT_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
            return it
        }
        // 2. Next check fraction (e.g. "4/6" -> 4.0 / 6.0 = 66.7, or "0/0" -> 0.0)
        FRACTION_REGEX.find(trimmed)?.let { matchResult ->
            val num = matchResult.groupValues[1].toDoubleOrNull() ?: 0.0
            val den = matchResult.groupValues[2].toDoubleOrNull() ?: 0.0
            return if (den > 0.0) (num / den) * 100.0 else 0.0
        }
        // 3. Otherwise extract leading numeric value (e.g. "8")
        return NUMBER_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun findSofascoreEventId(match: Match): Pair<String?, Boolean> {
        val p1Last = partnerTokens(match.player1.name.substringBefore("/")).firstOrNull() ?: ""
        val p2Last = partnerTokens(match.player2.name.substringBefore("/")).firstOrNull() ?: ""
        if (p1Last.isEmpty() && p2Last.isEmpty()) return Pair(null, true)

        try {
            searchSofascoreEventsUrl("$SOFASCORE_BASE/sport/tennis/events/live", match.player1.name, match.player2.name)?.let {
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

            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    sofaScoreBlockedUntil = System.currentTimeMillis() + BLOCKED_COOLDOWN_MS
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
                                    searchSofascoreEventsUrl(
                                        "$SOFASCORE_BASE/team/$teamId/events/last/0",
                                        match.player1.name,
                                        match.player2.name
                                    )?.let {
                                        return it
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return Pair(null, true)
    }

    private fun searchSofascoreEventsUrl(url: String, p1Name: String, p2Name: String): Pair<String, Boolean>? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) {
                    sofaScoreBlockedUntil = System.currentTimeMillis() + BLOCKED_COOLDOWN_MS
                    throw java.io.IOException("Remote stats provider returned HTTP ${response.code}")
                }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    val root = JSONObject(body)
                    val events = root.optJSONArray("events") ?: return null

                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        val home = normalizeText(ev.optJSONObject("homeTeam")?.optString("name", "") ?: "")
                        val away = normalizeText(ev.optJSONObject("awayTeam")?.optString("name", "") ?: "")
                        val eventId = ev.optString("id", "")
                        if (eventId.isEmpty()) continue

                        if (sideMatchesCompetitor(home, p1Name) && sideMatchesCompetitor(away, p2Name)) {
                            return Pair(eventId, true)
                        } else if (sideMatchesCompetitor(home, p2Name) && sideMatchesCompetitor(away, p1Name)) {
                            return Pair(eventId, false)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun fetchSofascoreEventStatistics(eventId: String, match: Match, isP1Home: Boolean): MatchStatistics? {
        val statsUrl = "$SOFASCORE_BASE/event/$eventId/statistics"
        val request = Request.Builder()
            .url(statsUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.sofascore.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseStatisticsJson(body, match, isP1Home)
        }
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
                        computeStatRatio(p1Display, p2Display)
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

        val now = System.currentTimeMillis()
        if (now < sofaScoreBlockedUntil) {
            return emptyMap()
        }

        // 1. Try Flashscore live feed first
        val flashscoreLiveScores = fetchFlashscoreLiveGameScores(liveMatches)
        if (flashscoreLiveScores.isNotEmpty()) {
            return flashscoreLiveScores
        }

        // 2. Fallback to Sofascore live events
        return try {
            val request = Request.Builder()
                .url("$SOFASCORE_BASE/sport/tennis/events/live")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.sofascore.com/")
                .build()

            liveScoreClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 403 || response.code == 429) {
                        sofaScoreBlockedUntil = System.currentTimeMillis() + BLOCKED_COOLDOWN_MS
                    }
                    return emptyMap()
                }
                val body = response.body?.string() ?: return emptyMap()
                val root = JSONObject(body)
                val events = root.optJSONArray("events") ?: return emptyMap()

                val scoreMap = mutableMapOf<String, Pair<String, String>>()

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

                    val eventTokens = (homeTeam.split(TOKEN_DELIMITER_REGEX) + awayTeam.split(TOKEN_DELIMITER_REGEX))
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }

                    val candidates = mutableSetOf<MatchTokens>()
                    for (token in eventTokens) {
                        tokenToMatches[token]?.let { candidates.addAll(it) }
                    }

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
            }
        } catch (_: Throwable) {
            sofaScoreBlockedUntil = System.currentTimeMillis() + (5 * 60 * 1000L)
            emptyMap()
        }
    }

    private fun fetchFlashscoreLiveGameScores(liveMatches: List<Match>): Map<String, Pair<String, String>> {
        return try {
            val request = Request.Builder()
                .url("$FLASHSCORE_FEED_BASE/f_2_0_2_en_1")
                .header("User-Agent", USER_AGENT)
                .header("x-fsign", FLASHSCORE_FSIGN)
                .build()
            liveScoreClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyMap()
                val body = response.body?.string() ?: return emptyMap()
                val blocks = body.split("~AA÷")
                if (blocks.size <= 1) return emptyMap()

                val scoreMap = mutableMapOf<String, Pair<String, String>>()
                for (i in 1 until blocks.size) {
                    val block = blocks[i]
                    val status = extractFlashscoreField(block, "AB")
                    if (status != "2") continue // Only live matches

                    val homeRaw = extractFlashscoreField(block, "AE") ?: continue
                    val awayRaw = extractFlashscoreField(block, "AF") ?: continue
                    val homePoint = extractFlashscoreField(block, "WA") ?: ""
                    val awayPoint = extractFlashscoreField(block, "WB") ?: ""
                    if (homePoint.isEmpty() && awayPoint.isEmpty()) continue

                    val homeNorm = normalizeText(homeRaw)
                    val awayNorm = normalizeText(awayRaw)

                    for (match in liveMatches) {
                        if (scoreMap.containsKey(match.id)) continue
                        if (sideMatchesCompetitor(homeNorm, match.player1.name) &&
                            sideMatchesCompetitor(awayNorm, match.player2.name)
                        ) {
                            scoreMap[match.id] = normalizePoint(homePoint) to normalizePoint(awayPoint)
                        } else if (sideMatchesCompetitor(homeNorm, match.player2.name) &&
                            sideMatchesCompetitor(awayNorm, match.player1.name)
                        ) {
                            scoreMap[match.id] = normalizePoint(awayPoint) to normalizePoint(homePoint)
                        }
                    }
                }
                scoreMap
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    internal fun sideMatchesCompetitor(sideNorm: String, competitorName: String): Boolean {
        val sideWords = sideNorm.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val partners = competitorName.split("/").map { it.trim() }.filter { it.isNotEmpty() }
        if (partners.isEmpty()) return false

        for (partner in partners) {
            val tokens = partnerTokens(partner)
            if (tokens.isEmpty()) return false
            val partnerMatches = tokens.any { token ->
                token in sideWords || (token.length >= 4 && sideNorm.contains(token))
            }
            if (!partnerMatches) return false
        }
        return true
    }

    internal fun partnerTokens(partnerName: String): List<String> {
        val words = normalizeText(partnerName)
            .split(WHITESPACE_REGEX)
            .filter { it.length >= 2 && it !in IGNORED_NAME_SUFFIXES }
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf(words.last())
        if (words.size >= 2 && words[words.size - 2].length >= 3) {
            result.add(words[words.size - 2])
        }
        return result.distinct()
    }

    internal fun normalizeText(input: String): String {
        val replaced = input.trim()
            .replace("đ", "dj")
            .replace("Đ", "dj")
            .replace('ł', 'l')
            .replace('Ł', 'l')
            .replace('ø', 'o')
            .replace('Ø', 'o')
            .replace("ß", "ss")
        val decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase()
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun extractFlashscoreField(block: String, key: String): String? {
        val prefix = "$key÷"
        val idx = block.indexOf(prefix)
        if (idx == -1) return null
        // Ensure we match on a field boundary (start of string or preceded by '¬' or '~')
        if (idx > 0 && block[idx - 1] != '¬' && block[idx - 1] != '~') {
            var searchFrom = idx + 1
            while (searchFrom < block.length) {
                val nextIdx = block.indexOf(prefix, searchFrom)
                if (nextIdx == -1) return null
                if (block[nextIdx - 1] == '¬' || block[nextIdx - 1] == '~') {
                    val valueStart = nextIdx + prefix.length
                    val valueEnd = block.indexOf('¬', valueStart).let { if (it == -1) block.length else it }
                    return block.substring(valueStart, valueEnd)
                }
                searchFrom = nextIdx + 1
            }
            return null
        }
        val valueStart = idx + prefix.length
        val valueEnd = block.indexOf('¬', valueStart).let { if (it == -1) block.length else it }
        return block.substring(valueStart, valueEnd)
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
