package com.tennis.tracker.data.local

import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState
import com.tennis.tracker.domain.model.PlayerPhotoProvider
import com.tennis.tracker.domain.usecase.DeepLinkGenerator
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object MockTournamentData {

    val matches: List<Match>
        get() {
            val now = Instant.now()
            val nyZone = ZoneId.of("America/New_York")
            val edtTimeFormatter = DateTimeFormatter.ofPattern("h:mm a 'EDT'", Locale.US).withZone(nyZone)

            val upcoming1Start = now.plus(Duration.ofHours(2))
            val upcoming2Start = now.plus(Duration.ofHours(2).plus(Duration.ofMinutes(10)))
            val upcoming3Start = now.plus(Duration.ofHours(2).plus(Duration.ofMinutes(10)))

            val up1Status = "Tonight • ${edtTimeFormatter.format(upcoming1Start)}"
            val up2Status = "Tonight • ${edtTimeFormatter.format(upcoming2Start)}"
            val up3Status = "Tonight • ${edtTimeFormatter.format(upcoming3Start)}"

            return listOf(
                // --- Live Matches ---
                createMatch(
                    id = "182580",
                    eventGrouping = "Women's Singles",
                    roundName = "Round 2",
                    state = MatchState.LIVE,
                    statusDetail = "In Progress • 2nd Set",
                    p1Name = "Emma Navarro",
                    p1Seed = 26,
                    p1Country = "USA",
                    p1Sets = listOf(6, 4),
                    p1Tiebreaks = emptyList(),
                    p1Game = "40",
                    p1Serving = true,
                    p2Name = "Karolina Muchova",
                    p2Seed = 7,
                    p2Country = "CZE",
                    p2Sets = listOf(3, 3),
                    p2Tiebreaks = emptyList(),
                    p2Game = "30",
                    p2Serving = false,
                    venue = "Arthur Ashe Stadium",
                    startTime = now.minus(Duration.ofMinutes(95)).toString(),
                    durationMinutes = 95
                ),
                createMatch(
                    id = "182582",
                    eventGrouping = "Women's Singles",
                    roundName = "Round 2",
                    state = MatchState.LIVE,
                    statusDetail = "In Progress • 2nd Set",
                    p1Name = "Linda Noskova",
                    p1Seed = 25,
                    p1Country = "CZE",
                    p1Sets = listOf(6, 3),
                    p1Tiebreaks = emptyList(),
                    p1Game = "Adv",
                    p1Serving = false,
                    p2Name = "Ann Li",
                    p2Seed = null,
                    p2Country = "USA",
                    p2Sets = listOf(2, 1),
                    p2Tiebreaks = emptyList(),
                    p2Game = "40",
                    p2Serving = true,
                    venue = "Louis Armstrong Stadium",
                    startTime = now.minus(Duration.ofMinutes(70)).toString(),
                    durationMinutes = 70
                ),
                createMatch(
                    id = "182885",
                    eventGrouping = "Men's Doubles",
                    roundName = "Round 2",
                    state = MatchState.LIVE,
                    statusDetail = "In Progress • 2nd Set",
                    p1Name = "Ram / Salisbury",
                    p1Seed = 3,
                    p1Country = "USA/GBR",
                    p1Sets = listOf(7, 4),
                    p1Tiebreaks = listOf(7, null),
                    p1Game = "15",
                    p1Serving = true,
                    p2Name = "Gonzalez / Smith",
                    p2Seed = null,
                    p2Country = "MEX/AUS",
                    p2Sets = listOf(6, 3),
                    p2Tiebreaks = listOf(4, null),
                    p2Game = "40",
                    p2Serving = false,
                    venue = "Grandstand",
                    startTime = now.minus(Duration.ofMinutes(108)).toString(),
                    durationMinutes = 108
                ),

                // --- Upcoming Matches ---
                createMatch(
                    id = "182747",
                    eventGrouping = "Men's Singles",
                    roundName = "Round 2",
                    state = MatchState.UPCOMING,
                    statusDetail = up1Status,
                    p1Name = "Stefanos Tsitsipas",
                    p1Seed = 11,
                    p1Country = "GRE",
                    p1Sets = emptyList(),
                    p1Game = null,
                    p2Name = "Jiri Lehecka",
                    p2Seed = 32,
                    p2Country = "CZE",
                    p2Sets = emptyList(),
                    p2Game = null,
                    venue = "Louis Armstrong Stadium",
                    startTime = upcoming1Start.toString()
                ),
                createMatch(
                    id = "182748",
                    eventGrouping = "Men's Singles",
                    roundName = "Round 2",
                    state = MatchState.UPCOMING,
                    statusDetail = up2Status,
                    p1Name = "Alex Michelsen",
                    p1Seed = null,
                    p1Country = "USA",
                    p1Sets = emptyList(),
                    p1Game = null,
                    p2Name = "Daniel Merida",
                    p2Seed = null,
                    p2Country = "ESP",
                    p2Sets = emptyList(),
                    p2Game = null,
                    venue = "Grandstand",
                    startTime = upcoming2Start.toString()
                ),
                createMatch(
                    id = "182820",
                    eventGrouping = "Women's Doubles",
                    roundName = "Round 2",
                    state = MatchState.UPCOMING,
                    statusDetail = up3Status,
                    p1Name = "Mertens / Shnaider",
                    p1Seed = 4,
                    p1Country = "BEL",
                    p1Sets = emptyList(),
                    p1Game = null,
                    p2Name = "Snigur / Vandewinkel",
                    p2Seed = null,
                    p2Country = "UKR/BEL",
                    p2Sets = emptyList(),
                    p2Game = null,
                    venue = "Court 17",
                    startTime = upcoming3Start.toString()
                ),

                // --- Completed Matches ---
                createMatch(
                    id = "182723",
                    eventGrouping = "Men's Singles",
                    roundName = "Round 2",
                    state = MatchState.FINISHED,
                    statusDetail = "Final • Round 2",
                    p1Name = "Carlos Alcaraz",
                    p1Seed = 2,
                    p1Country = "ESP",
                    p1Sets = listOf(6, 7, 6),
                    p1Tiebreaks = listOf(null, 7, null),
                    p1Game = null,
                    p1Winner = true,
                    p2Name = "Wu Yibing",
                    p2Seed = null,
                    p2Country = "CHN",
                    p2Sets = listOf(3, 6, 1),
                    p2Tiebreaks = listOf(null, 4, null),
                    p2Game = null,
                    p2Winner = false,
                    venue = "Arthur Ashe Stadium",
                    startTime = now.minus(Duration.ofMinutes(134 + 180)).toString(),
                    endTime = now.minus(Duration.ofMinutes(180)).toString(),
                    durationMinutes = 134
                ),
                createMatch(
                    id = "182687",
                    eventGrouping = "Men's Singles",
                    roundName = "Round 2",
                    state = MatchState.FINISHED,
                    statusDetail = "Final • Round 2",
                    p1Name = "Daniil Medvedev",
                    p1Seed = 5,
                    p1Country = null,
                    p1Sets = listOf(6, 6, 6),
                    p1Game = null,
                    p1Winner = true,
                    p2Name = "Arthur Rinderknech",
                    p2Seed = null,
                    p2Country = "FRA",
                    p2Sets = listOf(4, 4, 4),
                    p2Game = null,
                    p2Winner = false,
                    venue = "Arthur Ashe Stadium",
                    startTime = now.minus(Duration.ofMinutes(118 + 240)).toString(),
                    endTime = now.minus(Duration.ofMinutes(240)).toString(),
                    durationMinutes = 118
                ),
                createMatch(
                    id = "182719",
                    eventGrouping = "Men's Singles",
                    roundName = "Round 2",
                    state = MatchState.FINISHED,
                    statusDetail = "Final • Round 2",
                    p1Name = "Tommy Paul",
                    p1Seed = 14,
                    p1Country = "USA",
                    p1Sets = listOf(6, 3, 6, 6, 6),
                    p1Tiebreaks = listOf(null, null, 5, null, null),
                    p1Game = null,
                    p1Winner = true,
                    p2Name = "Alexander Bublik",
                    p2Seed = 23,
                    p2Country = "KAZ",
                    p2Sets = listOf(4, 6, 7, 1, 3),
                    p2Tiebreaks = listOf(null, null, 7, null, null),
                    p2Game = null,
                    p2Winner = false,
                    venue = "Grandstand",
                    startTime = now.minus(Duration.ofMinutes(232 + 200)).toString(),
                    endTime = now.minus(Duration.ofMinutes(200)).toString(),
                    durationMinutes = 232
                ),
                createMatch(
                    id = "182554",
                    eventGrouping = "Women's Singles",
                    roundName = "Round 2",
                    state = MatchState.FINISHED,
                    statusDetail = "Final • Round 2",
                    p1Name = "Aryna Sabalenka",
                    p1Seed = 1,
                    p1Country = null,
                    p1Sets = listOf(6, 6),
                    p1Game = null,
                    p1Winner = true,
                    p2Name = "Kamilla Rakhimova",
                    p2Seed = null,
                    p2Country = null,
                    p2Sets = listOf(3, 4),
                    p2Game = null,
                    p2Winner = false,
                    venue = "Arthur Ashe Stadium",
                    startTime = now.minus(Duration.ofMinutes(72 + 360)).toString(),
                    endTime = now.minus(Duration.ofMinutes(360)).toString(),
                    durationMinutes = 72
                ),
                createMatch(
                    id = "182545",
                    eventGrouping = "Women's Singles",
                    roundName = "Round 2",
                    state = MatchState.FINISHED,
                    statusDetail = "Final • Round 2",
                    p1Name = "Jessica Pegula",
                    p1Seed = 6,
                    p1Country = "USA",
                    p1Sets = listOf(1, 7, 6),
                    p1Tiebreaks = listOf(null, 7, null),
                    p1Game = null,
                    p1Winner = true,
                    p2Name = "Leylah Fernandez",
                    p2Seed = null,
                    p2Country = "CAN",
                    p2Sets = listOf(6, 6, 3),
                    p2Tiebreaks = listOf(null, 3, null),
                    p2Game = null,
                    p2Winner = false,
                    venue = "Louis Armstrong Stadium",
                    startTime = now.minus(Duration.ofMinutes(146 + 300)).toString(),
                    endTime = now.minus(Duration.ofMinutes(300)).toString(),
                    durationMinutes = 146
                )
            )
        }

    private fun createMatch(
        id: String,
        eventGrouping: String,
        roundName: String,
        state: MatchState,
        statusDetail: String,
        p1Name: String,
        p1Seed: Int?,
        p1Country: String?,
        p1Sets: List<Int>,
        p1Tiebreaks: List<Int?> = emptyList(),
        p1Game: String?,
        p1Serving: Boolean = false,
        p1Winner: Boolean = false,
        p2Name: String,
        p2Seed: Int?,
        p2Country: String?,
        p2Sets: List<Int>,
        p2Tiebreaks: List<Int?> = emptyList(),
        p2Game: String?,
        p2Serving: Boolean = false,
        p2Winner: Boolean = false,
        venue: String,
        startTime: String? = null,
        endTime: String? = null,
        durationMinutes: Int? = null
    ): Match {
        val player1 = Competitor(
            id = "${id}_p1",
            name = p1Name,
            seed = p1Seed,
            country = p1Country,
            isWinner = p1Winner,
            setScores = p1Sets,
            tiebreakScores = p1Tiebreaks,
            currentGameScore = p1Game,
            isServing = p1Serving,
            photoUrl = PlayerPhotoProvider.getPhotoUrl(p1Name)
        )
        val player2 = Competitor(
            id = "${id}_p2",
            name = p2Name,
            seed = p2Seed,
            country = p2Country,
            isWinner = p2Winner,
            setScores = p2Sets,
            tiebreakScores = p2Tiebreaks,
            currentGameScore = p2Game,
            isServing = p2Serving,
            photoUrl = PlayerPhotoProvider.getPhotoUrl(p2Name)
        )
        val matchWithoutLink = Match(
            id = id,
            tournamentName = "US Open",
            eventGrouping = eventGrouping,
            roundName = roundName,
            state = state,
            statusDetail = statusDetail,
            player1 = player1,
            player2 = player2,
            venue = venue,
            court = venue,
            startTime = startTime,
            endTime = endTime,
            durationMinutes = durationMinutes,
            espnEventId = id
        )
        return matchWithoutLink.copy(deepLink = DeepLinkGenerator.generate(matchWithoutLink))
    }
}
