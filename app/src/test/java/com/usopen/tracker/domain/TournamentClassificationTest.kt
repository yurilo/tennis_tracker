package com.usopen.tracker.domain

import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.TourType
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.TournamentCategory
import com.usopen.tracker.domain.model.TournamentResolver
import com.usopen.tracker.domain.model.Tournaments
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dedicated unit test suite verifying ATP & WTA tournament abstractions and live classification (Requirement R2).
 * Verifies TourType, TournamentCategory, CourtSurface, Tournament canonical registries,
 * TournamentResolver matching/discovery/refinement, and Match effective metadata properties.
 */
class TournamentClassificationTest {

    @Before
    fun setUp() {
        TournamentResolver.clearCache()
    }

    // -------------------------------------------------------------------------
    // 1. TourType Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTourType_properties() {
        // ATP
        assertEquals("ATP Tour", TourType.ATP.displayName)
        assertEquals("ATP", TourType.ATP.code)
        assertTrue(TourType.ATP.isAtp)
        assertFalse(TourType.ATP.isWta)

        // WTA
        assertEquals("WTA Tour", TourType.WTA.displayName)
        assertEquals("WTA", TourType.WTA.code)
        assertFalse(TourType.WTA.isAtp)
        assertTrue(TourType.WTA.isWta)

