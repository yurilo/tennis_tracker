package com.usopen.tracker.domain

import com.usopen.tracker.data.local.MockH2HData
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.H2HUiState
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Year

class H2HTest {

    private val alcaraz = Competitor(
        id = "4094",
        name = "Carlos Alcaraz",
        seed = 2,
        country = "ESP",
        setScores = listOf(6, 4, 6),
        isWinner = true
    )

    private val sinner = Competitor(
        id = "4096",
        name = "Jannik Sinner",
        seed = 1,
        country = "ITA",
        setScores = listOf(4, 6, 3),
        isWinner = false
    )

    @Test
    fun testFinishedMatchIncludedInH2H() {
        val finishedMatch = Match(
            id = "test-fin",
            tournamentName = "US Open",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(finishedMatch)
        assertNotNull(summary)
        assertTrue("Matches should not be empty", summary.matches.isNotEmpty())

        val latestMatch = summary.matches[0]
        assertTrue("First match should be marked as the current match", latestMatch.isCurrentMatch)
        assertEquals("US Open ${Year.now().value}", latestMatch.tournamentName)
        assertEquals(Year.now().value, latestMatch.year)
        assertEquals(LocalDate.now().toString(), latestMatch.date)
        assertEquals("Arthur Ashe Stadium", latestMatch.venue)
        assertEquals(CourtSurface.HARD, latestMatch.surface)
        assertEquals("Carlos Alcaraz", latestMatch.winnerName)
        assertTrue(latestMatch.score.contains("6-4"))
    }

    @Test
    fun testDynamicYearAndDateResolution() {
        val currentYear = Year.now().value
        val currentDate = LocalDate.now().toString()

        val matchWithTournament = Match(
            id = "test-dyn-1",
            tournamentName = "Wimbledon",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Centre Court"
        )
        val summary1 = MockH2HData.generateH2H(matchWithTournament)
        val current1 = summary1.matches.first { it.isCurrentMatch }
        assertEquals("Wimbledon $currentYear", current1.tournamentName)
        assertEquals(currentYear, current1.year)
        assertEquals(currentDate, current1.date)

        val matchWithoutTournament = Match(
            id = "test-dyn-2",
            tournamentName = "",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Court 1"
        )
        val summary2 = MockH2HData.generateH2H(matchWithoutTournament)
        val current2 = summary2.matches.first { it.isCurrentMatch }
        assertEquals("Tennis Tournament $currentYear", current2.tournamentName)
        assertEquals(currentYear, current2.year)
        assertEquals(currentDate, current2.date)

        val pastMatches = summary2.matches.filter { !it.isCurrentMatch }
        assertTrue("Should have past matches", pastMatches.isNotEmpty())
        for (past in pastMatches) {
            assertTrue("Past match year ${past.year} should be <= $currentYear", past.year <= currentYear)
            assertTrue("Past match year ${past.year} should be >= ${currentYear - 5}", past.year >= currentYear - 5)
        }
    }

    @Test
    fun testLiveMatchExcludedFromH2H() {
        val liveMatch = Match(
            id = "test-live",
            tournamentName = "US Open",
            roundName = "Semi-Final",
            state = MatchState.LIVE,
            statusDetail = "In Progress • Set 3",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(liveMatch)
        assertNotNull(summary)
        assertTrue("Matches should not be empty", summary.matches.isNotEmpty())

        // Ensure NO match is marked as the current match
        val hasCurrent = summary.matches.any { it.isCurrentMatch }
        assertFalse("Live match MUST NOT be included in H2H historical list", hasCurrent)
    }

    @Test
    fun testUpcomingMatchExcludedFromH2H() {
        val upcomingMatch = Match(
            id = "test-up",
            tournamentName = "US Open",
            roundName = "Quarter-Final",
            state = MatchState.UPCOMING,
            statusDetail = "Tonight • 7:00 PM EDT",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(upcomingMatch)
        val hasCurrent = summary.matches.any { it.isCurrentMatch }
        assertFalse("Upcoming match MUST NOT be included in H2H historical list", hasCurrent)
    }

    @Test
    fun testMatchesOrderedByDateDescending() {
        val finishedMatch = Match(
            id = "test-order",
            tournamentName = "US Open",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(finishedMatch)
        for (i in 0 until summary.matches.size - 1) {
            val d1 = summary.matches[i].date
            val d2 = summary.matches[i + 1].date
            assertTrue("Match at $i ($d1) should be >= match at ${i + 1} ($d2)", d1 >= d2)
        }
    }

    @Test
    fun testSurfaceBreakdownTotals() {
        val match = Match(
            id = "test-totals",
            tournamentName = "US Open",
            roundName = "Semi-Final",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(match)
        val totalP1 = summary.hardWinsP1 + summary.clayWinsP1 + summary.grassWinsP1
        val totalP2 = summary.hardWinsP2 + summary.clayWinsP2 + summary.grassWinsP2

        assertEquals(summary.p1Wins, totalP1)
        assertEquals(summary.p2Wins, totalP2)
        assertEquals(summary.totalMatches, summary.p1Wins + summary.p2Wins)
    }

    @Test
    fun testSurfaceFiltering() {
        val match = Match(
            id = "test-filter",
            tournamentName = "US Open",
            roundName = "Semi-Final",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(match)
        val uiStateAll = H2HUiState(summary = summary, selectedSurface = null)
        assertEquals(summary.matches.size, uiStateAll.filteredMatches.size)

        val uiStateClay = H2HUiState(summary = summary, selectedSurface = CourtSurface.CLAY)
        assertTrue(uiStateClay.filteredMatches.all { it.surface == CourtSurface.CLAY })
        assertEquals(summary.clayWinsP1 + summary.clayWinsP2, uiStateClay.filteredMatches.size)

        val uiStateGrass = H2HUiState(summary = summary, selectedSurface = CourtSurface.GRASS)
        assertTrue(uiStateGrass.filteredMatches.all { it.surface == CourtSurface.GRASS })
    }

    @Test
    fun testVenueAndCourtInformationPresent() {
        val match = Match(
            id = "test-venue",
            tournamentName = "US Open",
            roundName = "Final",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = alcaraz,
            player2 = sinner,
            venue = "Arthur Ashe Stadium"
        )

        val summary = MockH2HData.generateH2H(match)
        for (m in summary.matches) {
            assertTrue("Venue should not be blank", m.venue.isNotBlank())
            assertTrue("Tournament should not be blank", m.tournamentName.isNotBlank())
            assertTrue("Round should not be blank", m.round.isNotBlank())
            assertNotNull("Surface must be present", m.surface)
        }
    }
}
