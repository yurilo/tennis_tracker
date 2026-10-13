package com.tennis.tracker.challenger

import com.tennis.tracker.data.repository.H2HRepository
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.MatchStatistics
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.TournamentResolver
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.domain.repository.ScoreboardRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Challenger 2 Dual-Draw Combined Tournament Filtering Stress Test Suite.
 *
 * Stresses dual-draw combined tournament logic (e.g. China Open with Men's ATP 500
 * and Women's WTA 1000) under all UI filter permutations, checking:
 * 1. Filter evaluation on production TourTierFilter.
 * 2. Substring collision probe: Does "Women's Singles" erroneously match ATP_TOUR?
 * 3. Combined event leakage: Do Men's Grand Slam matches leak into WTA_TOUR?
 * 4. Multi-stage filtering in ScoreboardViewModel under dual-draw feeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualDrawFilterPermutationsTest {

    private val testDispatcher = StandardTestDispatcher()

    private fun createMatch(
        id: String,
        tournamentName: String,
        eventGrouping: String,
        p1Name: String,
        p2Name: String,
        tournament: Tournament? = null
    ): Match {
        val resolved = tournament ?: TournamentResolver.resolve(tournamentName, eventGrouping)
        return Match(
            id = id,
            tournamentName = tournamentName,
            eventGrouping = eventGrouping,
            roundName = "Quarter-Final",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = Competitor(id = "${id}_p1", name = p1Name, isServing = true, setScores = listOf(6, 4)),
            player2 = Competitor(id = "${id}_p2", name = p2Name, isServing = false, setScores = listOf(4, 6)),
            venue = resolved.defaultVenue,
            tournament = resolved,
            tour = resolved.tour,
            category = resolved.category,
            surface = resolved.surface
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // Test 1: China Open Dual-Draw Classification & Filter Permutations
    // =========================================================================
    @Test
    fun testChinaOpen_dualDrawFilterPermutations() {
        val mensMatch = createMatch(
            id = "bj_m1",
            tournamentName = "China Open",
            eventGrouping = "Men's Singles",
            p1Name = "Carlos Alcaraz",
            p2Name = "Jannik Sinner"
        )
        val womensMatch = createMatch(
            id = "bj_w1",
            tournamentName = "China Open",
            eventGrouping = "Women's Singles",
            p1Name = "Aryna Sabalenka",
            p2Name = "Coco Gauff"
        )

        // Verify domain models
        assertEquals(TourType.ATP, mensMatch.effectiveTour)
        assertEquals(TournamentCategory.ATP_500, mensMatch.effectiveCategory)
        assertEquals(TourType.WTA, womensMatch.effectiveTour)
        assertEquals(TournamentCategory.WTA_1000, womensMatch.effectiveCategory)

        // 1. ALL Filter
        assertTrue("ALL matches Men's", TourTierFilter.ALL.matches(mensMatch))
        assertTrue("ALL matches Women's", TourTierFilter.ALL.matches(womensMatch))

        // 2. GRAND_SLAMS Filter
        assertFalse("Grand Slams does not match Men's 500", TourTierFilter.GRAND_SLAMS.matches(mensMatch))
        assertFalse("Grand Slams does not match Women's 1000", TourTierFilter.GRAND_SLAMS.matches(womensMatch))

        // 3. MASTERS_1000 Filter
        assertFalse("1000s must NOT match Men's (ATP 500)", TourTierFilter.MASTERS_1000.matches(mensMatch))
        assertTrue("1000s MUST match Women's (WTA 1000)", TourTierFilter.MASTERS_1000.matches(womensMatch))

        // 4. TIER_500_250 Filter
        assertTrue("500s/250s MUST match Men's (ATP 500)", TourTierFilter.TIER_500_250.matches(mensMatch))
        assertFalse("500s/250s must NOT match Women's (WTA 1000)", TourTierFilter.TIER_500_250.matches(womensMatch))

        // 5. WTA_TOUR Filter
        assertTrue("WTA Tour must match Women's match", TourTierFilter.WTA_TOUR.matches(womensMatch))
        assertFalse("WTA Tour must NOT match Men's match", TourTierFilter.WTA_TOUR.matches(mensMatch))

        // 6. ATP_TOUR Filter - EMPIRICAL PROBE FOR SUBSTRING BUG
        assertTrue("ATP Tour must match Men's match", TourTierFilter.ATP_TOUR.matches(mensMatch))
        val atpMatchesWomen = TourTierFilter.ATP_TOUR.matches(womensMatch)
        println("EMPIRICAL PROBE: TourTierFilter.ATP_TOUR.matches(womensMatch) = $atpMatchesWomen")
    }

    // =========================================================================
    // Test 2: Substring Collision Bug Probe ("Women's Singles" contains "men")
    // =========================================================================
    @Test
    fun testSubstringCollision_womensSinglesMatchingAtpTour() {
        val wtaMatch = createMatch(
            id = "wta_charleston_1",
            tournamentName = "Charleston Open",
            eventGrouping = "Women's Singles",
            p1Name = "Jessica Pegula",
            p2Name = "Ons Jabeur",
            tournament = Tournaments.CHARLESTON_OPEN // pure WTA 500, tour = TourType.WTA
        )

        assertEquals(TourType.WTA, wtaMatch.effectiveTour)
        assertTrue(wtaMatch.eventGrouping.contains("men", ignoreCase = true)) // "Women's Singles" contains "men"

        val matchesAtp = TourTierFilter.ATP_TOUR.matches(wtaMatch)
        println("SUBSTRING PROBE: Charleston WTA match matching ATP_TOUR = $matchesAtp")
        // In a bug-free implementation, a pure WTA match with eventGrouping "Women's Singles" MUST NOT match ATP_TOUR!
    }

    // =========================================================================
    // Test 3: Combined Event Leakage Probe (US Open Men's matching WTA Tour)
    // =========================================================================
    @Test
    fun testCombinedTourLeakage_mensMatchMatchingWtaTour() {
        val usOpenMen = createMatch(
            id = "us_men_1",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            p1Name = "Carlos Alcaraz",
            p2Name = "Novak Djokovic",
            tournament = Tournaments.US_OPEN // tour = TourType.COMBINED
        )

        assertEquals(TourType.COMBINED, usOpenMen.effectiveTour)
        val matchesWta = TourTierFilter.WTA_TOUR.matches(usOpenMen)
        println("COMBINED LEAKAGE PROBE: US Open Men's Singles matching WTA_TOUR = $matchesWta")
        // If tour is COMBINED and tour.isWta is true, US Open Men's Singles matches WTA Tour!
    }

    // =========================================================================
    // Test 4: ScoreboardViewModel Multi-Stage Filtering with Dual-Draw Feed
    // =========================================================================
    @Test
    fun testScoreboardViewModel_dualDrawFiltering() {
        val mensMatch = createMatch(
            id = "bj_m1",
            tournamentName = "China Open",
            eventGrouping = "Men's Singles",
            p1Name = "Carlos Alcaraz",
            p2Name = "Jannik Sinner"
        )
        val womensMatch = createMatch(
            id = "bj_w1",
            tournamentName = "China Open",
            eventGrouping = "Women's Singles",
            p1Name = "Aryna Sabalenka",
            p2Name = "Coco Gauff"
        )

        val feed = listOf(mensMatch, womensMatch)

        val repo = object : ScoreboardRepository {
            private var currentTour = Tournaments.ALL
            override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> = Result.success(feed)
            override fun getOfflineScoreboard(): List<Match> = feed
            override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> = Result.failure(UnsupportedOperationException())
            override fun getActiveTournament(): Tournament = currentTour
            override fun setActiveTournament(tournament: Tournament) { currentTour = tournament }
        }

        val viewModel = ScoreboardViewModel(
            repository = repo,
            h2hRepository = H2HRepository(),
            enableAutoRefresh = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Initial state: ALL filter
        assertEquals(2, viewModel.uiState.value.filteredMatches.size)

        // Select 1000s: should show Women's only
        viewModel.selectTierFilter(TourTierFilter.MASTERS_1000)
        val state1000 = viewModel.uiState.value
        println("1000s FILTERED COUNT: ${state1000.filteredMatches.size}")
        for (m in state1000.filteredMatches) {
            println("  1000s Match: ${m.id} (${m.eventGrouping})")
        }

        // Select 500s/250s: should show Men's only
        viewModel.selectTierFilter(TourTierFilter.TIER_500_250)
        val state500 = viewModel.uiState.value
        println("500s FILTERED COUNT: ${state500.filteredMatches.size}")
        for (m in state500.filteredMatches) {
            println("  500s Match: ${m.id} (${m.eventGrouping})")
        }

        // Select WTA Tour: should show Women's only
        viewModel.selectTierFilter(TourTierFilter.WTA_TOUR)
        val stateWta = viewModel.uiState.value
        println("WTA FILTERED COUNT: ${stateWta.filteredMatches.size}")
        for (m in stateWta.filteredMatches) {
            println("  WTA Match: ${m.id} (${m.eventGrouping})")
        }

        // Select ATP Tour: should show Men's only
        viewModel.selectTierFilter(TourTierFilter.ATP_TOUR)
        val stateAtp = viewModel.uiState.value
        println("ATP FILTERED COUNT: ${stateAtp.filteredMatches.size}")
        for (m in stateAtp.filteredMatches) {
            println("  ATP Match: ${m.id} (${m.eventGrouping})")
        }
    }
}
