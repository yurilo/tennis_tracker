package com.usopen.tracker.e2e

import com.usopen.tracker.data.remote.EspnWatchMatcher
import com.usopen.tracker.data.remote.EspnWatchStream
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.TourType
import com.usopen.tracker.domain.model.TournamentCategory
import com.usopen.tracker.domain.model.TournamentResolver
import com.usopen.tracker.domain.model.Tournaments
import com.usopen.tracker.e2e.harness.E2ETestFixtures
import com.usopen.tracker.ui.viewmodel.TourTierFilter
import com.usopen.tracker.util.TimezoneFormatter
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset

/**
 * Tier 4: Real-World Application Scenarios Test Suite
 *
 * Implements the 6 end-to-end multi-step application scenarios defined in TEST_INFRA.md
 * directly against genuine production domain, ViewModel, and data classes:
 * 1. Full Tournament Transition: Indian Wells Masters 1000 to Miami Open
 * 2. Combined Tournament Ingest: China Open with Men's ATP 500 & Women's WTA 1000
 * 3. Calendar Year Boundary Rollover: United Cup to Australian Open
 * 4. Multi-Tournament Live Feed with 1000 Streams & Highlights
 * 5. Offline / Cache Startup with Dynamic Fallback Timestamps and Tier Filtering
 * 6. Year-End Finals Discovery & UI Filtering
 */
class Tier4RealWorldScenariosTest {

    // =========================================================================
    // Scenario 1: Full Tournament Transition (Indian Wells -> Miami Open)
    // Features: F1, F4, F5, F7, F8, F9, F10
    // =========================================================================
    @Test
    fun scenario1_fullTournamentTransitionIndianWellsToMiamiOpen() {
        val currentYear = Year.now().value
        val now = Instant.now()

        // Step 1: Ingest Indian Wells matches
        val iwMatches = listOf(
            E2ETestFixtures.createMatch(
                id = "iw-final",
                tournamentName = "BNP Paribas Open $currentYear",
                player1Name = "Carlos Alcaraz",
                player2Name = "Daniil Medvedev",
                state = MatchState.FINISHED,
                statusDetail = "Final",
                startTime = now.minus(Duration.ofDays(2)).toString(),
                endTime = now.minus(Duration.ofDays(2)).plus(Duration.ofHours(2)).toString(),
                tournament = Tournaments.INDIAN_WELLS
            )
        )

        // Step 2: Ingest Miami Open matches
        val miamiMatches = listOf(
            E2ETestFixtures.createMatch(
                id = "miami-r1",
                tournamentName = "Miami Open $currentYear",
                player1Name = "Jannik Sinner",
                player2Name = "Grigor Dimitrov",
                state = MatchState.LIVE,
                statusDetail = "In Progress",
                startTime = now.minus(Duration.ofMinutes(45)).toString(),
                tournament = Tournaments.MIAMI_OPEN
            )
        )

        // Step 3: Deduplicate combined feed
        val allMatches = (iwMatches + miamiMatches).distinctBy { it.id }
        assertEquals(2, allMatches.size)

        // Step 4: Tier Filter: 1000s
        val filter1000s = TourTierFilter.MASTERS_1000
        val contextual1000s = allMatches.mapNotNull { it.tournament }.filter { filter1000s.matches(it) }.distinct()
        assertEquals(2, contextual1000s.size)
        assertTrue(contextual1000s.contains(Tournaments.INDIAN_WELLS))
        assertTrue(contextual1000s.contains(Tournaments.MIAMI_OPEN))

        // Step 5: Switch user active selection from Indian Wells to Miami Open
        val userSelectedTournament = Tournaments.MIAMI_OPEN
        val displayedMatches = allMatches.filter { userSelectedTournament.matches(it.tournament?.displayName) }
        assertEquals(1, displayedMatches.size)
        assertEquals("miami-r1", displayedMatches[0].id)
        assertEquals("Jannik Sinner", displayedMatches[0].player1.name)
        assertEquals(CourtSurface.HARD, displayedMatches[0].effectiveSurface)
    }

