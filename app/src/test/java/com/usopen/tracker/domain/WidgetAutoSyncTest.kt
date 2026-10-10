package com.usopen.tracker.domain

import com.usopen.tracker.data.local.MockTournamentData
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.ui.widget.WidgetAutoSyncManager
import com.usopen.tracker.util.TimezoneFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WidgetAutoSyncTest {

    private fun createTestMatch(id: String, state: MatchState): Match {
        return Match(
            id = id,
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Quarterfinals",
            state = state,
            statusDetail = state.name,
            player1 = Competitor(id = "p1", name = "Carlos Alcaraz"),
            player2 = Competitor(id = "p2", name = "Jannik Sinner"),
            venue = "Arthur Ashe Stadium"
        )
    }

    @Test
    fun `calculateInterval returns 1 minute when a followed match is LIVE`() {
        val matches = listOf(
            createTestMatch("m1", MatchState.LIVE),
            createTestMatch("m2", MatchState.UPCOMING),
            createTestMatch("m3", MatchState.FINISHED)
        )
        val followedIds = setOf("m1", "m2")

        val interval = WidgetAutoSyncManager.calculateInterval(matches, followedIds)
        assertEquals(WidgetAutoSyncManager.INTERVAL_LIVE_MILLIS, interval)
        assertEquals(60_000L, interval)
    }

    @Test
    fun `calculateInterval returns 5 minutes when followed matches are UPCOMING`() {
        val matches = listOf(
            createTestMatch("m1", MatchState.LIVE),      // Live, but NOT followed
            createTestMatch("m2", MatchState.UPCOMING),  // Followed
            createTestMatch("m3", MatchState.FINISHED)   // Followed
        )
        val followedIds = setOf("m2", "m3")

        val interval = WidgetAutoSyncManager.calculateInterval(matches, followedIds)
        assertEquals(WidgetAutoSyncManager.INTERVAL_UPCOMING_MILLIS, interval)
        assertEquals(300_000L, interval)
    }

    @Test
    fun `calculateInterval returns 15 minutes when all followed matches are FINISHED`() {
        val matches = listOf(
            createTestMatch("m1", MatchState.LIVE),      // Not followed
            createTestMatch("m2", MatchState.FINISHED),  // Followed
            createTestMatch("m3", MatchState.FINISHED)   // Followed
        )
        val followedIds = setOf("m2", "m3")

        val interval = WidgetAutoSyncManager.calculateInterval(matches, followedIds)
        assertEquals(WidgetAutoSyncManager.INTERVAL_DEFAULT_MILLIS, interval)
        assertEquals(900_000L, interval)
    }

    @Test
    fun `calculateInterval defaults to 15 minutes when match list is empty`() {
        val interval = WidgetAutoSyncManager.calculateInterval(emptyList(), emptySet())
        assertEquals(WidgetAutoSyncManager.INTERVAL_DEFAULT_MILLIS, interval)
    }

    @Test
    fun `calculateInterval uses live rate when followed list is empty but live matches exist`() {
        val matches = listOf(
            createTestMatch("m1", MatchState.LIVE)
        )
        val interval = WidgetAutoSyncManager.calculateInterval(matches, emptySet())
        assertEquals(WidgetAutoSyncManager.INTERVAL_LIVE_MILLIS, interval)
    }

    @Test
    fun `formatEpochTime properly formats timestamps in 12h and 24h`() {
        // Fixed timestamp: 2026-09-05T17:30:00Z -> 1788629400000L
        val epochMillis = 1788629400000L
        val nyZone = ZoneId.of("America/New_York") // EDT is UTC-4: 13:30 (1:30 PM)

        val formatted12 = TimezoneFormatter.formatEpochTime(epochMillis, zoneId = nyZone, is24Hour = false)
        assertTrue("Expected 1:30 PM, got: $formatted12", formatted12.contains("1:30") && formatted12.contains("PM"))

        val formatted24 = TimezoneFormatter.formatEpochTime(epochMillis, zoneId = nyZone, is24Hour = true)
        assertEquals("13:30", formatted24)
    }
}
