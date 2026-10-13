package com.tennis.tracker.data

import com.tennis.tracker.data.remote.EspnWatchMatcher
import com.tennis.tracker.data.remote.EspnWatchStream
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EspnWatchMatcherTest {

    private val sampleStreams = listOf(
        EspnWatchStream(
            watchId = "d174bd1f-98f7-490e-b285-d75124819741",
            name = "Khachanov vs. Bonzi (Men's Third Round)",
            shortName = "Khachanov vs. Bonzi",
            subtitle = "ESPN Unlimited • US Open"
        ),
        EspnWatchStream(
            watchId = "9879b2c4-a798-4ea2-89d3-160419055425",
            name = "(4) Gauff vs. Bucsa (Women's Third Round)",
            shortName = "Gauff vs. Bucsa",
            subtitle = "ESPN Unlimited • US Open"
        ),
        EspnWatchStream(
            watchId = "e5b56889-01a2-4d23-a5ba-5ad600adfb8d",
            name = "(3) Arevalo/Pavic vs. Johnson/Zielinski (Men's Doubles First Round)",
            shortName = "Arevalo/Pavic vs. Johnson/Zielinski",
            subtitle = "ESPN Unlimited • US Open"
        ),
        EspnWatchStream(
            watchId = "court-4-uuid-1111",
            name = "Court 4",
            shortName = "Court 4",
            court = "Court 4",
            subtitle = "ESPN Unlimited • US Open"
        ),
        EspnWatchStream(
            watchId = "ashe-stadium-uuid-2222",
            name = "Arthur Ashe Stadium",
            shortName = "Arthur Ashe Stadium",
            court = "Arthur Ashe Stadium",
            subtitle = "ESPN Unlimited • US Open"
        ),
        EspnWatchStream(
            watchId = "main-coverage-uuid-9999",
            name = "2026 US Open: Coverage presented by Heineken (Third Round)",
            shortName = "US Open Coverage",
            subtitle = "ESPN2 • US Open"
        )
    )

    private fun createMatch(
        p1Name: String,
        p2Name: String,
        court: String? = null,
        venue: String = "Arthur Ashe Stadium",
        state: MatchState = MatchState.LIVE
    ): Match {
        return Match(
            id = "test-comp-1",
            state = state,
            statusDetail = "Live",
            player1 = Competitor(id = "p1", name = p1Name),
            player2 = Competitor(id = "p2", name = p2Name),
            venue = venue,
            court = court
        )
    }

    @Test
    fun `findBestStream should match singles players by surname`() {
        val match = createMatch("Karen Khachanov", "Benjamin Bonzi")
        val result = EspnWatchMatcher.findBestStream(match, sampleStreams)

        assertNotNull(result)
        assertEquals("d174bd1f-98f7-490e-b285-d75124819741", result?.watchId)
    }

    @Test
    fun `findBestStream should match seeded players against title containing seed prefix`() {
        val match = createMatch("Coco Gauff", "Cristina Bucsa")
        val result = EspnWatchMatcher.findBestStream(match, sampleStreams)

        assertNotNull(result)
        assertEquals("9879b2c4-a798-4ea2-89d3-160419055425", result?.watchId)
    }

    @Test
    fun `findBestStream should match doubles pairings`() {
        val match = createMatch("Marcelo Arevalo / Mate Pavic", "Luke Johnson / Jan Zielinski")
        val result = EspnWatchMatcher.findBestStream(match, sampleStreams)

        assertNotNull(result)
        assertEquals("e5b56889-01a2-4d23-a5ba-5ad600adfb8d", result?.watchId)
    }

    @Test
    fun `findBestStream should fall back to court camera stream when player names are not listed individually`() {
        val match = createMatch("Player Alpha", "Player Beta", court = "Court 4")
        val result = EspnWatchMatcher.findBestStream(match, sampleStreams)

        assertNotNull(result)
        assertEquals("court-4-uuid-1111", result?.watchId)
    }

    @Test
    fun `findBestStream should fall back to main tournament live coverage for live match when court not found`() {
        val match = createMatch("Unknown Player 1", "Unknown Player 2", court = "Court 99", state = MatchState.LIVE)
        val result = EspnWatchMatcher.findBestStream(match, sampleStreams)

        assertNotNull(result)
        assertEquals("main-coverage-uuid-9999", result?.watchId)
    }

    @Test
    fun `findBestStream should return null for upcoming match when neither players nor court stream matches`() {
        val match = createMatch("Unknown Player 1", "Unknown Player 2", court = "Court 99", state = MatchState.UPCOMING)
        val result = EspnWatchMatcher.findBestStream(match, sampleStreams)

        assertNull(result)
    }

    @Test
    fun `findBestStream should match composite surnames like van de Zandschulp and Bergs`() {
        val bergsStreams = listOf(
            EspnWatchStream(
                watchId = "5e11332f-2e81-4516-9f6f-7dfda1ed705d",
                name = "(31) Bergs vs. van de Zandschulp (Men's Third Round)",
                subtitle = "ESPN Unlimited • US Open"
            )
        )
        val match = createMatch("Zizou Bergs", "Botic Van De Zandschulp")
        val result = EspnWatchMatcher.findBestStream(match, bergsStreams)

        assertNotNull(result)
        assertEquals("5e11332f-2e81-4516-9f6f-7dfda1ed705d", result?.watchId)
    }
}
