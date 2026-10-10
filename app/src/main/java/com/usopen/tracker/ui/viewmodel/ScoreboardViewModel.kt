package com.usopen.tracker.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usopen.tracker.data.local.FollowManager
import com.usopen.tracker.data.repository.H2HRepository
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.H2HUiState
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.MatchStatistics
import com.usopen.tracker.domain.repository.ScoreboardRepository
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.Tournaments
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.usopen.tracker.data.remote.YouTubeHighlightResolver
import com.usopen.tracker.domain.usecase.DeepLinkGenerator
import com.usopen.tracker.util.IntentLauncher

enum class MatchFilter(val label: String) {
    ALL("All Matches"),
    LIVE("Live Now"),
    FOLLOWED("Following ⭐"),
    UPCOMING("Upcoming"),
    FINISHED("Results")
}

data class ScoreboardUiState(
    val isLoading: Boolean = false,
    val allMatches: List<Match> = emptyList(),
    val filteredMatches: List<Match> = emptyList(),
    val selectedFilter: MatchFilter = MatchFilter.ALL,
    val searchQuery: String = "",
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val liveCount: Int = 0,
    val followedMatchIds: Set<String> = emptySet(),
    val followedCount: Int = 0,
    val currentTournament: Tournament = Tournaments.US_OPEN
)

data class MatchStatsUiState(
    val isLoading: Boolean = false,
    val stats: MatchStatistics? = null,
    val errorMessage: String? = null,
    val selectedPeriodIndex: Int = 0
)

