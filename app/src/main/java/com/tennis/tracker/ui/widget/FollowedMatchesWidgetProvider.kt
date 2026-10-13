package com.tennis.tracker.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.tennis.tracker.MainActivity
import com.tennis.tracker.R
import com.tennis.tracker.data.local.FollowManager
import com.tennis.tracker.data.repository.ScoreboardRepositoryImpl
import com.tennis.tracker.domain.model.MatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FollowedMatchesWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetAutoSyncManager.scheduleNextSync(context, 0L)
        if (WidgetAutoSyncManager.isAutoSyncEnabled(context)) {
            triggerBackgroundSync(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetAutoSyncManager.cancelSync(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)

        // Ensure auto sync is scheduled
        WidgetAutoSyncManager.scheduleNextSync(context)

        // If auto-sync enabled, trigger background refresh
        if (WidgetAutoSyncManager.isAutoSyncEnabled(context)) {
            triggerBackgroundSync(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_AUTO_SYNC_WIDGET -> {
                triggerBackgroundSync(context)
            }
            ACTION_TOGGLE_AUTO_SYNC -> {
                val isEnabled = WidgetAutoSyncManager.toggleAutoSync(context)
                val msg = if (isEnabled) {
                    "Widget auto-sync enabled (auto-refreshes live scores)"
                } else {
                    "Widget auto-sync paused"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                updateAllWidgets(context)
                if (isEnabled) {
                    triggerBackgroundSync(context)
                }
            }
            ACTION_REFRESH_WIDGET -> {
                Toast.makeText(context, "Syncing followed matches…", Toast.LENGTH_SHORT).show()
                val pendingResult = try { goAsync() } catch (_: Throwable) { null }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = ScoreboardRepositoryImpl.getInstance(context)
                        repository.getScoreboard(forceRefresh = true)
                        WidgetAutoSyncManager.recordSyncResult(context, true)
                    } catch (_: Throwable) {
                        WidgetAutoSyncManager.recordSyncResult(context, false)
                    } finally {
                        withContext(Dispatchers.Main) {
                            updateAllWidgets(context)
                            Toast.makeText(context, "Followed matches updated", Toast.LENGTH_SHORT).show()
                            WidgetAutoSyncManager.scheduleNextSync(context)
                            try {
                                pendingResult?.finish()
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.USER_UNLOCKED",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED -> {
                // 1. Immediately update UI from local cache so user sees current data on login/boot
                updateAllWidgets(context)
                // 2. Schedule next sync / restart active ticker
                WidgetAutoSyncManager.scheduleNextSync(context, 0L)
                // 3. Immediately trigger background network sync so fresh data is loaded
                if (WidgetAutoSyncManager.isAutoSyncEnabled(context)) {
                    triggerBackgroundSync(context)
                }
            }
            ACTION_PINNED_SUCCESS -> {
                Toast.makeText(context, "Tennis Watchlist widget added to home screen!", Toast.LENGTH_LONG).show()
                WidgetAutoSyncManager.scheduleNextSync(context, 0L)
                if (WidgetAutoSyncManager.isAutoSyncEnabled(context)) {
                    triggerBackgroundSync(context)
                }
            }
        }
    }

    private fun triggerBackgroundSync(context: Context) {
        val pendingResult = try { goAsync() } catch (_: Throwable) { null }
        Companion.triggerBackgroundSync(context, pendingResult)
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.tennis.tracker.ACTION_REFRESH_WIDGET"
        const val ACTION_AUTO_SYNC_WIDGET = "com.tennis.tracker.ACTION_AUTO_SYNC_WIDGET"
        const val ACTION_TOGGLE_AUTO_SYNC = "com.tennis.tracker.ACTION_TOGGLE_AUTO_SYNC"
        const val ACTION_PINNED_SUCCESS = "com.tennis.tracker.ACTION_PINNED_SUCCESS"
        const val EXTRA_MATCH_ID = "extra_match_id"

        fun triggerBackgroundSync(
            context: Context,
            pendingResult: BroadcastReceiver.PendingResult? = null
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (WidgetAutoSyncManager.isAutoSyncEnabled(context)) {
                        val repository = ScoreboardRepositoryImpl.getInstance(context)
                        repository.getScoreboard(forceRefresh = true)
                        WidgetAutoSyncManager.recordSyncResult(context, true)
                    }
                } catch (_: Throwable) {
                    WidgetAutoSyncManager.recordSyncResult(context, false)
                } finally {
                    withContext(Dispatchers.Main) {
                        updateAllWidgets(context)
                        WidgetAutoSyncManager.scheduleNextSync(context)
                        try {
                            pendingResult?.finish()
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_followed_matches)

                // Setup RemoteViewsService adapter for the matches ListView with unique content Uri
                val serviceIntent = Intent(context, FollowedMatchesWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    data = Uri.parse("content://com.tennis.tracker.widget/$appWidgetId")
                }
                views.setRemoteAdapter(R.id.widget_matches_list, serviceIntent)
                views.setEmptyView(R.id.widget_matches_list, R.id.widget_empty_view)

                val immutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                // PendingIntent to launch MainActivity from header or empty state
                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openAppPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    openAppIntent,
                    immutableFlags
                )
                views.setOnClickPendingIntent(R.id.btn_widget_open_app, openAppPendingIntent)
                views.setOnClickPendingIntent(R.id.btn_empty_open_app, openAppPendingIntent)
                views.setOnClickPendingIntent(R.id.widget_empty_view, openAppPendingIntent)

                // PendingIntent to manually refresh widget
                val refreshIntent = Intent(context, FollowedMatchesWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH_WIDGET
                }
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1,
                    refreshIntent,
                    immutableFlags
                )
                views.setOnClickPendingIntent(R.id.btn_widget_refresh, refreshPendingIntent)

                // PendingIntent to toggle auto sync
                val toggleIntent = Intent(context, FollowedMatchesWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE_AUTO_SYNC
                }
                val togglePendingIntent = PendingIntent.getBroadcast(
                    context,
                    3,
                    toggleIntent,
                    immutableFlags
                )
                views.setOnClickPendingIntent(R.id.btn_widget_auto_sync_on, togglePendingIntent)
                views.setOnClickPendingIntent(R.id.btn_widget_auto_sync_off, togglePendingIntent)

                // Update Auto-Sync toggle visibility
                val isAutoSyncOn = WidgetAutoSyncManager.isAutoSyncEnabled(context)
                if (isAutoSyncOn) {
                    views.setViewVisibility(R.id.btn_widget_auto_sync_on, View.VISIBLE)
                    views.setViewVisibility(R.id.btn_widget_auto_sync_off, View.GONE)
                } else {
                    views.setViewVisibility(R.id.btn_widget_auto_sync_on, View.GONE)
                    views.setViewVisibility(R.id.btn_widget_auto_sync_off, View.VISIBLE)
                }

                // PendingIntent template for clicking on match items
                val itemClickIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val itemClickFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val itemClickPendingIntent = PendingIntent.getActivity(
                    context,
                    2,
                    itemClickIntent,
                    itemClickFlags
                )
                views.setPendingIntentTemplate(R.id.widget_matches_list, itemClickPendingIntent)

                // Update live count indicator if any followed matches are live
                var liveCount = 0
                try {
                    val followManager = FollowManager.getInstance(context)
                    val followedIds = followManager.getFollowedMatchIds()
                    val repository = ScoreboardRepositoryImpl.getInstance(context)
                    val matches = repository.getOfflineScoreboard()
                    liveCount = matches.count { it.state == MatchState.LIVE && it.id in followedIds }

                    if (liveCount > 0) {
                        views.setViewVisibility(R.id.txt_widget_live_count, View.VISIBLE)
                        views.setTextViewText(R.id.txt_widget_live_count, "● $liveCount LIVE")
                    } else {
                        views.setViewVisibility(R.id.txt_widget_live_count, View.GONE)
                    }
                } catch (_: Throwable) {
                    views.setViewVisibility(R.id.txt_widget_live_count, View.GONE)
                }

                // Update auto-sync status subtitle with timestamp
                val statusText = WidgetAutoSyncManager.getSyncStatusText(context, liveCount > 0)
                views.setTextViewText(R.id.txt_widget_sync_status, statusText)

                appWidgetManager.updateAppWidget(appWidgetId, views)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_matches_list)
            } catch (_: Throwable) {
                // Guard against framework errors
            }
        }

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, FollowedMatchesWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

                if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                    for (id in appWidgetIds) {
                        updateWidget(context, appWidgetManager, id)
                    }
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_matches_list)
                }
            } catch (_: Throwable) {
                // Guard against framework errors
            }
        }

        /**
         * Requests the launcher to pin the widget directly to the user's desktop / home screen.
         */
        fun requestPinWidget(context: Context): Boolean {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, FollowedMatchesWidgetProvider::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager.isRequestPinAppWidgetSupported) {
                val successIntent = Intent(context, FollowedMatchesWidgetProvider::class.java).apply {
                    action = ACTION_PINNED_SUCCESS
                }
                val immutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val successCallback = PendingIntent.getBroadcast(
                    context,
                    0,
                    successIntent,
                    immutableFlags
                )
                return appWidgetManager.requestPinAppWidget(componentName, null, successCallback)
            }
            return false
        }
    }
}
