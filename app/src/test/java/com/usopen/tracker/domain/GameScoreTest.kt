package com.usopen.tracker.domain

import com.usopen.tracker.data.remote.EspnScoreboardApi
import com.usopen.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

class GameScoreTest {

    @get:Rule
    val globalTimeout: Timeout = Timeout(10, TimeUnit.SECONDS)

    private val parser = EspnScoreboardApi()

    @Test
    fun testLiveMatchWithoutPointsInJsonDefaultsToValidGameScores() {
        val json = """
        {
          "events": [
            {
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "displayName": "Women's Singles" },
                  "competitions": [
                    {
                      "id": "182555",
                      "status": {
                        "type": { "state": "in", "completed": false, "description": "In Progress" }
                      },
                      "competitors": [
                        {
                          "id": "p1",
                          "athlete": { "displayName": "Mirra Andreeva" },
                          "possession": true,
                          "linescores": [{ "value": 5.0 }, { "value": 2.0 }]
                        },
                        {
                          "id": "p2",
                          "athlete": { "displayName": "Anastasia Potapova" },
                          "possession": false,
                          "linescores": [{ "value": 7.0 }, { "value": 0.0 }]
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

        val matches = parser.parseScoreboardJson(json)
        assertEquals(1, matches.size)
        val match = matches[0]

        assertEquals(MatchState.LIVE, match.state)
        assertTrue(match.player1.isServing)
        assertEquals("40", match.player1.currentGameScore)
        assertEquals("30", match.player2.currentGameScore)
    }

    @Test
    fun testLiveMatchWithAdvantagePointNormalizesCorrectly() {
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
                      "id": "182556",
                      "status": {
                        "type": { "state": "in", "completed": false, "description": "In Progress" }
                      },
                      "competitors": [
                        {
                          "id": "p1",
                          "athlete": { "displayName": "Carlos Alcaraz" },
                          "possession": true,
                          "points": "A",
                          "linescores": [{ "value": 6.0 }]
                        },
                        {
                          "id": "p2",
                          "athlete": { "displayName": "Jannik Sinner" },
                          "possession": false,
                          "points": "40",
                          "linescores": [{ "value": 4.0 }]
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

        val matches = parser.parseScoreboardJson(json)
        assertEquals(1, matches.size)
        val match = matches[0]

        assertEquals("Adv", match.player1.currentGameScore)
        assertEquals("40", match.player2.currentGameScore)
    }

    @Test
    fun testLiveMatchInTiebreakShowsTiebreakPoints() {
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
                      "id": "182557",
                      "status": {
                        "type": { "state": "in", "completed": false, "description": "In Progress" }
                      },
                      "competitors": [
                        {
                          "id": "p1",
                          "athlete": { "displayName": "Alexander Zverev" },
                          "possession": true,
                          "linescores": [{ "value": 6.0, "tiebreak": 5 }]
                        },
                        {
                          "id": "p2",
                          "athlete": { "displayName": "Daniil Medvedev" },
                          "possession": false,
                          "linescores": [{ "value": 6.0, "tiebreak": 3 }]
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

        val matches = parser.parseScoreboardJson(json)
        assertEquals(1, matches.size)
        val match = matches[0]

        assertEquals("TB 5", match.player1.currentGameScore)
        assertEquals("TB 3", match.player2.currentGameScore)
    }

    @Test
    fun testUpcomingAndFinishedMatchesHaveNullGameScore() {
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
                      "id": "182558",
                      "status": {
                        "type": { "state": "pre", "completed": false, "description": "Scheduled" }
                      },
                      "competitors": [
                        { "id": "p1", "athlete": { "displayName": "Player 1" } },
                        { "id": "p2", "athlete": { "displayName": "Player 2" } }
                      ]
                    },
                    {
                      "id": "182559",
                      "status": {
                        "type": { "state": "post", "completed": true, "description": "Final" }
                      },
                      "competitors": [
                        {
                          "id": "p3",
                          "athlete": { "displayName": "Player 3" },
                          "winner": true,
                          "linescores": [{ "value": 6.0 }, { "value": 6.0 }]
                        },
                        {
                          "id": "p4",
                          "athlete": { "displayName": "Player 4" },
                          "winner": false,
                          "linescores": [{ "value": 4.0 }, { "value": 3.0 }]
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

        val matches = parser.parseScoreboardJson(json)
        assertEquals(2, matches.size)

        // Upcoming
        assertNull(matches[0].player1.currentGameScore)
        assertNull(matches[0].player2.currentGameScore)

        // Finished
        assertNull(matches[1].player1.currentGameScore)
        assertNull(matches[1].player2.currentGameScore)
    }
}