        // COMBINED
        assertEquals("Combined", TourType.COMBINED.displayName)
        assertEquals("ATP/WTA", TourType.COMBINED.code)
        assertTrue(TourType.COMBINED.isAtp)
        assertTrue(TourType.COMBINED.isWta)
    }

    @Test
    fun testTourType_fromCode() {
        assertEquals(TourType.ATP, TourType.fromCode("atp"))
        assertEquals(TourType.ATP, TourType.fromCode("ATP"))
        assertEquals(TourType.WTA, TourType.fromCode("wta"))
        assertEquals(TourType.WTA, TourType.fromCode("WTA"))
        assertEquals(TourType.COMBINED, TourType.fromCode("combined"))
        assertEquals(TourType.COMBINED, TourType.fromCode("ATP/WTA"))
        assertEquals(TourType.COMBINED, TourType.fromCode("joint"))
        assertEquals(TourType.COMBINED, TourType.fromCode(null))
        assertEquals(TourType.COMBINED, TourType.fromCode("UNKNOWN"))
    }

    // -------------------------------------------------------------------------
    // 2. TournamentCategory Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTournamentCategory_rankingPointsAndTiers() {
        // Grand Slam: 2000 points
        val gs = TournamentCategory.GRAND_SLAM
        assertEquals(2000, gs.rankingPoints)
        assertTrue(gs.isGrandSlam)
        assertFalse(gs.is1000)
        assertFalse(gs.is500)
        assertFalse(gs.is250)
        assertFalse(gs.is500Or250)
        assertFalse(gs.isFinals)
        assertEquals(TourType.COMBINED, gs.tourType)

        // Masters 1000 & WTA 1000: 1000 points
        val m1000 = TournamentCategory.MASTERS_1000
        assertEquals(1000, m1000.rankingPoints)
        assertFalse(m1000.isGrandSlam)
        assertTrue(m1000.is1000)
        assertFalse(m1000.is500Or250)
        assertEquals(TourType.ATP, m1000.tourType)

        val w1000 = TournamentCategory.WTA_1000
        assertEquals(1000, w1000.rankingPoints)
        assertTrue(w1000.is1000)
        assertEquals(TourType.WTA, w1000.tourType)

        // 500s: 500 points
        val a500 = TournamentCategory.ATP_500
        assertEquals(500, a500.rankingPoints)
        assertTrue(a500.is500)
        assertTrue(a500.is500Or250)
        assertEquals(TourType.ATP, a500.tourType)

        val w500 = TournamentCategory.WTA_500
        assertEquals(500, w500.rankingPoints)
        assertTrue(w500.is500)
        assertTrue(w500.is500Or250)
        assertEquals(TourType.WTA, w500.tourType)

        // 250s: 250 points
        val a250 = TournamentCategory.ATP_250
        assertEquals(250, a250.rankingPoints)
        assertTrue(a250.is250)
        assertTrue(a250.is500Or250)
        assertEquals(TourType.ATP, a250.tourType)

        val w250 = TournamentCategory.WTA_250
        assertEquals(250, w250.rankingPoints)
        assertTrue(w250.is250)
        assertTrue(w250.is500Or250)
        assertEquals(TourType.WTA, w250.tourType)

        // Finals: 1500 points
        val finals = TournamentCategory.FINALS
        assertEquals(1500, finals.rankingPoints)
        assertTrue(finals.isFinals)
        assertFalse(finals.isGrandSlam)
        assertFalse(finals.is1000)
        assertEquals(TourType.COMBINED, finals.tourType)
    }

    @Test
    fun testTournamentCategory_fromString() {
        assertEquals(TournamentCategory.GRAND_SLAM, TournamentCategory.fromString("GRAND_SLAM"))
        assertEquals(TournamentCategory.GRAND_SLAM, TournamentCategory.fromString("Grand Slam"))
        assertEquals(TournamentCategory.GRAND_SLAM, TournamentCategory.fromString("GS"))
        assertEquals(TournamentCategory.MASTERS_1000, TournamentCategory.fromString("Masters 1000"))
        assertEquals(TournamentCategory.ATP_500, TournamentCategory.fromString("ATP 500"))
        assertEquals(TournamentCategory.ATP_250, TournamentCategory.fromString("ATP 250"))
        assertEquals(TournamentCategory.FINALS, TournamentCategory.fromString("Year-End Finals"))
        assertEquals(TournamentCategory.FINALS, TournamentCategory.fromString("Finals"))
        assertNull(TournamentCategory.fromString(null))
        assertNull(TournamentCategory.fromString(""))
        assertNull(TournamentCategory.fromString("NON_EXISTENT"))
    }

    // -------------------------------------------------------------------------
    // 3. CourtSurface Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testCourtSurface_propertiesAndColor() {
        assertEquals("Hard Court", CourtSurface.HARD.displayName)
        assertEquals("🟦", CourtSurface.HARD.icon)
        assertNotNull(CourtSurface.HARD.color)

        assertEquals("Clay Court", CourtSurface.CLAY.displayName)
        assertEquals("🟧", CourtSurface.CLAY.icon)
        assertNotNull(CourtSurface.CLAY.color)

        assertEquals("Grass Court", CourtSurface.GRASS.displayName)
        assertEquals("🟩", CourtSurface.GRASS.icon)
        assertNotNull(CourtSurface.GRASS.color)

        assertEquals("Carpet/Indoor", CourtSurface.CARPET.displayName)
        assertEquals("🟪", CourtSurface.CARPET.icon)
        assertNotNull(CourtSurface.CARPET.color)
    }

    @Test
    fun testCourtSurface_fromNameInference() {
        assertEquals(CourtSurface.CLAY, CourtSurface.fromName("Red Clay Court"))
        assertEquals(CourtSurface.CLAY, CourtSurface.fromName("Terre Battue"))
        assertEquals(CourtSurface.GRASS, CourtSurface.fromName("Grass Court"))
        assertEquals(CourtSurface.GRASS, CourtSurface.fromName("Gazon"))
        assertEquals(CourtSurface.CARPET, CourtSurface.fromName("Indoor Carpet"))
        assertEquals(CourtSurface.CARPET, CourtSurface.fromName("Indoor Taraflex"))
        assertEquals(CourtSurface.HARD, CourtSurface.fromName("Hard Court"))
        assertEquals(CourtSurface.HARD, CourtSurface.fromName(null))
        assertEquals(CourtSurface.HARD, CourtSurface.fromName(""))
        assertEquals(CourtSurface.HARD, CourtSurface.fromName("DecoTurf"))
    }

    // -------------------------------------------------------------------------
    // 4. Canonical Registry & Matching Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testCanonicalTournaments_registryCompleteness() {
        // Grand Slams (4)
        assertEquals(4, Tournaments.GRAND_SLAMS.size)
        assertTrue(Tournaments.GRAND_SLAMS.all { it.isGrandSlam && it.category == TournamentCategory.GRAND_SLAM })

        // Masters 1000s
        assertTrue(Tournaments.MASTERS_1000_LIST.size >= 9)
        assertTrue(Tournaments.MASTERS_1000_LIST.all { it.category.is1000 })

        // Finals
        assertTrue(Tournaments.FINALS_LIST.isNotEmpty())
        assertTrue(Tournaments.FINALS_LIST.all { it.category == TournamentCategory.FINALS })

        // Popular tournaments
        assertTrue(Tournaments.POPULAR_TOURNAMENTS.any { it.id == "all" })
        assertTrue(Tournaments.POPULAR_TOURNAMENTS.any { it.id == "us_open" })
    }

    @Test
    fun testTournamentResolver_matchesCanonicalEvents() {
        // US Open
        val usOpen = TournamentResolver.resolve("US Open")
        assertEquals("us_open", usOpen.id)
        assertTrue(usOpen.isGrandSlam)
        assertEquals(CourtSurface.HARD, usOpen.surface)
        assertEquals(TourType.COMBINED, usOpen.tour)

        // Wimbledon
        val wimbledon = TournamentResolver.resolve("Wimbledon")
        assertEquals("wimbledon", wimbledon.id)
        assertTrue(wimbledon.isGrandSlam)
        assertEquals(CourtSurface.GRASS, wimbledon.surface)

        // Roland Garros via alias "French Open"
        val rolandGarros = TournamentResolver.resolve("French Open")
        assertEquals("roland_garros", rolandGarros.id)
        assertTrue(rolandGarros.isGrandSlam)
        assertEquals(CourtSurface.CLAY, rolandGarros.surface)

        // Australian Open
        val ausOpen = TournamentResolver.resolve("Australian Open")
        assertEquals("australian_open", ausOpen.id)
        assertTrue(ausOpen.isGrandSlam)
        assertEquals(CourtSurface.HARD, ausOpen.surface)

        // Indian Wells via alias
        val indianWells = TournamentResolver.resolve("Indian Wells")
        assertEquals("indian_wells", indianWells.id)
        assertEquals(TournamentCategory.MASTERS_1000, indianWells.category)

        // Monte-Carlo Masters
        val monteCarlo = TournamentResolver.resolve("Rolex Monte-Carlo Masters")
        assertEquals("monte_carlo", monteCarlo.id)
        assertEquals(CourtSurface.CLAY, monteCarlo.surface)

        // "All" keyword returns Tournaments.ALL
        val all = TournamentResolver.resolve("All")
        assertEquals(Tournaments.ALL, all)
        val allTournaments = TournamentResolver.resolve("All Tournaments")
        assertEquals(Tournaments.ALL, allTournaments)
    }

    // -------------------------------------------------------------------------
    // 5. Joint Event Draw Refinement Unit Tests (China Open, Dubai, Washington)
    // -------------------------------------------------------------------------

    @Test
    fun testTournamentResolver_refinesChinaOpenJointEvent() {
        // China Open Men's Singles -> ATP 500
        val mensChina = TournamentResolver.resolve("China Open", groupingName = "Men's Singles")
        assertEquals(TourType.ATP, mensChina.tour)
        assertEquals(TournamentCategory.ATP_500, mensChina.category)
        assertEquals(CourtSurface.HARD, mensChina.surface)

        // China Open Women's Singles -> WTA 1000
        val womensChina = TournamentResolver.resolve("China Open", groupingName = "Women's Singles")
        assertEquals(TourType.WTA, womensChina.tour)
        assertEquals(TournamentCategory.WTA_1000, womensChina.category)
        assertEquals(CourtSurface.HARD, womensChina.surface)
    }

    @Test
    fun testTournamentResolver_refinesDubaiJointEvent() {
        // Dubai Men's -> ATP 500
        val mensDubai = TournamentResolver.resolve("Dubai Duty Free Tennis Championships", groupingName = "Men's Singles")
        assertEquals(TourType.ATP, mensDubai.tour)
        assertEquals(TournamentCategory.ATP_500, mensDubai.category)

        // Dubai Women's -> WTA 1000
        val womensDubai = TournamentResolver.resolve("Dubai Duty Free Tennis Championships", groupingName = "Women's Singles")
        assertEquals(TourType.WTA, womensDubai.tour)
        assertEquals(TournamentCategory.WTA_1000, womensDubai.category)
    }

    @Test
    fun testTournamentResolver_refinesWashingtonJointEvent() {
        // Washington Men's -> ATP 500
        val mensWash = TournamentResolver.resolve("Mubadala Citi DC Open", groupingName = "Men's Singles")
        assertEquals(TourType.ATP, mensWash.tour)
        assertEquals(TournamentCategory.ATP_500, mensWash.category)

        // Washington Women's -> WTA 500
        val womensWash = TournamentResolver.resolve("Mubadala Citi DC Open", groupingName = "Women's Singles")
        assertEquals(TourType.WTA, womensWash.tour)
        assertEquals(TournamentCategory.WTA_500, womensWash.category)
    }

    // -------------------------------------------------------------------------
    // 6. Dynamic Discovery & Inference Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTournamentResolver_dynamicDiscoveryOfUnlistedEvents() {
        // Unlisted ATP 250 event
        val almaty = TournamentResolver.resolve("Almaty Open", groupingName = "Men's Singles")
        assertEquals("almaty_open", almaty.id)
        assertEquals("Almaty Open", almaty.displayName)
        assertEquals(TourType.ATP, almaty.tour)
        assertEquals(TournamentCategory.ATP_250, almaty.category)
        assertFalse(almaty.isGrandSlam)

        // Unlisted WTA 500 event with "500" in name
        val huaHin500 = TournamentResolver.resolve("Hua Hin WTA 500", groupingName = "Women's Singles")
        assertEquals(TourType.WTA, huaHin500.tour)
        assertEquals(TournamentCategory.WTA_500, huaHin500.category)

        // Unlisted event with major flag in JSON -> GRAND_SLAM
        val majorJson = JSONObject().apply {
            put("major", true)
            put("venue", JSONObject().apply { put("displayName", "Custom Major Stadium") })
        }
        val customMajor = TournamentResolver.resolve("Grand Slam Invitational", groupingName = "Men's Singles", eventJson = majorJson)
        assertTrue(customMajor.isGrandSlam)
        assertEquals(TournamentCategory.GRAND_SLAM, customMajor.category)

        // Unlisted Year-End Finals event
        val wtaFinals = TournamentResolver.resolve("WTA Finals Riyadh", groupingName = "Women's Singles")
        assertEquals(TourType.WTA, wtaFinals.tour)
        assertEquals(TournamentCategory.FINALS, wtaFinals.category)
    }

    @Test
    fun testTournamentResolver_surfaceInferenceFromKeywords() {
        // Clay venue keyword
        val clayVenueJson = JSONObject().apply {
            put("venue", JSONObject().apply { put("displayName", "Club de Tenis, Terre Battue, Clay") })
        }
        val clayEvent = TournamentResolver.resolve("Cordoba Open", groupingName = "Men's Singles", eventJson = clayVenueJson)
        assertEquals(CourtSurface.CLAY, clayEvent.surface)

        // Grass tournament keyword
        val grassEvent = TournamentResolver.resolve("Mallorca Grass Championships", groupingName = "Men's Singles")
        assertEquals(CourtSurface.GRASS, grassEvent.surface)

        // Carpet/Indoor keyword
        val indoorEvent = TournamentResolver.resolve("Metz Indoor Open", groupingName = "Men's Singles")
        assertEquals(CourtSurface.CARPET, indoorEvent.surface)

        // Default hard court
        val hardEvent = TournamentResolver.resolve("Auckland Classic", groupingName = "Men's Singles")
        assertEquals(CourtSurface.HARD, hardEvent.surface)
    }

    // -------------------------------------------------------------------------
    // 7. Caching & Match Effective Getters Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTournamentResolver_cachingBehavior() {
        assertEquals(0, TournamentResolver.cachedCount)

        val first = TournamentResolver.resolve("Rolex Paris Masters")
        assertEquals(1, TournamentResolver.cachedCount)

        val second = TournamentResolver.resolve("Rolex Paris Masters")
        assertSame("Subsequent resolution must return cached instance", first, second)
        assertEquals(1, TournamentResolver.cachedCount)

        TournamentResolver.clearCache()
        assertEquals(0, TournamentResolver.cachedCount)
    }

    @Test
    fun testMatch_effectiveGettersFallback() {
        val canonicalUsOpen = Tournaments.US_OPEN

        // Case A: Explicit properties take highest priority
        val matchExplicit = createMatch(
            tour = TourType.ATP,
            category = TournamentCategory.ATP_500,
            surface = CourtSurface.CLAY,
            tournament = canonicalUsOpen
        )
        assertEquals(TourType.ATP, matchExplicit.effectiveTour)
        assertEquals(TournamentCategory.ATP_500, matchExplicit.effectiveCategory)
        assertEquals(CourtSurface.CLAY, matchExplicit.effectiveSurface)

        // Case B: Inferred from attached Tournament
        val matchFromTournament = createMatch(
            tour = null,
            category = null,
            surface = null,
            tournament = canonicalUsOpen
        )
        assertEquals(TourType.COMBINED, matchFromTournament.effectiveTour)
        assertEquals(TournamentCategory.GRAND_SLAM, matchFromTournament.effectiveCategory)
        assertEquals(CourtSurface.HARD, matchFromTournament.effectiveSurface)

        // Case C: Inferred from eventGrouping when tournament is null
        val matchFromGrouping = createMatch(
            tour = null,
            category = null,
            surface = null,
            tournament = null,
            eventGrouping = "Women's Singles"
        )
        assertEquals(TourType.WTA, matchFromGrouping.effectiveTour)
        assertEquals(TournamentCategory.ATP_250, matchFromGrouping.effectiveCategory) // default category
        assertEquals(CourtSurface.HARD, matchFromGrouping.effectiveSurface) // default surface
    }

    // -------------------------------------------------------------------------
    // 8. Tournament Display Name Limiting & shortDisplayName Best Practices
    // -------------------------------------------------------------------------

    @Test
    fun testTournament_shortDisplayName_canonicalAbbreviations() {
        assertEquals("All", Tournaments.ALL.shortDisplayName)
        assertEquals("US Open", Tournaments.US_OPEN.shortDisplayName)
        assertEquals("Australian Open", Tournaments.AUSTRALIAN_OPEN.shortDisplayName)
        assertEquals("Wimbledon", Tournaments.WIMBLEDON.shortDisplayName)

        // Known long tournament names abbreviated cleanly for UI chips
        assertEquals("Dubai", Tournaments.DUBAI_CHAMPIONSHIPS.shortDisplayName)
        assertEquals("Queen's", Tournaments.QUEENS_CLUB.shortDisplayName)
        assertEquals("Monte-Carlo", Tournaments.MONTE_CARLO_MASTERS.shortDisplayName)
        assertEquals("Shanghai", Tournaments.SHANGHAI_MASTERS.shortDisplayName)
        assertEquals("Paris", Tournaments.PARIS_MASTERS.shortDisplayName)
        assertEquals("Eastbourne", Tournaments.EASTBOURNE_INTERNATIONAL.shortDisplayName)
        assertEquals("Brisbane", Tournaments.BRISBANE_INTERNATIONAL.shortDisplayName)
        assertEquals("Adelaide", Tournaments.ADELAIDE_INTERNATIONAL.shortDisplayName)
        assertEquals("Kitzbühel", Tournaments.KITZBUHEL_OPEN.shortDisplayName)
        assertEquals("Gstaad", Tournaments.GSTAAD_OPEN.shortDisplayName)
    }

    @Test
    fun testTournament_shortDisplayName_filterIds() {
        val filter1000 = Tournament(id = "all_1000", displayName = "Masters & WTA 1000")
        assertEquals("All 1000s", filter1000.shortDisplayName)

        val filterSlams = Tournament(id = "all_grand_slams", displayName = "Grand Slam Majors")
        assertEquals("All Slams", filterSlams.shortDisplayName)

        val filter500 = Tournament(id = "all_500_250", displayName = "500s & 250s Tour")
        assertEquals("All 500/250", filter500.shortDisplayName)
    }

    @Test
    fun testTournament_shortDisplayName_truncationFallback() {
        val longTournament = Tournament(
            id = "custom_dynamic_tourney",
            displayName = "Exhibition International Super Cup"
        )
        // Length > 18 characters should be cleanly truncated with an ellipsis
        assertTrue(longTournament.shortDisplayName.endsWith("…"))
        assertTrue(longTournament.shortDisplayName.length <= 17)
        assertEquals("Exhibition Inter…", longTournament.shortDisplayName)

        val shortTournament = Tournament(
            id = "custom_short",
            displayName = "Tokyo Open"
        )
        assertEquals("Tokyo Open", shortTournament.shortDisplayName)
    }

    @Test
    fun testTournament_dubaiAliasesAndMatchingPreserved() {
        val dubai = Tournaments.DUBAI_CHAMPIONSHIPS
        // Verify aliases preserve the full official names
        assertTrue(dubai.aliases.contains("Dubai Tennis Championships"))
        assertTrue(dubai.aliases.contains("Dubai Duty Free Tennis Championships"))

        // Matching preserves backward-compatibility with live feeds
        assertTrue(dubai.matches("Dubai Open"))
        assertTrue(dubai.matches("Dubai Tennis Championships"))
        assertTrue(dubai.matches("Dubai Duty Free Tennis Championships"))
    }

    private fun createMatch(
        tour: TourType?,
        category: TournamentCategory?,
        surface: CourtSurface?,
        tournament: Tournament?,
        eventGrouping: String = "Men's Singles"
    ): Match {
        val dummyCompetitor1 = com.usopen.tracker.domain.model.Competitor(
            id = "p1",
            name = "Player 1",
            seed = 1,
            country = "USA",
            isWinner = false,
            setScores = emptyList(),
            currentGameScore = null,
            isServing = false
        )
        val dummyCompetitor2 = com.usopen.tracker.domain.model.Competitor(
            id = "p2",
            name = "Player 2",
            seed = 2,
            country = "ESP",
            isWinner = false,
            setScores = emptyList(),
            currentGameScore = null,
            isServing = false
        )
        return Match(
            id = "test_match_classification",
            tournamentName = tournament?.displayName ?: "Test Event",
            eventGrouping = eventGrouping,
            roundName = "Final",
            state = MatchState.LIVE,
            statusDetail = "In Progress",
            player1 = dummyCompetitor1,
            player2 = dummyCompetitor2,
            venue = "Center Court",
            court = "Center Court",
            tournament = tournament,
            tour = tour,
            category = category,
            surface = surface
        )
    }
}
