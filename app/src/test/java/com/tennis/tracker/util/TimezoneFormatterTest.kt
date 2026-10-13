package com.tennis.tracker.util

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TimezoneFormatterTest {

    @Test
    fun `formatIsoToLocal converts UTC instant to New York EDT time correctly`() {
        // 2026-09-04 21:00 UTC = 5:00 PM EDT (UTC-4)
        val iso = "2026-09-04T21:00:00Z"
        val nyZone = ZoneId.of("America/New_York")
        val formatted = TimezoneFormatter.formatIsoToLocal(iso, nyZone, is24Hour = false)

        assertNotNull(formatted)
        assertTrue("Expected Sep 4 date, got: $formatted", formatted!!.startsWith("Sep 4"))
        assertTrue("Expected 5:00 PM time, got: $formatted", formatted.contains("5:00 PM"))
        assertTrue("Expected EDT timezone, got: $formatted", formatted.contains("EDT"))
    }

    @Test
    fun `formatIsoToLocal parses ESPN ISO string without seconds correctly`() {
        // ESPN sends "2026-09-05T15:30Z" (no seconds)
        val isoWithoutSeconds = "2026-09-05T15:30Z"
        val nyZone = ZoneId.of("America/New_York")
        val formatted = TimezoneFormatter.formatIsoToLocal(isoWithoutSeconds, nyZone, is24Hour = false)

        assertNotNull("Should parse ISO without seconds", formatted)
        assertTrue("Expected Sep 5 date, got: $formatted", formatted!!.startsWith("Sep 5"))
        assertTrue("Expected 11:30 AM time, got: $formatted", formatted.contains("11:30 AM"))
        assertTrue("Expected EDT timezone, got: $formatted", formatted.contains("EDT"))
    }

    @Test
    fun `formatIsoToLocal converts UTC instant to Los Angeles PDT time correctly`() {
        // 2026-09-04 21:00 UTC = 2:00 PM PDT (UTC-7)
        val iso = "2026-09-04T21:00:00Z"
        val laZone = ZoneId.of("America/Los_Angeles")
        val formatted = TimezoneFormatter.formatIsoToLocal(iso, laZone, is24Hour = false)

        assertNotNull(formatted)
        assertTrue("Expected Sep 4 date, got: $formatted", formatted!!.startsWith("Sep 4"))
        assertTrue("Expected 2:00 PM time, got: $formatted", formatted.contains("2:00 PM"))
        assertTrue("Expected PDT timezone, got: $formatted", formatted.contains("PDT"))
    }

    @Test
    fun `formatIsoToLocal converts UTC instant to London BST time correctly`() {
        // 2026-09-04 21:00 UTC = 10:00 PM BST (UTC+1)
        val iso = "2026-09-04T21:00:00Z"
        val londonZone = ZoneId.of("Europe/London")
        val formatted = TimezoneFormatter.formatIsoToLocal(iso, londonZone, is24Hour = false)

        assertNotNull(formatted)
        assertTrue("Expected Sep 4 date, got: $formatted", formatted!!.startsWith("Sep 4"))
        assertTrue("Expected 10:00 PM time, got: $formatted", formatted.contains("10:00 PM"))
        assertTrue("Expected BST timezone, got: $formatted", formatted.contains("BST"))
    }

    @Test
    fun `formatIsoToLocal converts UTC instant to Tokyo JST next day time correctly`() {
        // 2026-09-04 21:00 UTC = 2026-09-05 06:00 AM JST (UTC+9)
        val iso = "2026-09-04T21:00:00Z"
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val formatted = TimezoneFormatter.formatIsoToLocal(iso, tokyoZone, is24Hour = false)

        assertNotNull(formatted)
        assertTrue("Expected next day Sep 5 date, got: $formatted", formatted!!.startsWith("Sep 5"))
        assertTrue("Expected 6:00 AM time, got: $formatted", formatted.contains("6:00 AM"))
        assertTrue("Expected JST timezone, got: $formatted", formatted.contains("JST"))
    }

    @Test
    fun `formatIsoToLocal formats in 24 hour mode when requested`() {
        val iso = "2026-09-04T21:00:00Z"
        val nyZone = ZoneId.of("America/New_York")
        val formatted = TimezoneFormatter.formatIsoToLocal(iso, nyZone, is24Hour = true)

        assertNotNull(formatted)
        assertTrue("Expected 17:00 24h time, got: $formatted", formatted!!.contains("17:00"))
        assertTrue("Expected EDT timezone, got: $formatted", formatted.contains("EDT"))
    }

    @Test
    fun `convertEdtStringToLocal transforms Eastern time strings to local timezone`() {
        val edtText = "Tonight • 9:00 PM EDT"
        val laZone = ZoneId.of("America/Los_Angeles")
        val converted = TimezoneFormatter.convertEdtStringToLocal(edtText, laZone, is24Hour = false)

        assertNotNull(converted)
        assertTrue("Expected 6:00 PM PDT, got: $converted", converted!!.contains("6:00 PM PDT"))
        assertTrue("Expected prefix preserved, got: $converted", converted.startsWith("Tonight •"))
    }

    @Test
    fun `match getFormattedStartTime uses target zoneId correctly`() {
        val match = Match(
            id = "test-1",
            tournamentName = "US Open",
            eventGrouping = "Men's Singles",
            roundName = "Round 2",
            state = MatchState.UPCOMING,
            statusDetail = "Scheduled",
            player1 = Competitor(id = "p1", name = "Alcaraz"),
            player2 = Competitor(id = "p2", name = "Sinner"),
            venue = "Arthur Ashe Stadium",
            startTime = "2026-09-04T21:00:00Z"
        )

        val pdtTime = match.getFormattedStartTime(ZoneId.of("America/Los_Angeles"))
        assertNotNull(pdtTime)
        assertTrue("PDT time expected, got: $pdtTime", pdtTime!!.contains("2:00 PM PDT"))

        val bstTime = match.getFormattedStartTime(ZoneId.of("Europe/London"))
        assertNotNull(bstTime)
        assertTrue("BST time expected, got: $bstTime", bstTime!!.contains("10:00 PM BST"))
    }

    @Test
    fun `match getDisplayStatus returns converted time for upcoming match`() {
        val match = Match(
            id = "test-2",
            tournamentName = "US Open",
            eventGrouping = "Women's Singles",
            roundName = "Quarterfinals",
            state = MatchState.UPCOMING,
            statusDetail = "Scheduled",
            player1 = Competitor(id = "p1", name = "Gauff"),
            player2 = Competitor(id = "p2", name = "Sabalenka"),
            venue = "Arthur Ashe Stadium",
            startTime = "2026-09-05T19:00:00Z"
        )

        val displayStatus = match.getDisplayStatus(ZoneId.of("America/New_York"))
        assertTrue("Expected EDT start time in status, got: $displayStatus", displayStatus.contains("3:00 PM EDT"))
    }
}
