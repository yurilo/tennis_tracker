package com.usopen.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.usopen.tracker.ui.screen.ScoreboardScreen
import com.usopen.tracker.ui.theme.UsOpenTrackerTheme
import com.usopen.tracker.ui.viewmodel.MatchFilter
import com.usopen.tracker.ui.viewmodel.ScoreboardViewModel
import com.usopen.tracker.ui.widget.FollowedMatchesWidgetProvider

class MainActivity : ComponentActivity() {

    private val viewModel: ScoreboardViewModel by viewModels()

    private val timeChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            viewModel.loadMatches(forceRefresh = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initFollowManager(this)
        handleWidgetIntent(intent)

        setContent {
            UsOpenTrackerTheme {
                ScoreboardScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        registerReceiver(timeChangeReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(timeChangeReceiver)
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent == null) return
        val matchId = intent.getStringExtra(FollowedMatchesWidgetProvider.EXTRA_MATCH_ID)
        if (!matchId.isNullOrEmpty()) {
            val match = viewModel.uiState.value.allMatches.find { it.id == matchId }
            if (match != null) {
                viewModel.openMatchStats(match)
            } else {
                viewModel.setFilter(MatchFilter.FOLLOWED)
            }
        }
    }
}
