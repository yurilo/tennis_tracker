package com.tennis.tracker.domain

import com.tennis.tracker.data.local.MockTournamentData
import com.tennis.tracker.data.remote.EspnScoreboardApi
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServingIndicatorTest {

    private val api = EspnScoreboardApi()

    private fun invokeParseCompetitors(array: JSONArray, state: MatchState, comp: JSONObject? = null): List<Competitor> {
        val method = EspnScoreboardApi::class.java.getDeclaredMethod(
            "parseCompetitors",
            JSONArray::class.java,
            MatchState::class.java,
            JSONObject::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(api, array, state, comp) as List<Competitor>
    }

    @Test
    fun `live match with possession flags correctly assigns server to player 1`() {
        val jsonArray = JSONArray("""
            [
                {"id": "p1", "name": "Carlos Alcaraz", "possession": true, "linescores": [{"value": 6}, {"value": 4}]},
                {"id": "p2", "name": "Jannik Sinner", "possession": false, "linescores": [{"value": 4}, {"value": 3}]}
            ]
        """.trimIndent())

        val competitors = invokeParseCompetitors(jsonArray, MatchState.LIVE)
        assertEquals(2, competitors.size)
        assertTrue("Player 1 must be serving", competitors[0].isServing)
        assertFalse("Player 2 must NOT be serving", competitors[1].isServing)
    }

    @Test
    fun `live match with possession flags correctly assigns server to player 2`() {
        val jsonArray = JSONArray("""
            [
                {"id": "p1", "name": "Carlos Alcaraz", "possession": false, "linescores": [{"value": 6}]},
                {"id": "p2", "name": "Jannik Sinner", "possession": true, "linescores": [{"value": 4}]}
            ]
        """.trimIndent())

        val competitors = invokeParseCompetitors(jsonArray, MatchState.LIVE)
        assertEquals(2, competitors.size)
        assertFalse("Player 1 must NOT be serving", competitors[0].isServing)
        assertTrue("Player 2 must be serving", competitors[1].isServing)
    }

    @Test
    fun `finished match never marks any player as serving even if raw json has possession`() {
        val jsonArray = JSONArray("""
            [
                {"id": "p1", "name": "Carlos Alcaraz", "possession": true, "winner": true},
                {"id": "p2", "name": "Jannik Sinner", "possession": false, "winner": false}
            ]
        """.trimIndent())

        val competitors = invokeParseCompetitors(jsonArray, MatchState.FINISHED)
        assertEquals(2, competitors.size)
        assertFalse("Player 1 must NOT be serving when match is finished", competitors[0].isServing)
        assertFalse("Player 2 must NOT be serving when match is finished", competitors[1].isServing)
    }

    @Test
    fun `upcoming match never marks any player as serving`() {
        val jsonArray = JSONArray("""
            [
                {"id": "p1", "name": "Aryna Sabalenka", "possession": false},
                {"id": "p2", "name": "Iga Swiatek", "possession": false}
            ]
        """.trimIndent())

        val competitors = invokeParseCompetitors(jsonArray, MatchState.UPCOMING)
        assertEquals(2, competitors.size)
        assertFalse("Player 1 must NOT be serving in upcoming match", competitors[0].isServing)
        assertFalse("Player 2 must NOT be serving in upcoming match", competitors[1].isServing)
    }

    @Test
    fun `live tiebreak fallback alternates serving according to tennis rules`() {
        // Point 1 completed (1-0): Next is points 2 & 3 -> Player 2 serves
        val json1 = JSONArray("""
            [
                {"id": "p1", "name": "Alcaraz", "linescores": [{"value": 6, "tiebreak": 1}]},
                {"id": "p2", "name": "Sinner", "linescores": [{"value": 6, "tiebreak": 0}]}
            ]
        """.trimIndent())
        val comps1 = invokeParseCompetitors(json1, MatchState.LIVE)
        assertFalse("At 1 point played, Player 1 does not serve", comps1[0].isServing)
        assertTrue("At 1 point played, Player 2 serves", comps1[1].isServing)

        // Point 2 completed (2-0): Next is point 3 -> Player 2 serves
        val json2 = JSONArray("""
            [
                {"id": "p1", "name": "Alcaraz", "linescores": [{"value": 6, "tiebreak": 2}]},
                {"id": "p2", "name": "Sinner", "linescores": [{"value": 6, "tiebreak": 0}]}
            ]
        """.trimIndent())
        val comps2 = invokeParseCompetitors(json2, MatchState.LIVE)
        assertFalse("At 2 points played, Player 1 does not serve", comps2[0].isServing)
        assertTrue("At 2 points played, Player 2 serves", comps2[1].isServing)

        // Points 3 completed (2-1): Next is points 4 & 5 -> Player 1 serves
        val json3 = JSONArray("""
            [
                {"id": "p1", "name": "Alcaraz", "linescores": [{"value": 6, "tiebreak": 2}]},
                {"id": "p2", "name": "Sinner", "linescores": [{"value": 6, "tiebreak": 1}]}
            ]
        """.trimIndent())
        val comps3 = invokeParseCompetitors(json3, MatchState.LIVE)
        assertTrue("At 3 points played, Player 1 serves", comps3[0].isServing)
        assertFalse("At 3 points played, Player 2 does not serve", comps3[1].isServing)
    }

    @Test
    fun `mock live matches all have exactly one active server`() {
        val liveMatches = MockTournamentData.matches.filter { it.state == MatchState.LIVE }
        assertTrue("Should have live matches in mock tournament data", liveMatches.isNotEmpty())

        for (match in liveMatches) {
            val p1Serving = match.player1.isServing
            val p2Serving = match.player2.isServing
            assertTrue(
                "Match ${match.id} must have exactly one server, got p1=$p1Serving, p2=$p2Serving",
                (p1Serving && !p2Serving) || (!p1Serving && p2Serving)
            )
        }
    }

    @Test
    fun `mock finished and upcoming matches have zero active servers`() {
        val nonLiveMatches = MockTournamentData.matches.filter { it.state != MatchState.LIVE }
        assertTrue("Should have non-live matches", nonLiveMatches.isNotEmpty())

        for (match in nonLiveMatches) {
            assertFalse("Player 1 must not be serving in match ${match.id}", match.player1.isServing)
            assertFalse("Player 2 must not be serving in match ${match.id}", match.player2.isServing)
        }
    }
}
