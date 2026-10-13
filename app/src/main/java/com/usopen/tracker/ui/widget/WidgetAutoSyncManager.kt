package com.usopen.tracker.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import com.usopen.tracker.data.local.FollowManager
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import com.usopen.tracker.data.repository.SettingsRepositoryImpl
import com.usopen.tracker.domain.model.AutoRefreshInterval
import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import com.usopen.tracker.domain.model.WidgetSyncMode
import com.usopen.tracker.domain.repository.SettingsRepository
import com.usopen.tracker.util.TimezoneFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages background periodic synchronization and lifecycle for the desktop widget.
 * Employs adaptive intervals:
 * - 1 minute (60s) when any followed match is LIVE
 * - 5 minutes (300s) when any followed match is UPCOMING
 * - 15 minutes (900s) when all followed matches are FINISHED or list is empty
 */
object WidgetAutoSyncManager {

    const val PREFS_NAME = "us_open_widget_sync"
    const val KEY_AUTO_SYNC_ENABLED = "key_auto_sync_enabled"
    const val KEY_LAST_SYNC_TIME = "key_last_sync_time"
    const val KEY_LAST_SYNC_SUCCESS = "key_last_sync_success"

    const val INTERVAL_LIVE_MILLIS = 60_000L        // 1 minute for live matches
    const val INTERVAL_UPCOMING_MILLIS = 300_000L    // 5 minutes for upcoming matches
    const val INTERVAL_DEFAULT_MILLIS = 900_000L     // 15 minutes default / results

    const val REQUEST_CODE_AUTO_SYNC = 42

    @Volatile
    var settingsRepository: SettingsRepository? = null
    private var settingsJob: Job? = null

    fun attachSettingsRepository(repository: SettingsRepository, context: Context? = null) {
        this.settingsRepository = repository
        if (context != null) {
            val appContext = context.applicationContext
            settingsJob?.cancel()
            settingsJob = syncScope.launch {
                repository.settings
                    .map { it.widgetSyncMode to it.autoRefreshInterval }
                    .distinctUntilChanged()
                    .collect { (mode, refreshInterval) ->
                        applySyncSettings(appContext, mode, refreshInterval)
                    }
            }
        }
    }

    fun getSyncMode(context: Context): WidgetSyncMode {
        settingsRepository?.let { return it.getSettings().widgetSyncMode }
        return try {
            val repo = SettingsRepositoryImpl.getInstance(context)
            repo.getSettings().widgetSyncMode
        } catch (_: Throwable) {
            WidgetSyncMode.DEFAULT
        }
    }

    fun getAutoRefreshInterval(context: Context): AutoRefreshInterval {
        settingsRepository?.let { return it.getSettings().autoRefreshInterval }
        return try {
            val repo = SettingsRepositoryImpl.getInstance(context)
            repo.getSettings().autoRefreshInterval
        } catch (_: Throwable) {
            AutoRefreshInterval.DEFAULT
        }
    }

    fun applySyncMode(context: Context, mode: WidgetSyncMode) {
        applySyncSettings(context, mode, getAutoRefreshInterval(context))
    }

    fun applySyncSettings(
        context: Context,
        mode: WidgetSyncMode,
        refreshInterval: AutoRefreshInterval
    ) {
        val appContext = context.applicationContext
        if (mode == WidgetSyncMode.DISABLED || !refreshInterval.isEnabled) {
            cancelSync(appContext)
        } else {
            scheduleNextSync(appContext, 0L)
        }
        try {
            FollowedMatchesWidgetProvider.updateAllWidgets(appContext)
        } catch (_: Throwable) {}
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if auto-sync is currently enabled by user preference (defaults to true).
     * Returns false if widget sync mode is DISABLED or if auto-refresh interval is OFF.
     */
    fun isAutoSyncEnabled(context: Context): Boolean {
        if (getSyncMode(context) == WidgetSyncMode.DISABLED) {
            return false
        }
        if (!getAutoRefreshInterval(context).isEnabled) {
            return false
        }
        return getPrefs(context).getBoolean(KEY_AUTO_SYNC_ENABLED, true)
    }

    /**
     * Sets auto-sync enabled or disabled, scheduling or canceling the background alarm accordingly.
     */
    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
        if (enabled) {
            scheduleNextSync(context, 0L)
        } else {
            cancelSync(context)
        }
    }

