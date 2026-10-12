package com.usopen.tracker.data

import com.usopen.tracker.data.local.MockTournamentData
import com.usopen.tracker.data.remote.TennisStatsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class MatchStatsParsingTest {

    @Test
    fun `parseStatisticsJson parses real Sofascore match statistics into periods, groups, and shot stats`() {
        val stream = javaClass.classLoader?.getResourceAsStream("sample_stats.json")
        assertNotNull("sample_stats.json fixture must be present in test resources", stream)

        val jsonString = InputStreamReader(stream!!).readText()
        val match = MockTournamentData.matches.first()

        val api = TennisStatsApi()
        val stats = api.parseStatisticsJson(jsonString, match, isP1Home = true)

        assertNotNull("Parsed stats should not be null", stats)
        assertEquals(match.id, stats!!.matchId)
        assertEquals(match.player1.name, stats.player1Name)
        assertEquals(match.player2.name, stats.player2Name)

        // Verify periods (ALL / Match, 1ST / Set 1, 2ND / Set 2, 3RD / Set 3)
        assertTrue("Must contain at least 2 periods", stats.periods.size >= 2)
        val matchPeriod = stats.periods.firstOrNull { it.period == "ALL" }
        assertNotNull("Must contain ALL (Match) period", matchPeriod)
        assertEquals("Match", matchPeriod!!.label)

        val set1Period = stats.periods.firstOrNull { it.period == "1ST" }
        assertNotNull("Must contain 1ST (Set 1) period", set1Period)
        assertEquals("Set 1", set1Period!!.label)

        // Verify groups in Match period
        val groupNames = matchPeriod.groups.map { it.groupName }
        assertTrue("Should contain Service stats", groupNames.any { it.contains("Service", ignoreCase = true) })
        assertTrue("Should contain Points stats", groupNames.any { it.contains("Point", ignoreCase = true) })

        // Verify specific shot stat items (Aces, Double faults, 1st serve %, etc.)
        val aces = matchPeriod.findItem("Service", "Aces")
        assertNotNull("Should contain Aces item", aces)
        assertTrue("Home display for aces should be numeric", aces!!.homeDisplay.isNotEmpty())
        assertTrue("Away display for aces should be numeric", aces.awayDisplay.isNotEmpty())
        assertTrue("Aces ratio should be in valid range [0, 1]", aces.homeRatio in 0.0f..1.0f)

        // Verify player orientation flip works properly when isP1Home is false
        val flippedStats = api.parseStatisticsJson(jsonString, match, isP1Home = false)
        assertNotNull(flippedStats)
        val flippedAces = flippedStats!!.periods.first { it.period == "ALL" }.findItem("Service", "Aces")
        assertNotNull(flippedAces)
        assertEquals(aces.awayDisplay, flippedAces!!.homeDisplay)
        assertEquals(aces.homeDisplay, flippedAces.awayDisplay)
    }

    @Test
    fun `fetchStatistics returns failure for upcoming matches without generating mock stats`() {
        val api = TennisStatsApi()
        val upcomingMatch = MockTournamentData.matches.first { it.state == com.usopen.tracker.domain.model.MatchState.UPCOMING }
        val result = api.fetchStatistics(upcomingMatch)

        assertTrue("Result must be failure for upcoming matches", result.isFailure)
    }

    @Test
    fun `parseStatisticsJson returns null when statistics array is empty or missing`() {
        val api = TennisStatsApi()
        val match = MockTournamentData.matches.first()

        val emptyResult = api.parseStatisticsJson("{\"statistics\":[]}", match)
        org.junit.Assert.assertNull("Empty statistics array must return null", emptyResult)

        val missingResult = api.parseStatisticsJson("{}", match)
        org.junit.Assert.assertNull("Missing statistics array must return null", missingResult)
    }

    @Test
    fun `MockStatsData generates complete and valid statistics for completed match with set scores`() {
        val match = MockTournamentData.matches.first { it.state == com.usopen.tracker.domain.model.MatchState.FINISHED }
        val stats = com.usopen.tracker.data.local.MockStatsData.generate(match)

        assertNotNull("Generated stats must not be null", stats)
        assertEquals(match.id, stats.matchId)
        assertEquals(match.player1.name, stats.player1Name)
        assertEquals(match.player2.name, stats.player2Name)

        val matchPeriod = stats.periods.firstOrNull { it.period == "ALL" }
        assertNotNull("Must contain ALL (Match) period", matchPeriod)
        assertEquals("Match", matchPeriod!!.label)

        // Verify key groups in Match period
        val groupNames = matchPeriod.groups.map { it.groupName }
        assertTrue("Must have Service stats", groupNames.contains("Service"))
        assertTrue("Must have Winners stats", groupNames.contains("Winners"))
        assertTrue("Must have Unforced errors stats", groupNames.contains("Unforced errors"))
        assertTrue("Must have Return stats", groupNames.contains("Return"))
        assertTrue("Must have Points stats", groupNames.contains("Points"))

        // Verify set periods
        val setPeriods = stats.periods.filter { it.period != "ALL" }
        assertTrue("Must contain at least 2 sets for finished match", setPeriods.size >= 2)
        assertEquals("1ST", setPeriods[0].period)
        assertEquals("Set 1", setPeriods[0].label)
        assertEquals("2ND", setPeriods[1].period)
        assertEquals("Set 2", setPeriods[1].label)

        // Verify all stat ratios are within [0.05, 0.95]
        for (period in stats.periods) {
            for (group in period.groups) {
                for (item in group.items) {
                    assertTrue(
                        "Stat item '${item.name}' ratio ${item.homeRatio} must be between 0.05 and 0.95",
                        item.homeRatio in 0.05f..0.95f
                    )
                    assertTrue("Home display must not be empty", item.homeDisplay.isNotEmpty())
                    assertTrue("Away display must not be empty", item.awayDisplay.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `MockStatsData generates valid statistics for live match and match with empty set scores`() {
        val liveMatch = MockTournamentData.matches.first { it.state == com.usopen.tracker.domain.model.MatchState.LIVE }
        val liveStats = com.usopen.tracker.data.local.MockStatsData.generate(liveMatch)

        assertNotNull(liveStats)
        assertTrue("Must contain at least Match period and current set", liveStats.periods.isNotEmpty())

        val emptyScoresMatch = liveMatch.copy(
            player1 = liveMatch.player1.copy(setScores = emptyList()),
            player2 = liveMatch.player2.copy(setScores = emptyList())
        )
        val statsWithEmptyScores = com.usopen.tracker.data.local.MockStatsData.generate(emptyScoresMatch)
        assertNotNull(statsWithEmptyScores)
        assertTrue(statsWithEmptyScores.periods.any { it.period == "ALL" })
    }
}
