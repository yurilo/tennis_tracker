package com.tennis.tracker.data

import com.tennis.tracker.data.local.MatchCacheManager
import com.tennis.tracker.data.remote.EspnScoreboardApi
import com.tennis.tracker.data.repository.ScoreboardRepositoryImpl
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.Tournaments
import com.tennis.tracker.ui.viewmodel.ScoreboardViewModel
import com.tennis.tracker.util.CoroutineTestRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TournamentFilteringTest {

    @get:Rule
    val coroutineTestRule = CoroutineTestRule()

    private val parser = EspnScoreboardApi()

    @Before
    fun setUp() {
        ScoreboardRepositoryImpl.appContext = null
        ScoreboardViewModel.defaultSettingsRepositoryInstance = null
        MatchCacheManager.clearCache(null)
    }

    @After
    fun tearDown() {
        ScoreboardRepositoryImpl.appContext = null
        ScoreboardViewModel.defaultSettingsRepositoryInstance = null
        MatchCacheManager.clearCache(null)
    }

    @Test
    fun `Tournament matches should match various naming formats and aliases`() {
        val usOpen = Tournaments.US_OPEN
        assertTrue(usOpen.matches("US Open"))
        assertTrue(usOpen.matches("U.S. Open"))
        assertTrue(usOpen.matches("us open"))
        assertTrue(usOpen.matches("US Open Tennis"))
        assertTrue(usOpen.matches("United States Open"))
        assertTrue(usOpen.matches("2026 US Open"))

        assertFalse(usOpen.matches("Cassis Challenger"))
        assertFalse(usOpen.matches("Wimbledon"))
        assertFalse(usOpen.matches("Australian Open"))
        assertFalse(usOpen.matches("Roland Garros"))
        assertFalse(usOpen.matches(""))
        assertFalse(usOpen.matches(null))

        val wimbledon = Tournaments.WIMBLEDON
        assertTrue(wimbledon.matches("Wimbledon"))
        assertTrue(wimbledon.matches("The Championships, Wimbledon"))
        assertFalse(wimbledon.matches("US Open"))

        val all = Tournaments.ALL
        assertTrue(all.matches("US Open"))
        assertTrue(all.matches("Wimbledon"))
        assertTrue(all.matches("Australian Open"))
        assertTrue(all.matches("Roland Garros"))
        assertTrue(all.matches("BNP Paribas Open"))
        assertTrue(all.matches("Any Tour Event"))
        assertFalse(all.matches(""))
        assertFalse(all.matches(null))
    }

    @Test
    fun `Competitor isTbdOrPlaceholder should correctly identify invalid placeholders`() {
        assertTrue(Competitor(id = "1", name = "TBD").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "tbd").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "To Be Determined").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "Competitor 2").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "Competitor").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "Unknown").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "BYE").isTbdOrPlaceholder)
        assertTrue(Competitor(id = "1", name = "   ").isTbdOrPlaceholder)

        assertFalse(Competitor(id = "1", name = "Carlos Alcaraz").isTbdOrPlaceholder)
        assertFalse(Competitor(id = "2", name = "Jannik Sinner").isTbdOrPlaceholder)
        assertFalse(Competitor(id = "3", name = "Coco Gauff").isTbdOrPlaceholder)
    }

    @Test
    fun `parseScoreboardJson should filter out non-US Open tournament events`() {
        val multiTournamentJson = """
        {
          "events": [
            {
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "101",
                      "status": { "type": { "state": "post", "completed": true, "description": "Final" } },
                      "competitors": [
                        { "id": "p1", "athlete": { "displayName": "Carlos Alcaraz" } },
                        { "id": "p2", "athlete": { "displayName": "Jannik Sinner" } }
                      ]
                    }
                  ]
                }
              ]
            },
            {
              "name": "Cassis Challenger",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "999",
                      "status": { "type": { "state": "post", "completed": true, "description": "Final" } },
                      "competitors": [
                        { "id": "c1", "athlete": { "displayName": "Challenger Player 1" } },
                        { "id": "c2", "athlete": { "displayName": "Challenger Player 2" } }
                      ]
                    }
                  ]
                }
              ]
            },
            {
              "name": "Seville Challenger",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "998",
                      "status": { "type": { "state": "post", "completed": true, "description": "Final" } },
                      "competitors": [
                        { "id": "s1", "athlete": { "displayName": "Seville Player 1" } },
                        { "id": "s2", "athlete": { "displayName": "Seville Player 2" } }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        // By default, parseScoreboardJson uses Tournaments.ALL and includes all tournaments
        val allMatches = parser.parseScoreboardJson(multiTournamentJson)
        assertEquals("Should contain matches from all tournaments by default", 3, allMatches.size)

        // When explicitly scoped to US Open, filters to US Open
        val matches = parser.parseScoreboardJson(multiTournamentJson, Tournaments.US_OPEN)
        assertEquals("Should only contain matches from US Open", 1, matches.size)
        assertEquals("101", matches[0].id)
        assertEquals("Carlos Alcaraz vs Jannik Sinner", matches[0].title)
        assertTrue(matches.none { it.id == "999" || it.id == "998" })
    }

    @Test
    fun `parseScoreboardJson should eliminate matches with TBD or placeholder opponents`() {
        val matchesWithTbdJson = """
        {
          "events": [
            {
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "201",
                      "status": { "type": { "state": "post", "completed": true, "description": "Final" } },
                      "competitors": [
                        { "id": "p1", "athlete": { "displayName": "Novak Djokovic" } },
                        { "id": "p2", "athlete": { "displayName": "TBD" } }
                      ]
                    },
                    {
                      "id": "202",
                      "status": { "type": { "state": "post", "completed": true, "description": "Final" } },
                      "competitors": [
                        { "id": "p3", "athlete": { "displayName": "Taylor Fritz" } },
                        { "id": "p4", "athlete": { "displayName": "To Be Determined" } }
                      ]
                    },
                    {
                      "id": "203",
                      "status": { "type": { "state": "pre", "completed": false, "description": "Scheduled" } },
                      "competitors": [
                        { "id": "p5", "name": "Competitor 1" },
                        { "id": "p6", "athlete": { "displayName": "Alexander Zverev" } }
                      ]
                    },
                    {
                      "id": "204",
                      "status": { "type": { "state": "in", "completed": false, "description": "In Progress" } },
                      "competitors": [
                        { "id": "p7", "athlete": { "displayName": "Daniil Medvedev" } },
                        { "id": "p8", "athlete": { "displayName": "Frances Tiafoe" } }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val matches = parser.parseScoreboardJson(matchesWithTbdJson)
        assertEquals("Only valid non-TBD matchup should remain", 1, matches.size)
        assertEquals("204", matches[0].id)
        assertEquals("Daniil Medvedev vs Frances Tiafoe", matches[0].title)
    }

    @Test
    fun `parseScoreboardJson allows scoping to future tournaments dynamically`() {
        val mixedJson = """
        {
          "events": [
            {
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "us1",
                      "status": { "type": { "state": "post", "completed": true } },
                      "competitors": [
                        { "id": "p1", "athlete": { "displayName": "Player A" } },
                        { "id": "p2", "athlete": { "displayName": "Player B" } }
                      ]
                    }
                  ]
                }
              ]
            },
            {
              "name": "Wimbledon",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "wim1",
                      "status": { "type": { "state": "post", "completed": true } },
                      "competitors": [
                        { "id": "p3", "athlete": { "displayName": "Player C" } },
                        { "id": "p4", "athlete": { "displayName": "Player D" } }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val usOpenMatches = parser.parseScoreboardJson(mixedJson, Tournaments.US_OPEN)
        assertEquals(1, usOpenMatches.size)
        assertEquals("us1", usOpenMatches[0].id)

        val wimbledonMatches = parser.parseScoreboardJson(mixedJson, Tournaments.WIMBLEDON)
        assertEquals(1, wimbledonMatches.size)
        assertEquals("wim1", wimbledonMatches[0].id)
    }

    @Test
    fun `ScoreboardRepositoryImpl scopes cached and offline matches to active tournament and excludes TBD`() {
        val validUsOpenMatch = Match(
            id = "valid1",
            tournamentName = "US Open",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = Competitor(id = "1", name = "Carlos Alcaraz"),
            player2 = Competitor(id = "2", name = "Jannik Sinner")
        )
        val tbdUsOpenMatch = Match(
            id = "tbd1",
            tournamentName = "US Open",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = Competitor(id = "3", name = "Novak Djokovic"),
            player2 = Competitor(id = "4", name = "TBD")
        )
        val challengerMatch = Match(
            id = "challenger1",
            tournamentName = "Cassis Challenger",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = Competitor(id = "5", name = "Challenger A"),
            player2 = Competitor(id = "6", name = "Challenger B")
        )

        MatchCacheManager.saveMatches(null, listOf(validUsOpenMatch, tbdUsOpenMatch, challengerMatch))

        val repository = ScoreboardRepositoryImpl(activeTournament = Tournaments.US_OPEN)
        val offlineMatches = repository.getOfflineScoreboard()

        assertEquals("Only valid US Open match should be returned", 1, offlineMatches.size)
        assertEquals("valid1", offlineMatches[0].id)

        // Switching tournament scope to Wimbledon should return empty for US Open cached data
        val wimbledonOffline = repository.getOfflineScoreboardForTournament(Tournaments.WIMBLEDON)
        assertTrue("Wimbledon offline matches should be empty when only US Open cached", wimbledonOffline.isEmpty())
    }

    @Test
    fun `ScoreboardViewModel selectTournament updates current tournament and triggers reload`() = runTest(coroutineTestRule.dispatcher) {
        val fakeRepo = object : com.tennis.tracker.domain.repository.ScoreboardRepository {
            private var currentTourney = Tournaments.ALL

            override suspend fun getScoreboard(forceRefresh: Boolean): Result<List<Match>> {
                return Result.success(emptyList())
            }

            override fun getOfflineScoreboard(): List<Match> = emptyList()

            override suspend fun getMatchStatistics(match: Match) =
                Result.failure<com.tennis.tracker.domain.model.MatchStatistics>(NoSuchElementException())

            override fun getActiveTournament(): Tournament = currentTourney

            override fun setActiveTournament(tournament: Tournament) {
                currentTourney = tournament
            }
        }

        val viewModel = ScoreboardViewModel(
            repository = fakeRepo,
            enableAutoRefresh = false
        )
        try {
            coroutineTestRule.dispatcher.scheduler.advanceUntilIdle()

            assertEquals(Tournaments.ALL, viewModel.uiState.value.currentTournament)

            viewModel.selectTournament(Tournaments.ROLAND_GARROS)
            coroutineTestRule.dispatcher.scheduler.advanceUntilIdle()

            assertEquals(Tournaments.ROLAND_GARROS, viewModel.uiState.value.currentTournament)
            assertEquals(Tournaments.ROLAND_GARROS, fakeRepo.getActiveTournament())
        } finally {
            viewModel.cleanup()
        }
    }
}