class ScoreboardViewModel(
    private val repository: ScoreboardRepository = ScoreboardRepositoryImpl(),
    private val h2hRepository: H2HRepository = H2HRepository(),
    private var followManager: FollowManager? = null,
    private val enableAutoRefresh: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScoreboardUiState(isLoading = true, currentTournament = repository.getActiveTournament()))
    val uiState: StateFlow<ScoreboardUiState> = _uiState.asStateFlow()

    private val _selectedMatchForStats = MutableStateFlow<Match?>(null)
    val selectedMatchForStats: StateFlow<Match?> = _selectedMatchForStats.asStateFlow()

    private val _statsUiState = MutableStateFlow(MatchStatsUiState())
    val statsUiState: StateFlow<MatchStatsUiState> = _statsUiState.asStateFlow()

    private val _selectedMatchForH2H = MutableStateFlow<Match?>(null)
    val selectedMatchForH2H: StateFlow<Match?> = _selectedMatchForH2H.asStateFlow()

    private val _h2hUiState = MutableStateFlow(H2HUiState())
    val h2hUiState: StateFlow<H2HUiState> = _h2hUiState.asStateFlow()

    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var followObserverJob: kotlinx.coroutines.Job? = null

    init {
        loadMatches(forceRefresh = false)
        if (enableAutoRefresh) {
            startAutoRefresh()
        }
        followManager?.let { fm ->
            attachFollowManager(fm)
        }
    }

    fun initFollowManager(context: Context) {
        val appCtx = context.applicationContext
        ScoreboardRepositoryImpl.appContext = appCtx
        val fm = followManager ?: FollowManager.getInstance(appCtx).also {
            followManager = it
            attachFollowManager(it)
        }
        val current = _uiState.value.allMatches.ifEmpty {
            com.usopen.tracker.data.local.MatchCacheManager.loadMatches(appCtx)
        }
        if (current.isNotEmpty()) {
            com.usopen.tracker.data.local.MatchCacheManager.saveMatches(appCtx, current)
            fm.cleanupExpiredFollowedMatches(appCtx, current)
        }
        val followedIds = fm.getFollowedMatchIds()
        setFollowedMatches(followedIds)
    }

    private fun attachFollowManager(fm: FollowManager) {
        followObserverJob?.cancel()
        followObserverJob = viewModelScope.launch {
            fm.followedMatchIds.collect { ids ->
                setFollowedMatches(ids)
            }
        }
    }

    fun toggleFollow(context: Context, matchId: String): Boolean {
        val appCtx = context.applicationContext
        val fm = followManager ?: FollowManager.getInstance(appCtx).also {
            followManager = it
            attachFollowManager(it)
        }
        val match = _uiState.value.allMatches.find { it.id == matchId }
        val isNowFollowed = fm.toggleFollow(appCtx, matchId, match)
        val allMatches = _uiState.value.allMatches
        if (allMatches.isNotEmpty()) {
            fm.cleanupExpiredFollowedMatches(appCtx, allMatches)
        }
        val updatedIds = fm.getFollowedMatchIds()
        setFollowedMatches(updatedIds)
        return isNowFollowed
    }

    fun isMatchFollowed(matchId: String): Boolean {
        return _uiState.value.followedMatchIds.contains(matchId)
    }

    fun setFollowedMatches(ids: Set<String>) {
        _uiState.update { state ->
            state.copy(
                followedMatchIds = ids,
                followedCount = state.allMatches.count { it.id in ids },
                filteredMatches = applyFilterAndSearch(
                    matches = state.allMatches,
                    filter = state.selectedFilter,
                    query = state.searchQuery,
                    followedIds = ids
                )
            )
        }
    }

    fun startAutoRefresh(intervalMillis: Long = 60_000L) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMillis)
                refreshSilently()
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /**
     * Cancels all active background jobs (auto-refresh and follow observer).
     * Useful in tests and component destruction to prevent coroutines from leaking or looping indefinitely.
     */
    fun cleanup() {
        stopAutoRefresh()
        followObserverJob?.cancel()
        followObserverJob = null
    }

    public override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    suspend fun refreshSilently() {
        val result = repository.getScoreboard(forceRefresh = true)
        result.onSuccess { matches ->
            val fm = followManager ?: ScoreboardRepositoryImpl.appContext?.let { FollowManager.getInstance(it) }
            fm?.cleanupExpiredFollowedMatches(allMatches = matches)
            val followedIds = fm?.getFollowedMatchIds() ?: _uiState.value.followedMatchIds
            val live = matches.count { it.state == MatchState.LIVE }
            val followed = matches.count { it.id in followedIds }
            _uiState.update { state ->
                state.copy(
                    allMatches = matches,
                    followedMatchIds = followedIds,
                    filteredMatches = applyFilterAndSearch(matches, state.selectedFilter, state.searchQuery, followedIds),
                    liveCount = live,
                    followedCount = followed,
                    isOffline = false
                )
            }
            notifyWidgetUpdate()
        }
    }

    fun loadMatches(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.getScoreboard(forceRefresh)

            result.onSuccess { matches ->
                val fm = followManager ?: ScoreboardRepositoryImpl.appContext?.let { FollowManager.getInstance(it) }
                if (fm != null && followManager == null) {
                    followManager = fm
                    attachFollowManager(fm)
                }
                fm?.cleanupExpiredFollowedMatches(allMatches = matches)
                val followedIds = fm?.getFollowedMatchIds() ?: _uiState.value.followedMatchIds
                val live = matches.count { it.state == MatchState.LIVE }
                val followed = matches.count { it.id in followedIds }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        allMatches = matches,
                        followedMatchIds = followedIds,
                        filteredMatches = applyFilterAndSearch(matches, state.selectedFilter, state.searchQuery, followedIds),
                        liveCount = live,
                        followedCount = followed,
                        isOffline = false
                    )
                }
                notifyWidgetUpdate()

                if (!forceRefresh && enableAutoRefresh) {
                    viewModelScope.launch {
                        refreshSilently()
                    }
                }
            }.onFailure { throwable ->
                // Use offline fallback
                val fallback = repository.getOfflineScoreboard()
                val fm = followManager ?: ScoreboardRepositoryImpl.appContext?.let { FollowManager.getInstance(it) }
                fm?.cleanupExpiredFollowedMatches(allMatches = fallback)
                val followedIds = fm?.getFollowedMatchIds() ?: _uiState.value.followedMatchIds
                val live = fallback.count { it.state == MatchState.LIVE }
                val followed = fallback.count { it.id in followedIds }
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        allMatches = fallback,
                        followedMatchIds = followedIds,
                        filteredMatches = applyFilterAndSearch(fallback, state.selectedFilter, state.searchQuery, followedIds),
                        liveCount = live,
                        followedCount = followed,
                        isOffline = true,
                        errorMessage = throwable.message ?: "Failed to load matches"
                    )
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredMatches = applyFilterAndSearch(state.allMatches, state.selectedFilter, query, state.followedMatchIds)
            )
        }
    }

    fun clearSearchQuery() {
        onSearchQueryChange("")
    }

    fun selectTournament(tournament: Tournament) {
        repository.setActiveTournament(tournament)
        _uiState.update { it.copy(currentTournament = tournament) }
        loadMatches(forceRefresh = true)
    }

    fun setFilter(filter: MatchFilter) {
        _uiState.update { state ->
            state.copy(
                selectedFilter = filter,
                filteredMatches = applyFilterAndSearch(state.allMatches, filter, state.searchQuery, state.followedMatchIds)
            )
        }
    }

    fun openMatchStats(match: Match) {
        _selectedMatchForStats.value = match
        _statsUiState.value = MatchStatsUiState(isLoading = true, selectedPeriodIndex = 0)
        viewModelScope.launch {
            val result = repository.getMatchStatistics(match)
            result.onSuccess { stats ->
                _statsUiState.update {
                    it.copy(isLoading = false, stats = stats, errorMessage = null)
                }
            }.onFailure { throwable ->
                _statsUiState.update {
                    it.copy(isLoading = false, errorMessage = throwable.message ?: "Failed to load statistics")
                }
            }
        }
    }

    fun closeMatchStats() {
        _selectedMatchForStats.value = null
        _statsUiState.value = MatchStatsUiState()
    }

    fun selectStatsPeriod(index: Int) {
        _statsUiState.update { it.copy(selectedPeriodIndex = index) }
    }

    fun refreshMatchStats() {
        val currentMatch = _selectedMatchForStats.value ?: return
        openMatchStats(currentMatch)
    }

    fun openH2H(match: Match) {
        _selectedMatchForH2H.value = match
        _h2hUiState.value = H2HUiState(isLoading = true)

        viewModelScope.launch {
            val result = h2hRepository.getH2HSummary(match)
            result.onSuccess { summary ->
                _h2hUiState.value = H2HUiState(
                    isLoading = false,
                    summary = summary
                )
            }.onFailure { error ->
                _h2hUiState.value = H2HUiState(
                    isLoading = false,
                    errorMessage = error.localizedMessage ?: "Failed to load H2H records"
                )
            }
        }
    }

    fun closeH2H() {
        _selectedMatchForH2H.value = null
        _h2hUiState.value = H2HUiState()
    }

    fun refreshH2H() {
        val currentMatch = _selectedMatchForH2H.value ?: return
        openH2H(currentMatch)
    }

    fun selectH2HSurface(surface: CourtSurface?) {
        _h2hUiState.update { it.copy(selectedSurface = surface) }
    }

    private fun applyFilterAndSearch(
        matches: List<Match>,
        filter: MatchFilter,
        query: String,
        followedIds: Set<String> = _uiState.value.followedMatchIds
    ): List<Match> {
        val filteredByState = when (filter) {
            MatchFilter.ALL -> matches
            MatchFilter.LIVE -> matches.filter { it.state == MatchState.LIVE }
            MatchFilter.FOLLOWED -> matches.filter { it.id in followedIds }
            MatchFilter.UPCOMING -> matches.filter { it.state == MatchState.UPCOMING }
            MatchFilter.FINISHED -> matches.filter { it.state == MatchState.FINISHED }
        }

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return filteredByState
        }

        return filteredByState.filter { match ->
            matchMatchesQuery(match, trimmed)
        }
    }

    private fun matchMatchesQuery(match: Match, query: String): Boolean {
        val q = query.lowercase()
        return match.player1.name.lowercase().contains(q) ||
            match.player2.name.lowercase().contains(q) ||
            (match.player1.country?.lowercase()?.contains(q) == true) ||
            (match.player2.country?.lowercase()?.contains(q) == true) ||
            match.eventGrouping.lowercase().contains(q) ||
            match.roundName.lowercase().contains(q) ||
            match.venue.lowercase().contains(q) ||
            (match.court?.lowercase()?.contains(q) == true) ||
            (match.displayCourt?.lowercase()?.contains(q) == true) ||
            match.statusDetail.lowercase().contains(q)
    }

    fun launchWatch(context: Context, match: Match) {
        if (match.espnWatchId != null) {
            val deepLink = match.deepLink ?: DeepLinkGenerator.generate(match)
            IntentLauncher.launchWatchIntent(context, deepLink)
            return
        }

        viewModelScope.launch {
            try {
                android.widget.Toast.makeText(context, "Opening ESPN Watch stream...", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}

            val resolved = try {
                withTimeoutOrNull(6000L) {
                    repository.resolveWatchStreamForMatch(match)
                } ?: match
            } catch (_: Throwable) {
                match
            }

            if (resolved.espnWatchId != null) {
                _uiState.update { state ->
                    val updatedAll = state.allMatches.map { if (it.id == resolved.id) resolved else it }
                    val updatedFiltered = state.filteredMatches.map { if (it.id == resolved.id) resolved else it }
                    state.copy(allMatches = updatedAll, filteredMatches = updatedFiltered)
                }
            }

            val deepLink = resolved.deepLink ?: DeepLinkGenerator.generate(resolved)
            IntentLauncher.launchWatchIntent(context, deepLink)
        }
    }

    fun launchHighlights(context: Context, match: Match) {
        if (match.state != MatchState.FINISHED) return

        if (match.youtubeVideoId != null) {
            val deepLink = match.youtubeDeepLink ?: YouTubeHighlightResolver.generateDeepLink(match)
            IntentLauncher.launchYouTubeIntent(context, deepLink)
            return
        }

        viewModelScope.launch {
            try {
                android.widget.Toast.makeText(context, "Finding official match highlights...", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}

            val resolved = try {
                withTimeoutOrNull(4000L) {
                    repository.resolveHighlightsForMatch(match)
                } ?: match
            } catch (_: Throwable) {
                match
            }

            if (resolved.youtubeVideoId != null) {
                _uiState.update { state ->
                    val updatedAll = state.allMatches.map { if (it.id == resolved.id) resolved else it }
                    val updatedFiltered = state.filteredMatches.map { if (it.id == resolved.id) resolved else it }
                    state.copy(allMatches = updatedAll, filteredMatches = updatedFiltered)
                }
            }

            val deepLink = resolved.youtubeDeepLink ?: YouTubeHighlightResolver.generateDeepLink(resolved)
            IntentLauncher.launchYouTubeIntent(context, deepLink)
        }
    }

    private fun notifyWidgetUpdate() {
        try {
            val context = ScoreboardRepositoryImpl.appContext ?: return
            com.usopen.tracker.ui.widget.WidgetAutoSyncManager.recordSyncResult(context, true)
            com.usopen.tracker.ui.widget.FollowedMatchesWidgetProvider.updateAllWidgets(context)
        } catch (_: Throwable) {
            // Safe fallback during tests or prior to context attachment
        }
    }
}

