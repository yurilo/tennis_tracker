package com.tennis.tracker.ui.widget

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.tennis.tracker.R
import com.tennis.tracker.data.local.FollowManager
import com.tennis.tracker.data.repository.ScoreboardRepositoryImpl
import com.tennis.tracker.domain.model.Competitor
import com.tennis.tracker.domain.model.Match
import com.tennis.tracker.domain.model.MatchState

class FollowedMatchesWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return FollowedMatchesRemoteViewsFactory(applicationContext)
    }
}

class FollowedMatchesRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var followedMatches: List<Match> = emptyList()

    override fun onCreate() {
        loadFollowedMatches()
    }

    override fun onDataSetChanged() {
        loadFollowedMatches()
    }

    private fun loadFollowedMatches() {
        try {
            val followManager = FollowManager.getInstance(context)
            val repository = ScoreboardRepositoryImpl.getInstance(context)
            var allMatches = com.tennis.tracker.data.local.MatchCacheManager.loadMatches(context)
            val isCacheStale = !com.tennis.tracker.data.local.MatchCacheManager.isCacheFresh(context)

            // Refresh from network whenever cache is empty or stale (> TTL)
            if (allMatches.isEmpty() || isCacheStale) {
                try {
                    val fetchResult = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(4500L) {
                            repository.getScoreboard(forceRefresh = true)
                        }
                    }
                    if (fetchResult != null && fetchResult.isSuccess) {
                        val fetched = fetchResult.getOrNull()
                        if (!fetched.isNullOrEmpty()) {
                            allMatches = fetched
                            WidgetAutoSyncManager.recordSyncResult(context, true)
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Automatically clean up any finished or stale matches that completed past the retention policy
            val nowMillis = System.currentTimeMillis()
            followManager.cleanupExpiredFollowedMatches(context, allMatches, nowMillis)

            var followedIds = followManager.getFollowedMatchIds()
            var filtered = allMatches.filter { it.id in followedIds }

            // If user follows matches but they weren't in local cache, attempt synchronous fetch
            // (safe because RemoteViewsFactory methods run on a background binder worker thread)
            if (followedIds.isNotEmpty() && filtered.isEmpty() && !isCacheStale) {
                try {
                    val fetchResult = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(5000L) {
                            repository.getScoreboard(forceRefresh = true)
                        }
                    }
                    if (fetchResult != null && fetchResult.isSuccess) {
                        allMatches = fetchResult.getOrNull() ?: allMatches
                        followManager.cleanupExpiredFollowedMatches(context, allMatches, nowMillis)
                        followedIds = followManager.getFollowedMatchIds()
                        filtered = allMatches.filter { it.id in followedIds }
                    }
                } catch (_: Throwable) {}
            }

            val policy = followManager.getActiveRetentionPolicy()
            followedMatches = filtered.mapNotNull { match ->
                val normalized = if (match.state != MatchState.FINISHED && followManager.isStaleIncompleteMatch(match, nowMillis)) {
                    match.copy(state = MatchState.FINISHED, statusDetail = "Final")
                } else {
                    match
                }
                if (normalized.state == MatchState.FINISHED) {
                    val completionTime = followManager.getCompletionTimestamp(normalized, nowMillis)
                    if (policy.isExpired(completionTime, nowMillis)) {
                        followManager.unfollowMatch(context, normalized.id)
                        null
                    } else {
                        normalized
                    }
                } else {
                    normalized
                }
            }
        } catch (_: Throwable) {
            followedMatches = emptyList()
        }
    }

    override fun onDestroy() {
        followedMatches = emptyList()
    }

    override fun getCount(): Int = followedMatches.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in followedMatches.indices) return null
        return try {
            val match = followedMatches[position]
            val views = RemoteViews(context.packageName, R.layout.widget_match_item)

            // 1. Header: Grouping & Court/Venue
            val courtInfo = match.displayCourt ?: match.venue.takeIf { !it.equals("New York, USA", ignoreCase = true) }
            val headerText = buildString {
                append(match.eventGrouping)
                if (!courtInfo.isNullOrBlank()) {
                    append(" • ")
                    append(courtInfo)
                }
            }
            views.setTextViewText(R.id.txt_item_header, headerText)

            // 2. Status Badge
            val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
            val badgeText = match.getDisplayStatus(java.time.ZoneId.systemDefault(), is24Hour)
            when (match.state) {
                MatchState.LIVE -> {
                    views.setViewVisibility(R.id.txt_item_status_live, View.VISIBLE)
                    views.setViewVisibility(R.id.txt_item_status_final, View.GONE)
                    views.setViewVisibility(R.id.txt_item_status_upcoming, View.GONE)
                    views.setTextViewText(R.id.txt_item_status_live, badgeText)
                }
                MatchState.FINISHED -> {
                    views.setViewVisibility(R.id.txt_item_status_live, View.GONE)
                    views.setViewVisibility(R.id.txt_item_status_final, View.VISIBLE)
                    views.setViewVisibility(R.id.txt_item_status_upcoming, View.GONE)
                    views.setTextViewText(R.id.txt_item_status_final, badgeText)
                }
                MatchState.UPCOMING -> {
                    views.setViewVisibility(R.id.txt_item_status_live, View.GONE)
                    views.setViewVisibility(R.id.txt_item_status_final, View.GONE)
                    views.setViewVisibility(R.id.txt_item_status_upcoming, View.VISIBLE)
                    views.setTextViewText(R.id.txt_item_status_upcoming, badgeText)
                }
            }

            val isLive = match.state == MatchState.LIVE

            // 3. Competitor 1
            views.setTextViewText(R.id.txt_player1_name, formatPlayerDisplay(match.player1))
            views.setTextViewText(R.id.txt_player1_scores, formatSetScores(match.player1, match.player2, isLive))
            val p1Serving = isLive && match.player1.isServing
            views.setViewVisibility(R.id.txt_player1_serve, if (p1Serving) View.VISIBLE else View.GONE)

            val p1Game = getGameScore(match.player1, match.player2, isLive)
            if (p1Game != null) {
                if (p1Serving) {
                    views.setViewVisibility(R.id.txt_player1_game_serving, View.VISIBLE)
                    views.setTextViewText(R.id.txt_player1_game_serving, "● $p1Game")
                    views.setViewVisibility(R.id.txt_player1_game, View.GONE)
                } else {
                    views.setViewVisibility(R.id.txt_player1_game_serving, View.GONE)
                    views.setViewVisibility(R.id.txt_player1_game, View.VISIBLE)
                    views.setTextViewText(R.id.txt_player1_game, p1Game)
                }
            } else {
                views.setViewVisibility(R.id.txt_player1_game, View.GONE)
                views.setViewVisibility(R.id.txt_player1_game_serving, View.GONE)
            }

            // 4. Competitor 2
            views.setTextViewText(R.id.txt_player2_name, formatPlayerDisplay(match.player2))
            views.setTextViewText(R.id.txt_player2_scores, formatSetScores(match.player2, match.player1, isLive))
            val p2Serving = isLive && match.player2.isServing
            views.setViewVisibility(R.id.txt_player2_serve, if (p2Serving) View.VISIBLE else View.GONE)

            val p2Game = getGameScore(match.player2, match.player1, isLive)
            if (p2Game != null) {
                if (p2Serving) {
                    views.setViewVisibility(R.id.txt_player2_game_serving, View.VISIBLE)
                    views.setTextViewText(R.id.txt_player2_game_serving, "● $p2Game")
                    views.setViewVisibility(R.id.txt_player2_game, View.GONE)
                } else {
                    views.setViewVisibility(R.id.txt_player2_game_serving, View.GONE)
                    views.setViewVisibility(R.id.txt_player2_game, View.VISIBLE)
                    views.setTextViewText(R.id.txt_player2_game, p2Game)
                }
            } else {
                views.setViewVisibility(R.id.txt_player2_game, View.GONE)
                views.setViewVisibility(R.id.txt_player2_game_serving, View.GONE)
            }

            // 5. FillInIntent for item click
            val fillInIntent = Intent().apply {
                putExtra(FollowedMatchesWidgetProvider.EXTRA_MATCH_ID, match.id)
                putExtra(FollowedMatchesWidgetProvider.EXTRA_FROM_WIDGET, true)
            }
            views.setOnClickFillInIntent(R.id.widget_match_item_root, fillInIntent)

            views
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatPlayerDisplay(competitor: Competitor): String {
        return buildString {
            competitor.countryFlag?.let { flag ->
                append(flag)
                append(" ")
            }
            if (competitor.isWinner) {
                append("✓ ")
            }
            append(competitor.displayNameWithSeed)
        }
    }

    private fun formatSetScores(competitor: Competitor, opponent: Competitor, isLive: Boolean): CharSequence {
        if (competitor.setScores.isEmpty()) return ""

        val rawText = StringBuilder()
        val tiebreaks = mutableListOf<Pair<Int, Int>>() // Pair(charIndex, tiebreakValue)

        for (i in competitor.setScores.indices) {
            val score = competitor.setScores[i]
            val oppScore = opponent.setScores.getOrNull(i) ?: 0
            val tb = competitor.tiebreakScores.getOrNull(i)

            if (rawText.isNotEmpty()) {
                rawText.append("   ")
            }
            rawText.append(score)

            val isLiveTiebreak = isLive && (score >= 6 && oppScore >= 6) && tb != null
            val isFinishedTiebreak = score < oppScore && tb != null && tb >= 0

            if ((isLiveTiebreak || isFinishedTiebreak) && tb != null) {
                val tbIndex = rawText.length
                rawText.append(tb)
                tiebreaks.add(tbIndex to (tbIndex + tb.toString().length))
            }
        }

        val spannable = SpannableString(rawText.toString())
        for ((start, end) in tiebreaks) {
            spannable.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun getGameScore(competitor: Competitor, opponent: Competitor, isLive: Boolean): String? {
        if (!isLive) return null
        val lastIdx = competitor.setScores.size - 1
        val mySet = if (lastIdx >= 0) competitor.setScores[lastIdx] else 0
        val oppSet = if (lastIdx >= 0) opponent.setScores.getOrNull(lastIdx) ?: 0 else 0
        val myTb = if (lastIdx >= 0) competitor.tiebreakScores.getOrNull(lastIdx) else null
        val oppTb = if (lastIdx >= 0) opponent.tiebreakScores.getOrNull(lastIdx) else null
        val isSixAllTiebreak = lastIdx >= 0 && (
            (mySet >= 6 && oppSet >= 6 && mySet == oppSet) || myTb != null || oppTb != null
        )

        if (isSixAllTiebreak) {
            val raw = competitor.currentGameScore?.trim()
            val standardPoints = setOf("15", "30", "40", "Adv", "ADV", "A", "Ad", "AD", "Deuce", "DEUCE")
            return when {
                !raw.isNullOrEmpty() && raw.startsWith("TB", ignoreCase = true) -> raw
                myTb != null || oppTb != null -> "TB ${myTb ?: 0}"
                !raw.isNullOrEmpty() && raw !in standardPoints && raw.all { it.isDigit() } -> "TB $raw"
                else -> "TB"
            }
        }

        if (!competitor.currentGameScore.isNullOrBlank()) {
            val raw = competitor.currentGameScore.trim()
            return when {
                raw.equals("A", ignoreCase = true) || raw.equals("Ad", ignoreCase = true) -> "Adv"
                raw.equals("Deuce", ignoreCase = true) -> "40"
                else -> raw
            }
        }
        return if (competitor.isServing) "40" else "30"
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        return followedMatches.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    }

    override fun hasStableIds(): Boolean = true
}
