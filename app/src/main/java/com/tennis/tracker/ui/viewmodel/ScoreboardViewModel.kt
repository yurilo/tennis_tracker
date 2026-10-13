package com.tennis.tracker.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tennis.tracker.data.local.FollowManager
import com.tennis.tracker.data.repository.H2HRepository
import com.tennis.tracker.data.repository.ScoreboardRepositoryImpl
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.H2HUiState
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.repository.ScoreboardRepository
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.TournamentResolver
import com.tennis.tracker.domain.model.TourType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.tennis.tracker.data.remote.YouTubeHighlightResolver
import com.tennis.tracker.domain.usecase.DeepLinkGenerator
import com.tennis.tracker.util.IntentLauncher
import com.tennis.tracker.domain.model.AppSettings
import com.tennis.tracker.domain.model.AutoRefreshInterval
import com.tennis.tracker.domain.model.MatchRetentionPolicy
import com.tennis.tracker.domain.model.StartupTournamentTier
import com.tennis.tracker.domain.model.WidgetSyncMode
import com.tennis.tracker.domain.repository.SettingsRepository
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    val currentTournament: Tournament = Tournaments.ALL,
    val availableTournaments: List<Tournament> = Tournaments.POPULAR_TOURNAMENTS,
    val selectedTierFilter: TourTierFilter = TourTierFilter.ALL,
    val contextualTournaments: List<Tournament> = emptyList()
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
    private val enableAutoRefresh: Boolean = true,
    private var settingsRepository: SettingsRepository = defaultSettingsRepository()
) : ViewModel() {

    constructor(
        repository: ScoreboardRepository,
        settingsRepository: SettingsRepository
    ) : this(repository, H2HRepository(), null, true, settingsRepository)

    constructor(
        settingsRepository: SettingsRepository
    ) : this(ScoreboardRepositoryImpl(), H2HRepository(), null, true, settingsRepository)

    private val initialTier: TourTierFilter = settingsRepository.getSettings().defaultTournament.tourTierFilter

    private val _uiState = MutableStateFlow(
        run {
            val initialMatches = repository.getOfflineScoreboard()
            val initialTournament = if (initialTier != TourTierFilter.ALL) {
                getTierAllTournament(initialTier).also { repository.setActiveTournament(it) }
            } else {
                repository.getActiveTournament()
            }
            val initialContextual = computeContextualTournaments(initialMatches, initialTier, initialTournament)
            ScoreboardUiState(
                isLoading = initialMatches.isEmpty(),
                allMatches = initialMatches,
                currentTournament = initialTournament,
                selectedTierFilter = initialTier,
                contextualTournaments = initialContextual,
                availableTournaments = initialContextual,
                filteredMatches = applyFilterAndSearch(
                    matches = initialMatches,
                    tier = initialTier,
                    tournament = initialTournament,
                    filter = MatchFilter.ALL,
                    query = "",
                    followedIds = emptySet()
                ),
                liveCount = initialMatches.count { it.state == MatchState.LIVE },
                isOffline = false
            )
        }
    )
    val uiState: StateFlow<ScoreboardUiState> = _uiState.asStateFlow()

    private val _selectedMatchForStats = MutableStateFlow<Match?>(null)
    val selectedMatchForStats: StateFlow<Match?> = _selectedMatchForStats.asStateFlow()

    private val _statsUiState = MutableStateFlow(MatchStatsUiState())
    val statsUiState: StateFlow<MatchStatsUiState> = _statsUiState.asStateFlow()

    private val _selectedMatchForH2H = MutableStateFlow<Match?>(null)
    val selectedMatchForH2H: StateFlow<Match?> = _selectedMatchForH2H.asStateFlow()

    private val _h2hUiState = MutableStateFlow(H2HUiState())
    val h2hUiState: StateFlow<H2HUiState> = _h2hUiState.asStateFlow()

    val settings: StateFlow<AppSettings>
        get() = settingsRepository.settings

    private var loadMatchesJob: kotlinx.coroutines.Job? = null
    private var silentRefreshJob: kotlinx.coroutines.Job? = null
    private var autoRefreshJob: kotlinx.coroutines.Job? = null
    private var followObserverJob: kotlinx.coroutines.Job? = null
    private var settingsObserverJob: kotlinx.coroutines.Job? = null
    private var currentRefreshIntervalMillis: Long? = null

    fun updateAutoRefreshInterval(interval: AutoRefreshInterval) {
        viewModelScope.launch {
            settingsRepository.updateAutoRefreshInterval(interval)
        }
    }

    fun updateMatchRetention(policy: MatchRetentionPolicy) {
        viewModelScope.launch {
            settingsRepository.updateMatchRetention(policy)
        }
    }

    fun updateWidgetSyncMode(mode: WidgetSyncMode) {
        viewModelScope.launch {
            settingsRepository.updateWidgetSyncMode(mode)
        }
    }

    fun updateDefaultTournament(tier: StartupTournamentTier) {
        viewModelScope.launch {
            settingsRepository.updateDefaultTournament(tier)
        }
    }

    fun updateTelemetryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateTelemetryEnabled(enabled)
        }
    }

    fun updateTelemetryEndpoint(endpoint: String) {
        viewModelScope.launch {
            settingsRepository.updateTelemetryEndpoint(endpoint)
        }
    }

    fun resetSettingsToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
        }
    }

    init {
        if (initialTier != TourTierFilter.ALL) {
            selectTierFilter(initialTier)
        }
        loadMatches(forceRefresh = false)
        if (enableAutoRefresh) {
            val initialInterval = settingsRepository.getSettings().autoRefreshInterval
            if (initialInterval.isEnabled) {
                startAutoRefresh(initialInterval.periodMillis)
            }
            startSettingsObserver()
        }
        followManager?.let { fm ->
            attachFollowManager(fm)
        }
    }

    private fun startSettingsObserver() {
        settingsObserverJob?.cancel()
        settingsObserverJob = viewModelScope.launch {
            settingsRepository.settings
                .map { it.autoRefreshInterval }
                .distinctUntilChanged()
                .collect { interval ->
                    if (interval.isEnabled) {
                        if (interval.periodMillis != currentRefreshIntervalMillis || autoRefreshJob == null || autoRefreshJob?.isActive != true) {
                            startAutoRefresh(interval.periodMillis)
                        }
                    } else {
                        stopAutoRefresh()
                    }
                }
        }
    }

    fun attachSettingsRepository(repo: SettingsRepository) {
        settingsRepository = repo
        if (enableAutoRefresh) {
            val interval = repo.getSettings().autoRefreshInterval
            if (interval.isEnabled) {
                startAutoRefresh(interval.periodMillis)
            } else {
                stopAutoRefresh()
            }
            startSettingsObserver()
        }
    }

    fun getSettingsRepository(): SettingsRepository = settingsRepository

    fun initFollowManager(context: Context) {
        val appCtx = context.applicationContext
        ScoreboardRepositoryImpl.appContext = appCtx
        val fm = followManager ?: FollowManager.getInstance(appCtx).also {
            followManager = it
            attachFollowManager(it)
        }
        val current = _uiState.value.allMatches.ifEmpty {
            com.tennis.tracker.data.local.MatchCacheManager.loadMatches(appCtx)
        }
        if (current.isNotEmpty()) {
            com.tennis.tracker.data.local.MatchCacheManager.saveMatches(appCtx, current)
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
                    tier = state.selectedTierFilter,
                    tournament = state.currentTournament,
                    filter = state.selectedFilter,
                    query = state.searchQuery,
                    followedIds = ids
                )
            )
        }
    }

    fun startAutoRefresh(intervalMillis: Long = 60_000L) {
        currentRefreshIntervalMillis = intervalMillis
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMillis)
                refreshSilently()
            }
        }
    }

    fun stopAutoRefresh() {
        currentRefreshIntervalMillis = null
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /**
     * Cancels all active background jobs (auto-refresh, follow observer, settings observer, and match loading).
     * Useful in tests and component destruction to prevent coroutines from leaking or looping indefinitely.
     */
    fun cleanup() {
        loadMatchesJob?.cancel()
        loadMatchesJob = null
        silentRefreshJob?.cancel()
        silentRefreshJob = null
        stopAutoRefresh()
        followObserverJob?.cancel()
        followObserverJob = null
        settingsObserverJob?.cancel()
        settingsObserverJob = null
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
                val contextual = computeContextualTournaments(matches, state.selectedTierFilter, state.currentTournament)
                state.copy(
                    allMatches = matches,
                    followedMatchIds = followedIds,
                    contextualTournaments = contextual,
                    availableTournaments = contextual,
                    filteredMatches = applyFilterAndSearch(
                        matches = matches,
                        tier = state.selectedTierFilter,
                        tournament = state.currentTournament,
                        filter = state.selectedFilter,
                        query = state.searchQuery,
                        followedIds = followedIds
                    ),
                    liveCount = live,
                    followedCount = followed,
                    isOffline = false
                )
            }
            notifyWidgetUpdate()
        }
    }

    fun loadMatches(forceRefresh: Boolean = false) {
        loadMatchesJob?.cancel()
        loadMatchesJob = viewModelScope.launch {
            if (_uiState.value.allMatches.isEmpty()) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
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
                    val contextual = computeContextualTournaments(matches, state.selectedTierFilter, state.currentTournament)
                    state.copy(
                        isLoading = false,
                        allMatches = matches,
                        followedMatchIds = followedIds,
                        contextualTournaments = contextual,
                        availableTournaments = contextual,
                        filteredMatches = applyFilterAndSearch(
                            matches = matches,
                            tier = state.selectedTierFilter,
                            tournament = state.currentTournament,
                            filter = state.selectedFilter,
                            query = state.searchQuery,
                            followedIds = followedIds
                        ),
                        liveCount = live,
                        followedCount = followed,
                        isOffline = false
                    )
                }
                notifyWidgetUpdate()

                if (!forceRefresh && enableAutoRefresh) {
                    silentRefreshJob?.cancel()
                    silentRefreshJob = viewModelScope.launch {
                        refreshSilently()
                    }
                }
            }.onFailure { throwable ->
                // Use offline fallback
                val current = _uiState.value.allMatches
                val fallback = if (current.isNotEmpty()) current else repository.getOfflineScoreboard()
                val fm = followManager ?: ScoreboardRepositoryImpl.appContext?.let { FollowManager.getInstance(it) }
                fm?.cleanupExpiredFollowedMatches(allMatches = fallback)
                val followedIds = fm?.getFollowedMatchIds() ?: _uiState.value.followedMatchIds
                val live = fallback.count { it.state == MatchState.LIVE }
                val followed = fallback.count { it.id in followedIds }
                _uiState.update { state ->
                    val contextual = computeContextualTournaments(fallback, state.selectedTierFilter, state.currentTournament)
                    state.copy(
                        isLoading = false,
                        allMatches = fallback,
                        followedMatchIds = followedIds,
                        contextualTournaments = contextual,
                        availableTournaments = contextual,
                        filteredMatches = applyFilterAndSearch(
                            matches = fallback,
                            tier = state.selectedTierFilter,
                            tournament = state.currentTournament,
                            filter = state.selectedFilter,
                            query = state.searchQuery,
                            followedIds = followedIds
                        ),
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
                filteredMatches = applyFilterAndSearch(
                    matches = state.allMatches,
                    tier = state.selectedTierFilter,
                    tournament = state.currentTournament,
                    filter = state.selectedFilter,
                    query = query,
                    followedIds = state.followedMatchIds
                )
            )
        }
    }

    fun clearSearchQuery() {
        onSearchQueryChange("")
    }

    fun selectTournament(tournament: Tournament) {
        repository.setActiveTournament(tournament)
        _uiState.update { state ->
            val contextual = computeContextualTournaments(state.allMatches, state.selectedTierFilter, tournament)
            state.copy(
                currentTournament = tournament,
                contextualTournaments = contextual,
                availableTournaments = contextual,
                filteredMatches = applyFilterAndSearch(
                    matches = state.allMatches,
                    tier = state.selectedTierFilter,
                    tournament = tournament,
                    filter = state.selectedFilter,
                    query = state.searchQuery,
                    followedIds = state.followedMatchIds
                )
            )
        }
    }

    fun selectTierFilter(tier: TourTierFilter) {
        _uiState.update { state ->
            val newTournament = getTierAllTournament(tier)
            val contextual = computeContextualTournaments(state.allMatches, tier, newTournament)
            repository.setActiveTournament(newTournament)
            state.copy(
                selectedTierFilter = tier,
                currentTournament = newTournament,
                contextualTournaments = contextual,
                availableTournaments = contextual,
                filteredMatches = applyFilterAndSearch(
                    matches = state.allMatches,
                    tier = tier,
                    tournament = newTournament,
                    filter = state.selectedFilter,
                    query = state.searchQuery,
                    followedIds = state.followedMatchIds
                )
            )
        }
    }

    fun setFilter(filter: MatchFilter) {
        _uiState.update { state ->
            state.copy(
                selectedFilter = filter,
                filteredMatches = applyFilterAndSearch(
                    matches = state.allMatches,
                    tier = state.selectedTierFilter,
                    tournament = state.currentTournament,
                    filter = filter,
                    query = state.searchQuery,
                    followedIds = state.followedMatchIds
                )
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
        tier: TourTierFilter = _uiState.value.selectedTierFilter,
        tournament: Tournament = _uiState.value.currentTournament,
        filter: MatchFilter = _uiState.value.selectedFilter,
        query: String = _uiState.value.searchQuery,
        followedIds: Set<String> = _uiState.value.followedMatchIds
    ): List<Match> {
        // Stage 1: Tour tier filter
        val tierFiltered = if (tier == TourTierFilter.ALL) {
            matches
        } else {
            matches.filter { match -> tier.matches(match.tournament, match) }
        }

        // Stage 2: Contextual tournament filter
        val tournamentFiltered = if (tournament.id.equals("all", ignoreCase = true) || tournament.id.startsWith("all_")) {
            tierFiltered
        } else {
            tierFiltered.filter { match ->
                tournament.matches(match.tournamentName) ||
                    match.tournament?.matches(tournament.displayName) == true ||
                    match.tournament?.id?.equals(tournament.id, ignoreCase = true) == true
            }
        }

        // Stage 3: Match status filter
        val statusFiltered = when (filter) {
            MatchFilter.ALL -> tournamentFiltered
            MatchFilter.LIVE -> tournamentFiltered.filter { it.state == MatchState.LIVE }
            MatchFilter.FOLLOWED -> tournamentFiltered.filter { it.id in followedIds }
            MatchFilter.UPCOMING -> tournamentFiltered.filter { it.state == MatchState.UPCOMING }
            MatchFilter.FINISHED -> tournamentFiltered.filter { it.state == MatchState.FINISHED }
        }

        // Stage 4: Search query filter
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return statusFiltered
        }

        return statusFiltered.filter { match ->
            matchMatchesQuery(match, trimmed)
        }
    }

    private fun applyFilterAndSearch(
        matches: List<Match>,
        filter: MatchFilter,
        query: String,
        followedIds: Set<String>
    ): List<Match> = applyFilterAndSearch(
        matches = matches,
        tier = _uiState.value.selectedTierFilter,
        tournament = _uiState.value.currentTournament,
        filter = filter,
        query = query,
        followedIds = followedIds
    )

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
            match.statusDetail.lowercase().contains(q) ||
            match.tournamentName.lowercase().contains(q) ||
            (match.tournament?.displayName?.lowercase()?.contains(q) == true) ||
            (match.tournament?.aliases?.any { it.lowercase().contains(q) } == true)
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
            com.tennis.tracker.ui.widget.WidgetAutoSyncManager.recordSyncResult(context, true)
            com.tennis.tracker.ui.widget.FollowedMatchesWidgetProvider.updateAllWidgets(context)
        } catch (_: Throwable) {
            // Safe fallback during tests or prior to context attachment
        }
    }

    companion object {
        fun getTierAllTournament(tier: TourTierFilter): Tournament {
            return when (tier) {
                TourTierFilter.ALL -> Tournaments.ALL
                TourTierFilter.GRAND_SLAMS -> Tournament(
                    id = "all_grand_slams",
                    displayName = "All Grand Slams",
                    aliases = setOf("All Grand Slams", "All Majors"),
                    isGrandSlam = true,
                    category = TournamentCategory.GRAND_SLAM,
                    tour = TourType.COMBINED
                )
                TourTierFilter.ATP_TOUR -> Tournament(
                    id = "all_atp",
                    displayName = "All ATP Tour",
                    aliases = setOf("All ATP", "All ATP Tour"),
                    tour = TourType.ATP
                )
                TourTierFilter.WTA_TOUR -> Tournament(
                    id = "all_wta",
                    displayName = "All WTA Tour",
                    aliases = setOf("All WTA", "All WTA Tour"),
                    tour = TourType.WTA
                )
                TourTierFilter.MASTERS_1000 -> Tournament(
                    id = "all_1000s",
                    displayName = "All 1000s",
                    aliases = setOf("All 1000s", "All Masters 1000"),
                    category = TournamentCategory.MASTERS_1000,
                    tour = TourType.COMBINED
                )
                TourTierFilter.TIER_500_250 -> Tournament(
                    id = "all_500_250",
                    displayName = "All 500s/250s",
                    aliases = setOf("All 500s/250s", "All 500s", "All 250s"),
                    category = TournamentCategory.ATP_500,
                    tour = TourType.COMBINED
                )
            }
        }

        fun computeContextualTournaments(
            matches: List<Match>,
            tier: TourTierFilter,
            selectedTournament: Tournament? = null
        ): List<Tournament> {
            val tierAllOption = getTierAllTournament(tier)
            val matchesInTier = if (tier == TourTierFilter.ALL) {
                matches
            } else {
                matches.filter { tier.matches(it.tournament, it) }
            }

            val activeTournaments = matchesInTier.mapNotNull { match ->
                match.tournament ?: TournamentResolver.resolve(match.tournamentName, match.eventGrouping)
            }.filterNot {
                it.id.equals("all", ignoreCase = true) || it.id.startsWith("all_")
            }.distinctBy {
                it.id.lowercase()
            }

            val canonicalDefaults = when (tier) {
                TourTierFilter.ALL -> Tournaments.POPULAR_TOURNAMENTS.filterNot { it.id == "all" }
                TourTierFilter.GRAND_SLAMS -> Tournaments.GRAND_SLAMS
                TourTierFilter.MASTERS_1000 -> Tournaments.MASTERS_1000_LIST
                TourTierFilter.ATP_TOUR -> Tournaments.ALL_CANONICAL_TOURNAMENTS.filter { it.tour.isAtp || it.tour == TourType.COMBINED }
                TourTierFilter.WTA_TOUR -> Tournaments.ALL_CANONICAL_TOURNAMENTS.filter { it.tour.isWta || it.tour == TourType.COMBINED }
                TourTierFilter.TIER_500_250 -> (Tournaments.ATP_500_LIST + Tournaments.WTA_500_LIST + Tournaments.ATP_250_LIST + Tournaments.WTA_250_LIST)
            }

            val result = if (activeTournaments.isNotEmpty()) {
                listOf(tierAllOption) + activeTournaments
            } else {
                listOf(tierAllOption) + canonicalDefaults
            }

            if (selectedTournament != null && !selectedTournament.id.startsWith("all") && result.none { it.id.equals(selectedTournament.id, ignoreCase = true) }) {
                return result + selectedTournament
            }

            return result
        }

        val inMemoryFallback: SettingsRepository by lazy { InMemorySettingsRepository() }

        @Volatile
        var defaultSettingsRepositoryInstance: SettingsRepository? = null

        fun defaultSettingsRepository(): SettingsRepository {
            defaultSettingsRepositoryInstance?.let { return it }
            val ctx = ScoreboardRepositoryImpl.appContext
            if (ctx != null) {
                try {
                    return SettingsRepositoryImpl.getInstance(ctx)
                } catch (_: Throwable) {}
            }
            return inMemoryFallback
        }
    }
}

private class InMemorySettingsRepository(
    initialSettings: AppSettings = AppSettings.DEFAULT
) : SettingsRepository {
    private val _settings = MutableStateFlow(initialSettings)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    override fun getSettings(): AppSettings = _settings.value

    override suspend fun updateAutoRefreshInterval(interval: AutoRefreshInterval) {
        _settings.value = _settings.value.copy(autoRefreshInterval = interval)
    }

    override suspend fun updateMatchRetention(policy: MatchRetentionPolicy) {
        _settings.value = _settings.value.copy(matchRetention = policy)
    }

    override suspend fun updateWidgetSyncMode(mode: WidgetSyncMode) {
        _settings.value = _settings.value.copy(widgetSyncMode = mode)
    }

    override suspend fun updateDefaultTournament(tier: StartupTournamentTier) {
        _settings.value = _settings.value.copy(defaultTournament = tier)
    }

    override suspend fun updateTelemetryEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(telemetryEnabled = enabled)
    }

    override suspend fun updateTelemetryEndpoint(endpoint: String) {
        _settings.value = _settings.value.copy(telemetryEndpoint = endpoint)
    }

    override suspend fun resetToDefaults() {
        _settings.value = AppSettings.DEFAULT
    }
}


