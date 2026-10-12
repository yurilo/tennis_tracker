package com.usopen.tracker.ui.component

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import com.usopen.tracker.domain.model.TennisTour
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usopen.tracker.domain.model.Competitor
import com.usopen.tracker.domain.model.PlayerPhotoProvider
import com.usopen.tracker.ui.theme.UsOpenGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import java.io.File
import java.util.concurrent.TimeUnit

object PlayerImageCache {
    private val memoryCache = object : LruCache<String, ImageBitmap>(100) {}
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun get(url: String): ImageBitmap? = memoryCache.get(url)

    fun put(url: String, bitmap: ImageBitmap) {
        memoryCache.put(url, bitmap)
    }

    fun clear() {
        memoryCache.evictAll()
    }

    suspend fun loadImage(url: String?): ImageBitmap? {
        if (url.isNullOrBlank() || url.contains("espncdn.com")) return null
        get(url)?.let { return it }

        return withContext(Dispatchers.IO) {
            val diskFile = getDiskCacheFile(url)
            if (diskFile != null && diskFile.exists() && diskFile.length() > 0) {
                try {
                    val bytes = diskFile.readBytes()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val imageBitmap = bitmap.asImageBitmap()
                        put(url, imageBitmap)
                        return@withContext imageBitmap
                    }
                } catch (_: Throwable) {
                    // Ignore corrupted disk file and re-fetch
                }
            }

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "TennisTracker/1.0 (Android; dev@tennis.tracker)")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            if (diskFile != null) {
                                try {
                                    diskFile.parentFile?.mkdirs()
                                    diskFile.writeBytes(bytes)
                                } catch (_: Throwable) {}
                            }
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val imageBitmap = bitmap.asImageBitmap()
                                put(url, imageBitmap)
                                return@withContext imageBitmap
                            }
                        }
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    suspend fun loadPlayerBitmap(competitor: Competitor, tour: TennisTour? = null): ImageBitmap? {
        val directUrl = competitor.photoUrl?.takeIf { !it.contains("espncdn.com") }
            ?: PlayerPhotoProvider.getPhotoUrl(competitor)
        if (directUrl != null) {
            return loadImage(directUrl)
        }
        val resolvedUrl = PlayerPhotoProvider.resolvePhotoUrl(competitor.name, tour)
        if (!resolvedUrl.isNullOrBlank()) {
            return loadImage(resolvedUrl)
        }
        return null
    }

    private fun getDiskCacheFile(url: String): File? {
        val cacheDir = ScoreboardRepositoryImpl.appContext?.cacheDir ?: return null
        val photosDir = File(cacheDir, "player_photos")
        val filename = Integer.toHexString(url.hashCode()) + ".jpg"
        return File(photosDir, filename)
    }
}

