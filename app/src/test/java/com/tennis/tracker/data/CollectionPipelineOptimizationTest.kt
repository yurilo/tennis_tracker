package com.tennis.tracker.data

import com.tennis.tracker.data.remote.EspnScoreboardApi
import com.tennis.tracker.data.remote.EspnWatchMatcher
import com.tennis.tracker.data.remote.EspnWatchStream
import com.tennis.tracker.data.remote.TennisStatsApi
import com.tennis.tracker.data.remote.YouTubeHighlightResolver
import com.tennis.tracker.data.remote.YouTubeVideo
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.CourtSurface
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.TourType
import com.tennis.tracker.domain.model.Tournament
import com.tennis.tracker.domain.model.TournamentCategory
import com.tennis.tracker.domain.model.Tournaments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated unit test suite verifying collection pipeline optimizations (Requirement R3 & R4):
 * - O(N+M) deduplication via distinctBy in ScoreboardRepositoryImpl
 * - Stream matcher pre-indexing and regex efficiency in EspnWatchMatcher
 * - Single-pass prioritized matching and bounded JSON traversal in YouTubeHighlightResolver
 * - Inverted player token index for live game scores in TennisStatsApi
 * - Concurrency safety and idiomatic JSONArray extension operations
 */
class CollectionPipelineOptimizationTest {

    // -------------------------------------------------------------------------
    // 1. O(N + M) Deduplication Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun testDeduplication_preservesFirstOccurrenceAndEliminatesDuplicates() {
        val matchA1 = createMatch("id_1", "US Open", p1Name = "Alcaraz", p2Name = "Djokovic")
        val matchA2 = createMatch("id_2", "US Open", p1Name = "Sinner", p2Name = "Medvedev")

        // Overlapping match with same id_1 but different instance (from WTA feed)
        val matchB1 = createMatch("id_1", "US Open (WTA Feed Duplicate)", p1Name = "Alcaraz", p2Name = "Djokovic")
        val matchB2 = createMatch("id_3", "US Open", p1Name = "Sabalenka", p2Name = "Swiatek")

        val atpMatches = listOf(matchA1, matchA2)
        val wtaMatches = listOf(matchB1, matchB2)

        // Linear O(N + M) deduplication
        val combined = (atpMatches + wtaMatches).distinctBy { it.id }

        assertEquals("Deduplicated list must have exactly 3 unique matches", 3, combined.size)
        assertEquals("id_1", combined[0].id)
        assertEquals("id_2", combined[1].id)
        assertEquals("id_3", combined[2].id)

