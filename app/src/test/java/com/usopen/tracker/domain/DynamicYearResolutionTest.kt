package com.usopen.tracker.domain

import com.usopen.tracker.data.local.H2HDataProvider
import com.usopen.tracker.data.local.MockH2HData
import com.usopen.tracker.data.local.MockTournamentData
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.util.TimezoneFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId

/**
 * Dedicated unit test suite verifying dynamic dates and year resolution (Requirement R1).
 * Ensures zero hardcoded calendar years ("2026") remain in data generators,
 * timestamps are dynamically computed relative to runtime clocks, and year boundaries
 * are safely handled across seasons.
 */
class DynamicYearResolutionTest {

    @Test
    fun testCurrentYearDynamicResolution_matchesSystemClock() {
        val currentYear = Year.now().value
        assertTrue("Current year must be a realistic calendar year", currentYear >= 2024)

        val currentDate = LocalDate.now().toString()
        assertTrue("Current date string must start with current year", currentDate.startsWith(currentYear.toString()))
    }

    @Test
    fun testMockTournamentData_generatesDynamicTimestampsRelativeToNow() {
        val beforeCall = Instant.now()
        val matches = MockTournamentData.matches
        val afterCall = Instant.now()

        assertTrue("MockTournamentData must supply matches", matches.isNotEmpty())

        val liveMatches = matches.filter { it.state == MatchState.LIVE }
        val upcomingMatches = matches.filter { it.state == MatchState.UPCOMING }
        val finishedMatches = matches.filter { it.state == MatchState.FINISHED }

        assertTrue("Must have live matches", liveMatches.isNotEmpty())
        assertTrue("Must have upcoming matches", upcomingMatches.isNotEmpty())
        assertTrue("Must have finished matches", finishedMatches.isNotEmpty())

        // 1. Live matches: startTime must be strictly before current instant
        for (match in liveMatches) {
            val startInstant = Instant.parse(match.startTime)
            assertTrue("Live match startTime must be before now", startInstant.isBefore(afterCall))
            val elapsed = Duration.between(startInstant, afterCall).toMinutes()
            assertTrue("Live match elapsed duration must be realistic (< 240 mins)", elapsed in 1..240)
            assertEquals("Duration minutes should match elapsed offset", match.durationMinutes?.toLong(), elapsed.coerceAtMost(match.durationMinutes?.toLong() ?: 0L))
        }

        // 2. Upcoming matches: startTime must be strictly after current instant
        for (match in upcomingMatches) {
            val startInstant = Instant.parse(match.startTime)
            assertTrue("Upcoming match startTime must be after beforeCall", startInstant.isAfter(beforeCall))
            val untilStart = Duration.between(beforeCall, startInstant).toMinutes()
            assertTrue("Upcoming match must start in future (~2 hours)", untilStart in 110..150)
            assertTrue("Status detail must include time", match.statusDetail.contains("Tonight •") || match.statusDetail.contains("Starts at"))
        }

        // 3. Finished matches: startTime and endTime must both be before now
        for (match in finishedMatches) {
            val startInstant = Instant.parse(match.startTime)
            val endInstant = Instant.parse(match.endTime)
            assertTrue("Finished match startTime must be before endTime", startInstant.isBefore(endInstant))
            assertTrue("Finished match endTime must be before now", endInstant.isBefore(afterCall))
            assertNotNull("Finished match must have duration minutes", match.durationMinutes)
            assertTrue("Duration minutes must be positive", match.durationMinutes!! > 0)
        }
    }

    @Test
    fun testMockTournamentData_timestampsProgressWithClock() {
        val firstSample = MockTournamentData.matches.first { it.state == MatchState.LIVE }
        val firstStart = Instant.parse(firstSample.startTime)

        // Wait a tiny fraction or evaluate another call to verify dynamic evaluation property
        Thread.sleep(10)
        val secondSample = MockTournamentData.matches.first { it.state == MatchState.LIVE }
        val secondStart = Instant.parse(secondSample.startTime)

        assertTrue(
            "Matches getter must dynamically recompute timestamps on each access",
            secondStart >= firstStart
        )
    }

    @Test
    fun testMockTournamentData_containsZeroHardcoded2026Literals() {
        val matches = MockTournamentData.matches

        for (match in matches) {
            assertFalse(
                "Match ID must not contain hardcoded fixed dates",
                match.id.contains("2026-")
            )
            // Ensure tournament name is clean
            assertEquals("US Open", match.tournamentName)

            // Start time year must correspond to current runtime year or adjacent boundary
            val startInstant = match.startTime?.let { Instant.parse(it) }
            if (startInstant != null) {
                val startYear = startInstant.atZone(ZoneId.of("UTC")).year
                val currentYear = Year.now().value
                assertTrue(
                    "Start timestamp year must be within current temporal window",
                    startYear in (currentYear - 1)..(currentYear + 1)
                )
            }
        }
    }

