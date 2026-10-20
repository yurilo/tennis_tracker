package com.tennis.tracker.ui

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
import com.tennis.tracker.ui.viewmodel.MatchTypeFilter
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

@OptIn(ExperimentalCoroutinesApi::class)
class MatchTypeFilterTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val barcelonaAtpTournament = Tournament(
        id = "barcelona",
        displayName = "Barcelona Open",
        aliases = setOf("Barcelona Open", "Barcelona"),
        category = TournamentCategory.ATP_500,
        tour = TourType.ATP,
        surface = CourtSurface.CLAY
    )

    private fun createMatch(
        id: String,
        player1Name: String = "Player One",
        player2Name: String = "Player Two",
        eventGrouping: String = "Men's Singles",
        tournament: Tournament = Tournaments.US_OPEN,
        tour: TourType = tournament.tour,
        state: MatchState = MatchState.LIVE
    ): Match {
        return Match(
            id = id,
            tournamentName = tournament.displayName,
            eventGrouping = eventGrouping,
            roundName = "Quarterfinal",
            state = state,
            statusDetail = "In Progress",
            player1 = Competitor(id = "${id}_p1", name = player1Name),
            player2 = Competitor(id = "${id}_p2", name = player2Name),
            venue = "USTA Billie Jean King National Tennis Center",
            court = "Arthur Ashe Stadium",
            startTime = "2026-09-01T16:00Z",
            tournament = tournament,
            tour = tour,
            category = tournament.category,
            surface = tournament.surface
        )
    }

    @Test
    fun `MatchTypeFilter matches Men's Singles in Combined Grand Slam`() {
        val match = createMatch(
            id = "m1",
            player1Name = "Carlos Alcaraz",
            player2Name = "Jannik Sinner",
            eventGrouping = "Men's Singles",
            tournament = Tournaments.US_OPEN
        )

        assertTrue(MatchTypeFilter.ALL.matches(match))
        assertTrue(MatchTypeFilter.ATP.matches(match))
        assertFalse(MatchTypeFilter.WTA.matches(match))
        assertTrue(MatchTypeFilter.SINGLES.matches(match))
        assertFalse(MatchTypeFilter.DOUBLES.matches(match))
    }

    @Test
    fun `MatchTypeFilter matches Women's Singles in Combined Grand Slam`() {
        val match = createMatch(
            id = "m2",
            player1Name = "Aryna Sabalenka",
            player2Name = "Iga Swiatek",
            eventGrouping = "Women's Singles",
            tournament = Tournaments.US_OPEN
        )

        assertTrue(MatchTypeFilter.ALL.matches(match))
        assertFalse(MatchTypeFilter.ATP.matches(match))
        assertTrue(MatchTypeFilter.WTA.matches(match))
        assertTrue(MatchTypeFilter.SINGLES.matches(match))
        assertFalse(MatchTypeFilter.DOUBLES.matches(match))
    }

    @Test
    fun `MatchTypeFilter matches Men's Doubles and Women's Doubles accurately`() {
        val mensDoubles = createMatch(
            id = "m3",
            player1Name = "R. Ram / J. Salisbury",
            player2Name = "M. Arévalo / M. Pavić",
            eventGrouping = "Men's Doubles",
            tournament = Tournaments.US_OPEN
        )
        val womensDoubles = createMatch(
            id = "m4",
            player1Name = "C. Gauff / J. Pegula",
            player2Name = "G. Dabrowski / E. Routliffe",
            eventGrouping = "Women's Doubles",
            tournament = Tournaments.US_OPEN
        )

        assertTrue(MatchTypeFilter.ATP.matches(mensDoubles))
        assertFalse(MatchTypeFilter.WTA.matches(mensDoubles))
        assertFalse(MatchTypeFilter.SINGLES.matches(mensDoubles))
        assertTrue(MatchTypeFilter.DOUBLES.matches(mensDoubles))

        assertFalse(MatchTypeFilter.ATP.matches(womensDoubles))
        assertTrue(MatchTypeFilter.WTA.matches(womensDoubles))
        assertFalse(MatchTypeFilter.SINGLES.matches(womensDoubles))
        assertTrue(MatchTypeFilter.DOUBLES.matches(womensDoubles))
    }

    @Test
    fun `MatchTypeFilter matches Mixed Doubles under DOUBLES and ALL but excludes from ATP and WTA`() {
        val mixedDoubles = createMatch(
            id = "m5",
            player1Name = "S. Errani / A. Vavassori",
            player2Name = "T. Townsend / D. Young",
            eventGrouping = "Mixed Doubles",
            tournament = Tournaments.US_OPEN
        )

        assertTrue(MatchTypeFilter.ALL.matches(mixedDoubles))
        assertFalse(MatchTypeFilter.ATP.matches(mixedDoubles))
        assertFalse(MatchTypeFilter.WTA.matches(mixedDoubles))
        assertFalse(MatchTypeFilter.SINGLES.matches(mixedDoubles))
        assertTrue(MatchTypeFilter.DOUBLES.matches(mixedDoubles))
    }

    @Test
    fun `MatchTypeFilter detects doubles from Competitor slash name even with generic eventGrouping`() {
        val doublesWithGenericGrouping = createMatch(
            id = "m6",
            player1Name = "M. Granollers / H. Zeballos",
            player2Name = "S. Bolelli / A. Vavassori",
            eventGrouping = "ATP Tour",
            tournament = barcelonaAtpTournament,
            tour = TourType.ATP
        )

        assertTrue(MatchTypeFilter.ATP.matches(doublesWithGenericGrouping))
        assertFalse(MatchTypeFilter.SINGLES.matches(doublesWithGenericGrouping))
        assertTrue(MatchTypeFilter.DOUBLES.matches(doublesWithGenericGrouping))
    }

    @Test
    fun `ScoreboardViewModel filters matches by MatchTypeFilter and resolves tier conflicts`() {
        val sampleMatches = listOf(
            createMatch("atp_s", "Carlos Alcaraz", "Novak Djokovic", "Men's Singles", Tournaments.US_OPEN, state = MatchState.LIVE),
            createMatch("wta_s", "Aryna Sabalenka", "Coco Gauff", "Women's Singles", Tournaments.US_OPEN, state = MatchState.LIVE),
            createMatch("atp_d", "R. Ram / J. Salisbury", "W. Koolhof / N. Mektic", "Men's Doubles", Tournaments.US_OPEN, state = MatchState.UPCOMING),
            createMatch("wta_d", "S. Hsieh / E. Mertens", "K. Siniakova / T. Townsend", "Women's Doubles", Tournaments.US_OPEN, state = MatchState.FINISHED),
            createMatch("mix_d", "S. Errani / A. Vavassori", "T. Townsend / D. Young", "Mixed Doubles", Tournaments.US_OPEN, state = MatchState.FINISHED)
        )

        val fakeRepo = object : ScoreboardRepository {
            private var activeTournament: Tournament = Tournaments.ALL
            override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> = Result.success(sampleMatches)
            override fun getOfflineScoreboard(): List<Match> = sampleMatches
            override suspend fun getMatchStatistics(match: Match): Result<MatchStatistics> = Result.failure(UnsupportedOperationException())
            override fun setActiveTournament(tournament: Tournament) { activeTournament = tournament }
            override fun getActiveTournament(): Tournament = activeTournament
        }

        val viewModel = ScoreboardViewModel(
            repository = fakeRepo,
            enableAutoRefresh = false
        )

        try {
            // Initially ALL (5 matches)
            assertEquals(MatchTypeFilter.ALL, viewModel.uiState.value.selectedMatchTypeFilter)
            assertEquals(5, viewModel.uiState.value.filteredMatches.size)

            // Filter by ATP -> Men's Singles + Men's Doubles (2 matches)
            viewModel.selectMatchTypeFilter(MatchTypeFilter.ATP)
            assertEquals(MatchTypeFilter.ATP, viewModel.uiState.value.selectedMatchTypeFilter)
            assertEquals(listOf("atp_s", "atp_d"), viewModel.uiState.value.filteredMatches.map { it.id })

            // Filter by WTA -> Women's Singles + Women's Doubles (2 matches)
            viewModel.selectMatchTypeFilter(MatchTypeFilter.WTA)
            assertEquals(MatchTypeFilter.WTA, viewModel.uiState.value.selectedMatchTypeFilter)
            assertEquals(listOf("wta_s", "wta_d"), viewModel.uiState.value.filteredMatches.map { it.id })

            // Selecting ATP_TOUR tier while WTA match type is active resets MatchTypeFilter to ALL
            viewModel.selectTierFilter(TourTierFilter.ATP_TOUR)
            assertEquals(MatchTypeFilter.ALL, viewModel.uiState.value.selectedMatchTypeFilter)

            // Reset tier to ALL, select DOUBLES -> Men's Doubles, Women's Doubles, Mixed Doubles (3 matches)
            viewModel.selectTierFilter(TourTierFilter.ALL)
            viewModel.selectMatchTypeFilter(MatchTypeFilter.DOUBLES)
            assertEquals(listOf("atp_d", "wta_d", "mix_d"), viewModel.uiState.value.filteredMatches.map { it.id })

            // Switching tier between ATP_TOUR and WTA_TOUR preserves DOUBLES (including Mixed Doubles in combined slams)
            viewModel.selectTierFilter(TourTierFilter.ATP_TOUR)
            assertEquals(MatchTypeFilter.DOUBLES, viewModel.uiState.value.selectedMatchTypeFilter)
            assertEquals(listOf("atp_d", "mix_d"), viewModel.uiState.value.filteredMatches.map { it.id })

            viewModel.selectTierFilter(TourTierFilter.WTA_TOUR)
            assertEquals(MatchTypeFilter.DOUBLES, viewModel.uiState.value.selectedMatchTypeFilter)
            assertEquals(listOf("wta_d", "mix_d"), viewModel.uiState.value.filteredMatches.map { it.id })

            // Filter by SINGLES across ALL tiers -> Men's Singles + Women's Singles (2 matches)
            viewModel.selectTierFilter(TourTierFilter.ALL)
            viewModel.selectMatchTypeFilter(MatchTypeFilter.SINGLES)
            assertEquals(listOf("atp_s", "wta_s"), viewModel.uiState.value.filteredMatches.map { it.id })

            // Combine SINGLES + LIVE + search query
            viewModel.setFilter(MatchFilter.LIVE)
            viewModel.onSearchQueryChange("Sabalenka")
            assertEquals(listOf("wta_s"), viewModel.uiState.value.filteredMatches.map { it.id })
        } finally {
            viewModel.cleanup()
        }
    }
}