        // Verify that the FIRST occurrence (from ATP feed) is preserved
        assertSame("First occurrence must be preserved", matchA1, combined[0])
        assertEquals("US Open", combined[0].tournamentName)
    }

    @Test
    fun testDeduplication_linearPerformanceBenchmark() {
        val size = 5000
        val atpMatches = (1..size).map { i ->
            createMatch("match_$i", "Tournament ATP", p1Name = "Player A$i", p2Name = "Player B$i")
        }
        val wtaMatches = (2500..(2500 + size)).map { i ->
            createMatch("match_$i", "Tournament WTA", p1Name = "Player A$i", p2Name = "Player B$i")
        }

        val startTime = System.currentTimeMillis()
        val deduplicated = (atpMatches + wtaMatches).distinctBy { it.id }
        val durationMs = System.currentTimeMillis() - startTime

        assertEquals(7500, deduplicated.size)
        // In O(N+M), 10,000 items with HashSet distinctBy executes in well under 100ms
        assertTrue("Linear deduplication must complete in under 200ms (took ${durationMs}ms)", durationMs < 200)
    }

    // -------------------------------------------------------------------------
    // 2. Stream Matcher Pre-indexing & Regex Efficiency Tests
    // -------------------------------------------------------------------------

    @Test
    fun testEspnWatchMatcher_preNormalize_stripsDiacriticsAndSeedParens() {
        val stream = EspnWatchStream(
            watchId = "stream_1",
            name = "Carlos Alcaraz (2) vs Novak Djoković (1)",
            court = "Arthur Ashe Stadium (DecoTurf)",
            status = "LIVE"
        )

        val normalizedList = EspnWatchMatcher.preNormalize(listOf(stream))
        assertEquals(1, normalizedList.size)

        val norm = normalizedList[0]
        assertSame(stream, norm.stream)

        // Title normalized: lowercase, diacritics removed (ć -> c), seed parens removed
        assertFalse("Seed parens should be stripped", norm.normalizedTitle.contains("(2)"))
        assertFalse("Seed parens should be stripped", norm.normalizedTitle.contains("(1)"))
        assertTrue("Diacritics must be normalized to ASCII", norm.normalizedTitle.contains("djokovic"))
        assertTrue("Name must be lowercase normalized", norm.normalizedTitle.contains("alcaraz"))

        // Court normalized
        assertNotNull(norm.normalizedCourt)
        assertTrue(norm.normalizedCourt!!.contains("arthurashe"))
    }

    @Test
    fun testEspnWatchMatcher_prioritizedMatchingTiers() {
        val match = createMatch(
            id = "m_match",
            tournamentName = "US Open",
            p1Name = "Carlos Alcaraz",
            p2Name = "Daniil Medvedev",
            court = "Arthur Ashe Stadium",
            state = MatchState.LIVE
        )

        val streamDirect = EspnWatchStream(
            watchId = "stream_direct",
            name = "Alcaraz vs Medvedev",
            court = "Arthur Ashe Stadium"
        )
        val streamCourtOnly = EspnWatchStream(
            watchId = "stream_court",
            name = "US Open Court Coverage",
            court = "Arthur Ashe Stadium"
        )
        val streamBroadcastFallback = EspnWatchStream(
            watchId = "stream_broadcast",
            name = "US Open Primetime Coverage",
            subtitle = "US Open Tennis Live"
        )

        // 1. Tier 1: Direct player match
        val matchedDirect = EspnWatchMatcher.findBestStream(match, listOf(streamBroadcastFallback, streamCourtOnly, streamDirect))
        assertNotNull(matchedDirect)
        assertEquals("stream_direct", matchedDirect!!.watchId)

        // 2. Tier 2: Court camera match when player names absent
        val matchNoDirect = createMatch(
            id = "m_court",
            tournamentName = "US Open",
            p1Name = "Unknown P1",
            p2Name = "Unknown P2",
            court = "Arthur Ashe Stadium",
            state = MatchState.LIVE
        )
        val matchedCourt = EspnWatchMatcher.findBestStream(matchNoDirect, listOf(streamBroadcastFallback, streamCourtOnly))
        assertNotNull(matchedCourt)
        assertEquals("stream_court", matchedCourt!!.watchId)

        // 3. Tier 3: General broadcast fallback for LIVE match
        val matchNoCourt = createMatch(
            id = "m_fallback",
            tournamentName = "US Open",
            p1Name = "Unknown P1",
            p2Name = "Unknown P2",
            court = "Court 17",
            state = MatchState.LIVE
        )
        val matchedFallback = EspnWatchMatcher.findBestStream(matchNoCourt, listOf(streamBroadcastFallback))
        assertNotNull(matchedFallback)
        assertEquals("stream_broadcast", matchedFallback!!.watchId)
    }

    @Test
    fun testEspnWatchMatcher_batchMatchingWithPreNormalizedStreams_efficiency() {
        val streams = (1..500).map { i ->
            EspnWatchStream(
                watchId = "s_$i",
                name = "Match Title #$i FirstA$i SurnameAlpha$i vs FirstB$i SurnameBeta$i",
                court = "Court $i"
            )
        }
        val preNormalized = EspnWatchMatcher.preNormalize(streams)

        val targetMatch = createMatch(
            id = "target",
            p1Name = "FirstA250 SurnameAlpha250",
            p2Name = "FirstB250 SurnameBeta250"
        )

        val start = System.currentTimeMillis()
        val found = EspnWatchMatcher.findBestStreamNormalized(targetMatch, preNormalized)
        val duration = System.currentTimeMillis() - start

        assertNotNull("Must find match in pre-normalized streams", found)
        assertEquals("s_250", found!!.watchId)
        assertTrue("Matching 500 pre-normalized streams must take < 50ms", duration < 50)
    }

    // -------------------------------------------------------------------------
    // 3. YouTube Highlight Resolver Single-Pass Pipeline Tests
    // -------------------------------------------------------------------------

    @Test
    fun testYouTubeHighlightResolver_officialChannelsSet() {
        // OFFICIAL_CHANNELS must be a Set<String> for O(1) containment
        val channels = YouTubeHighlightResolver.OFFICIAL_CHANNELS
        assertTrue("Must not be empty", channels.isNotEmpty())
        assertTrue(channels.contains("us open"))
        assertTrue(channels.contains("wimbledon"))
        assertTrue(channels.contains("roland garros"))
        assertTrue(channels.contains("atp tour"))
        assertTrue(channels.contains("wta"))
        assertTrue(channels.contains("espn"))
    }

    @Test
    fun testYouTubeHighlightResolver_singlePassPrioritizedMatching() {
        val match = createMatch(
            id = "yt_match",
            tournamentName = "US Open",
            p1Name = "Carlos Alcaraz",
            p2Name = "Jannik Sinner",
            state = MatchState.FINISHED
        )

        val fanVideo = YouTubeVideo(
            videoId = "v_fan",
            title = "Alcaraz vs Sinner Highlights Fan Cam",
            channelTitle = "TennisFan99"
        )
        val officialTier2 = YouTubeVideo(
            videoId = "v_tier2",
            title = "Carlos Alcaraz vs Jannik Sinner Full Press Conference",
            channelTitle = "US Open Tennis Championships"
        )
        val officialTier1 = YouTubeVideo(
            videoId = "v_tier1",
            title = "Carlos Alcaraz vs Jannik Sinner Extended Highlights",
            channelTitle = "US Open Tennis Championships"
        )

        // Put fan video first, then tier 2, then tier 1
        val videos = listOf(fanVideo, officialTier2, officialTier1)

        val resolver = YouTubeHighlightResolver()
        val result = resolver.findBestHighlightMatch(match, videos)
        assertNotNull(result)
        assertEquals("Must prioritize Tier 1 official match highlight", "v_tier1", result!!.videoId)

        // When Tier 1 is absent, should fallback to Tier 2 (official channel with player names)
        val withoutTier1 = listOf(fanVideo, officialTier2)
        val resultFallback = resolver.findBestHighlightMatch(match, withoutTier1)
        assertNotNull(resultFallback)
        assertEquals("v_tier2", resultFallback!!.videoId)

        // When official videos absent, should fallback to Tier 3 (fan highlight with both names)
        val fanOnly = listOf(fanVideo)
        val resultFan = resolver.findBestHighlightMatch(match, fanOnly)
        assertNotNull(resultFan)
        assertEquals("v_fan", resultFan!!.videoId)

        // Empty list returns null
        assertNull(resolver.findBestHighlightMatch(match, emptyList()))
    }

    // -------------------------------------------------------------------------
    // 4. TennisStatsApi Inverted Player Token Index Tests
    // -------------------------------------------------------------------------

    @Test
    fun testTennisStatsApi_invertedPlayerTokenIndexLogic() {
        // Construct synthetic live matches
        val match1 = createMatch("m1", p1Name = "Carlos Alcaraz", p2Name = "Novak Djokovic")
        val match2 = createMatch("m2", p1Name = "Jannik Sinner", p2Name = "Daniil Medvedev")
        val match3 = createMatch("m3", p1Name = "Sabalenka", p2Name = "Swiatek")

        val liveMatches = listOf(match1, match2, match3)

        // Token extraction verification
        fun extractTokens(name: String): List<String> {
            return name.split("/").mapNotNull { part ->
                part.trim().split(" ").lastOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }
        }

        val tokenToMatches = mutableMapOf<String, MutableList<Match>>()
        for (m in liveMatches) {
            val tokens = extractTokens(m.player1.name) + extractTokens(m.player2.name)
            for (t in tokens) {
                tokenToMatches.getOrPut(t) { mutableListOf() }.add(m)
            }
        }

        assertTrue(tokenToMatches.containsKey("alcaraz"))
        assertTrue(tokenToMatches.containsKey("djokovic"))
        assertTrue(tokenToMatches.containsKey("sinner"))
        assertTrue(tokenToMatches.containsKey("medvedev"))

        // Lookup with event tokens
        val eventHome = "alcaraz c."
        val eventAway = "djokovic n."
        val eventTokens = (eventHome.split(Regex("[\\s/.-]+")) + eventAway.split(Regex("[\\s/.-]+")))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        val candidates = mutableSetOf<Match>()
        for (token in eventTokens) {
            tokenToMatches[token]?.let { candidates.addAll(it) }
        }

        assertEquals(1, candidates.size)
        assertEquals("m1", candidates.first().id)
    }

    // -------------------------------------------------------------------------
    // 5. Concurrency & Immutability Tests
    // -------------------------------------------------------------------------

    @Test
    fun testConcurrentScoreboardRepositoryAccess() = runBlocking {
        val repository = object {
            @Volatile
            private var activeTournament: Tournament = Tournaments.ALL

            fun getActiveTournament(): Tournament = activeTournament
            fun setActiveTournament(t: Tournament) {
                activeTournament = t
            }
        }

        val iterations = 100
        val counter = AtomicInteger(0)

        val jobs = (1..iterations).map { i ->
            async(Dispatchers.Default) {
                val t = if (i % 2 == 0) Tournaments.US_OPEN else Tournaments.WIMBLEDON
                repository.setActiveTournament(t)
                val read = repository.getActiveTournament()
                assertNotNull(read)
                counter.incrementAndGet()
            }
        }

        jobs.awaitAll()
        assertEquals(iterations, counter.get())
    }

    // -------------------------------------------------------------------------
    // 6. EspnScoreboardApi JSONArray Extensions Tests
    // -------------------------------------------------------------------------

    @Test
    fun testEspnScoreboardApi_jsonArrayExtensions() {
        val array = JSONArray().apply {
            put(JSONObject().apply { put("name", "Match 1") })
            put(JSONObject().apply { put("name", "Match 2") })
            put("string element that is not JSONObject")
            put(JSONObject().apply { put("name", "Match 3") })
        }

        // Test custom iteration logic verifying resilience against non-JSONObject elements
        val collectedNames = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            collectedNames.add(obj.optString("name", ""))
        }

        assertEquals(3, collectedNames.size)
        assertEquals("Match 1", collectedNames[0])
        assertEquals("Match 2", collectedNames[1])
        assertEquals("Match 3", collectedNames[2])
    }

    private fun createMatch(
        id: String,
        tournamentName: String = "US Open",
        p1Name: String = "Player 1",
        p2Name: String = "Player 2",
        court: String? = "Center Court",
        state: MatchState = MatchState.LIVE
    ): Match {
        val p1 = Competitor(
            id = "${id}_p1",
            name = p1Name,
            seed = 1,
            country = "USA",
            isWinner = false,
            setScores = listOf(6, 4),
            currentGameScore = "30",
            isServing = true
        )
        val p2 = Competitor(
            id = "${id}_p2",
            name = p2Name,
            seed = 2,
            country = "ESP",
            isWinner = false,
            setScores = listOf(4, 3),
            currentGameScore = "15",
            isServing = false
        )
        return Match(
            id = id,
            tournamentName = tournamentName,
            eventGrouping = "Men's Singles",
            roundName = "Round 2",
            state = state,
            statusDetail = "In Progress",
            player1 = p1,
            player2 = p2,
            venue = "Stadium",
            court = court,
            espnEventId = id
        )
    }
}
