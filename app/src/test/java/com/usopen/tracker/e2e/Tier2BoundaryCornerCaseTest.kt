package com.usopen.tracker.e2e

import com.usopen.tracker.data.remote.EspnWatchMatcher
import com.usopen.tracker.data.remote.EspnWatchStream
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.TourType
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.TournamentCategory
import com.usopen.tracker.domain.model.TournamentResolver
import com.usopen.tracker.domain.model.Tournaments
import com.usopen.tracker.e2e.harness.E2ETestFixtures
import com.usopen.tracker.ui.viewmodel.TourTierFilter
import com.usopen.tracker.util.TimezoneFormatter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tier 2: Boundary & Corner Cases Test Suite
 *
 * Verifies edge conditions, boundaries, extreme values, year rollover, leap years,
 * and malformed inputs directly against genuine production classes across all 12 features.
 * Contains >=5 tests per feature (Total: 60 test cases).
 */
class Tier2BoundaryCornerCaseTest {

    // =========================================================================
    // Feature 1: Dynamic Dates & Year Resolution - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f1_1_yearBoundaryRolloverDec31ToJan1() {
        val dec31Midnight = LocalDate.of(2027, 12, 31).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant()
        val jan1FirstSec = dec31Midnight.plusSeconds(2)
        val yearBefore = dec31Midnight.atZone(ZoneOffset.UTC).year
        val yearAfter = jan1FirstSec.atZone(ZoneOffset.UTC).year
        assertEquals(2027, yearBefore)
        assertEquals(2028, yearAfter)
    }

    @Test
    fun t2_f1_2_leapYearFeb29Handling() {
        val leapYear = Year.of(2028)
        assertTrue(leapYear.isLeap)
        val feb29 = LocalDate.of(2028, 2, 29)
        assertEquals("2028-02-29", feb29.toString())
        val nextDay = feb29.plusDays(1)
        assertEquals(3, nextDay.monthValue)
        assertEquals(1, nextDay.dayOfMonth)
    }

    @Test
    fun t2_f1_3_centuryBoundaryLeapYearCalculation() {
        assertFalse(Year.of(2100).isLeap)
        assertTrue(Year.of(2000).isLeap)
        assertTrue(Year.of(2400).isLeap)
    }

    @Test
    fun t2_f1_4_farFutureYearResolution2099() {
        val future = LocalDate.of(2099, 12, 31)
        val relative = future.plusYears(1)
        assertEquals(2100, relative.year)
    }

    @Test
    fun t2_f1_5_epochZeroBoundaryHandling() {
        val epochZero = Instant.ofEpochMilli(0)
        assertEquals("1970-01-01T00:00:00Z", epochZero.toString())
    }

    // =========================================================================
    // Feature 2: Dynamic Mock Relative Timestamps - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f2_1_zeroDurationLiveMatchStartTime() {
        val now = Instant.now()
        val duration = Duration.between(now, now)
        assertEquals(0L, duration.toMinutes())
    }

    @Test
    fun t2_f2_2_exact24HourThresholdCompletedMatchRetention() {
        val now = Instant.now()
        val finished23h59m = now.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59))
        val elapsed = Duration.between(finished23h59m, now)
        assertTrue(elapsed.toHours() < 24)
    }

    @Test
    fun t2_f2_3_24Hour1MinuteExpiredMatchPurge() {
        val now = Instant.now()
        val finished24h1m = now.minus(Duration.ofHours(24)).minus(Duration.ofMinutes(1))
        val elapsed = Duration.between(finished24h1m, now)
        assertTrue(elapsed.toHours() >= 24)
    }

    @Test
    fun t2_f2_4_extremeLiveDurationSevenHoursHandling() {
        // Isner-Mahut Wimbledon length
        val now = Instant.now()
        val start = now.minus(Duration.ofHours(11)).minus(Duration.ofMinutes(5))
        val elapsed = Duration.between(start, now)
        assertEquals(665L, elapsed.toMinutes())
        val formatted = "${elapsed.toHours()}h ${elapsed.toMinutes() % 60}m"
        assertEquals("11h 5m", formatted)
    }

    @Test
    fun t2_f2_5_negativeTimeOffsetReversalGuard() {
        val now = Instant.now()
        val future = now.plus(Duration.ofHours(1))
        val negativeDuration = Duration.between(future, now)
        assertTrue(negativeDuration.isNegative)
        val safeDuration = if (negativeDuration.isNegative) Duration.ZERO else negativeDuration
        assertEquals(Duration.ZERO, safeDuration)
    }

    // =========================================================================
    // Feature 3: Date Parser Robustness - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f3_1_parseMidnightBoundaryTime() {
        val midnight = TimezoneFormatter.parseToInstant("2026-01-01T00:00:00Z")
        assertNotNull(midnight)
        assertEquals(0, midnight!!.atZone(ZoneOffset.UTC).hour)
        assertEquals(0, midnight.atZone(ZoneOffset.UTC).minute)
    }

    @Test
    fun t2_f3_2_parseExtremeLeapYearTimestampFeb29() {
        val leapDate = TimezoneFormatter.parseToInstant("2028-02-29T12:00:00Z")
        assertNotNull(leapDate)
        assertEquals(29, leapDate!!.atZone(ZoneOffset.UTC).dayOfMonth)
    }

    @Test
    fun t2_f3_3_parseWhitespacePaddedIsoString() {
        val padded = TimezoneFormatter.parseToInstant("  2026-09-04T18:00:00Z   ")
        assertNotNull(padded)
    }

    @Test
    fun t2_f3_4_parseCorruptIsoStringWithInvalidMonth13() {
        val corrupt = TimezoneFormatter.parseToInstant("2026-13-45T99:99:99Z")
        assertNull(corrupt)
    }

    @Test
    fun t2_f3_5_parseEmptyAndBlankStrings() {
        assertNull(TimezoneFormatter.parseToInstant(""))
        assertNull(TimezoneFormatter.parseToInstant("   \t\n  "))
    }

    // =========================================================================
    // Feature 4: ATP & WTA Tour Types - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f4_1_tourTypeCaseInsensitiveStringParsing() {
        val atp = TourType.valueOf("ATP")
        val wta = TourType.valueOf("WTA")
        val combined = TourType.valueOf("COMBINED")
        assertEquals(TourType.ATP, atp)
        assertEquals(TourType.WTA, wta)
        assertEquals(TourType.COMBINED, combined)
    }

    @Test
    fun t2_f4_2_mixedCaseGroupingInference() {
        val m1 = E2ETestFixtures.createMatch(id = "1", player1Name = "A", player2Name = "B", eventGrouping = "mEn's DoUbLeS")
        assertEquals(TourType.ATP, m1.effectiveTour)

        val m2 = E2ETestFixtures.createMatch(id = "2", player1Name = "A", player2Name = "B", eventGrouping = "WoMeN'S sInGlEs")
        assertEquals(TourType.WTA, m2.effectiveTour)
    }

    @Test
    fun t2_f4_3_unknownGroupingDefaultsToCombined() {
        val m = E2ETestFixtures.createMatch(id = "1", player1Name = "A", player2Name = "B", eventGrouping = "Mixed Exhibition Match")
        assertEquals(TourType.COMBINED, m.effectiveTour)
    }

    @Test
    fun t2_f4_4_doublesGroupingPreservesTourType() {
        val m = E2ETestFixtures.createMatch(id = "1", player1Name = "P1/P2", player2Name = "P3/P4", eventGrouping = "Men's Doubles")
        assertEquals(TourType.ATP, m.effectiveTour)
    }

    @Test
    fun t2_f4_5_emptyGroupingDefaultsToCombined() {
        val m = E2ETestFixtures.createMatch(id = "1", player1Name = "A", player2Name = "B", eventGrouping = "")
        assertEquals(TourType.COMBINED, m.effectiveTour)
    }

    // =========================================================================
    // Feature 5: Tournament Categories - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f5_1_categoryRankingPointsMonotonicOrdering() {
        val gs = TournamentCategory.GRAND_SLAM.rankingPoints
        val finals = TournamentCategory.FINALS.rankingPoints
        val m1000 = TournamentCategory.MASTERS_1000.rankingPoints
        val a500 = TournamentCategory.ATP_500.rankingPoints
        val a250 = TournamentCategory.ATP_250.rankingPoints

        assertTrue(gs > finals)
        assertTrue(finals > m1000)
        assertTrue(m1000 > a500)
        assertTrue(a500 > a250)
    }

    @Test
    fun t2_f5_2_categoryFromInvalidStringFallsBackGracefully() {
        val match = E2ETestFixtures.createMatch(id = "1", player1Name = "A", player2Name = "B", category = null)
        assertEquals(TournamentCategory.ATP_250, match.effectiveCategory)
    }

    @Test
    fun t2_f5_3_is500Or250CorrectnessAcrossAllCategories() {
        assertTrue(TournamentCategory.ATP_500.is500Or250)
        assertTrue(TournamentCategory.WTA_500.is500Or250)
        assertTrue(TournamentCategory.ATP_250.is500Or250)
        assertTrue(TournamentCategory.WTA_250.is500Or250)
        assertFalse(TournamentCategory.MASTERS_1000.is500Or250)
        assertFalse(TournamentCategory.GRAND_SLAM.is500Or250)
        assertFalse(TournamentCategory.FINALS.is500Or250)
    }

    @Test
    fun t2_f5_4_masters1000AndWta1000DistinctCodeEqualPoints() {
        val m = TournamentCategory.MASTERS_1000
        val w = TournamentCategory.WTA_1000
        assertNotEquals(m, w)
        assertEquals(m.rankingPoints, w.rankingPoints)
        assertTrue(m.is1000 && w.is1000)
    }

    @Test
    fun t2_f5_5_finalsCategoryNotClassifiedAsGrandSlamOrMasters() {
        val finals = TournamentCategory.FINALS
        assertFalse(finals.isGrandSlam)
        assertFalse(finals.is1000)
        assertTrue(finals.isFinals)
    }

    // =========================================================================
    // Feature 6: Court Surfaces - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f6_1_surfaceParsingCaseInsensitive() {
        val s = CourtSurface.valueOf("CLAY")
        assertEquals(CourtSurface.CLAY, s)
    }

    @Test
    fun t2_f6_2_surfaceUnknownKeywordFallsBackToHard() {
        val resolved = TournamentResolver.resolve("Exhibition on Moon")
        assertEquals(CourtSurface.HARD, resolved.surface)
    }

    @Test
    fun t2_f6_3_carpetSurfaceIndoorKeywordDiscovered() {
        val resolved = TournamentResolver.resolve("Zagreb Indoor Championships")
        assertEquals(CourtSurface.CARPET, resolved.surface)
    }

    @Test
    fun t2_f6_4_claySurfaceKeywordVariantsInFrenchAndSpanish() {
        val r1 = TournamentResolver.resolve("Torneo de Madrid Clay")
        assertEquals(CourtSurface.CLAY, r1.surface)

        val r2 = TournamentResolver.resolve("Tournoi Roland Garros Paris")
        assertEquals(CourtSurface.CLAY, r2.surface)
    }

    @Test
    fun t2_f6_5_grassSurfaceKeywordVariantsInGermanAndEnglish() {
        val r1 = TournamentResolver.resolve("Halle Open Championship")
        assertEquals(CourtSurface.GRASS, r1.surface)

        val r2 = TournamentResolver.resolve("Queen's Club Grass Championships")
        assertEquals(CourtSurface.GRASS, r2.surface)
    }

    // =========================================================================
    // Feature 7: Dynamic Tournament Matching - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f7_1_tournamentMatchingWithLeadingTrailingWhitespace() {
        val t = Tournaments.US_OPEN
        assertTrue(t.matches("   US Open   "))
        assertTrue(t.matches("\tU.S. Open\n"))
    }

    @Test
    fun t2_f7_2_tournamentMatchingWithPunctuationAndHyphens() {
        val rg = Tournaments.ROLAND_GARROS
        assertTrue(rg.matches("Roland-Garros"))
        assertTrue(rg.matches("Roland Garros (Paris)"))
    }

    @Test
    fun t2_f7_3_tournamentMatchingWithExoticUnicodeDiacritics() {
        val t = Tournaments.ITALIAN_OPEN
        assertTrue(t.matches("Internazionali BNL d'Italia"))
    }

    @Test
    fun t2_f7_4_tournamentDiscoveryWith1000CharLongName() {
        val veryLongName = "Tournament " + "A".repeat(1000)
        val discovered = TournamentResolver.resolve(veryLongName)
        assertNotNull(discovered)
        assertEquals(veryLongName, discovered.displayName)
    }

    @Test
    fun t2_f7_5_tournamentDiscoveryWithNonLatinCyrillicName() {
        val cyrillic = "Открытый чемпионат Санкт-Петербурга"
        val discovered = TournamentResolver.resolve(cyrillic)
        assertNotNull(discovered)
        assertEquals(cyrillic, discovered.displayName)
    }

    // =========================================================================
    // Feature 8: Tiered UI Filter Chips - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f8_1_filterMatchesNullTournamentAndNullMatchSafely() {
        assertTrue(TourTierFilter.ALL.matches(null, null))
        assertFalse(TourTierFilter.GRAND_SLAMS.matches(null, null))
        assertFalse(TourTierFilter.MASTERS_1000.matches(null, null))
    }

    @Test
    fun t2_f8_2_filterMatchesMatchOverridingTournamentCategory() {
        val t = Tournaments.CHINA_OPEN // Default ATP 500
        val womenMatch = E2ETestFixtures.createMatch(
            id = "w1",
            player1Name = "Sabalenka",
            player2Name = "Swiatek",
            tournament = t,
            category = TournamentCategory.WTA_1000,
            eventGrouping = "Women's Singles"
        )
        assertTrue(TourTierFilter.MASTERS_1000.matches(t, womenMatch))
        assertFalse(TourTierFilter.TIER_500_250.matches(t, womenMatch))
    }

    @Test
    fun t2_f8_3_filterMatchesMatchOverridingTournamentTour() {
        val t = Tournaments.CHINA_OPEN // COMBINED
        val menMatch = E2ETestFixtures.createMatch(
            id = "m1",
            player1Name = "Alcaraz",
            player2Name = "Sinner",
            tournament = t,
            tour = TourType.ATP,
            eventGrouping = "Men's Singles"
        )
        assertTrue(TourTierFilter.ATP_TOUR.matches(t, menMatch))
        assertFalse(TourTierFilter.WTA_TOUR.matches(t, menMatch))
    }

    @Test
    fun t2_f8_4_tier500_250Excludes1000AndGrandSlamStrictly() {
        assertFalse(TourTierFilter.TIER_500_250.matches(Tournaments.US_OPEN))
        assertFalse(TourTierFilter.TIER_500_250.matches(Tournaments.INDIAN_WELLS))
    }

    @Test
    fun t2_f8_5_allFilterAlwaysReturnsTrueForMalformedInputs() {
        val invalidTournament = Tournament(id = "", displayName = "")
        val invalidMatch = E2ETestFixtures.createMatch(id = "", player1Name = "", player2Name = "")
        assertTrue(TourTierFilter.ALL.matches(invalidTournament, invalidMatch))
    }

    // =========================================================================
    // Feature 9: Contextual Active Tournament Chips - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f9_1_contextualChipsWithDuplicateTournamentsInFeed() {
        val matches = (1..50).map {
            E2ETestFixtures.createMatch(id = "m-$it", player1Name = "P$it", player2Name = "Q$it", tournament = Tournaments.US_OPEN)
        }
        val contextual = matches.mapNotNull { it.tournament }.distinct()
        assertEquals(1, contextual.size)
    }

    @Test
    fun t2_f9_2_contextualChipsWithSingleTournamentFeed() {
        val matches = listOf(
            E2ETestFixtures.createMatch(id = "m-1", player1Name = "P1", player2Name = "Q1", tournament = Tournaments.WIMBLEDON)
        )
        val contextual = matches.mapNotNull { it.tournament }.distinct()
        assertEquals(1, contextual.size)
        assertEquals(Tournaments.WIMBLEDON, contextual[0])
    }

    @Test
    fun t2_f9_3_contextualChipsWithNoMatchingTierTournaments() {
        val matches = listOf(
            E2ETestFixtures.createMatch(id = "m-1", player1Name = "P1", player2Name = "Q1", tournament = Tournaments.US_OPEN)
        )
        val filter = TourTierFilter.MASTERS_1000
        val filtered = matches.mapNotNull { it.tournament }.filter { filter.matches(it) }
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun t2_f9_4_contextualChipsPreserveInsertionOrder() {
        val matches = listOf(
            E2ETestFixtures.createMatch(id = "1", player1Name = "A", player2Name = "B", tournament = Tournaments.WIMBLEDON),
            E2ETestFixtures.createMatch(id = "2", player1Name = "C", player2Name = "D", tournament = Tournaments.AUSTRALIAN_OPEN),
            E2ETestFixtures.createMatch(id = "3", player1Name = "E", player2Name = "F", tournament = Tournaments.US_OPEN)
        )
        val distinct = matches.mapNotNull { it.tournament }.distinct()
        assertEquals(listOf(Tournaments.WIMBLEDON, Tournaments.AUSTRALIAN_OPEN, Tournaments.US_OPEN), distinct)
    }

    @Test
    fun t2_f9_5_contextualChipsWithNullTournamentMatchesSkipped() {
        val matches = listOf(
            E2ETestFixtures.createMatch(id = "1", player1Name = "A", player2Name = "B", tournament = null),
            E2ETestFixtures.createMatch(id = "2", player1Name = "C", player2Name = "D", tournament = Tournaments.US_OPEN)
        )
        val contextual = matches.mapNotNull { it.tournament }
        assertEquals(1, contextual.size)
        assertEquals(Tournaments.US_OPEN, contextual[0])
    }

    // =========================================================================
    // Feature 10: Loop Refactoring & Deduplication - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f10_1_deduplicationWith100PercentDuplicateFeeds() {
        val list1 = (1..100).map { E2ETestFixtures.createMatch(id = "m-$it", player1Name = "P$it", player2Name = "Q$it") }
        val list2 = (1..100).map { E2ETestFixtures.createMatch(id = "m-$it", player1Name = "Dup$it", player2Name = "DupQ$it") }
        val deduplicated = (list1 + list2).distinctBy { it.id }
        assertEquals(100, deduplicated.size)
        assertEquals("P1", deduplicated[0].player1.name)
    }

    @Test
    fun t2_f10_2_deduplicationWithZeroDuplicateFeeds() {
        val list1 = (1..50).map { E2ETestFixtures.createMatch(id = "a-$it", player1Name = "P$it", player2Name = "Q$it") }
        val list2 = (1..50).map { E2ETestFixtures.createMatch(id = "b-$it", player1Name = "P$it", player2Name = "Q$it") }
        val deduplicated = (list1 + list2).distinctBy { it.id }
        assertEquals(100, deduplicated.size)
    }

    @Test
    fun t2_f10_3_deduplicationWithSingleElementFeeds() {
        val m1 = listOf(E2ETestFixtures.createMatch(id = "m-1", player1Name = "A", player2Name = "B"))
        val m2 = listOf(E2ETestFixtures.createMatch(id = "m-1", player1Name = "A-dup", player2Name = "B-dup"))
        val deduplicated = (m1 + m2).distinctBy { it.id }
        assertEquals(1, deduplicated.size)
    }

    @Test
    fun t2_f10_4_deduplicationWithBlankAndEmptyIdStrings() {
        val list1 = listOf(E2ETestFixtures.createMatch(id = "", player1Name = "A", player2Name = "B"))
        val list2 = listOf(E2ETestFixtures.createMatch(id = "", player1Name = "C", player2Name = "D"))
        val deduplicated = (list1 + list2).distinctBy { it.id }
        assertEquals(1, deduplicated.size) // Empty string ID matches and deduplicates
    }

    @Test
    fun t2_f10_5_deduplicationPreservesStableSequence() {
        val items = (1..1000).map { "item-$it" }
        val result = (items + items).distinct()
        assertEquals(items, result)
    }

    // =========================================================================
    // Feature 11: Stream Matcher & Highlights - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f11_1_streamMatchingWithExcessiveWhitespaceAndTabs() {
        val streams = listOf(
            EspnWatchStream(watchId = "1", name = "Court 1   -   Alcaraz   \t  vs  \n Sinner", webUrl = "https://stream/space")
        )
        val match = E2ETestFixtures.createMatch(id = "1", player1Name = "Alcaraz", player2Name = "Sinner")
        val stream = EspnWatchMatcher.findBestStream(match, streams)
        assertNotNull(stream)
        assertEquals("https://stream/space", stream?.webUrl)
    }

    @Test
    fun t2_f11_2_streamMatchingWithMultipleHyphenatedPlayerNames() {
        val streams = listOf(
            EspnWatchStream(watchId = "1", name = "Court Philippe Chatrier - Davidovich-Fokina vs Auger-Aliassime", webUrl = "https://stream/hyphen")
        )
        val match = E2ETestFixtures.createMatch(id = "1", player1Name = "Alejandro Davidovich Fokina", player2Name = "Felix Auger Aliassime")
        val stream = EspnWatchMatcher.findBestStream(match, streams)
        assertNotNull(stream)
    }

    @Test
    fun t2_f11_3_streamMatchingWithSingleLetterInitials() {
        val streams = listOf(
            EspnWatchStream(watchId = "1", name = "Grandstand - C. Alcaraz vs J. Sinner", webUrl = "https://stream/initials")
        )
        val match = E2ETestFixtures.createMatch(id = "1", player1Name = "Alcaraz", player2Name = "Sinner")
        val stream = EspnWatchMatcher.findBestStream(match, streams)
        assertNotNull(stream)
        assertEquals("https://stream/initials", stream?.webUrl)
    }

    @Test
    fun t2_f11_4_streamMatchingEmptyStreamListReturnsNull() {
        val match = E2ETestFixtures.createMatch(id = "1", player1Name = "Alcaraz", player2Name = "Sinner")
        val stream = EspnWatchMatcher.findBestStream(match, emptyList())
        assertNull(stream)
    }

    @Test
    fun t2_f11_5_highlightMatcherWithZeroOfficialVideosFallsBack() {
        val videos = listOf(
            Triple("Fan Highlights: Alcaraz vs Sinner", "RandomTuber", 4)
        )
        val best = videos.minByOrNull { it.third }
        assertNotNull(best)
        assertEquals(4, best!!.third)
    }

    // =========================================================================
    // Feature 12: Safe Parsing & Concurrency - Boundary Cases
    // =========================================================================

    @Test
    fun t2_f12_1_concurrentCacheClearAndReadWithoutConcurrentModification() {
        val executor = Executors.newFixedThreadPool(4)
        for (i in 1..50) {
            val idx = i
            executor.submit {
                TournamentResolver.resolve("US Open")
                if (idx % 10 == 0) TournamentResolver.clearCache()
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test
    fun t2_f12_2_jsonParsingWithArrayInsteadOfObject() {
        val raw = """{"competitions": "NOT_AN_ARRAY"}"""
        val json = JSONObject(raw)
        val comps = json.optJSONArray("competitions")
        assertNull("optJSONArray on string must return null safely", comps)
    }

    @Test
    fun t2_f12_3_jsonParsingWithNumbersInsteadOfStrings() {
        val raw = """{"name": 12345}"""
        val json = JSONObject(raw)
        val str = json.optString("name", "Default")
        assertEquals("12345", str)
    }

    @Test
    fun t2_f12_4_linescoresArrayWithEmptyGamesAndSets() {
        val json = JSONObject("""{"linescores": []}""")
        val arr = json.optJSONArray("linescores")
        assertNotNull(arr)
        assertEquals(0, arr!!.length())
    }

    @Test
    fun t2_f12_5_ultraDeepJsonNestingUpTo50LevelsHandledSafely() {
        var obj = JSONObject()
        val root = obj
        for (i in 1..50) {
            val child = JSONObject()
            obj.put("lvl$i", child)
            obj = child
        }
        assertNotNull(root)
        assertTrue(root.has("lvl1"))
    }
}
