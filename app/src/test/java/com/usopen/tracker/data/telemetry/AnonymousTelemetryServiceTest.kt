package com.usopen.tracker.data.telemetry

import android.content.Context
import android.content.SharedPreferences
import com.usopen.tracker.domain.telemetry.TelemetryEvent
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AnonymousTelemetryServiceTest {

    private lateinit var mockPrefs: FakeSharedPreferences
    private lateinit var mockContext: FakeContext

    @Before
    fun setUp() {
        mockPrefs = FakeSharedPreferences()
        mockContext = FakeContext(mockPrefs)
        AnonymousTelemetryService.resetInstanceForTesting()
    }

    @Test
    fun testTelemetryEvent_jsonSerialization() {
        val event = TelemetryEvent(
            eventType = "app_launch",
            anonymousId = "anon-12345",
            sessionId = "session-abc",
            appVersion = "1.0.17",
            versionCode = 18,
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
        assertEquals("1.0.17", json.getString("appVersion"))
        assertEquals(18, json.getInt("versionCode"))
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
    fun testTelemetryService_optOutPurgesStoredUuidAndThrottle() = runBlocking {
        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())
        assertTrue(service.isTelemetryEnabled())

        val firstId = service.getAnonymousId()
        mockPrefs.putLong("last_heartbeat_ms", System.currentTimeMillis())

        // User opts out
        service.setTelemetryEnabled(false)
        assertFalse(service.isTelemetryEnabled())

        // Verify UUID and last heartbeat timestamp are purged from disk (GDPR erasure)
        assertNull(mockPrefs.getString("anonymous_device_uuid", null))
        assertEquals(0L, mockPrefs.getLong("last_heartbeat_ms", 0L))

        // When opted out, sendStartupHeartbeat returns false immediately
        assertFalse(service.sendStartupHeartbeat(force = true))

        // Re-enabling generates a completely fresh, decoupled anonymous ID
        service.setTelemetryEnabled(true)
        val secondId = service.getAnonymousId()
        assertNotNull(secondId)
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun testTelemetryService_endpointCustomizationAndHttpsEnforcement() {
        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())
        assertEquals(AnonymousTelemetryService.DEFAULT_ENDPOINT, service.getEndpointUrl())

        // Secure HTTPS endpoint succeeds
        service.setEndpointUrl("https://my-custom-telemetry.example.com/api/heartbeat")
        assertEquals("https://my-custom-telemetry.example.com/api/heartbeat", service.getEndpointUrl())

        // Reset to default
        service.setEndpointUrl("")
        assertEquals(AnonymousTelemetryService.DEFAULT_ENDPOINT, service.getEndpointUrl())

        // Cleartext HTTP endpoint is rejected with IllegalArgumentException
        try {
            service.setEndpointUrl("http://insecure.example.com/api/heartbeat")
            fail("Expected IllegalArgumentException for non-HTTPS URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("HTTPS") == true)
        }
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
    fun testTelemetryService_clockRollbackAllowsTransmission() = runBlocking {
        // Simulate clock skew: last heartbeat is in the "future" due to system clock rollback
        val now = System.currentTimeMillis()
        mockPrefs.putLong("last_heartbeat_ms", now + 60000L)

        val successClient = createSuccessfulOkHttpClient()
        val service = AnonymousTelemetryService(mockContext, client = successClient)

        // Negative elapsed time (now - lastHeartbeat < 0) should not permanently lock out heartbeat
        val result = service.sendStartupHeartbeat(force = false)
        assertTrue(result)
    }

    @Test
    fun testTelemetryService_successfulHttp200Transmission() = runBlocking {
        val successClient = createSuccessfulOkHttpClient()
        val service = AnonymousTelemetryService(mockContext, client = successClient)

        val result = service.sendStartupHeartbeat(force = true)
        assertTrue(result)
        assertTrue(mockPrefs.getLong("last_heartbeat_ms", 0L) > 0L)
    }

    @Test
    fun testTelemetryService_http500ResponseRollsBackThrottle() = runBlocking {
        val failingStatusClient = createFailingStatusOkHttpClient(500)
        val service = AnonymousTelemetryService(mockContext, client = failingStatusClient)

        val beforeCall = System.currentTimeMillis()
        val result = service.sendStartupHeartbeat(force = true)
        assertFalse(result)

        // After failure, throttle should be rolled back to allow retry earlier (~5 min remaining)
        val recordedTime = mockPrefs.getLong("last_heartbeat_ms", 0L)
        val elapsed = beforeCall - recordedTime
        assertTrue("Expected rollback timestamp, elapsed=$elapsed", elapsed >= (AnonymousTelemetryService.HEARTBEAT_THROTTLE_MS - 6 * 60 * 1000L))
    }

    @Test
    fun testTelemetryService_concurrentAnonymousIdThreadSafety() {
        val service = AnonymousTelemetryService(mockContext, client = OkHttpClient())
        val executor = Executors.newFixedThreadPool(8)
        val results = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        for (i in 0 until 50) {
            executor.submit {
                results.add(service.getAnonymousId())
            }
        }
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        // All concurrent threads must receive the EXACT same UUID
        assertEquals(1, results.size)
    }

    @Test
    fun testTelemetryService_safeExecutionUnderNetworkFailure() = runBlocking {
        val failingClient = createFailingOkHttpClient()
        val service = AnonymousTelemetryService(mockContext, client = failingClient)

        // When network fails, should catch exception and return false gracefully without throwing
        val result = service.sendStartupHeartbeat(force = true)
        assertFalse(result)
    }

    private fun createSuccessfulOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"ok"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun createFailingStatusOkHttpClient(statusCode: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("Internal Server Error")
                    .body("""{"error":"fail"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun createFailingOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Simulated network connection failure")
            }
            .build()
    }
}

// Thread-safe in-memory SharedPreferences for unit testing without Android SDK dependencies
class FakeSharedPreferences : SharedPreferences {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = key != null && data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    fun putLong(key: String, value: Long) { data[key] = value }

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = value
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
            if (key != null) {
                removed.add(key)
                temp.remove(key)
            }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            prefs.data.clear()
            temp.clear()
            removed.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            for (key in removed) {
                prefs.data.remove(key)
            }
            for ((key, value) in temp) {
                if (value != null) {
                    prefs.data[key] = value
                } else {
                    prefs.data.remove(key)
                }
            }
        }
    }
}

// Minimal mock Context providing fake SharedPreferences
class FakeContext(private val prefs: FakeSharedPreferences) : android.content.ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "com.usopen.tracker"
}
