package com.usopen.tracker.domain.model

import android.content.Context
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PlayerPhotoProvider {

    private val DIACRITICS_REGEX = Regex("\\p{M}")
    private const val USER_AGENT = "TennisTracker/1.0 (Android; dev@tennis.tracker)"
    private const val PREFS_NAME = "player_photo_cache"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // In-memory cache for dynamically resolved photo URLs: normalized name -> URL (or "" for not found)
    internal val dynamicPhotoCache = ConcurrentHashMap<String, String>()

    /**
     * Normalizes a player name by stripping accents/diacritics, transliterating special letters,
     * converting to lowercase, and collapsing whitespace.
     */
    fun normalizeName(name: String): String {
        val replacedSpecial = name.trim()
            .replace("đ", "dj")
            .replace("Đ", "dj")
            .replace('ł', 'l')
            .replace('Ł', 'l')
            .replace('ø', 'o')
            .replace('Ø', 'o')
            .replace("ß", "ss")
        val decomposed = Normalizer.normalize(replacedSpecial, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Synchronously resolves a player photo URL from memory cache, persisted cache, or custom URL.
     * Rejects defunct espncdn.com headshot URLs.
     */
    fun getPhotoUrl(
        name: String,
        @Suppress("UNUSED_PARAMETER") athleteId: String? = null,
        existingUrl: String? = null
    ): String? {
        if (!existingUrl.isNullOrBlank() && !existingUrl.contains("espncdn.com")) {
            return existingUrl
        }

        val normalized = normalizeName(name)
        if (normalized.isEmpty()) return null

        // 1. Check in-memory cache
        dynamicPhotoCache[normalized]?.let {
            return it.ifEmpty { null }
        }

        // 2. Check persistent SharedPreferences cache
        val persisted = getPersistedPhotoUrl(normalized)
        if (!persisted.isNullOrBlank()) {
            dynamicPhotoCache[normalized] = persisted
            return persisted
        }

        return null
    }

    fun getPhotoUrl(competitor: Competitor): String? {
        return getPhotoUrl(
            name = competitor.name,
            athleteId = competitor.id,
            existingUrl = competitor.photoUrl
        )
    }

    /**
     * Asynchronously resolves a player photo dynamically from official WTA directories
     * or Wikipedia / Wikimedia Commons on-the-fly.
     */
    suspend fun resolvePhotoUrl(name: String, tour: TennisTour? = null): String? {
        val cached = getPhotoUrl(name)
        if (cached != null) return cached

        val normalized = normalizeName(name)
        if (normalized.isEmpty()) return null

        // If previously negative-cached (confirmed unavailable)
        if (dynamicPhotoCache[normalized] == "") return null

        return withContext(Dispatchers.IO) {
            // 1. For WTA players (or unspecified), query official WTA API first
            if (tour == TennisTour.WTA || tour == null) {
                val wtaUrl = resolveFromWta(name)
                if (wtaUrl != null) {
                    cacheResolvedUrl(normalized, wtaUrl)
                    return@withContext wtaUrl
                }
            }

            // 2. Query Wikipedia Summary REST API (primary for ATP, fallback for WTA)
            val wikiUrl = resolveFromWikipedia(name)
            if (wikiUrl != null) {
                cacheResolvedUrl(normalized, wikiUrl)
                return@withContext wikiUrl
            }

            // 3. If tour was ATP (so WTA wasn't queried initially), try WTA as secondary fallback
            if (tour == TennisTour.ATP) {
                val wtaUrl = resolveFromWta(name)
                if (wtaUrl != null) {
                    cacheResolvedUrl(normalized, wtaUrl)
                    return@withContext wtaUrl
                }
            }

            // Negative cache to prevent redundant queries
            cacheResolvedUrl(normalized, "")
            null
        }
    }

    suspend fun resolvePhotoUrl(competitor: Competitor, match: Match? = null): String? {
        val direct = competitor.photoUrl?.takeIf { !it.contains("espncdn.com") }
        if (direct != null) return direct
        val tour = match?.let { TennisProfileProvider.resolveTour(competitor, it) }
        return resolvePhotoUrl(competitor.name, tour)
    }

    /**
     * Dynamically queries the official WTA Tour API for player metadata and resolves official headshot.
     */
    private fun resolveFromWta(name: String): String? {
        try {
            val clean = name.trim()
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val apiUrl = "https://api.wtatennis.com/tennis/players?name=$encoded"
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return null
                    val json = JSONObject(body)
                    val content = json.optJSONArray("content") ?: return null
                    if (content.length() > 0) {
                        for (i in 0 until content.length().coerceAtMost(3)) {
                            val p = content.optJSONObject(i) ?: continue
                            val id = p.optInt("id", 0)
                            if (id > 0) {
                                val headshotUrl = "https://wtafiles.blob.core.windows.net/images/headshots/$id.jpg"
                                val headReq = Request.Builder()
                                    .url(headshotUrl)
                                    .header("User-Agent", USER_AGENT)
                                    .head()
                                    .build()
                                client.newCall(headReq).execute().use { headResp ->
                                    if (headResp.isSuccessful) {
                                        return headshotUrl
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Dynamically queries Wikipedia REST summary API for player article thumbnail.
     */
    private fun resolveFromWikipedia(name: String): String? {
        try {
            val clean = name.trim()
            val candidates = listOf(
                clean,
                "$clean (tennis)"
            )

            for (cand in candidates) {
                val encoded = URLEncoder.encode(cand.replace(' ', '_'), "UTF-8")
                val apiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        if (json.optString("type") != "disambiguation") {
                            val thumb = json.optJSONObject("thumbnail")?.optString("source")
                            if (!thumb.isNullOrBlank()) {
                                return thumb
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun cacheResolvedUrl(normalized: String, url: String) {
        dynamicPhotoCache[normalized] = url
        try {
            val context = ScoreboardRepositoryImpl.appContext ?: return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(normalized, url).apply()
        } catch (_: Throwable) {}
    }

    private fun getPersistedPhotoUrl(normalized: String): String? {
        try {
            val context = ScoreboardRepositoryImpl.appContext ?: return null
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(normalized, null)
        } catch (_: Throwable) {
            return null
        }
    }

    fun clearDynamicCache() {
        dynamicPhotoCache.clear()
        try {
            val context = ScoreboardRepositoryImpl.appContext ?: return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (_: Throwable) {}
    }
}
