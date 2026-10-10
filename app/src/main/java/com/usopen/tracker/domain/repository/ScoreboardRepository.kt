package com.usopen.tracker.domain.repository

import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchStatistics
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.Tournaments

interface ScoreboardRepository {
    suspend fun getScoreboard(forceRefresh: Boolean = false): Result<List<Match>>
    suspend fun getScoreboardForTournament(tournament: Tournament, forceRefresh: Boolean = false): Result<List<Match>> = getScoreboard(forceRefresh)
    fun getOfflineScoreboard(): List<Match>
    fun getOfflineScoreboardForTournament(tournament: Tournament): List<Match> = getOfflineScoreboard()
    suspend fun getMatchStatistics(match: Match): Result<MatchStatistics>
    fun getActiveTournament(): Tournament = Tournaments.ALL
    fun setActiveTournament(tournament: Tournament) {}
    suspend fun resolveWatchStreamForMatch(match: Match): Match = match
    suspend fun resolveHighlightsForMatch(match: Match): Match = match
}