    // =========================================================================
    // Scenario 2: Combined Tournament Ingest (China Open Dual-Tier)
    // Features: F4, F5, F6, F7, F8, F9
    // =========================================================================
    @Test
    fun scenario2_combinedTournamentIngestChinaOpenDualTier() {
        val chinaOpenRaw = "China Open"
        val resolvedMen = TournamentResolver.resolve(chinaOpenRaw, "Men's Singles")
        val resolvedWomen = TournamentResolver.resolve(chinaOpenRaw, "Women's Singles")

        assertEquals(TourType.ATP, resolvedMen.tour)
        assertEquals(TournamentCategory.ATP_500, resolvedMen.category)
        assertEquals(TourType.WTA, resolvedWomen.tour)
        assertEquals(TournamentCategory.WTA_1000, resolvedWomen.category)

        val matches = listOf(
            E2ETestFixtures.createMatch(id = "bj-m1", player1Name = "Alcaraz", player2Name = "Sinner", eventGrouping = "Men's Singles", tournament = resolvedMen),
            E2ETestFixtures.createMatch(id = "bj-w1", player1Name = "Sabalenka", player2Name = "Gauff", eventGrouping = "Women's Singles", tournament = resolvedWomen)
        )

        // Filter: ATP Tour -> only Men's
        val atpMatches = matches.filter { TourTierFilter.ATP_TOUR.matches(it.tournament, it) }
        assertEquals(1, atpMatches.size)
        assertEquals("Alcaraz", atpMatches[0].player1.name)

        // Filter: WTA Tour -> only Women's
        val wtaMatches = matches.filter { TourTierFilter.WTA_TOUR.matches(it.tournament, it) }
        assertEquals(1, wtaMatches.size)
        assertEquals("Sabalenka", wtaMatches[0].player1.name)

        // Filter: 1000s -> only Women's (WTA 1000)
        val matches1000 = matches.filter { TourTierFilter.MASTERS_1000.matches(it.tournament, it) }
        assertEquals(1, matches1000.size)
        assertEquals("Sabalenka", matches1000[0].player1.name)

        // Filter: 500s/250s -> only Men's (ATP 500)
        val matches500 = matches.filter { TourTierFilter.TIER_500_250.matches(it.tournament, it) }
        assertEquals(1, matches500.size)
        assertEquals("Alcaraz", matches500[0].player1.name)
    }

    // =========================================================================
    // Scenario 3: Calendar Year Boundary Rollover (United Cup -> Australian Open)
    // Features: F1, F2, F3, F5, F7
    // =========================================================================
    @Test
    fun scenario3_calendarYearBoundaryRolloverUnitedCupToAustralianOpen() {
        val baseYear = Year.now().value
        val dec31 = LocalDate.of(baseYear, 12, 31).atTime(22, 0).atZone(ZoneOffset.UTC).toInstant()
        val jan2 = LocalDate.of(baseYear + 1, 1, 2).atTime(11, 0).atZone(ZoneOffset.UTC).toInstant()

        val unitedCupMatch = E2ETestFixtures.createMatch(
            id = "uc-1",
            tournamentName = "United Cup $baseYear",
            player1Name = "De Minaur",
            player2Name = "Zverev",
            startTime = dec31.toString(),
            tournament = TournamentResolver.resolve("United Cup", "Mixed")
        )

        val aoMatch = E2ETestFixtures.createMatch(
            id = "ao-1",
            tournamentName = "Australian Open ${baseYear + 1}",
            player1Name = "Djokovic",
            player2Name = "Sinner",
            startTime = jan2.toString(),
            tournament = Tournaments.AUSTRALIAN_OPEN
        )

        val start1 = TimezoneFormatter.parseToInstant(unitedCupMatch.startTime)
        val start2 = TimezoneFormatter.parseToInstant(aoMatch.startTime)
        assertNotNull(start1)
        assertNotNull(start2)

        assertEquals(baseYear, start1!!.atZone(ZoneOffset.UTC).year)
        assertEquals(baseYear + 1, start2!!.atZone(ZoneOffset.UTC).year)
        assertTrue(start2.isAfter(start1))

        // Verify Australian Open is Grand Slam
        assertTrue(aoMatch.tournament!!.isGrandSlam)
        assertEquals(TournamentCategory.GRAND_SLAM, aoMatch.tournament!!.category)
        assertEquals(CourtSurface.HARD, aoMatch.tournament!!.surface)
    }

    // =========================================================================
    // Scenario 4: Multi-Tournament Live Feed with 1000 Streams & Highlights
    // Features: F7, F10, F11, F12
    // =========================================================================
    @Test
    fun scenario4_multiTournamentLiveFeedWith1000StreamsAndHighlights() {
        val matches = (1..200).map { idx ->
            val tourney = if (idx % 2 == 0) Tournaments.US_OPEN else Tournaments.SHANGHAI_MASTERS
            E2ETestFixtures.createMatch(
                id = "live-m-$idx",
                player1Name = "PlayerA$idx",
                player2Name = "PlayerB$idx",
                state = MatchState.LIVE,
                statusDetail = "In Progress",
                tournament = tourney
            )
        }

        val streams = (1..1000).map { idx ->
            EspnWatchStream(watchId = "$idx", name = "Court $idx - PlayerA$idx vs PlayerB$idx [ESPN+ Live]", webUrl = "https://watch.espn.com/stream/$idx")
        }

        // Benchmark pre-indexing and matching
        val start = System.nanoTime()
        val normalized = EspnWatchMatcher.preNormalize(streams)
        val indexedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("Pre-indexing 1000 streams should take < 300ms (took ${indexedMs}ms)", indexedMs < 300)

        val matchStart = System.nanoTime()
        var matchedCount = 0
        for (m in matches) {
            val stream = EspnWatchMatcher.findBestStreamNormalized(m, normalized)
            if (stream != null) matchedCount++
        }
        val matchElapsedMs = (System.nanoTime() - matchStart) / 1_000_000

        assertEquals(200, matchedCount)
        assertTrue("Matching 200 matches against 1000 streams should take < 200ms (took ${matchElapsedMs}ms)", matchElapsedMs < 200)
    }

