package com.usopen.tracker.domain

import com.usopen.tracker.data.remote.EspnScoreboardApi
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class MatchDurationTest {

    private val player1 = Competitor(
        id = "1",
        name = "Carlos Alcaraz",
        seed = 2,
        setScores = listOf(6, 4, 6),
        isWinner = true
    )

    private val player2 = Competitor(
        id = "2",
        name = "Jannik Sinner",
        seed = 1,
        setScores = listOf(4, 6, 3),
        isWinner = false
    )

    @Test
    fun testLiveMatchElapsedDuration_hoursAndMinutes() {
        val startTime = "2026-09-05T14:00:00Z"
        val match = Match(
            id = "live-1",
            state = MatchState.LIVE,
            statusDetail = "In Progress - Set 3",
            player1 = player1,
            player2 = player2,
            startTime = startTime
        )

        val nowInstant = Instant.parse("2026-09-05T15:42:00Z")
        val formatted = match.getFormattedDuration(nowInstant)

        assertEquals("1h 42m", formatted)
    }

    @Test
    fun testLiveMatchElapsedDuration_minutesOnly() {
        val startTime = "2026-09-05T14:00:00Z"
        val match = Match(
            id = "live-2",
            state = MatchState.LIVE,
            statusDetail = "In Progress - Set 1",
            player1 = player1,
            player2 = player2,
            startTime = startTime
        )

        val nowInstant = Instant.parse("2026-09-05T14:38:00Z")
        val formatted = match.getFormattedDuration(nowInstant)

        assertEquals("38m", formatted)
    }

    @Test
    fun testLiveMatchElapsedDuration_fallbackWhenStartTimeMissing() {
        val match = Match(
            id = "live-fallback",
            state = MatchState.LIVE,
            statusDetail = "In Progress - Set 3",
            player1 = player1,
            player2 = player2,
            startTime = null
        )

        val formatted = match.getFormattedDuration()
        assertNotNull(formatted)
        // 3 sets in progress should estimate around 2h 28m
        assertEquals("2h 28m", formatted)
    }

    @Test
    fun testFinishedMatchDuration_fromDurationMinutes() {
        val match = Match(
            id = "finished-1",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = player1,
            player2 = player2,
            durationMinutes = 154 // 2h 34m
        )

        val formatted = match.getFormattedDuration()
        assertEquals("2h 34m", formatted)
    }

    @Test
    fun testFinishedMatchDuration_fromStartAndEndTime() {
        val match = Match(
            id = "finished-2",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = player1,
            player2 = player2,
            startTime = "2026-09-05T12:00:00Z",
            endTime = "2026-09-05T14:48:00Z"
        )

        val formatted = match.getFormattedDuration()
        assertEquals("2h 48m", formatted)
    }

    @Test
    fun testFinishedMatchDuration_fallbackWhenDurationMissing() {
        val match = Match(
            id = "finished-fallback",
            state = MatchState.FINISHED,
            statusDetail = "Final",
            player1 = player1, // 3 sets played
            player2 = player2
        )

        val formatted = match.getFormattedDuration()
        assertNotNull(formatted)
        // 3 sets played should estimate around 2h 15m
        assertEquals("2h 15m", formatted)
    }

    @Test
    fun testUpcomingMatchDuration_returnsNull() {
        val match = Match(
            id = "upcoming-1",
            state = MatchState.UPCOMING,
            statusDetail = "Tonight • 7:00 PM EDT",
            player1 = player1,
            player2 = player2,
            startTime = "2026-09-05T23:00:00Z"
        )

        assertNull(match.getFormattedDuration())
    }

    @Test
    fun testEspnParserExtractsDuration() {
        val parser = EspnScoreboardApi()
        val json = """
        {
          "events": [
            {
              "id": "189-2026",
              "name": "US Open",
              "groupings": [
                {
                  "grouping": { "id": "1", "displayName": "Men's Singles" },
                  "competitions": [
                    {
                      "id": "184607",
                      "date": "2026-09-04T18:00Z",
                      "endDate": "2026-09-04T20:34Z",
                      "status": {
                        "displayClock": "2:34:00",
                        "type": {
                          "id": "2",
                          "state": "post",
                          "completed": true,
                          "description": "Final"
                        }
                      },
                      "venue": { "fullName": "Arthur Ashe Stadium" },
                      "competitors": [
                        {
                          "id": "1001",
                          "athlete": { "displayName": "Carlos Alcaraz" },
                          "curatedRank": { "current": 2 },
                          "winner": true,
                          "linescores": [{ "value": 6.0 }, { "value": 6.0 }, { "value": 6.0 }]
                        },
                        {
                          "id": "1002",
                          "athlete": { "displayName": "Alexander Zverev" },
                          "curatedRank": { "current": 4 },
                          "winner": false,
                          "linescores": [{ "value": 3.0 }, { "value": 4.0 }, { "value": 2.0 }]
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
        assertEquals(MatchState.FINISHED, match.state)
        assertEquals(154, match.durationMinutes)
        assertEquals("2h 34m", match.getFormattedDuration())
    }
}
