package com.usopen.tracker.domain.usecase

import com.usopen.tracker.domain.model.DeepLink
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object DeepLinkGenerator {

    private const val ESPN_APP_SCHEME_WATCH_STREAM = "sportscenter://x-callback-url/showWatchStream?playID="
    private const val ESPN_APP_SCHEME_SEARCH = "sportscenter://x-callback-url/showSearch?query="
    private const val ESPN_APP_SCHEME_SPORTSCENTER = "sportscenter://x-callback-url/showEvent?eventId="
    private const val ESPN_WEB_WATCH_PLAYER_URL = "https://www.espn.com/watch/player?id="
    private const val ESPN_WEB_SEARCH_WATCH_BASE = "https://www.espn.com/search/_/q/"

    fun generate(match: Match): DeepLink {
        val watchId = match.espnWatchId

        // 1. If a genuine Watch Stream ID (UUID) is known, generate direct stream playback deep links
        if (!watchId.isNullOrEmpty()) {
            val appUri = "$ESPN_APP_SCHEME_WATCH_STREAM$watchId"
            val webUrl = "$ESPN_WEB_WATCH_PLAYER_URL$watchId"
            val buttonLabel = when (match.state) {
                MatchState.LIVE -> "Watch Live on ESPN"
                MatchState.FINISHED -> "Watch Replay on ESPN"
                MatchState.UPCOMING -> "Watch on ESPN"
            }
            return DeepLink(
                appUri = appUri,
                webFallbackUrl = webUrl,
                isLiveBroadcast = match.state == MatchState.LIVE,
                buttonLabel = buttonLabel
            )
        }

        // 2. Specific match search fallback when direct stream UUID is not matched:
        // Opens the specific match stream/recap search on ESPN Watch instead of generic tennis schedule
        val matchQuery = buildMatchSearchQuery(match)
        val encodedQuery = try {
            URLEncoder.encode(matchQuery, StandardCharsets.UTF_8.toString())
        } catch (_: Throwable) {
            matchQuery.replace(" ", "+")
        }
        val matchWatchWebUrl = "$ESPN_WEB_SEARCH_WATCH_BASE$encodedQuery/o/watch"
        val matchAppUri = "$ESPN_APP_SCHEME_SEARCH$encodedQuery"

        return when (match.state) {
            MatchState.LIVE -> {
                DeepLink(
                    appUri = matchAppUri,
                    webFallbackUrl = matchWatchWebUrl,
                    isLiveBroadcast = true,
                    buttonLabel = "Watch Live on ESPN"
                )
            }
            MatchState.FINISHED -> {
                DeepLink(
                    appUri = matchAppUri,
                    webFallbackUrl = matchWatchWebUrl,
                    isLiveBroadcast = false,
                    buttonLabel = "Watch Replay on ESPN"
                )
            }
            MatchState.UPCOMING -> {
                DeepLink(
                    appUri = matchAppUri,
                    webFallbackUrl = matchWatchWebUrl,
                    isLiveBroadcast = false,
                    buttonLabel = "Watch Coverage on ESPN"
                )
            }
        }
    }

    fun buildMatchSearchQuery(match: Match): String {
        val p1 = match.player1.name.trim()
        val p2 = match.player2.name.trim()
        val tournament = match.tournamentName.trim().ifEmpty { "US Open" }
        return when {
            p1.isNotEmpty() && p2.isNotEmpty() -> "$p1 vs $p2 $tournament"
            p1.isNotEmpty() -> "$p1 $tournament"
            else -> "$tournament tennis"
        }
    }
}
