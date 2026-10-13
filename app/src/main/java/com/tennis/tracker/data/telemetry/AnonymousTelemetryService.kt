package com.tennis.tracker.data.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tennis.tracker.domain.telemetry.TelemetryEvent
import com.tennis.tracker.domain.telemetry.TelemetryService
import com.tennis.tracker.domain.repository.SettingsRepository
import com.tennis.tracker.data.repository.SettingsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Robust implementation of AnonymousTelemetryService.
 * Transmits non-identifiable lifecycle telemetry (app launches and active sessions)
 * to a configurable dashboard endpoint with automatic rate-limiting and local persistence.
 */
class AnonymousTelemetryService @JvmOverloads constructor(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
    private val defaultEndpoint: String = DEFAULT_ENDPOINT,
    private var settingsRepository: SettingsRepository? = null
) : TelemetryService {

    companion object {
        private const val TAG = "TennisTelemetry"
        private const val PREFS_NAME = "tennis_anonymous_telemetry_prefs"
        private const val KEY_ANONYMOUS_ID = "anonymous_device_uuid"
        private const val KEY_TELEMETRY_ENABLED = "telemetry_enabled"
        private const val KEY_LAST_HEARTBEAT_TIME = "last_heartbeat_ms"
        private const val KEY_ENDPOINT_URL = "custom_endpoint_url"

        // Default endpoint for community telemetry; easily overridden via preferences or UI
        const val DEFAULT_ENDPOINT = "https://telemetry.tennis-tracker.app/api/heartbeat"

        // Rate-limit: send at most one heartbeat every 30 minutes to conserve battery & bandwidth
        const val HEARTBEAT_THROTTLE_MS = 30 * 60 * 1000L // 30 minutes

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val lock = Any()

        // Transient session ID regenerated on every cold start
        val processSessionId: String by lazy { UUID.randomUUID().toString() }

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        @Volatile
        private var instance: AnonymousTelemetryService? = null

        fun getInstance(context: Context): AnonymousTelemetryService {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val repo = try {
                        SettingsRepositoryImpl.getInstance(context.applicationContext)
                    } catch (_: Throwable) {
                        null
                    }
                    AnonymousTelemetryService(
                        context = context.applicationContext,
                        settingsRepository = repo
                    ).also { instance = it }
                }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance?.let {
                    it.settingsObserverJob?.cancel()
                }
                instance = null
            }
        }
    }

    private var settingsObserverJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        settingsRepository?.let { attachSettingsRepository(it) }
    }

    fun attachSettingsRepository(repository: SettingsRepository) {
        this.settingsRepository = repository
        val currentSettings = repository.getSettings()
        syncWithSettings(currentSettings.telemetryEnabled, currentSettings.telemetryEndpoint)

        settingsObserverJob?.cancel()
        settingsObserverJob = serviceScope.launch {
            repository.settings.collect { settings ->
                syncWithSettings(settings.telemetryEnabled, settings.telemetryEndpoint)
            }
        }
    }

    fun getSettingsRepository(): SettingsRepository? = settingsRepository

    private fun syncWithSettings(enabled: Boolean, endpoint: String) {
        synchronized(lock) {
            val currentRepo = settingsRepository?.getSettings()
            val currentRepoEnabled = currentRepo?.telemetryEnabled ?: enabled
            if (enabled != currentRepoEnabled) {
                return
            }

            val prefsEnabled = prefs.getBoolean(KEY_TELEMETRY_ENABLED, true)
            if (prefsEnabled != currentRepoEnabled || (!currentRepoEnabled && prefs.contains(KEY_ANONYMOUS_ID))) {
                setTelemetryEnabledInternal(currentRepoEnabled)
            }
            val cleanEndpoint = endpoint.trim()
            val currentEndpoint = prefs.getString(KEY_ENDPOINT_URL, "") ?: ""
            if (cleanEndpoint.isNotEmpty() && cleanEndpoint != currentEndpoint) {
                try {
                    setEndpointUrlInternal(cleanEndpoint)
                } catch (_: Throwable) {}
            } else if (cleanEndpoint.isEmpty() && currentEndpoint.isNotEmpty()) {
                try {
                    setEndpointUrlInternal("")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun setTelemetryEnabledInternal(enabled: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_TELEMETRY_ENABLED, enabled)
        if (!enabled) {
            // Privacy compliance (GDPR Right to Erasure): Purge stored anonymous UUID upon opt-out
            editor.remove(KEY_ANONYMOUS_ID)
            editor.remove(KEY_LAST_HEARTBEAT_TIME)
        }
        editor.apply()
        Log.i(TAG, "Anonymous telemetry enabled=$enabled")
    }

    private fun setEndpointUrlInternal(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isNotEmpty()) {
            require(cleanUrl.startsWith("https://", ignoreCase = true)) {
                "Only secure HTTPS endpoints are permitted"
            }
            prefs.edit().putString(KEY_ENDPOINT_URL, cleanUrl).apply()
        } else {
            prefs.edit().remove(KEY_ENDPOINT_URL).apply()
        }
    }


    override fun isTelemetryEnabled(): Boolean {
        synchronized(lock) {
            return prefs.getBoolean(KEY_TELEMETRY_ENABLED, true)
        }
    }

    override fun setTelemetryEnabled(enabled: Boolean) {
        synchronized(lock) {
            setTelemetryEnabledInternal(enabled)
            settingsRepository?.let { repo ->
                if (repo.getSettings().telemetryEnabled != enabled) {
                    try {
                        kotlinx.coroutines.runBlocking {
                            repo.updateTelemetryEnabled(enabled)
                        }
                    } catch (_: Throwable) {
                        serviceScope.launch {
                            try {
                                repo.updateTelemetryEnabled(enabled)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
    }

    override fun getAnonymousId(): String {
        synchronized(lock) {
            var id = prefs.getString(KEY_ANONYMOUS_ID, null)
            if (id.isNullOrBlank()) {
                val newId = UUID.randomUUID().toString()
                if (isTelemetryEnabled()) {
                    prefs.edit().putString(KEY_ANONYMOUS_ID, newId).apply()
                }
                return newId
            }
            return id
        }
    }

    override fun getEndpointUrl(): String {
        settingsRepository?.let {
            val endpoint = it.getSettings().telemetryEndpoint
            if (endpoint.isNotBlank()) return endpoint
        }
        return prefs.getString(KEY_ENDPOINT_URL, defaultEndpoint) ?: defaultEndpoint
    }

    override fun setEndpointUrl(url: String) {
        setEndpointUrlInternal(url)
        settingsRepository?.let { repo ->
            if (repo.getSettings().telemetryEndpoint != url) {
                serviceScope.launch {
                    try {
                        repo.updateTelemetryEndpoint(url)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Builds the anonymous event payload.
     */
    fun createStartupEvent(): TelemetryEvent {
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Throwable) {
            null
        }

        val versionName = packageInfo?.versionName ?: "1.0.17"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toInt() ?: 18
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode ?: 18
        }

        return TelemetryEvent(
            eventType = "app_launch",
            anonymousId = getAnonymousId(),
            sessionId = processSessionId,
            appVersion = versionName,
            versionCode = versionCode,
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            deviceManufacturer = Build.MANUFACTURER ?: "generic",
            deviceModel = Build.MODEL ?: "generic",
            locale = Locale.getDefault().toLanguageTag(),
            timestamp = Instant.now().toString(),
            metadata = mapOf(
                "sdkInt" to Build.VERSION.SDK_INT.toString()
            )
        )
    }

    override suspend fun sendStartupHeartbeat(force: Boolean): Boolean {
        if (!isTelemetryEnabled()) {
            Log.d(TAG, "Telemetry skipped: user opted out")
            return false
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT_TIME, 0L)
            val elapsed = now - lastHeartbeat
            // Handle clock skew (negative elapsed) and enforce throttle window
            if (!force && elapsed in 0 until HEARTBEAT_THROTTLE_MS) {
                Log.d(TAG, "Telemetry throttled (last sent ${elapsed / 1000}s ago)")
                return false
            }
            // Optimistically update throttle timestamp to prevent TOCTOU race storms
            prefs.edit().putLong(KEY_LAST_HEARTBEAT_TIME, now).apply()
        }

        val event = createStartupEvent()
        val endpoint = getEndpointUrl()

        return withContext(Dispatchers.IO) {
            try {
                val jsonPayload = event.toJson().toString()
                val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("User-Agent", "TennisTracker/${event.appVersion} (Android ${event.osVersion})")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Anonymous heartbeat transmitted successfully (HTTP ${response.code})")
                        true
                    } else {
                        Log.w(TAG, "Telemetry endpoint returned HTTP ${response.code}")
                        // Rollback throttle timestamp to allow retry earlier (e.g. 5 minutes from now)
                        synchronized(lock) {
                            prefs.edit().putLong(KEY_LAST_HEARTBEAT_TIME, now - (HEARTBEAT_THROTTLE_MS - 5 * 60 * 1000L)).apply()
                        }
                        false
                    }
                }
            } catch (e: Exception) {
                // Telemetry transmission is purely non-critical; never crash or disturb user
                Log.d(TAG, "Telemetry transmission silently bypassed: ${e.message}")
                synchronized(lock) {
                    prefs.edit().putLong(KEY_LAST_HEARTBEAT_TIME, now - (HEARTBEAT_THROTTLE_MS - 5 * 60 * 1000L)).apply()
                }
                false
            }
        }
    }
}