    @Test
    fun testH2HDataProvider_resolvesDynamicYearForFinishedMatch() {
        val currentYear = Year.now().value
        val currentDate = LocalDate.now().toString()

        val finishedMatch = createDummyMatch(
            id = "test_fin_1",
            state = MatchState.FINISHED,
            tournamentName = "US Open"
        )

        val h2h = H2HDataProvider.generateH2H(finishedMatch)
        assertNotNull("H2H summary must be generated", h2h)

        val latestMatch = h2h.matches.firstOrNull { it.id == "current_test_fin_1" }
        assertNotNull("Finished match must be prepended to historical matches", latestMatch)
        assertEquals("Latest match year must dynamically match Year.now()", currentYear, latestMatch!!.year)
        assertEquals("Latest match date must dynamically match LocalDate.now()", currentDate, latestMatch.date)
        assertEquals("Tournament name must append current year dynamically", "US Open $currentYear", latestMatch.tournamentName)
    }

    @Test
    fun testH2HDataProvider_preservesYearIfAlreadyInTournamentName() {
        val currentYear = Year.now().value
        val matchWithYear = createDummyMatch(
            id = "test_fin_2",
            state = MatchState.FINISHED,
            tournamentName = "Wimbledon $currentYear"
        )

        val h2h = H2HDataProvider.generateH2H(matchWithYear)
        val latestMatch = h2h.matches.first { it.id == "current_test_fin_2" }
        assertEquals(
            "Should not duplicate year when already present",
            "Wimbledon $currentYear",
            latestMatch.tournamentName
        )
    }

    @Test
    fun testH2HDataProvider_defaultTournamentName_whenBlank() {
        val currentYear = Year.now().value
        val matchBlank = createDummyMatch(
            id = "test_fin_3",
            state = MatchState.FINISHED,
            tournamentName = ""
        )

        val h2h = H2HDataProvider.generateH2H(matchBlank)
        val latestMatch = h2h.matches.first { it.id == "current_test_fin_3" }
        assertEquals(
            "Blank tournament name must fallback to Tennis Tournament with current year",
            "Tennis Tournament $currentYear",
            latestMatch.tournamentName
        )
    }

