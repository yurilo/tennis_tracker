package com.usopen.tracker

import android.app.Application
import com.usopen.tracker.data.repository.ScoreboardRepositoryImpl
import com.usopen.tracker.data.telemetry.AnonymousTelemetryService
import com.usopen.tracker.ui.widget.FollowedMatchesWidgetProvider
import com.usopen.tracker.ui.widget.WidgetAutoSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UsOpenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScoreboardRepositoryImpl.appContext = this
        FollowedMatchesWidgetProvider.updateAllWidgets(this)

        // Ensure that when the system starts up or user logs in, active desktop widgets
        // immediately initialize sync and load fresh scoreboard data
        if (WidgetAutoSyncManager.hasActiveWidgets(this)) {
            WidgetAutoSyncManager.scheduleNextSync(this)
            if (WidgetAutoSyncManager.isAutoSyncEnabled(this)) {
                FollowedMatchesWidgetProvider.triggerBackgroundSync(this)
            }
        }

        // Asynchronously dispatch anonymous startup heartbeat to live telemetry dashboard
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AnonymousTelemetryService.getInstance(this@UsOpenApp)
                    .sendStartupHeartbeat(force = false)
            } catch (_: Throwable) {
                // Ignore any telemetry dispatch failure
            }
        }
    }
}
