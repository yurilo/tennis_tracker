package com.usopen.tracker.data.remote

import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.util.TimezoneFormatter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class EspnWatchStream(
    val watchId: String,
    val name: String,
    val shortName: String = "",
    val subtitle: String = "",
    val court: String? = null,
    val status: String? = null,
    val eventId: Long? = null,
    val appPlayUrl: String? = null,
    val webUrl: String? = null
)

open class EspnWatchApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val WATCH_HOME_URL = "https://watch-cdn.product.api.espn.com/api/product/v3/watchespn/web/home?tz=UTC-0400&lang=en&countryCode=US"
        const val US_OPEN_CATALOG_URL = "https://watch-cdn.product.api.espn.com/api/product/v3/watchespn/web/catalog/2d592335-f46f-335f-8629-521585e4432a?tz=UTC-0400&lang=en&countryCode=US"
        const val TENNIS_CATALOG_URL = "https://watch-cdn.product.api.espn.com/api/product/v3/watchespn/web/catalog/aa07e63d-cf6e-3705-9c8d-8d5208e6af14?tz=UTC-0400&lang=en&countryCode=US"
        const val SCHEDULE_LIVE_URL = "https://www.espn.com/watch/schedule/_/type/live/category/tennis"
        const val SCHEDULE_UPCOMING_URL = "https://www.espn.com/watch/schedule/_/type/upcoming/category/tennis"
        const val SCHEDULE_REPLAY_URL_PREFIX = "https://www.espn.com/watch/schedule/_/type/replay/startDate/"
    }

    private val scheduleCache = java.util.concurrent.ConcurrentHashMap<String, List<EspnWatchStream>>()

    open fun fetchWatchStreams(url: String = US_OPEN_CATALOG_URL): Result<List<EspnWatchStream>> {
        val isDefaultOrCatalog = url == US_OPEN_CATALOG_URL || url == WATCH_HOME_URL
        return fetchWatchStreams(
            dates = emptyList(),
            overrideUrl = if (!isDefaultOrCatalog) url else null
        )
    }

    open fun fetchWatchStreams(
        dates: List<String> = emptyList(),
        overrideUrl: String? = null
    ): Result<List<EspnWatchStream>> {
        val streamsMap = mutableMapOf<String, EspnWatchStream>()

        if (overrideUrl != null) {
            fetchSingleUrlStreams(overrideUrl).forEach { streamsMap[it.watchId] = it }
            return Result.success(streamsMap.values.toList())
        }

        // Target URLs: Catalogs + Live schedule + Replay schedules for dates
        val catalogUrls = listOf(US_OPEN_CATALOG_URL, TENNIS_CATALOG_URL, SCHEDULE_LIVE_URL)
        val scheduleUrls = dates.map { "$SCHEDULE_REPLAY_URL_PREFIX$it/category/tennis" }
        val allUrls = (catalogUrls + scheduleUrls).distinct()

        val futures = allUrls.map { targetUrl ->
            CompletableFuture.supplyAsync({
                if (targetUrl.contains("/schedule/")) {
                    fetchScheduleStreams(targetUrl)
                } else {
                    fetchSingleUrlStreams(targetUrl)
                }
            }, client.dispatcher.executorService)
        }

        try {
            CompletableFuture.allOf(*futures.toTypedArray())
                .get(15, TimeUnit.SECONDS)
        } catch (_: Throwable) {}

        for (future in futures) {
            try {
                future.getNow(emptyList()).forEach { stream ->
                    if (!streamsMap.containsKey(stream.watchId) ||
                        (!stream.name.contains("Coverage", ignoreCase = true) &&
                         streamsMap[stream.watchId]?.name?.contains("Coverage", ignoreCase = true) == true)
                    ) {
                        streamsMap[stream.watchId] = stream
                    }
                }
            } catch (_: Throwable) {}
        }

        return Result.success(streamsMap.values.toList())
    }

    open fun resolveWatchStreamForMatch(match: Match): EspnWatchStream? {
        val urlsToTry = mutableListOf<String>()

        if (match.state == com.usopen.tracker.domain.model.MatchState.LIVE) {
            urlsToTry.add(SCHEDULE_LIVE_URL)
        }

        val rawTime = match.startTime
        if (!rawTime.isNullOrEmpty()) {
            val dateCandidates = mutableListOf<String>()
            try {
                val instant = TimezoneFormatter.parseToInstant(rawTime)
                if (instant != null) {
                    val nyDate = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                        .withZone(TimezoneFormatter.NEW_YORK_ZONE)
                        .format(instant)
                    dateCandidates.add(nyDate)
                }
            } catch (_: Throwable) {}

            val utcDate = rawTime.take(10).replace("-", "")
            if (utcDate.length == 8 && !dateCandidates.contains(utcDate)) {
                dateCandidates.add(utcDate)
            }

            for (date in dateCandidates) {
                urlsToTry.add("$SCHEDULE_REPLAY_URL_PREFIX$date/category/tennis")
            }
        }

        if (match.state == com.usopen.tracker.domain.model.MatchState.UPCOMING) {
            urlsToTry.add(SCHEDULE_UPCOMING_URL)
        }

        // 1. Check in-memory cached streams first
        for (url in urlsToTry) {
            val cached = scheduleCache[url]
            if (cached != null) {
                val matched = EspnWatchMatcher.findBestStream(match, cached)
                if (matched != null) return matched
            }
        }

        // 2. Fetch uncached schedule streams
        for (url in urlsToTry) {
            val streams = fetchScheduleStreams(url)
            val matched = EspnWatchMatcher.findBestStream(match, streams)
            if (matched != null) return matched
        }

        return null
    }

    fun fetchSingleUrlStreams(url: String): List<EspnWatchStream> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val jsonBody = response.body?.string() ?: return emptyList()
            parseWatchStreamsJson(jsonBody)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun fetchScheduleStreams(url: String): List<EspnWatchStream> {
        val cached = scheduleCache[url]
        if (cached != null) return cached

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string() ?: return emptyList()
            val parsed = parseScheduleHtml(html)
            if (parsed.isNotEmpty()) {
                scheduleCache[url] = parsed
            }
            parsed
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseScheduleHtml(html: String): List<EspnWatchStream> {
        val jsonStr = extractEspnFittJson(html) ?: return emptyList()
        return try {
            val root = JSONObject(jsonStr)
            val page = root.optJSONObject("page") ?: return emptyList()
            val content = page.optJSONObject("content") ?: return emptyList()
            val watch = content.optJSONObject("watch") ?: return emptyList()
            val arngs = watch.optJSONArray("arngs") ?: return emptyList()

            val streams = mutableListOf<EspnWatchStream>()
            for (i in 0 until arngs.length()) {
                val group = arngs.optJSONObject(i) ?: continue
                val sport = group.optString("nme", "")
                val sctgys = group.optJSONArray("sctgys") ?: continue
                for (j in 0 until sctgys.length()) {
                    val subcat = sctgys.optJSONObject(j) ?: continue
                    val subcatName = subcat.optString("nme", "")
                    if (!sport.contains("tennis", ignoreCase = true) &&
                        !subcatName.contains("tennis", ignoreCase = true) &&
                        !subcatName.contains("open", ignoreCase = true) &&
                        !sport.contains("open", ignoreCase = true)
                    ) {
                        continue
                    }
                    val items = subcat.optJSONArray("arngs") ?: continue
                    for (k in 0 until items.length()) {
                        val item = items.optJSONObject(k) ?: continue
                        val id = item.optString("id", "")
                        val name = item.optString("nme", "")
                        if (id.isEmpty() || name.isEmpty()) continue

                        val shortName = item.optString("fnme", "")
                        val status = item.optString("tp", "")
                        val court = extractCourtName(name)
                        val nb = item.optString("nb", "")
                        val appPlayUrl = if (nb.isNotEmpty()) nb else "sportscenter://x-callback-url/showWatchStream?playID=$id"
                        val webUrl = "https://www.espn.com/watch/player?id=$id"

                        streams.add(
                            EspnWatchStream(
                                watchId = id,
                                name = name,
                                shortName = shortName,
                                subtitle = subcatName.ifEmpty { "US Open" },
                                court = court,
                                status = status,
                                appPlayUrl = appPlayUrl,
                                webUrl = webUrl
                            )
                        )
                    }
                }
            }
            streams
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun extractEspnFittJson(html: String): String? {
        val marker1 = "window['__espnfitt__']"
        val marker2 = "window[\"__espnfitt__\"]"
        var idx = html.indexOf(marker1)
        if (idx == -1) {
            idx = html.indexOf(marker2)
        }
        if (idx == -1) return null

        val start = html.indexOf('{', idx)
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escape = false
        var end = -1

        for (i in start until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }

        return if (end != -1) html.substring(start, end + 1) else null
    }

    fun parseWatchStreamsJson(jsonString: String): List<EspnWatchStream> {
        val root = JSONObject(jsonString)
        val page = root.optJSONObject("page") ?: return emptyList()
        val buckets = page.optJSONArray("buckets") ?: return emptyList()

        val streamsMap = mutableMapOf<String, EspnWatchStream>()

        for (b in 0 until buckets.length()) {
            val bucket = buckets.optJSONObject(b) ?: continue
            val bucketName = bucket.optString("name", "")
            val contents = bucket.optJSONArray("contents") ?: continue

            for (c in 0 until contents.length()) {
                val item = contents.optJSONObject(c) ?: continue
                val id = item.optString("id", "")
                if (id.isEmpty()) continue

                val name = item.optString("name", "")
                val shortName = item.optString("shortName", "")
                val subtitle = item.optString("subtitle", "")
                val status = item.optString("status", "")
                val eventId = if (item.has("eventId")) item.optLong("eventId") else null

                // Filter for tennis / US Open streams and exclude other sports
                val isOtherSport = subtitle.contains("Football", ignoreCase = true) ||
                        subtitle.contains("Soccer", ignoreCase = true) ||
                        subtitle.contains("Baseball", ignoreCase = true) ||
                        subtitle.contains("Volleyball", ignoreCase = true) ||
                        subtitle.contains("Basketball", ignoreCase = true) ||
                        subtitle.contains("MLB", ignoreCase = true)

                val isTennisStream = !isOtherSport && (
                        subtitle.contains("Tennis", ignoreCase = true) ||
                        subtitle.contains("US Open", ignoreCase = true) ||
                        subtitle.contains("Wimbledon", ignoreCase = true) ||
                        subtitle.contains("Roland Garros", ignoreCase = true) ||
                        subtitle.contains("Australian Open", ignoreCase = true) ||
                        name.contains("Tennis", ignoreCase = true) ||
                        name.contains("Court", ignoreCase = true) ||
                        name.contains("Stadium", ignoreCase = true) ||
                        bucketName.contains("Tennis", ignoreCase = true) ||
                        bucketName.contains("Open", ignoreCase = true)
                )

                if (!isTennisStream) continue

                var appPlayUrl: String? = null
                var webUrl: String? = null

                val itemLinks = item.optJSONObject("links")
                if (itemLinks != null && itemLinks.has("appPlay")) {
                    appPlayUrl = itemLinks.optString("appPlay")
                }

                val streamsArray = item.optJSONArray("streams")
                if (streamsArray != null && streamsArray.length() > 0) {
                    val streamObj = streamsArray.optJSONObject(0)
                    val sLinks = streamObj?.optJSONObject("links")
                    if (sLinks != null) {
                        if (appPlayUrl == null && sLinks.has("appPlay")) {
                            appPlayUrl = sLinks.optString("appPlay")
                        }
                        if (sLinks.has("web")) {
                            webUrl = sLinks.optString("web")
                        } else if (sLinks.has("shareUrl")) {
                            webUrl = sLinks.optString("shareUrl")
                        }
                    }
                }

                // Canonical direct stream links using verified watch UUID format
                if (webUrl.isNullOrEmpty()) {
                    webUrl = "https://www.espn.com/watch/player?id=$id"
                }
                if (appPlayUrl.isNullOrEmpty()) {
                    appPlayUrl = "sportscenter://x-callback-url/showWatchStream?playID=$id"
                }

                val court = extractCourtName(name)

                // Store stream, prioritizing entries with explicit player/court titles
                if (!streamsMap.containsKey(id) || (!name.contains("Coverage", ignoreCase = true) && streamsMap[id]?.name?.contains("Coverage", ignoreCase = true) == true)) {
                    streamsMap[id] = EspnWatchStream(
                        watchId = id,
                        name = name,
                        shortName = shortName,
                        subtitle = subtitle,
                        court = court,
                        status = status,
                        eventId = eventId,
                        appPlayUrl = appPlayUrl,
                        webUrl = webUrl
                    )
                }
            }
        }

        return streamsMap.values.toList()
    }

    private fun extractCourtName(title: String): String? {
        val courtPatterns = listOf(
            "Arthur Ashe Stadium",
            "Louis Armstrong Stadium",
            "Grandstand",
            "Stadium 17"
        )
        for (pattern in courtPatterns) {
            if (title.contains(pattern, ignoreCase = true)) {
                return pattern
            }
        }
        val courtRegex = Regex("""Court\s+(\d+)""", RegexOption.IGNORE_CASE)
        val match = courtRegex.find(title)
        return match?.value
    }
}