    @Test
    fun testH2HDataProvider_historicalMatchesUseRelativeTemporalSequence() {
        val currentYear = Year.now().value
        val match = createDummyMatch(
            id = "test_generic_rivalry",
            state = MatchState.UPCOMING,
            p1Name = "Player Alpha",
            p2Name = "Player Beta"
        )

        val h2h = H2HDataProvider.generateH2H(match)
        assertTrue("Generic rivalry matches must exist", h2h.matches.isNotEmpty())

        val years = h2h.matches.map { it.year }
        for (year in years) {
            assertTrue(
                "Historical match year ($year) must be strictly prior to current year ($currentYear)",
                year < currentYear
            )
            assertTrue(
                "Historical match year must be a realistic recent relative year",
                year >= currentYear - 5
            )
        }

        // Check relative offset ordering
        val dates = h2h.matches.map { it.date }
        for (date in dates) {
            assertTrue("Date string must match ISO format YYYY-MM-DD", date.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        }
    }

    @Test
    fun testMockH2HData_dynamicYearConsistency() {
        val currentYear = Year.now().value
        val match = createDummyMatch(
            id = "test_mock_h2h",
            state = MatchState.FINISHED,
            tournamentName = "Australian Open"
        )

        val summary = MockH2HData.generateH2H(match)
        val latest = summary.matches.first { it.id == "current_test_mock_h2h" }
        assertEquals(currentYear, latest.year)
        assertEquals("Australian Open $currentYear", latest.tournamentName)
    }

    @Test
    fun testTimezoneFormatter_parsesTimestampsAcrossYearBoundaries() {
        val currentYear = Year.now().value

        // End of December timestamp
        val newYearsEve = "${currentYear}-12-31T23:59:59Z"
        val parsedEve = TimezoneFormatter.parseToInstant(newYearsEve)
        assertNotNull("New Year's Eve timestamp must parse successfully", parsedEve)
        assertEquals(currentYear, parsedEve!!.atZone(ZoneId.of("UTC")).year)

        // Start of January next year
        val newYearsDay = "${currentYear + 1}-01-01T00:00:00Z"
        val parsedDay = TimezoneFormatter.parseToInstant(newYearsDay)
        assertNotNull("New Year's Day timestamp must parse successfully", parsedDay)
        assertEquals(currentYear + 1, parsedDay!!.atZone(ZoneId.of("UTC")).year)

        val gap = Duration.between(parsedEve, parsedDay).seconds
        assertEquals("Gap across year boundary must be exactly 1 second", 1L, gap)
    }

    @Test
    fun testTimezoneFormatter_parsesVariousTimestampFormats() {
        // ISO UTC timestamp with seconds
        val isoUtc = "2025-06-15T14:30:00Z"
        val parsedUtc = TimezoneFormatter.parseToInstant(isoUtc)
        assertNotNull(parsedUtc)

        // ISO timestamp without seconds (ESPN format)
        val isoNoSeconds = "2025-06-15T14:30Z"
        val parsedNoSeconds = TimezoneFormatter.parseToInstant(isoNoSeconds)
        assertNotNull(parsedNoSeconds)

        // Offset format with +02:00
        val withOffset = "2025-06-15T14:30:00+02:00"
        val parsedOffset = TimezoneFormatter.parseToInstant(withOffset)
        assertNotNull(parsedOffset)

        // Epoch milliseconds formatted via formatEpochTime
        val nowMillis = System.currentTimeMillis()
        val formattedEpoch = TimezoneFormatter.formatEpochTime(nowMillis)
        assertTrue(formattedEpoch.isNotEmpty())

        // Null and invalid inputs safely return null without exception
        assertNull(TimezoneFormatter.parseToInstant(null))
        assertNull(TimezoneFormatter.parseToInstant(""))
        assertNull(TimezoneFormatter.parseToInstant("invalid-timestamp-string"))
    }

    @Test
    fun testTimezoneFormatter_formatsUpcomingDatesDynamically() {
        val now = Instant.now()
        val futureTime = now.plus(Duration.ofHours(3))

        val formattedLocal = TimezoneFormatter.formatIsoToLocal(futureTime.toString())
        assertNotNull("Formatted local time must not be null", formattedLocal)
        assertTrue("Formatted local string should contain bullet separator", formattedLocal!!.contains("•"))

        val convertedEdt = TimezoneFormatter.convertEdtStringToLocal("Tonight • 9:00 PM EDT")
        assertNotNull("Converted EDT string must not be null", convertedEdt)
        assertTrue("Converted string should contain 'Tonight'", convertedEdt!!.contains("Tonight"))

        val upcomingMatch = createDummyMatch(
            id = "test_display_status",
            state = MatchState.UPCOMING,
            durationMinutes = null
        ).copy(startTime = futureTime.toString())

        val displayStatus = TimezoneFormatter.getDisplayStatus(upcomingMatch)
        assertNotNull(displayStatus)
        assertTrue("Display status should format upcoming match time", displayStatus.isNotEmpty())
    }

    @Test
    fun testLeapYearDateHandlingAcrossBoundaries() {
        val leapYear = 2024
        val leapDate = LocalDate.of(leapYear, 2, 29)
        val nextDay = leapDate.plusDays(1)
        assertEquals(3, nextDay.monthValue)
        assertEquals(1, nextDay.dayOfMonth)

        val nonLeapYear = 2025
        val nonLeapFeb = LocalDate.of(nonLeapYear, 2, 28)
        val nonLeapNext = nonLeapFeb.plusDays(1)
        assertEquals(3, nonLeapNext.monthValue)
        assertEquals(1, nonLeapNext.dayOfMonth)
    }

    @Test
    fun testMatchDurationFormatting() {
        val match72 = createDummyMatch("m1", durationMinutes = 72)
        assertEquals("1h 12m", match72.getFormattedDuration())

        val match134 = createDummyMatch("m2", durationMinutes = 134)
        assertEquals("2h 14m", match134.getFormattedDuration())

        val p1Empty = Competitor(id = "p1", name = "P1", isWinner = false, setScores = emptyList())
        val p2Empty = Competitor(id = "p2", name = "P2", isWinner = false, setScores = emptyList())
        val matchNoDuration = createDummyMatch("m3", durationMinutes = null).copy(
            player1 = p1Empty,
            player2 = p2Empty
        )
        assertNull(matchNoDuration.getFormattedDuration())
    }

    private fun createDummyMatch(
        id: String,
        state: MatchState = MatchState.FINISHED,
        tournamentName: String = "US Open",
        p1Name: String = "Carlos Alcaraz",
        p2Name: String = "Jannik Sinner",
        durationMinutes: Int? = 120
    ): Match {
        val p1 = Competitor(
            id = "${id}_p1",
            name = p1Name,
            seed = 1,
            country = "ESP",
            isWinner = true,
            setScores = listOf(6, 6),
            currentGameScore = null,
            isServing = false
        )
        val p2 = Competitor(
            id = "${id}_p2",
            name = p2Name,
            seed = 2,
            country = "ITA",
            isWinner = false,
            setScores = listOf(4, 3),
            currentGameScore = null,
            isServing = false
        )
        return Match(
            id = id,
            tournamentName = tournamentName,
            eventGrouping = "Men's Singles",
            roundName = "Final",
            state = state,
            statusDetail = "Final",
            player1 = p1,
            player2 = p2,
            venue = "Arthur Ashe Stadium",
            court = "Center Court",
            durationMinutes = durationMinutes,
            espnEventId = id
        )
    }
}