    // =========================================================================
    // Scenario 5: Offline / Cache Startup with Dynamic Fallback Timestamps
    // Features: F1, F2, F8, F9, F12
    // =========================================================================
    @Test
    fun scenario5_offlineCacheStartupWithDynamicFallbackTimestamps() {
        val now = Instant.now()

        // Generate 3 dynamic fallback matches rooted in current time
        val fallbackMatches = listOf(
            E2ETestFixtures.createMatch(
                id = "fb-1",
                player1Name = "Alcaraz",
                player2Name = "Sinner",
                state = MatchState.LIVE,
                statusDetail = "In Progress",
                startTime = now.minus(Duration.ofMinutes(50)).toString(),
                tournament = Tournaments.US_OPEN
            ),
            E2ETestFixtures.createMatch(
                id = "fb-2",
                player1Name = "Sabalenka",
                player2Name = "Swiatek",
                state = MatchState.FINISHED,
                statusDetail = "Final",
                startTime = now.minus(Duration.ofHours(4)).toString(),
                endTime = now.minus(Duration.ofHours(2)).toString(),
                tournament = Tournaments.US_OPEN
            ),
            E2ETestFixtures.createMatch(
                id = "fb-3",
                player1Name = "Medvedev",
                player2Name = "Zverev",
                state = MatchState.UPCOMING,
                statusDetail = "Scheduled",
                startTime = now.plus(Duration.ofHours(2)).toString(),
                tournament = Tournaments.INDIAN_WELLS
            )
        )

        // Retention check: verify no matches expired under 24h retention window
        for (m in fallbackMatches) {
            if (m.state == MatchState.FINISHED && m.endTime != null) {
                val endInstant = TimezoneFormatter.parseToInstant(m.endTime)
                assertNotNull(endInstant)
                val hoursSinceEnd = Duration.between(endInstant, now).toHours()
                assertTrue("Fallback completed match must not be expired under 24h cleanup", hoursSinceEnd < 24)
            }
        }

        // Tier filter in offline mode
        val gsMatches = fallbackMatches.filter { TourTierFilter.GRAND_SLAMS.matches(it.tournament) }
        assertEquals(2, gsMatches.size)

        val m1000Matches = fallbackMatches.filter { TourTierFilter.MASTERS_1000.matches(it.tournament) }
        assertEquals(1, m1000Matches.size)
    }

    // =========================================================================
    // Scenario 6: Year-End Finals Discovery & UI Filtering
    // Features: F4, F5, F6, F7, F8, F9
    // =========================================================================
    @Test
    fun scenario6_yearEndFinalsDiscoveryAndUiFiltering() {
        val atpFinals = TournamentResolver.resolve("Nitto ATP Finals Turin", "Men's Singles")
        val wtaFinals = TournamentResolver.resolve("WTA Finals Riyadh", "Women's Singles")

        assertEquals(TourType.ATP, atpFinals.tour)
        assertEquals(TournamentCategory.FINALS, atpFinals.category)
        assertEquals(CourtSurface.HARD, atpFinals.surface)

        assertEquals(TourType.WTA, wtaFinals.tour)
        assertEquals(TournamentCategory.FINALS, wtaFinals.category)
        assertEquals(CourtSurface.HARD, wtaFinals.surface)

        val matches = listOf(
            E2ETestFixtures.createMatch(id = "finals-m1", player1Name = "Sinner", player2Name = "Alcaraz", tournament = atpFinals),
            E2ETestFixtures.createMatch(id = "finals-w1", player1Name = "Gauff", player2Name = "Sabalenka", tournament = wtaFinals)
        )

        // Tier filter tests
        val atpFiltered = matches.filter { TourTierFilter.ATP_TOUR.matches(it.tournament) }
        assertEquals(1, atpFiltered.size)
        assertEquals("finals-m1", atpFiltered[0].id)

        val wtaFiltered = matches.filter { TourTierFilter.WTA_TOUR.matches(it.tournament) }
        assertEquals(1, wtaFiltered.size)
        assertEquals("finals-w1", wtaFiltered[0].id)

        val gsFiltered = matches.filter { TourTierFilter.GRAND_SLAMS.matches(it.tournament) }
        assertTrue("Finals must not appear under Grand Slams", gsFiltered.isEmpty())

        val m1000Filtered = matches.filter { TourTierFilter.MASTERS_1000.matches(it.tournament) }
        assertTrue("Finals must not appear under 1000s", m1000Filtered.isEmpty())
    }
}