@Composable
fun PlayerAvatar(
    competitor: Competitor,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    accentColor: Color = UsOpenGold,
    showBadges: Boolean = true,
    showTourBadge: Boolean = false,
    tour: TennisTour? = null,
    onClick: (() -> Unit)? = null
) {
    if (competitor.isDoubles && competitor.individualPlayers.size >= 2) {
        DoublesAvatar(
            competitor = competitor,
            modifier = modifier,
            size = size,
            accentColor = accentColor,
            showBadges = showBadges,
            showTourBadge = showTourBadge,
            tour = tour,
            onClick = onClick
        )
        return
    }

    val isDark = isSystemInDarkTheme()
    val badgeSize = (size * 0.32f).coerceIn(14.dp, 20.dp)

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            onClick = onClick,
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = "Open ${tour?.displayName ?: "ATP/WTA"} Profile for ${competitor.name}"
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Main Avatar Circle
        SingleAvatarCircle(
            competitor = competitor,
            size = size,
            accentColor = accentColor,
            isDark = isDark,
            modifier = clickModifier,
            tour = tour
        )

        // Overlay Badges
        if (showBadges && size >= 40.dp) {
            // Serving indicator badge at bottom-end
            if (competitor.isServing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(Color(0xFFCCFF00))
                        .border(1.5.dp, Color.Black, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎾",
                        fontSize = (badgeSize.value * 0.62f).sp
                    )
                }
            }

            // Winner badge at top-end
            if (competitor.isWinner) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(UsOpenGold)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Winner",
                        tint = Color(0xFF0A2540),
                        modifier = Modifier.size(badgeSize * 0.7f)
                    )
                }
            }

            // Seed badge at top-start
            if (competitor.seed != null && competitor.seed > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-3).dp, y = (-2).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) Color(0xFF0F172A) else Color(0xFF1E293B))
                        .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "#${competitor.seed}",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tour badge (ATP / WTA) at bottom-start
            if (showTourBadge && tour != null) {
                val tourColor = Color(tour.brandColorHex)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-3).dp, y = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tourColor)
                        .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .then(clickModifier)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${tour.displayName} ↗",
                        color = Color.White,
                        fontSize = (badgeSize.value * 0.44f).coerceIn(8f, 10f).sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleAvatarCircle(
    competitor: Competitor,
    size: Dp,
    accentColor: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    tour: TennisTour? = null
) {
    val photoUrl = competitor.photoUrl?.takeIf { !it.contains("espncdn.com") }
        ?: PlayerPhotoProvider.getPhotoUrl(competitor)
    val imageBitmapState = produceState<ImageBitmap?>(
        initialValue = photoUrl?.let { PlayerImageCache.get(it) },
        key1 = competitor.name,
        key2 = photoUrl
    ) {
        if (value == null) {
            value = if (photoUrl != null) {
                PlayerImageCache.loadImage(photoUrl)
            } else {
                PlayerImageCache.loadPlayerBitmap(competitor, tour)
            }
        }
    }
    val borderWidth = if (size >= 48.dp) 2.5.dp else 1.5.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .shadow(elevation = 3.dp, shape = CircleShape)
            .clip(CircleShape)
            .border(borderWidth, accentColor, CircleShape)
            .background(if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = imageBitmapState.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = competitor.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accentColor.copy(alpha = if (isDark) 0.35f else 0.25f),
                                accentColor.copy(alpha = if (isDark) 0.12f else 0.08f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = competitor.initials,
                    color = accentColor,
                    fontWeight = FontWeight.Black,
                    fontSize = (size.value * 0.36f).sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun DoublesAvatar(
    competitor: Competitor,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    accentColor: Color = UsOpenGold,
    showBadges: Boolean = true,
    showTourBadge: Boolean = false,
    tour: TennisTour? = null,
    onClick: (() -> Unit)? = null
) {
    val players = competitor.individualPlayers
    val p1 = players.getOrNull(0) ?: competitor
    val p2 = players.getOrNull(1) ?: competitor
    val isDark = isSystemInDarkTheme()

    val subSize = (size.value * 0.68f).dp.coerceAtLeast(18.dp)
    val badgeSize = (size * 0.32f).coerceIn(14.dp, 20.dp)

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            onClick = onClick,
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = "Open ${tour?.displayName ?: "ATP/WTA"} Profiles for ${competitor.name}"
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .then(clickModifier)
    ) {
        // Partner 1 Circle (Top-Start)
        Box(
            modifier = Modifier
                .size(subSize)
                .align(Alignment.TopStart)
        ) {
            SingleAvatarCircle(
                competitor = p1,
                size = subSize,
                accentColor = accentColor,
                isDark = isDark,
                tour = tour
            )
        }

        // Partner 2 Circle (Bottom-End, overlapping Partner 1)
        Box(
            modifier = Modifier
                .size(subSize)
                .align(Alignment.BottomEnd)
        ) {
            SingleAvatarCircle(
                competitor = p2,
                size = subSize,
                accentColor = accentColor,
                isDark = isDark,
                tour = tour
            )
        }

        // Overlay Badges
        if (showBadges && size >= 40.dp) {
            // Serving indicator badge at bottom-end
            if (competitor.isServing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(Color(0xFFCCFF00))
                        .border(1.5.dp, Color.Black, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎾",
                        fontSize = (badgeSize.value * 0.62f).sp
                    )
                }
            }

            // Winner badge at top-end
            if (competitor.isWinner) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(badgeSize)
                        .clip(CircleShape)
                        .background(UsOpenGold)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Winner",
                        tint = Color(0xFF0A2540),
                        modifier = Modifier.size(badgeSize * 0.7f)
                    )
                }
            }

            // Seed badge at top-start
            if (competitor.seed != null && competitor.seed > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-3).dp, y = (-2).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) Color(0xFF0F172A) else Color(0xFF1E293B))
                        .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "#${competitor.seed}",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tour badge (ATP / WTA) at bottom-start
            if (showTourBadge && tour != null) {
                val tourColor = Color(tour.brandColorHex)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-3).dp, y = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tourColor)
                        .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${tour.displayName} ↗",
                        color = Color.White,
                        fontSize = (badgeSize.value * 0.44f).coerceIn(8f, 10f).sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.2.sp
                    )
                }
            }
        }
    }
}
