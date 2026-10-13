package com.tennis.tracker.ui

import com.tennis.tracker.data.repository.H2HRepository
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.repository.ScoreboardRepository
import com.tennis.tracker.ui.viewmodel.MatchFilter
import com.tennis.tracker.ui.viewmodel.ScoreboardViewModel
import com.tennis.tracker.ui.viewmodel.TourTierFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dedicated unit test suite verifying tiered filter chips, contextual tournament resolution,
 * and the 4-stage filtering pipeline in ScoreboardViewModel (Requirement R2 & R4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TieredFilterViewModelTest {

    @get:org.junit.Rule
    val globalTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(10)

    private val testDispatcher = StandardTestDispatcher()

    private val indianWellsTournament = Tournament(
        id = "indian_wells",
        displayName = "BNP Paribas Open",
        aliases = setOf("Indian Wells", "BNP Paribas Open"),
        defaultVenue = "Indian Wells Tennis Garden",
        isGrandSlam = false,
        tour = TourType.COMBINED,
        category = TournamentCategory.MASTERS_1000,
        surface = CourtSurface.HARD
    )

    private val barcelonaTournament = Tournament(
        id = "barcelona_open",
        displayName = "Barcelona Open",
        aliases = setOf("Barcelona Open"),
        defaultVenue = "Real Club de Tenis Barcelona",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_500,
        surface = CourtSurface.CLAY
    )

    private val brisbaneTournament = Tournament(
        id = "brisbane_international",
        displayName = "Brisbane International",
        aliases = setOf("Brisbane International"),
        defaultVenue = "Queensland Tennis Centre",
        isGrandSlam = false,
        tour = TourType.ATP,
        category = TournamentCategory.ATP_250,
        surface = CourtSurface.HARD
    )

    private val sampleMatches = listOf(
        // Match 1: Grand Slam (US Open) - Live
        createSampleMatch(
            id = "m_gs_1",
            tournament = Tournaments.US_OPEN,
            state = MatchState.LIVE,
            p1Name = "Carlos Alcaraz",
            p2Name = "Novak Djokovic",
            eventGrouping = "Men's Singles"
        ),
        // Match 2: Masters 1000 (Indian Wells) - Upcoming
        createSampleMatch(
            id = "m_1000_1",
            tournament = indianWellsTournament,
            state = MatchState.UPCOMING,
            p1Name = "Jannik Sinner",
            p2Name = "Daniil Medvedev",
            eventGrouping = "Men's Singles"
        ),
        // Match 3: ATP 500 (Barcelona Open) - Finished
        createSampleMatch(
            id = "m_500_1",
            tournament = barcelonaTournament,
            state = MatchState.FINISHED,
            p1Name = "Stefanos Tsitsipas",
            p2Name = "Casper Ruud",
            eventGrouping = "Men's Singles"
        ),
        // Match 4: WTA 1000 (China Open Women's) - Live
        createSampleMatch(
            id = "m_wta_1",
            tournament = Tournament(
                id = "china_open",
                displayName = "China Open",
                aliases = setOf("China Open"),
                defaultVenue = "National Tennis Center",
                isGrandSlam = false,
                tour = TourType.WTA,
                category = TournamentCategory.WTA_1000,
                surface = CourtSurface.HARD
            ),
            state = MatchState.LIVE,
            p1Name = "Aryna Sabalenka",
            p2Name = "Iga Swiatek",
            eventGrouping = "Ladies Singles"
        ),
        // Match 5: ATP 250 (Brisbane) - Finished
        createSampleMatch(
            id = "m_250_1",
            tournament = brisbaneTournament,
            state = MatchState.FINISHED,
            p1Name = "Grigor Dimitrov",
            p2Name = "Holger Rune",
            eventGrouping = "Men's Singles"
        )
    )

    private val testRepository = object : ScoreboardRepository {
        var activeTour: Tournament = Tournaments.ALL

        override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
            return Result.success(sampleMatches)
        }
        override fun getOfflineScoreboard(): List<Match> = sampleMatches
        override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> {
            return Result.failure(UnsupportedOperationException())
        }
        override fun getActiveTournament(): Tournament = activeTour
        override fun setActiveTournament(tournament: Tournament) {
            activeTour = tournament
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // 1. TourTierFilter Enum & Matching Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTourTierFilter_enumProperties() {
        assertEquals("All", TourTierFilter.ALL.displayName)
        assertEquals("🎾", TourTierFilter.ALL.icon)

        assertEquals("Grand Slams", TourTierFilter.GRAND_SLAMS.displayName)
        assertEquals("🏆", TourTierFilter.GRAND_SLAMS.icon)

        assertEquals("ATP Tour", TourTierFilter.ATP_TOUR.displayName)
        assertEquals("🔷", TourTierFilter.ATP_TOUR.icon)

        assertEquals("WTA Tour", TourTierFilter.WTA_TOUR.displayName)
        assertEquals("🟣", TourTierFilter.WTA_TOUR.icon)

        assertEquals("1000s", TourTierFilter.MASTERS_1000.displayName)
        assertEquals("⭐", TourTierFilter.MASTERS_1000.icon)

        assertEquals("500s/250s", TourTierFilter.TIER_500_250.displayName)
        assertEquals("⚡", TourTierFilter.TIER_500_250.icon)
    }

    @Test
    fun testTourTierFilter_matchingLogic() {
        val gsMatch = sampleMatches[0]
        val m1000Match = sampleMatches[1]
        val a500Match = sampleMatches[2]
        val w1000Match = sampleMatches[3]
        val a250Match = sampleMatches[4]

        // ALL matches everything
        assertTrue(TourTierFilter.ALL.matches(gsMatch))
        assertTrue(TourTierFilter.ALL.matches(m1000Match))
        assertTrue(TourTierFilter.ALL.matches(a500Match))
        assertTrue(TourTierFilter.ALL.matches(w1000Match))
        assertTrue(TourTierFilter.ALL.matches(a250Match))

        // GRAND_SLAMS matches GS only
        assertTrue(TourTierFilter.GRAND_SLAMS.matches(gsMatch))
        assertFalse(TourTierFilter.GRAND_SLAMS.matches(m1000Match))
        assertFalse(TourTierFilter.GRAND_SLAMS.matches(a500Match))
        assertFalse(TourTierFilter.GRAND_SLAMS.matches(w1000Match))

        // ATP_TOUR matches ATP & COMBINED men's
        assertTrue(TourTierFilter.ATP_TOUR.matches(gsMatch))
        assertTrue(TourTierFilter.ATP_TOUR.matches(m1000Match))
        assertTrue(TourTierFilter.ATP_TOUR.matches(a500Match))
        assertFalse(TourTierFilter.ATP_TOUR.matches(w1000Match))

        // WTA_TOUR matches WTA & COMBINED women's
        assertTrue(TourTierFilter.WTA_TOUR.matches(w1000Match))
        assertFalse(TourTierFilter.WTA_TOUR.matches(gsMatch))
        val wgsMatch = gsMatch.copy(id = "m_wgs_1", eventGrouping = "Women's Singles")
        assertTrue(TourTierFilter.WTA_TOUR.matches(wgsMatch))
        assertFalse(TourTierFilter.ATP_TOUR.matches(wgsMatch))
        assertFalse(TourTierFilter.WTA_TOUR.matches(a500Match))
        assertFalse(TourTierFilter.WTA_TOUR.matches(a250Match))

        // MASTERS_1000 matches 1000s
        assertTrue(TourTierFilter.MASTERS_1000.matches(m1000Match))
        assertTrue(TourTierFilter.MASTERS_1000.matches(w1000Match))
        assertFalse(TourTierFilter.MASTERS_1000.matches(gsMatch))
        assertFalse(TourTierFilter.MASTERS_1000.matches(a500Match))

        // TIER_500_250 matches 500s and 250s
        assertTrue(TourTierFilter.TIER_500_250.matches(a500Match))
        assertTrue(TourTierFilter.TIER_500_250.matches(a250Match))
        assertFalse(TourTierFilter.TIER_500_250.matches(m1000Match))
        assertFalse(TourTierFilter.TIER_500_250.matches(gsMatch))
    }

    // -------------------------------------------------------------------------
    // 2. ViewModel Initial State & Tier Selection Tests
    // -------------------------------------------------------------------------

    @Test
    fun testViewModel_initialStateHasAllTierFilter() {
        val viewModel = ScoreboardViewModel(
            repository = testRepository,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(TourTierFilter.ALL, state.selectedTierFilter)
        assertEquals(Tournaments.ALL.id, state.currentTournament.id)
        assertEquals(5, state.filteredMatches.size)
    }

    @Test
    fun testViewModel_selectTierFilter_grandSlams() {
        val viewModel = ScoreboardViewModel(
            repository = testRepository,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectTierFilter(TourTierFilter.GRAND_SLAMS)
        val state = viewModel.uiState.value

        assertEquals(TourTierFilter.GRAND_SLAMS, state.selectedTierFilter)
        assertEquals("all_grand_slams", state.currentTournament.id)
        assertEquals("All Grand Slams", state.currentTournament.displayName)
        assertEquals(1, state.filteredMatches.size)
        assertEquals("m_gs_1", state.filteredMatches[0].id)

        // Contextual tournaments must have "All Grand Slams" prepended
        assertTrue(state.contextualTournaments.isNotEmpty())
        assertEquals("all_grand_slams", state.contextualTournaments[0].id)
    }

    @Test
    fun testViewModel_selectTierFilter_masters1000() {
        val viewModel = ScoreboardViewModel(
            repository = testRepository,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectTierFilter(TourTierFilter.MASTERS_1000)
        val state = viewModel.uiState.value

        assertEquals(TourTierFilter.MASTERS_1000, state.selectedTierFilter)
        assertEquals("all_1000s", state.currentTournament.id)
        // Matches should contain Indian Wells (m_1000_1) and China Open WTA (m_wta_1)
        assertEquals(2, state.filteredMatches.size)
        assertTrue(state.filteredMatches.any { it.id == "m_1000_1" })
        assertTrue(state.filteredMatches.any { it.id == "m_wta_1" })

        // Contextual tournaments must include All 1000s, Indian Wells, and China Open
        val tourIds = state.contextualTournaments.map { it.id }
        assertTrue(tourIds.contains("all_1000s"))
        assertTrue(tourIds.contains("indian_wells"))
        assertTrue(tourIds.contains("china_open"))
    }

    @Test
    fun testViewModel_selectTierFilter_tier500And250() {
        val viewModel = ScoreboardViewModel(
            repository = testRepository,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.selectTierFilter(TourTierFilter.TIER_500_250)
        val state = viewModel.uiState.value

        assertEquals(TourTierFilter.TIER_500_250, state.selectedTierFilter)
        assertEquals("all_500_250", state.currentTournament.id)
        // Matches should contain Barcelona (m_500_1) and Brisbane (m_250_1)
        assertEquals(2, state.filteredMatches.size)
        assertTrue(state.filteredMatches.any { it.id == "m_500_1" })
        assertTrue(state.filteredMatches.any { it.id == "m_250_1" })
    }

    @Test
    fun testViewModel_selectTierFilter_atpAndWta() {
        val viewModel = ScoreboardViewModel(
            repository = testRepository,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // WTA Tour - filters to WTA matches, excluding Men's matches from combined tournaments
        viewModel.selectTierFilter(TourTierFilter.WTA_TOUR)
        val wtaState = viewModel.uiState.value
        assertEquals(TourTierFilter.WTA_TOUR, wtaState.selectedTierFilter)
        assertEquals("all_wta", wtaState.currentTournament.id)
        assertEquals(1, wtaState.filteredMatches.size)
        assertTrue(wtaState.filteredMatches.any { it.id == "m_wta_1" })
        assertFalse(wtaState.filteredMatches.any { it.id == "m_500_1" })
        assertFalse(wtaState.filteredMatches.any { it.id == "m_250_1" })
        assertFalse(wtaState.filteredMatches.any { it.id == "m_gs_1" })
        assertFalse(wtaState.filteredMatches.any { it.id == "m_1000_1" })

        // ATP Tour
        viewModel.selectTierFilter(TourTierFilter.ATP_TOUR)
        val atpState = viewModel.uiState.value
        assertEquals(TourTierFilter.ATP_TOUR, atpState.selectedTierFilter)
        assertEquals("all_atp", atpState.currentTournament.id)
        assertEquals(4, atpState.filteredMatches.size)
        assertFalse(atpState.filteredMatches.any { it.id == "m_wta_1" })
    }

    // -------------------------------------------------------------------------
    // 3. Multi-Stage Synchronous Filtering Pipeline Tests
    // -------------------------------------------------------------------------

    @Test
    fun testViewModel_4StageFilteringPipeline() {
        val viewModel = ScoreboardViewModel(
            repository = testRepository,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Stage 1: Tier filter -> MASTERS_1000 (2 matches: Indian Wells, China Open)
        viewModel.selectTierFilter(TourTierFilter.MASTERS_1000)
        assertEquals(2, viewModel.uiState.value.filteredMatches.size)

        // Stage 2: Specific Contextual Tournament -> Indian Wells (1 match: Jannik Sinner vs Daniil Medvedev)
        viewModel.selectTournament(indianWellsTournament)
        assertEquals(1, viewModel.uiState.value.filteredMatches.size)
        assertEquals("m_1000_1", viewModel.uiState.value.filteredMatches[0].id)

        // Stage 3: Match Status Filter -> UPCOMING (m_1000_1 is UPCOMING)
        viewModel.setFilter(MatchFilter.UPCOMING)
        assertEquals(1, viewModel.uiState.value.filteredMatches.size)

        // When switching to LIVE, should return empty because m_1000_1 is UPCOMING
        viewModel.setFilter(MatchFilter.LIVE)
        assertEquals(0, viewModel.uiState.value.filteredMatches.size)

        // Reset status filter to ALL
        viewModel.setFilter(MatchFilter.ALL)
        assertEquals(1, viewModel.uiState.value.filteredMatches.size)

        // Stage 4: Search Query Filter
        viewModel.onSearchQueryChange("Sinner")
        assertEquals(1, viewModel.uiState.value.filteredMatches.size)

        viewModel.onSearchQueryChange("Alcaraz") // Not in Indian Wells match
        assertEquals(0, viewModel.uiState.value.filteredMatches.size)

        // Clear query
        viewModel.clearSearchQuery()
        assertEquals(1, viewModel.uiState.value.filteredMatches.size)
    }

    @Test
    fun testViewModel_computeContextualTournaments_fallbackToCanonicalDefaults() {
        // When there are no live matches, computeContextualTournaments must return canonical defaults
        val emptyList = emptyList<Match>()

        val gsDefaults = ScoreboardViewModel.computeContextualTournaments(emptyList, TourTierFilter.GRAND_SLAMS)
        assertEquals("all_grand_slams", gsDefaults[0].id)
        assertTrue(gsDefaults.size > 1)
        assertTrue(gsDefaults.any { it.id == "us_open" })
        assertTrue(gsDefaults.any { it.id == "wimbledon" })

        val m1000Defaults = ScoreboardViewModel.computeContextualTournaments(emptyList, TourTierFilter.MASTERS_1000)
        assertEquals("all_1000s", m1000Defaults[0].id)
        assertTrue(m1000Defaults.size > 1)
        assertTrue(m1000Defaults.any { it.id == "indian_wells" })

        val atpDefaults = ScoreboardViewModel.computeContextualTournaments(emptyList, TourTierFilter.ATP_TOUR)
        assertEquals("all_atp", atpDefaults[0].id)
        assertTrue(atpDefaults.size > 1)

        val wtaDefaults = ScoreboardViewModel.computeContextualTournaments(emptyList, TourTierFilter.WTA_TOUR)
        assertEquals("all_wta", wtaDefaults[0].id)
        assertTrue(wtaDefaults.size > 1)
    }

    private fun createSampleMatch(
        id: String,
        tournament: Tournament,
        state: MatchState,
        p1Name: String,
        p2Name: String,
        eventGrouping: String
    ): Match {
        val p1 = Competitor(
            id = "${id}_p1",
            name = p1Name,
            seed = 1,
            country = "USA",
            isWinner = state == MatchState.FINISHED,
            setScores = if (state == MatchState.FINISHED) listOf(6, 6) else listOf(3, 2),
            currentGameScore = if (state == MatchState.LIVE) "30" else null,
            isServing = true
        )
        val p2 = Competitor(
            id = "${id}_p2",
            name = p2Name,
            seed = 2,
            country = "ESP",
            isWinner = false,
            setScores = if (state == MatchState.FINISHED) listOf(4, 3) else listOf(4, 1),
            currentGameScore = if (state == MatchState.LIVE) "15" else null,
            isServing = false
        )
        return Match(
            id = id,
            tournamentName = tournament.displayName,
            eventGrouping = eventGrouping,
            roundName = "Semi-Final",
            state = state,
            statusDetail = if (state == MatchState.FINISHED) "Final" else if (state == MatchState.LIVE) "In Progress" else "Upcoming",
            player1 = p1,
            player2 = p2,
            venue = tournament.defaultVenue,
            court = "Center Court",
            tournament = tournament,
            tour = tournament.tour,
            category = tournament.category,
            surface = tournament.surface
        )
    }
}
