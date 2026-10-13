package com.tennis.tracker.data.repository

import android.content.Context
import com.tennis.tracker.data.local.MatchCacheManager
import com.tennis.tracker.data.local.MockStatsData
import com.tennis.tracker.data.local.MockTournamentData
import com.tennis.tracker.data.remote.EspnScoreboardApi
import com.tennis.tracker.data.remote.EspnWatchApi
import com.tennis.tracker.data.remote.EspnWatchMatcher
import com.tennis.tracker.data.remote.TennisStatsApi
import com.tennis.tracker.data.remote.YouTubeHighlightResolver
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.repository.ScoreboardRepository
import com.tennis.tracker.domain.usecase.DeepLinkGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class ScoreboardRepositoryImpl(
    context: Context? = null,
    private val api: EspnScoreboardApi = EspnScoreboardApi(),
    private val statsApi: TennisStatsApi = TennisStatsApi(),
    private val watchApi: EspnWatchApi = EspnWatchApi(),
    private val youTubeHighlightResolver: YouTubeHighlightResolver = YouTubeHighlightResolver(),
    @Volatile
    private var activeTournament: Tournament = Tournaments.ALL
) : ScoreboardRepository {

    private val threadLocalTournament = ThreadLocal<Tournament>()

    init {
        context?.let { appContext = it.applicationContext }
    }

    override fun getActiveTournament(): Tournament = threadLocalTournament.get() ?: activeTournament

    override fun setActiveTournament(tournament: Tournament) {
        threadLocalTournament.set(tournament)
        activeTournament = tournament
    }

    private val statsCache = java.util.concurrent.ConcurrentHashMap<String, MatchStatistics>()

    fun clearStatsCache() {
        statsCache.clear()
    }

    override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
        if (match.state == MatchState.UPCOMING) {
            return Result.failure(NoSuchElementException("Match statistics will become available once the match begins."))
        }
        return withContext(Dispatchers.IO) {
            val cached = statsCache[match.id]
            if (cached != null && match.state == MatchState.FINISHED) {
                return@withContext Result.success(cached)
            }

            val remoteResult = try {
                statsApi.fetchStatistics(match)
            } catch (t: Throwable) {
                Log.w("ScoreboardRepo", "Failed to fetch remote statistics for match ${match.id}: ${t.message}")
                Result.failure(t)
            }

            val stats = if (remoteResult.isSuccess && remoteResult.getOrNull()?.periods?.isNotEmpty() == true) {
                remoteResult.getOrNull()!!
            } else {
                MockStatsData.generate(match)
            }

            statsCache[match.id] = stats
            Result.success(stats)
        }
    }

    override suspend fun getScoreboardForTournament(
        tournament: Tournament,
        forceRefresh: Boolean
    ): Result<List<Match>> {
        return getScoreboardInternal(tournament, forceRefresh)
    }

    override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
        return getScoreboardInternal(Tournaments.ALL, forceRefresh)
    }

    private suspend fun getScoreboardInternal(
        targetTournament: Tournament,
        forceRefresh: Boolean
    ): Result<List<Match>> = withContext(Dispatchers.IO) {
        var cachedMatches: List<Match>? = null
        fun loadCached(): List<Match> {
            val current = cachedMatches
            if (current != null) return current
            val loaded = MatchCacheManager.loadMatches(appContext)
            cachedMatches = loaded
            return loaded
        }

        if (!forceRefresh && MatchCacheManager.isCacheFresh(appContext)) {
            val cached = loadCached()
            val filteredCached = if (targetTournament == Tournaments.ALL) cached else filterMatches(cached, targetTournament)
            if (filteredCached.isNotEmpty()) {
                return@withContext Result.success(sortMatches(filteredCached))
            }
        }

        // Concurrently fetch ATP and WTA endpoints in parallel
        val (atpResult, wtaResult) = coroutineScope {
            val atpDeferred = async { api.fetchScoreboard(EspnScoreboardApi.ATP_SCOREBOARD_URL, targetTournament) }
            val wtaDeferred = async { api.fetchScoreboard(EspnScoreboardApi.WTA_SCOREBOARD_URL, targetTournament) }
            Pair(atpDeferred.await(), wtaDeferred.await())
        }
        val atpMatches = atpResult.getOrNull() ?: emptyList()
        val wtaMatches = wtaResult.getOrNull() ?: emptyList()

        // Deduplicate ATP and WTA match feeds in O(N + M) time preserving first occurrence
        val combined = (atpMatches + wtaMatches).distinctBy { it.id }

        // Strictly scope matches to target tournament and exclude invalid TBD matchups
        val scopedMatches = if (targetTournament == Tournaments.ALL) combined else filterMatches(combined, targetTournament)

        if (scopedMatches.isNotEmpty()) {
            // Enrich live matches with authentic real-time game scores
            val liveScores = try {
                statsApi.fetchLiveGameScores(scopedMatches)
            } catch (t: Throwable) {
                Log.w("ScoreboardRepo", "Failed to fetch live game scores: ${t.message}")
                emptyMap()
            }

            val withScores = if (liveScores.isNotEmpty()) {
                scopedMatches.map { match ->
                    val pair = liveScores[match.id]
                    if (pair != null) {
                        match.copy(
                            player1 = match.player1.copy(currentGameScore = pair.first),
                            player2 = match.player2.copy(currentGameScore = pair.second)
                        )
                    } else match
                }
            } else scopedMatches

            // Load existing cached matches to preserve any previously resolved espnWatchIds & youtubeVideoIds
            val existingCacheMap = loadCached().associateBy { it.id }

            val finalMatches = withScores.map { match ->
                val existing = existingCacheMap[match.id]
                val watchId = existing?.espnWatchId ?: match.espnWatchId
                val ytVideoId = existing?.youtubeVideoId ?: match.youtubeVideoId
                val m = match.copy(
                    espnWatchId = watchId,
                    youtubeVideoId = ytVideoId
                )
                val withDeepLink = m.copy(deepLink = DeepLinkGenerator.generate(m))
                if (m.state == MatchState.FINISHED) {
                    withDeepLink.copy(youtubeDeepLink = YouTubeHighlightResolver.generateDeepLink(withDeepLink))
                } else {
                    withDeepLink
                }
            }

            val sorted = sortMatches(finalMatches)
            val existingCached = loadCached()
            val updatedIds = sorted.map { it.id }.toSet()
            val mergedCache = existingCached.filterNot { it.id in updatedIds } + sorted
            MatchCacheManager.saveMatches(appContext, mergedCache)
            Result.success(sorted)
        } else {
            // If network returned empty or failed, fall back to local cached matches if available
            val cached = loadCached()
            val filteredCached = if (targetTournament == Tournaments.ALL) cached else filterMatches(cached, targetTournament)
            if (filteredCached.isNotEmpty()) {
                Result.success(sortMatches(filteredCached))
            } else if (atpResult.isSuccess || wtaResult.isSuccess) {
                Result.success(emptyList())
            } else {
                Result.failure(Exception("Scoreboard data unavailable. Connect to internet to sync."))
            }
        }
    }

    override fun getOfflineScoreboard(): List<Match> {
        return getOfflineScoreboardForTournament(activeTournament)
    }

    override fun getOfflineScoreboardForTournament(tournament: Tournament): List<Match> {
        val cached = MatchCacheManager.loadMatches(appContext).ifEmpty {
            MockTournamentData.matches
        }
        val filtered = if (tournament == Tournaments.ALL) cached else filterMatches(cached, tournament)
        return sortMatches(filtered)
    }

    private fun filterMatches(matches: List<Match>, tournament: Tournament): List<Match> {
        return matches.filter {
            tournament.matches(it.tournamentName) && it.hasValidCompetitors
        }
    }

    override suspend fun resolveWatchStreamForMatch(match: Match): Match {
        if (match.espnWatchId != null) return match
        return withContext(Dispatchers.IO) {
            try {
                val stream = watchApi.resolveWatchStreamForMatch(match)
                if (stream != null) {
                    val updated = match.copy(espnWatchId = stream.watchId)
                    val withDeepLink = updated.copy(deepLink = DeepLinkGenerator.generate(updated))
                    MatchCacheManager.updateMatch(appContext, withDeepLink)
                    withDeepLink
                } else {
                    match
                }
            } catch (t: Throwable) {
                Log.w("ScoreboardRepo", "Failed to resolve stream for match ${match.id}: ${t.message}")
                match
            }
        }
    }

    override suspend fun resolveHighlightsForMatch(match: Match): Match {
        if (match.state != MatchState.FINISHED) return match
        if (match.youtubeVideoId != null) return match
        return withContext(Dispatchers.IO) {
            try {
                val highlightResult = youTubeHighlightResolver.resolveHighlight(match)
                if (highlightResult != null && highlightResult.videoId != null) {
                    val updated = match.copy(
                        youtubeVideoId = highlightResult.videoId,
                        youtubeDeepLink = highlightResult.deepLink
                    )
                    MatchCacheManager.updateMatch(appContext, updated)
                    updated
                } else {
                    val fallback = YouTubeHighlightResolver.generateDeepLink(match)
                    match.copy(youtubeDeepLink = fallback)
                }
            } catch (t: Throwable) {
                Log.w("ScoreboardRepo", "Failed to resolve YouTube highlight for match ${match.id}: ${t.message}")
                val fallback = YouTubeHighlightResolver.generateDeepLink(match)
                match.copy(youtubeDeepLink = fallback)
            }
        }
    }

    private fun sortMatches(matches: List<Match>): List<Match> {
        return matches.sortedWith(
            compareBy<Match> { match ->
                when (match.state) {
                    com.tennis.tracker.domain.model.MatchState.LIVE -> 0
                    com.tennis.tracker.domain.model.MatchState.UPCOMING -> 1
                    com.tennis.tracker.domain.model.MatchState.FINISHED -> 2
                }
            }.thenComparing { a, b ->
                when {
                    a.state == com.tennis.tracker.domain.model.MatchState.UPCOMING && b.state == com.tennis.tracker.domain.model.MatchState.UPCOMING -> {
                        (a.startTime ?: "").compareTo(b.startTime ?: "")
                    }
                    a.state == com.tennis.tracker.domain.model.MatchState.FINISHED && b.state == com.tennis.tracker.domain.model.MatchState.FINISHED -> {
                        (b.startTime ?: "").compareTo(a.startTime ?: "")
                    }
                    else -> 0
                }
            }
        )
    }

    companion object {
        @Volatile
        var appContext: Context? = null

        @Volatile
        private var instance: ScoreboardRepositoryImpl? = null

        fun getInstance(context: Context): ScoreboardRepositoryImpl {
            appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: ScoreboardRepositoryImpl(context.applicationContext).also { instance = it }
            }
        }
    }
}
