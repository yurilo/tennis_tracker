package com.tennis.tracker.data

import com.tennis.tracker.data.remote.EspnScoreboardApi
import com.tennis.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchParsingTest {

    private val parser = EspnScoreboardApi()

    private val sampleJson = """
    {
      "events": [
        {
          "id": "189-2026",
          "name": "US Open",
          "groupings": [
            {
              "grouping": {
                "id": "1",
                "displayName": "Men's Singles"
              },
              "competitions": [
                {
                  "id": "184607",
                  "date": "2026-09-04T18:00Z",
                  "status": {
                    "type": {
                      "id": "3",
                      "state": "in",
                      "completed": false,
                      "description": "In Progress - Set 3"
                    }
                  },
                  "venue": {
                    "fullName": "Arthur Ashe Stadium"
                  },
                  "competitors": [
                    {
                      "id": "1001",
                      "athlete": { "displayName": "Carlos Alcaraz" },
                      "curatedRank": { "current": 2 },
                      "winner": false,
                      "linescores": [{ "value": 6.0 }, { "value": 4.0 }, { "value": 3.0 }]
                    },
                    {
                      "id": "1002",
                      "athlete": { "displayName": "Jannik Sinner" },
                      "curatedRank": { "current": 1 },
                      "winner": false,
                      "linescores": [{ "value": 4.0 }, { "value": 6.0 }, { "value": 2.0 }]
                    }
                  ]
                },
                {
                  "id": "184608",
                  "date": "2026-09-04T22:00Z",
                  "status": {
                    "type": {
                      "id": "1",
                      "state": "pre",
                      "completed": false,
                      "description": "Scheduled"
                    }
                  },
                  "venue": {
                    "fullName": "Louis Armstrong Stadium"
                  },
                  "competitors": [
                    {
                      "id": "1003",
                      "athlete": { "displayName": "Novak Djokovic" },
                      "curatedRank": { "current": 4 },
                      "winner": false,
                      "linescores": []
                    },
                    {
                      "id": "1004",
                      "athlete": { "displayName": "Taylor Fritz" },
                      "curatedRank": { "current": 12 },
                      "winner": false,
                      "linescores": []
                    }
                  ]
                }
              ]
            }
          ]
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `parseScoreboardJson should parse live and upcoming matches correctly`() {
        val matches = parser.parseScoreboardJson(sampleJson)

        assertEquals("Should parse 2 competitions", 2, matches.size)

        val liveMatch = matches[0]
        assertEquals("184607", liveMatch.id)
        assertEquals("US Open", liveMatch.tournamentName)
        assertEquals("Men's Singles", liveMatch.eventGrouping)
        assertEquals(MatchState.LIVE, liveMatch.state)
        assertEquals("Arthur Ashe Stadium", liveMatch.venue)

        assertEquals("Carlos Alcaraz", liveMatch.player1.name)
        assertEquals(2, liveMatch.player1.seed)
        assertEquals(listOf(6, 4, 3), liveMatch.player1.setScores)

        assertEquals("Jannik Sinner", liveMatch.player2.name)
        assertEquals(1, liveMatch.player2.seed)
        assertEquals(listOf(4, 6, 2), liveMatch.player2.setScores)

        assertNotNull("DeepLink must be attached", liveMatch.deepLink)
        assertTrue(liveMatch.deepLink!!.isLiveBroadcast)

        val upcomingMatch = matches[1]
        assertEquals(MatchState.UPCOMING, upcomingMatch.state)
        assertEquals("Novak Djokovic", upcomingMatch.player1.name)
        assertEquals(4, upcomingMatch.player1.seed)
    }

    @Test
    fun `parseScoreboardJson should handle empty and malformed json gracefully`() {
        val emptyResult = parser.parseScoreboardJson("{}")
        assertTrue("Empty JSON should return empty list", emptyResult.isEmpty())

        val eventsNoComps = parser.parseScoreboardJson("""{"events": [{"name": "US Open"}]}""")
        assertTrue("Events without groupings/competitions should return empty list", eventsNoComps.isEmpty())
    }

    @Test
    fun `parseScoreboardJson should parse tiebreaks and server possession correctly`() {
        val tiebreakJson = """
        {
          "events": [
            {
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "displayName": "Women's Singles" },
                  "competitions": [
                    {
                      "id": "2001",
                      "status": {
                        "type": { "state": "in", "completed": false, "description": "In Progress" }
                      },
                      "competitors": [
                        {
                          "id": "p1",
                          "athlete": { "displayName": "Emma Navarro" },
                          "possession": true,
                          "score": "40",
                          "linescores": [
                            { "value": 7.0, "tiebreak": 7, "winner": true },
                            { "value": 5.0 }
                          ]
                        },
                        {
                          "id": "p2",
                          "athlete": { "displayName": "Karolina Muchova" },
                          "possession": false,
                          "score": "30",
                          "linescores": [
                            { "value": 6.0, "tiebreak": 4, "winner": false },
                            { "value": 4.0 }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val matches = parser.parseScoreboardJson(tiebreakJson)
        assertEquals(1, matches.size)
        val match = matches[0]

        // Player 1 checks (Navarro: serving, 7-6 tiebreak winner)
        assertTrue("Player 1 should be serving (possession: true)", match.player1.isServing)
        assertEquals("40", match.player1.currentGameScore)
        assertEquals(listOf(7, 5), match.player1.setScores)
        assertEquals(listOf(7, null), match.player1.tiebreakScores)

        // Player 2 checks (Muchova: not serving, tiebreak points: 4)
        org.junit.Assert.assertFalse("Player 2 should not be serving", match.player2.isServing)
        assertEquals("30", match.player2.currentGameScore)
        assertEquals(listOf(6, 4), match.player2.setScores)
        assertEquals(listOf(4, null), match.player2.tiebreakScores)
    }

    @Test
    fun `parseScoreboardJson should parse court information from ESPN venue structure`() {
        val json = """
        {
          "events": [
            {
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "182876",
                      "date": "2026-09-05T15:30Z",
                      "status": {
                        "type": { "state": "in", "completed": false, "description": "1st Set" }
                      },
                      "venue": {
                        "fullName": "New York, USA",
                        "court": "Court 7"
                      },
                      "competitors": [
                        { "id": "p1", "athlete": { "displayName": "Player 1" } },
                        { "id": "p2", "athlete": { "displayName": "Player 2" } }
                      ]
                    },
                    {
                      "id": "182877",
                      "date": "2026-09-05T19:00Z",
                      "status": {
                        "type": { "state": "pre", "completed": false, "description": "Scheduled" }
                      },
                      "venue": {
                        "fullName": "New York, USA",
                        "court": "Arthur Ashe Stadium"
                      },
                      "competitors": [
                        { "id": "p3", "athlete": { "displayName": "Player 3" } },
                        { "id": "p4", "athlete": { "displayName": "Player 4" } }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val matches = parser.parseScoreboardJson(json)
        assertEquals(2, matches.size)

        val m1 = matches[0]
        assertEquals("Court 7", m1.court)
        assertEquals("Court 7", m1.displayCourt)
        assertEquals("Court 7", m1.venue)

        val m2 = matches[1]
        assertEquals("Arthur Ashe Stadium", m2.court)
        assertEquals("Arthur Ashe Stadium", m2.displayCourt)
        assertEquals("Arthur Ashe Stadium", m2.venue)
    }
}
