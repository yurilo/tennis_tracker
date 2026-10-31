package com.tennis.tracker.data.diagnostic

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Stress Test Suite for Milestone 1.
 * Tests PiiScrubber and DiagnosticBreadcrumbBuffer against malicious, nested,
 * pathological, ReDoS, boundary, and high-concurrency scenarios.
 */
class ChallengerM1StressTest {

    @Before
    @After
    fun resetBuffer() {
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
    }

    // =========================================================================
    // 1. PII SCRUBBER: CREDENTIAL LEAK & MALICIOUS INPUT CHALLENGES
    // =========================================================================

    @Test
    fun testScrubGitHubTokens_boundariesAndFormats() {
        // Exact 20-char PAT
        val pat20 = "ghp_" + "a".repeat(20)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(pat20))

        // Exact 80-char PAT
        val pat80 = "ghp_" + "b".repeat(80)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(pat80))

        // Fine-grained PAT min 30 chars
        val fg30 = "github_pat_" + "c".repeat(30)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(fg30))

        // Fine-grained PAT max 120 chars
        val fg120 = "github_pat_" + "d".repeat(120)
        assertEquals("[REDACTED_GH_TOKEN]", PiiScrubber.scrub(fg120))

        // Nested token inside string
        val nested = "Header: Authorization=ghp_1234567890abcdef1234567890abcdef; other"
        val scrubbed = PiiScrubber.scrub(nested)
        assertFalse(scrubbed.contains("ghp_1234567890"))
        assertTrue(scrubbed.contains("[REDACTED_GH_TOKEN]"))
    }

    @Test
    fun testScrubBearerTokens_variations() {
        val bearerJwt = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig=="
        val res1 = PiiScrubber.scrub(bearerJwt)
        assertEquals("Bearer [REDACTED_TOKEN]", res1)

        val lowercaseBearer = "bearer ya29.a0AfH6SMBxyz123456789"
        val res2 = PiiScrubber.scrub(lowercaseBearer)
        assertEquals("Bearer [REDACTED_TOKEN]", res2)

        val bearerWithTabsAndSpaces = "Bearer   token-with.dash_underscore~tilde+plus/slash=="
        val res3 = PiiScrubber.scrub(bearerWithTabsAndSpaces)
        assertEquals("Bearer [REDACTED_TOKEN]", res3)
    }

    @Test
    fun testScrubUrlCredentials_adversarial() {
        val standard = "https://user:password123@api.tennis.com/v1"
        assertEquals("https://user:[REDACTED_PASSWORD]@api.tennis.com/v1", PiiScrubber.scrub(standard))

        val complexPass = "http://admin:p%23ss%24w0rd!%2A@10.0.0.1:8080/data"
        val scrubbedComplex = PiiScrubber.scrub(complexPass)
        assertTrue(scrubbedComplex.contains("[REDACTED_PASSWORD]@"))
        assertFalse(scrubbedComplex.contains("p%23ss%24w0rd"))
    }

    @Test
    fun testScrubQueryParameterTokens() {
        val url = "https://api.tennis-tracker.app/sync?api_key=secretKey123&access_token=token456&auth_token=auth789&secret=superSecret&password=secretPasswordVal"
        val scrubbed = PiiScrubber.scrub(url)

        assertFalse(scrubbed.contains("secretKey123"))
        assertFalse(scrubbed.contains("token456"))
        assertFalse(scrubbed.contains("auth789"))
        assertFalse(scrubbed.contains("superSecret"))
        assertFalse(scrubbed.contains("secretPasswordVal"))
        assertTrue(scrubbed.contains("api_key=[REDACTED_SECRET]"))
        assertTrue(scrubbed.contains("access_token=[REDACTED_SECRET]"))
        assertTrue(scrubbed.contains("auth_token=[REDACTED_SECRET]"))
        assertTrue(scrubbed.contains("secret=[REDACTED_SECRET]"))
        assertTrue(scrubbed.contains("password=[REDACTED_SECRET]"))
    }

    @Test
    fun testScrubJsonFormattedCredentials() {
        val jsonPayload = """{"api_key": "secret_api_key_12345", "password": "super_secret_password_123", "token": "session_token_abc12345"}"""
        val scrubbed = PiiScrubber.scrub(jsonPayload)
        assertFalse("API key leaked in JSON: $scrubbed", scrubbed.contains("secret_api_key_12345"))
        assertFalse("Password leaked in JSON: $scrubbed", scrubbed.contains("super_secret_password_123"))
        assertFalse("Token leaked in JSON: $scrubbed", scrubbed.contains("session_token_abc12345"))
    }

    @Test
    fun testScrubFilesystemPaths_comprehensive() {
        val paths = listOf(
            "/storage/emulated/0/Download/secret.txt" to "[REDACTED_PATH]",
            "/storage/emulated/10/agy/test.log" to "[REDACTED_PATH]",
            "/data/user/0/com.tennis.tracker/shared_prefs/creds.xml" to "[REDACTED_PATH]",
            "/data/data/com.tennis.tracker/databases/tennis.db" to "[REDACTED_PATH]",
            "/home/developer/.ssh/id_ed25519" to "[REDACTED_PATH]",
            "/Users/admin/Library/ApplicationSupport/TennisTracker" to "[REDACTED_PATH]",
            "/sdcard/DCIM/Camera/match.mp4" to "[REDACTED_PATH]",
            "/tmp/trace-dump.prof" to "[REDACTED_PATH]",
            "/var/log/nginx/access.log" to "[REDACTED_PATH]",
            "/private/var/mobile/Containers/Data" to "[REDACTED_PATH]"
        )

        for ((input, expected) in paths) {
            val res = PiiScrubber.scrub("Log at $input occurred")
            assertEquals("Log at $expected occurred", res)
        }
    }

    @Test
    fun testPathWithSpaces_boundaryObservation() {
        val input = "Path: /Users/admin/Library/Application Support/TennisTracker"
        val scrubbed = PiiScrubber.scrub(input)
        // Empirical observation: PATH_REGEX does not match across spaces, leaving subpaths after space unredacted
        assertEquals("Path: [REDACTED_PATH] Support/TennisTracker", scrubbed)
    }

    @Test
    fun testScrubIPv6Addresses_edgeCases() {
        // Full IPv6
        val full = "Error connecting to 2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        assertEquals("Error connecting to [REDACTED_IP]", PiiScrubber.scrub(full))

        // Compressed loopback
        val loopback = "Connecting ::1 port 80"
        assertEquals("Connecting [REDACTED_IP] port 80", PiiScrubber.scrub(loopback))

        // Single group after double-colon
        val singleGroup = "fe80::1"
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub(singleGroup))
    }

    @Test
    fun testScrubIPv6Addresses_compressedMultiGroupVulnerability() {
        val compressed = "fe80::1ff:fe23:4567:890a"
        val scrubbed = PiiScrubber.scrub(compressed)
        assertEquals("[REDACTED_IP]", scrubbed)
        val leakedTrailing = scrubbed.contains(":fe23:4567:890a")
        assertFalse("Trailing IPv6 address must not leak: $scrubbed", leakedTrailing)
    }

    @Test
    fun testScrubIPv4Addresses_edgeCases() {
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub("127.0.0.1"))
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub("10.254.12.1"))
        assertEquals("[REDACTED_IP]", PiiScrubber.scrub("255.255.255.255"))

        // False positive prevention: invalid IP ranges should NOT be redacted as IP
        val invalidIp = "999.999.999.999"
        assertEquals(invalidIp, PiiScrubber.scrub(invalidIp))
    }

    // =========================================================================
    // 2. PRESERVATION OF LEGITIMATE TENNIS METADATA
    // =========================================================================

    @Test
    fun testPreserveLegitimateTennisMetadata() {
        val tennisTexts = listOf(
            // Scores with dashes and colons
            "Alcaraz def. Djokovic 6-4, 3-6, 7-6(5), 6-2",
            "Swiatek won 6-0, 6-1 against Sabalenka in 1h:15m",
            "Score tiebreak: 7-6(7-4)",
            "Game score: 40-30, Advantage Server",

            // Player names and diacritics
            "Carlos Alcaraz, Novak Đoković, Jannik Sinner, Daniil Medvedev",
            "Iga Świątek, Aryna Sabalenka, Coco Gauff, Zheng Qinwen",
            "Jiří Lehečka vs Tomáš Macháč on Court 17",

            // Tournaments and venues
            "US Open 2026 - Arthur Ashe Stadium (Grand Slam, Flushing Meadows)",
            "Roland Garros - Court Philippe-Chatrier",
            "Wimbledon - Centre Court",
            "BNP Paribas Open - Indian Wells Tennis Garden",

            // App version and timestamps
            "Tennis Tracker v1.2.3 (versionCode 42) build at 2026-10-31T14:00:00Z",

            // Stats
            "Aces: 14, Double Faults: 2, 1st Serve: 72%, Break Points: 5/8, Net Points: 18/22",

            // Normal words that could collide with secret keys
            "Highlight video available on official YouTube channel",
            "Authorized spectator attendance: 23,771",
            "Tournament secretarial staff announced order of play",
            "Token of appreciation presented to tournament director"
        )

        for (text in tennisTexts) {
            val scrubbed = PiiScrubber.scrub(text)
            // Ensure no false redactions occurred on legitimate tennis terms
            assertFalse("False redaction of token in '$text': $scrubbed", scrubbed.contains("[REDACTED_GH_TOKEN]"))
            assertFalse("False redaction of secret in '$text': $scrubbed", scrubbed.contains("[REDACTED_SECRET]"))
            assertFalse("False redaction of password in '$text': $scrubbed", scrubbed.contains("[REDACTED_PASSWORD]"))
            assertFalse("False redaction of email in '$text': $scrubbed", scrubbed.contains("[REDACTED_EMAIL]"))
        }
    }

    // =========================================================================
    // 3. PATHOLOGICAL INPUTS & REDOS / RESILIENCE
    // =========================================================================

    @Test(timeout = 5000)
    fun testReDosResilience_pathologicalStrings() {
        // Pathological colon patterns
        val colonStorm = "a:".repeat(5000)
        val t0 = System.currentTimeMillis()
        val res1 = PiiScrubber.scrub(colonStorm)
        val elapsed1 = System.currentTimeMillis() - t0
        assertTrue("Colon storm must complete under 1000ms (took ${elapsed1}ms)", elapsed1 < 1000)
        assertNotNull(res1)

        // Pathological slash patterns
        val slashStorm = "/storage/data/home/tmp/var/private/Users/".repeat(500)
        val t1 = System.currentTimeMillis()
        val res2 = PiiScrubber.scrub(slashStorm)
        val elapsed2 = System.currentTimeMillis() - t1
        assertTrue("Slash storm must complete under 1000ms (took ${elapsed2}ms)", elapsed2 < 1000)
        assertNotNull(res2)

        // Massive 50KB string with mixed credentials
        val massive = StringBuilder().apply {
            repeat(1000) { i ->
                append("Match #$i: Alcaraz 6-4, ghp_${"1".repeat(36)} at /storage/emulated/10/file_$i.txt ")
                append("Bearer eyJhbGciOiJIUzI1NiJ9.sig user$i@tennis.org 192.168.1.${i % 250}\n")
            }
        }.toString()

        val t2 = System.currentTimeMillis()
        val scrubbedMassive = PiiScrubber.scrub(massive)
        val elapsed3 = System.currentTimeMillis() - t2
        assertTrue("50KB massive string scrubbing must complete under 2000ms (took ${elapsed3}ms)", elapsed3 < 2000)

        assertFalse(scrubbedMassive.contains("ghp_11111"))
        assertFalse(scrubbedMassive.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(scrubbedMassive.contains("/storage/emulated/10"))
        assertFalse(scrubbedMassive.contains("@tennis.org"))
    }

    // =========================================================================
    // 4. DIAGNOSTIC BREADCRUMB BUFFER: BOUNDS & EXTREME CONCURRENCY
    // =========================================================================

    @Test
    fun testBufferCapacity_neverExceeds25_sequential() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)

        for (i in 1..500) {
            buffer.log(
                tag = "Tag-$i",
                message = "Message-$i with ghp_${"1234567890abcdef1234567890abcdef"}",
                metadata = mapOf("metaKey" to "path=/data/user/0/file_$i")
            )
            assertTrue("Buffer size must never exceed 25 (current: ${buffer.size})", buffer.size <= 25)
            assertTrue("getRecentBreadcrumbs size must never exceed 25", buffer.getRecentBreadcrumbs().size <= 25)
            assertTrue("toJson length must never exceed 25", buffer.toJson().length() <= 25)
        }

        assertEquals(25, buffer.size)
        assertEquals(25, buffer.getRecentBreadcrumbs().size)
        assertEquals(25, buffer.toJson().length())

        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals("Tag-476", crumbs.first().tag)
        assertEquals("Tag-500", crumbs.last().tag)

        // Verify all 25 items are cleanly scrubbed
        for (c in crumbs) {
            assertFalse(c.message.contains("ghp_"))
            assertTrue(c.message.contains("[REDACTED_GH_TOKEN]"))
            assertFalse(c.metadata["metaKey"]?.contains("/data/user/0") ?: false)
        }
    }

    @Test
    fun testBufferHighConcurrency_noConcurrentModification_neverExceeds25() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        val writerThreads = 20
        val itemsPerWriter = 100
        val readerThreads = 10
        val totalOperations = writerThreads * itemsPerWriter

        val executor = Executors.newFixedThreadPool(writerThreads + readerThreads + 2)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(writerThreads + readerThreads + 2)

        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val observedSizesExceeding25 = AtomicInteger(0)
        val keepReading = AtomicBoolean(true)

        // 20 Writer Threads
        for (w in 0 until writerThreads) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until itemsPerWriter) {
                        buffer.log(
                            tag = "Writer-$w",
                            message = "Event-$i: user$w-$i@tennis.org token=ghp_${"a".repeat(30)}",
                            metadata = mapOf("id" to "$w-$i", "file" to "/tmp/test_$w.log")
                        )
                        val s = buffer.size
                        if (s > 25) observedSizesExceeding25.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 10 Reader Threads concurrently calling getRecentBreadcrumbs(), toJson(), and size
        for (r in 0 until readerThreads) {
            executor.submit {
                try {
                    startLatch.await()
                    while (keepReading.get()) {
                        val crumbs = buffer.getRecentBreadcrumbs()
                        if (crumbs.size > 25) observedSizesExceeding25.incrementAndGet()

                        val json = buffer.toJson()
                        if (json.length() > 25) observedSizesExceeding25.incrementAndGet()

                        val s = buffer.size
                        if (s > 25) observedSizesExceeding25.incrementAndGet()

                        // Verify integrity of read elements
                        for (crumb in crumbs) {
                            assertNotNull(crumb.tag)
                            assertNotNull(crumb.message)
                            assertFalse(crumb.message.contains("@tennis.org"))
                            assertFalse(crumb.message.contains("ghp_"))
                        }
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 2 Intermittent Clear / Reset Threads to stress lock contention
        for (c in 0 until 2) {
            executor.submit {
                try {
                    startLatch.await()
                    while (keepReading.get()) {
                        Thread.sleep(15)
                        buffer.clear()
                    }
                } catch (t: Throwable) {
                    exceptions.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Trigger all threads simultaneously
        startLatch.countDown()

        // Wait for all writers to complete
        Thread.sleep(1500)
        keepReading.set(false)

        val completed = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent tasks must complete cleanly", completed)
        assertTrue("No exceptions (such as ConcurrentModificationException) must be thrown. Found: $exceptions", exceptions.isEmpty())
        assertEquals("Buffer size must never exceed 25 under high concurrency", 0, observedSizesExceeding25.get())
        assertTrue("Final buffer size must be <= 25", buffer.size <= 25)
    }

    @Test
    fun testExtremeContentionSingleton() {
        val threadCount = 16
        val operationsPerThread = 150
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val exceptions = Collections.synchronizedList(mutableListOf<Throwable>())

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    val instance = DiagnosticBreadcrumbBuffer.getInstance()
                    for (i in 0 until operationsPerThread) {
                        instance.log("Singleton-$t", "Message $i")
                        val list = instance.getRecentBreadcrumbs()
                        assertTrue(list.size <= 25)
                    }
                } catch (e: Throwable) {
                    exceptions.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("No exceptions in singleton stress test. Found: $exceptions", exceptions.isEmpty())
        val finalInstance = DiagnosticBreadcrumbBuffer.getInstance()
        assertEquals(25, finalInstance.size)
    }
}