    /**
     * Toggles auto-sync state and returns the new value.
     */
    fun toggleAutoSync(context: Context): Boolean {
        val newState = !isAutoSyncEnabled(context)
        setAutoSyncEnabled(context, newState)
        return newState
    }

    /**
     * Records the outcome and timestamp of a widget sync attempt.
     */
    fun recordSyncResult(context: Context, success: Boolean) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .putBoolean(KEY_LAST_SYNC_SUCCESS, success)
            .apply()
    }

    /**
     * Returns the epoch millisecond timestamp of the last recorded sync, or 0 if never.
     */
    fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    /**
     * Checks whether at least one instance of the followed matches widget is pinned to the desktop.
     */
    fun hasActiveWidgets(context: Context): Boolean {
        return try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, FollowedMatchesWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            ids != null && ids.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Calculates the background sync interval based on match states, [WidgetSyncMode], and [AutoRefreshInterval].
     */
    @JvmOverloads
    fun calculateInterval(
        matches: List<Match>,
        followedIds: Set<String>,
        mode: WidgetSyncMode = WidgetSyncMode.ADAPTIVE,
        autoRefreshInterval: AutoRefreshInterval? = null
    ): Long {
        return mode.resolveIntervalMillis(matches, followedIds, autoRefreshInterval) ?: INTERVAL_DEFAULT_MILLIS
    }

    @Volatile
    private var activeTickerJob: Job? = null
    private val syncScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Runs an in-process active ticker so that when the device is awake and user is active,
     * the live match auto refresh executes reliably without being delayed by OS doze windows,
     * strictly honoring the user's configured auto-refresh interval setting.
     */
    fun startActiveTicker(context: Context) {
        val appContext = context.applicationContext
        activeTickerJob?.cancel()
        val syncMode = getSyncMode(appContext)
        val refreshInterval = getAutoRefreshInterval(appContext)
        if (syncMode == WidgetSyncMode.DISABLED || !refreshInterval.isEnabled || !isAutoSyncEnabled(appContext) || !hasActiveWidgets(appContext)) return

        activeTickerJob = syncScope.launch {
            while (isActive) {
                val mode = getSyncMode(appContext)
                val currentRefresh = getAutoRefreshInterval(appContext)
                if (mode == WidgetSyncMode.DISABLED || !currentRefresh.isEnabled || !isAutoSyncEnabled(appContext) || !hasActiveWidgets(appContext)) break

                val repository = ScoreboardRepositoryImpl.getInstance(appContext)
                val matches = repository.getOfflineScoreboard()
                val followManager = FollowManager.getInstance(appContext)
                val followedIds = followManager.getFollowedMatchIds()
                val interval = calculateInterval(matches, followedIds, mode, currentRefresh)

                delay(interval)
                if (!isActive || getSyncMode(appContext) == WidgetSyncMode.DISABLED || !getAutoRefreshInterval(appContext).isEnabled || !isAutoSyncEnabled(appContext) || !hasActiveWidgets(appContext)) break

                FollowedMatchesWidgetProvider.triggerBackgroundSync(appContext)
            }
        }
    }

    /**
     * Stops the active in-process ticker.
     */
    fun stopActiveTicker() {
        activeTickerJob?.cancel()
        activeTickerJob = null
    }

    /**
     * Schedules the next background sync alarm with AlarmManager honoring [WidgetSyncMode] and [AutoRefreshInterval].
     */
    fun scheduleNextSync(context: Context, delayMillis: Long? = null) {
        val appContext = context.applicationContext
        val syncMode = getSyncMode(appContext)
        val refreshInterval = getAutoRefreshInterval(appContext)

        if (syncMode == WidgetSyncMode.DISABLED || !refreshInterval.isEnabled || !isAutoSyncEnabled(appContext)) {
            cancelSync(appContext)
            return
        }

        if (!hasActiveWidgets(appContext)) {
            cancelSync(appContext)
            return
        }

        // Keep active ticker running while process is alive
        startActiveTicker(appContext)

        val delay = delayMillis ?: run {
            try {
                val repository = ScoreboardRepositoryImpl.getInstance(appContext)
                val matches = repository.getOfflineScoreboard()
                val followManager = FollowManager.getInstance(appContext)
                followManager.cleanupExpiredFollowedMatches(appContext, matches)
                val followedIds = followManager.getFollowedMatchIds()
                calculateInterval(matches, followedIds, syncMode, refreshInterval)
            } catch (_: Throwable) {
                when (syncMode) {
                    WidgetSyncMode.INTERVAL_15M -> 15 * 60 * 1000L
                    WidgetSyncMode.INTERVAL_30M -> 30 * 60 * 1000L
                    else -> refreshInterval.periodMillis.takeIf { it > 0 } ?: INTERVAL_DEFAULT_MILLIS
                }
            }
        }

        try {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(appContext, FollowedMatchesWidgetProvider::class.java).apply {
                action = FollowedMatchesWidgetProvider.ACTION_AUTO_SYNC_WIDGET
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQUEST_CODE_AUTO_SYNC,
                intent,
                flags
            )

            val triggerAtMillis = SystemClock.elapsedRealtime() + delay.coerceAtLeast(1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        alarmManager.canScheduleExactAlarms()
                    } catch (_: Throwable) {
                        false
                    }
                } else true

                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (_: Throwable) {
            // Guard against system alarm scheduling restrictions
        }
    }

    /**
     * Cancels any pending auto-sync alarm.
     */
    fun cancelSync(context: Context) {
        stopActiveTicker()
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, FollowedMatchesWidgetProvider::class.java).apply {
                action = FollowedMatchesWidgetProvider.ACTION_AUTO_SYNC_WIDGET
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                REQUEST_CODE_AUTO_SYNC,
                intent,
                flags
            )
            alarmManager.cancel(pendingIntent)
        } catch (_: Throwable) {}
    }

    /**
     * Generates a concise user-facing status string for the widget header honoring [WidgetSyncMode] and [AutoRefreshInterval].
     */
    @JvmOverloads
    fun getSyncStatusText(
        context: Context,
        @Suppress("UNUSED_PARAMETER") hasLiveMatches: Boolean,
        mode: WidgetSyncMode = getSyncMode(context),
        autoRefreshInterval: AutoRefreshInterval = getAutoRefreshInterval(context)
    ): String {
        val isEnabled = isAutoSyncEnabled(context) && mode != WidgetSyncMode.DISABLED && autoRefreshInterval.isEnabled
        val lastSync = getLastSyncTime(context)
        val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        val timeString = if (lastSync > 0L) {
            TimezoneFormatter.formatEpochTime(lastSync, is24Hour = is24Hour)
        } else {
            null
        }

        if (!isEnabled || mode == WidgetSyncMode.DISABLED || !autoRefreshInterval.isEnabled) {
            return if (timeString != null) "Auto: OFF • $timeString" else "Auto: OFF"
        }

        return when (mode) {
            WidgetSyncMode.DISABLED -> if (timeString != null) "Auto: OFF • $timeString" else "Auto: OFF"
            WidgetSyncMode.INTERVAL_15M -> if (timeString != null) "⚡ Auto (15m) • $timeString" else "⚡ Auto (15m)"
            WidgetSyncMode.INTERVAL_30M -> if (timeString != null) "⚡ Auto (30m) • $timeString" else "⚡ Auto (30m)"
            WidgetSyncMode.ADAPTIVE -> {
                val intervalTag = when (autoRefreshInterval) {
                    AutoRefreshInterval.INTERVAL_30S -> "30s"
                    AutoRefreshInterval.INTERVAL_60S -> "1m"
                    AutoRefreshInterval.INTERVAL_2M -> "2m"
                    AutoRefreshInterval.OFF -> "OFF"
                }
                if (timeString != null) {
                    "⚡ Auto ($intervalTag) • $timeString"
                } else {
                    "⚡ Auto ($intervalTag) • Active"
                }
            }
        }
    }
}
