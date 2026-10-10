package com.usopen.tracker.data.repository

import android.content.Context
import com.usopen.tracker.data.local.MatchCacheManager
import com.usopen.tracker.data.remote.EspnScoreboardApi
import com.usopen.tracker.data.remote.EspnWatchApi
import com.usopen.tracker.data.remote.EspnWatchMatcher
import com.usopen.tracker.data.remote.TennisStatsApi
import com.usopen.tracker.data.remote.YouTubeHighlightResolver
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.MatchStatistics
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.Tournaments
import com.usopen.tracker.domain.repository.ScoreboardRepository
import com.usopen.tracker.domain.usecase.DeepLinkGenerator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScoreboardRepositoryImpl(
    context: Context? = null,
    private val api: EspnScoreboardApi = EspnScoreboardApi(),
    private val statsApi: TennisStatsApi = TennisStatsApi(),
    private val watchApi: EspnWatchApi = EspnWatchApi(),
    private val youTubeHighlightResolver: YouTubeHighlightResolver = YouTubeHighlightResolver(),
    private var activeTournament: Tournament = Tournaments.ALL
) : ScoreboardRepository {

    init {
        context?.let { appContext = it.applicationContext }
    }

    override fun getActiveTournament(): Tournament = activeTournament

    override fun setActiveTournament(tournament: Tournament) {
        activeTournament = tournament
    }

    override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
        return withContext(Dispatchers.IO) {
            statsApi.fetchStatistics(match)
        }
    }

    override suspend fun getScoreboardForTournament(
        tournament: Tournament,
        forceRefresh: Boolean
    ): Result<List<Match>> {
        val previous = activeTournament
        activeTournament = tournament
        return try {
            getScoreboard(forceRefresh)
        } finally {
            activeTournament = previous
        }
    }

    override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
        return withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                val cached = MatchCacheManager.loadMatches(appContext)
                val filteredCached = cached.filter {
                    activeTournament.matches(it.tournamentName) && it.hasValidCompetitors
                }
                if (filteredCached.isNotEmpty()) {
                    return@withContext Result.success(sortMatches(filteredCached))
                }
            }

            // Attempt online fetch from ATP endpoint
            val atpResult = api.fetchScoreboard(EspnScoreboardApi.ATP_SCOREBOARD_URL, activeTournament)
            val atpMatches = atpResult.getOrNull() ?: emptyList()

            // Also attempt WTA endpoint for full Men's and Women's coverage
            val wtaResult = api.fetchScoreboard(EspnScoreboardApi.WTA_SCOREBOARD_URL, activeTournament)
            val wtaMatches = wtaResult.getOrNull() ?: emptyList()

            val combined = mutableListOf<Match>()
            combined.addAll(atpMatches)
            for (m in wtaMatches) {
                if (combined.none { it.id == m.id }) {
                    combined.add(m)
                }
            }

            // Strictly scope matches to target tournament and exclude invalid TBD matchups
            val scopedMatches = combined.filter {
                activeTournament.matches(it.tournamentName) && it.hasValidCompetitors
            }

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
                val existingCacheMap = MatchCacheManager.loadMatches(appContext).associateBy { it.id }

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
                MatchCacheManager.saveMatches(appContext, sorted)
                Result.success(sorted)
            } else {
                // If network returned empty or failed, fall back to local cached matches if available
                val cached = MatchCacheManager.loadMatches(appContext)
                val filteredCached = cached.filter {
                    activeTournament.matches(it.tournamentName) && it.hasValidCompetitors
                }
                if (filteredCached.isNotEmpty()) {
                    Result.success(sortMatches(filteredCached))
                } else {
                    Result.failure(Exception("Scoreboard data unavailable. Connect to internet to sync."))
                }
            }
        }
    }

    override fun getOfflineScoreboard(): List<Match> {
        val cached = MatchCacheManager.loadMatches(appContext)
        val filtered = cached.filter {
            activeTournament.matches(it.tournamentName) && it.hasValidCompetitors
        }
        return sortMatches(filtered)
    }

    override fun getOfflineScoreboardForTournament(tournament: Tournament): List<Match> {
        val cached = MatchCacheManager.loadMatches(appContext)
        val filtered = cached.filter {
            tournament.matches(it.tournamentName) && it.hasValidCompetitors
        }
        return sortMatches(filtered)
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
                    com.usopen.tracker.domain.model.MatchState.LIVE -> 0
                    com.usopen.tracker.domain.model.MatchState.UPCOMING -> 1
                    com.usopen.tracker.domain.model.MatchState.FINISHED -> 2
                }
            }.thenComparing { a, b ->
                when {
                    a.state == com.usopen.tracker.domain.model.MatchState.UPCOMING && b.state == com.usopen.tracker.domain.model.MatchState.UPCOMING -> {
                        (a.startTime ?: "").compareTo(b.startTime ?: "")
                    }
                    a.state == com.usopen.tracker.domain.model.MatchState.FINISHED && b.state == com.usopen.tracker.domain.model.MatchState.FINISHED -> {
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
