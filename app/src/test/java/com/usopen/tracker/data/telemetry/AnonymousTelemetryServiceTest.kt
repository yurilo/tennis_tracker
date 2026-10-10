package com.usopen.tracker.data.telemetry

import android.content.Context
import android.content.SharedPreferences
import com.usopen.tracker.domain.telemetry.TelemetryEvent
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AnonymousTelemetryServiceTest {

    private lateinit var mockPrefs: FakeSharedPreferences
    private lateinit var mockContext: FakeContext

    @Before
    fun setUp() {
        mockPrefs = FakeSharedPreferences()
        mockContext = FakeContext(mockPrefs)
    }

    @Test
    fun testTelemetryEvent_jsonSerialization() {
        val event = TelemetryEvent(
            eventType = "app_launch",
            anonymousId = "anon-12345",
            sessionId = "session-abc",
            appVersion = "1.0.16",
            versionCode = 17,
            osVersion = "14",
            deviceManufacturer = "Google",
            deviceModel = "Pixel",
            locale = "en-US",
            timestamp = "2026-10-10T23:00:00Z",
            metadata = mapOf("sdkInt" to "34")
        )

        val json = event.toJson()
        assertEquals("app_launch", json.getString("eventType"))
        assertEquals("anon-12345", json.getString("anonymousId"))
        assertEquals("session-abc", json.getString("sessionId"))
        assertEquals("1.0.16", json.getString("appVersion"))
        assertEquals(17, json.getInt("versionCode"))
        assertEquals("14", json.getString("osVersion"))
        assertEquals("Google", json.getString("deviceManufacturer"))
        assertEquals("Pixel", json.getString("deviceModel"))
        assertEquals("en-US", json.getString("locale"))
        assertEquals("2026-10-10T23:00:00Z", json.getString("timestamp"))
        assertEquals("34", json.getJSONObject("metadata").getString("sdkInt"))
    }

    @Test
    fun testTelemetryService_anonymousIdGenerationAndPersistence() {
        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())

        val id1 = service.getAnonymousId()
        assertNotNull(id1)
        assertTrue(id1.isNotEmpty())

        val id2 = service.getAnonymousId()
        assertEquals(id1, id2) // Should persist same ID across calls
    }

    @Test
    fun testTelemetryService_optOutDisablesTransmission() = runBlocking {
        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())
        assertTrue(service.isTelemetryEnabled())

        service.setTelemetryEnabled(false)
        assertFalse(service.isTelemetryEnabled())

        // When opted out, sendStartupHeartbeat returns false immediately without network call
        val result = service.sendStartupHeartbeat(force = true)
        assertFalse(result)
    }

    @Test
    fun testTelemetryService_endpointCustomization() {
        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())
        assertEquals(AnonymousTelemetryService.DEFAULT_ENDPOINT, service.getEndpointUrl())

        service.setEndpointUrl("https://my-custom-telemetry.example.com/api/heartbeat")
        assertEquals("https://my-custom-telemetry.example.com/api/heartbeat", service.getEndpointUrl())

        service.setEndpointUrl("") // resetting
        assertEquals(AnonymousTelemetryService.DEFAULT_ENDPOINT, service.getEndpointUrl())
    }

    @Test
    fun testTelemetryService_throttlingPreventsRapidHeartbeats() = runBlocking {
        // Record last heartbeat as 5 seconds ago
        mockPrefs.putLong("last_heartbeat_ms", System.currentTimeMillis() - 5000L)

        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())

        // Without force=true, heartbeat is throttled
        val result = service.sendStartupHeartbeat(force = false)
        assertFalse(result)
    }

    @Test
    fun testTelemetryService_safeExecutionUnderNetworkFailure() = runBlocking {
        val failingClient = createFailingOkHttpClient()
        val service = AnonymousTelemetryService(mockContext, client = failingClient)

        // When network fails, should catch exception and return false gracefully without throwing
        val result = service.sendStartupHeartbeat(force = true)
        assertFalse(result)
    }

    private fun createFailingOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Simulated network connection failure")
            }
            .build()
    }
}

// Minimal in-memory SharedPreferences for unit testing without Android SDK dependencies
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    fun putLong(key: String, value: Long) { data[key] = value }

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null && value != null) temp[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) prefs.data.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            prefs.data.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            prefs.data.putAll(temp)
        }
    }
}

// Minimal mock Context providing fake SharedPreferences
class FakeContext(private val prefs: FakeSharedPreferences) : android.content.ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.usopen.tracker"
}
