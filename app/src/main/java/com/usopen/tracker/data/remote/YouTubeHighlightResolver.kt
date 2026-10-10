package com.usopen.tracker.data.remote

import android.util.Log
import com.usopen.tracker.domain.model.DeepLink
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val channelTitle: String
)

data class YouTubeHighlightResult(
    val videoId: String?,
    val title: String?,
    val channel: String?,
    val deepLink: DeepLink
)

open class YouTubeHighlightResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "YouTubeHighlight"
        private const val YOUTUBE_SEARCH_URL = "https://www.youtube.com/results?search_query="
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        val OFFICIAL_CHANNELS: Set<String> = setOf(
            "us open tennis championships",
            "us open",
            "@usopen",
            "wimbledon",
            "@wimbledon",
            "roland garros",
            "@rolandgarros",
            "australian open tv",
            "@australianopen",
            "espn",
            "espn tennis",
            "wta",
            "atp tour",
            "tennis channel",
            "tennis tv"
        )

        fun buildSearchQuery(match: Match): String {
            val p1 = match.player1.name.trim()
            val p2 = match.player2.name.trim()
            val tournament = match.tournamentName.trim().ifEmpty { "Tennis" }

            return when {
                p1.isNotEmpty() && p2.isNotEmpty() -> {
                    val channelTag = when {
                        tournament.contains("US Open", ignoreCase = true) -> "@usopen"
                        tournament.contains("Wimbledon", ignoreCase = true) -> "@wimbledon"
                        tournament.contains("Roland Garros", ignoreCase = true) || tournament.contains("French Open", ignoreCase = true) -> "@rolandgarros"
                        tournament.contains("Australian Open", ignoreCase = true) -> "@australianopen"
                        else -> "official highlights"
                    }
                    "$p1 vs $p2 $tournament highlights $channelTag"
                }
                p1.isNotEmpty() -> "$p1 $tournament highlights"
                else -> "$tournament tennis highlights"
            }
        }

        fun buildVideoDeepLink(videoId: String): DeepLink {
            return DeepLink(
                appUri = "vnd.youtube:$videoId",
                webFallbackUrl = "https://www.youtube.com/watch?v=$videoId",
                isLiveBroadcast = false,
                buttonLabel = "Highlights"
            )
        }

        fun buildSearchDeepLink(match: Match): DeepLink {
            val query = buildSearchQuery(match)
            val encoded = try {
                URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            } catch (_: Throwable) {
                query.replace(" ", "+")
            }
            val searchUrl = "$YOUTUBE_SEARCH_URL$encoded"
            return DeepLink(
                appUri = searchUrl,
                webFallbackUrl = searchUrl,
                isLiveBroadcast = false,
                buttonLabel = "Highlights"
            )
        }

        fun generateDeepLink(match: Match): DeepLink {
            val videoId = match.youtubeVideoId
            return if (!videoId.isNullOrBlank()) {
                buildVideoDeepLink(videoId)
            } else {
                buildSearchDeepLink(match)
            }
        }
    }

    open suspend fun resolveHighlight(match: Match): YouTubeHighlightResult? {
        if (match.state != MatchState.FINISHED) return null
        val existingVideoId = match.youtubeVideoId
        if (!existingVideoId.isNullOrBlank()) {
            return YouTubeHighlightResult(
                videoId = existingVideoId,
                title = "Match Highlights",
                channel = "US Open",
                deepLink = buildVideoDeepLink(existingVideoId)
            )
        }

        return try {
            val query = buildSearchQuery(match)
            val encoded = try {
                URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            } catch (_: Throwable) {
                query.replace(" ", "+")
            }
            val url = "$YOUTUBE_SEARCH_URL$encoded"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val videos = parseVideosFromHtml(body)
            val matchedVideo = findBestHighlightMatch(match, videos)

            if (matchedVideo != null) {
                YouTubeHighlightResult(
                    videoId = matchedVideo.videoId,
                    title = matchedVideo.title,
                    channel = matchedVideo.channelTitle,
                    deepLink = buildVideoDeepLink(matchedVideo.videoId)
                )
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error resolving YouTube highlight for match ${match.id}: ${t.message}")
            null
        }
    }

    fun parseVideosFromHtml(html: String): List<YouTubeVideo> {
        val videos = mutableListOf<YouTubeVideo>()

        // 1. Try to extract ytInitialData JSON object
        try {
            val pattern = Pattern.compile("""var\s+ytInitialData\s*=\s*(\{.+?\});</script>""")
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val jsonStr = matcher.group(1)
                if (jsonStr != null) {
                    val root = JSONObject(jsonStr)
                    extractVideoRenderers(root, videos)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "ytInitialData extraction failed: ${t.message}")
        }

        // 2. Fallback: regex scan for videoId and surrounding title
        if (videos.isEmpty()) {
            try {
                val vidPattern = Pattern.compile(""""videoId":"([a-zA-Z0-9_-]{11})"""")
                val vidMatcher = vidPattern.matcher(html)
                val seenIds = mutableSetOf<String>()
                while (vidMatcher.find() && seenIds.size < 10) {
                    val vid = vidMatcher.group(1) ?: continue
                    if (seenIds.add(vid)) {
                        videos.add(YouTubeVideo(videoId = vid, title = "US Open Highlights", channelTitle = "US Open Tennis Championships"))
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "Regex fallback video extraction failed: ${t.message}")
            }
        }

        return videos
    }

    private fun extractVideoRenderers(json: Any, results: MutableList<YouTubeVideo>) {
        if (results.size >= 25) return

        when (json) {
            is JSONObject -> {
                if (json.has("videoRenderer")) {
                    val vr = json.optJSONObject("videoRenderer")
                    if (vr != null) {
                        val videoId = vr.optString("videoId", "")
                        val title = vr.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                            ?: vr.optJSONObject("title")?.optString("simpleText", "")
                            ?: ""
                        val channel = vr.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                            ?: vr.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
                            ?: ""

                        if (videoId.isNotEmpty() && title.isNotEmpty()) {
                            results.add(YouTubeVideo(videoId = videoId, title = title, channelTitle = channel))
                        }
                    }
                }
                val keys = json.keys()
                while (keys.hasNext() && results.size < 25) {
                    val key = keys.next()
                    extractVideoRenderers(json.get(key), results)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    if (results.size >= 25) break
                    extractVideoRenderers(json.get(i), results)
                }
            }
        }
    }

    fun findBestHighlightMatch(match: Match, videos: List<YouTubeVideo>): YouTubeVideo? {
        if (videos.isEmpty()) return null

        val p1Names = EspnWatchMatcher.extractSearchNames(match.player1.name)
        val p2Names = EspnWatchMatcher.extractSearchNames(match.player2.name)

        var bestTier2: YouTubeVideo? = null
        var bestTier3: YouTubeVideo? = null
        var bestTier4: YouTubeVideo? = null

        // Single prioritized pass over candidate videos with cached normalization
        for (video in videos) {
            val isOfficial = isOfficialChannel(video.channelTitle)
            val normTitle = EspnWatchMatcher.normalize(video.title)
            val hasP1 = p1Names.any { it.length >= 3 && normTitle.contains(it) }
            val hasP2 = p2Names.any { it.length >= 3 && normTitle.contains(it) }
            val isHighlight = normTitle.contains("highlight") || normTitle.contains("recap")
            val isCondensedHighlight = isHighlight || normTitle.contains("condensed")

            // Tier 1: Official Channel + contains both player surname tokens + "highlight" or "match"
            if (isOfficial && hasP1 && hasP2 && isCondensedHighlight) {
                return video
            }

            // Tier 2: Official Channel + contains both player surname tokens
            if (bestTier2 == null && isOfficial && hasP1 && hasP2) {
                bestTier2 = video
            }

            // Tier 3: Contains both player surnames + "highlight" even if channel text variant
            if (bestTier3 == null && hasP1 && hasP2 && isHighlight) {
                bestTier3 = video
            }

            // Tier 4: First official video from top results
            if (bestTier4 == null && isOfficial) {
                bestTier4 = video
            }
        }

        return bestTier2 ?: bestTier3 ?: bestTier4
    }

    private fun isOfficialChannel(channel: String): Boolean {
        if (channel.isBlank()) return false
        val clean = channel.lowercase()
        return clean in OFFICIAL_CHANNELS ||
                clean.contains("us open") ||
                clean.contains("espn") ||
                clean.contains("atp") ||
                clean.contains("wta") ||
                OFFICIAL_CHANNELS.any { clean.contains(it) }
    }
}
