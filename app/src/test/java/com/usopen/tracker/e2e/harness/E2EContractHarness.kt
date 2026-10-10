package com.usopen.tracker.e2e.harness

import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.CourtSurface
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.TourType
import com.usopen.tracker.domain.model.Tournament
import com.usopen.tracker.domain.model.TournamentCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal E2E Test Fixtures for Tennis Tracker Multi-Tournament Support.
 *
 * All duplicate mock/shadow classes (E2ETourType, E2ETournamentCategory, E2ECourtSurface,
 * E2ETournament, E2EMatch, E2ETournaments, E2ETournamentResolver, E2ETourTierFilter,
 * E2ECollectionPipeline) have been completely removed.
 *
 * All E2E tests directly exercise authentic production classes:
 * - Domain: TourType, TournamentCategory, CourtSurface, Tournament, Tournaments, TournamentResolver, Match
 * - UI: TourTierFilter, ScoreboardViewModel
 * - Data: EspnScoreboardApi, EspnWatchMatcher, YouTubeHighlightResolver, ScoreboardRepositoryImpl, MatchCacheManager
 */
object E2ETestFixtures {

    /**
     * Factory function creating genuine production Match domain entities with sensible defaults.
     */
    fun createMatch(
        id: String,
        player1Name: String = "Player 1",
        player2Name: String = "Player 2",
        tournamentName: String = "Tennis",
        eventGrouping: String = "Men's Singles",
        roundName: String = "",
        state: MatchState = MatchState.UPCOMING,
        statusDetail: String = "",
        venue: String = "Center Court",
        court: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        durationMinutes: Int? = null,
        espnEventId: String? = null,
        espnWatchId: String? = null,
        tournament: Tournament? = null,
        tour: TourType? = null,
        category: TournamentCategory? = null,
        surface: CourtSurface? = null
    ): Match = Match(
        id = id,
        tournamentName = tournamentName,
        eventGrouping = eventGrouping,
        roundName = roundName,
        state = state,
        statusDetail = statusDetail,
        player1 = Competitor(id = "p1-$id", name = player1Name),
        player2 = Competitor(id = "p2-$id", name = player2Name),
        venue = venue,
        court = court,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        espnEventId = espnEventId,
        espnWatchId = espnWatchId,
        tournament = tournament,
        tour = tour,
        category = category,
        surface = surface
    )

    /**
     * Constructs genuine ESPN feed JSON structure for testing API parsers and tournament discovery.
     */
    fun buildEspnEventJson(
        eventName: String,
        groupings: List<Pair<String, List<Pair<String, String>>>>,
        major: Boolean = false
    ): JSONObject {
        val eventObj = JSONObject()
        eventObj.put("name", eventName)
        eventObj.put("major", major)
        val groupingsArr = JSONArray()

        var matchId = 1
        for ((groupingName, matchups) in groupings) {
            val groupingObj = JSONObject()
            val groupingMeta = JSONObject()
            groupingMeta.put("displayName", groupingName)
            groupingObj.put("grouping", groupingMeta)

            val compsArr = JSONArray()
            for ((p1, p2) in matchups) {
                val comp = JSONObject()
                comp.put("id", "comp-${eventName.hashCode()}-$matchId")
                matchId++

                val competitorsArr = JSONArray()
                val c1 = JSONObject().apply {
                    put("athlete", JSONObject().apply { put("displayName", p1) })
                    put("order", 0)
                }
                val c2 = JSONObject().apply {
                    put("athlete", JSONObject().apply { put("displayName", p2) })
                    put("order", 1)
                }
                competitorsArr.put(c1)
                competitorsArr.put(c2)
                comp.put("competitors", competitorsArr)
                compsArr.put(comp)
            }
            groupingObj.put("competitions", compsArr)
            groupingsArr.put(groupingObj)
        }

        eventObj.put("groupings", groupingsArr)
        return eventObj
    }
}
