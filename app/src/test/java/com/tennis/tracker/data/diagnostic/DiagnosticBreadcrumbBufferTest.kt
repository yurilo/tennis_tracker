package com.tennis.tracker.data.diagnostic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticBreadcrumbBufferTest {

    @Before
    @After
    fun tearDown() {
        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
    }

    @Test
    fun testInitialState_bufferIsEmpty() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.size())
        assertTrue(buffer.getRecentBreadcrumbs().isEmpty())
    }

    @Test
    fun testLogSingleEvent() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        val beforeTime = System.currentTimeMillis()
        buffer.log(
            tag = "TestTag",
            message = "Test message",
            metadata = mapOf("key" to "value")
        )
        val afterTime = System.currentTimeMillis()

        assertEquals(1, buffer.size)
        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals(1, crumbs.size)
        val first = crumbs[0]
        assertEquals("TestTag", first.tag)
        assertEquals("Test message", first.message)
        assertEquals("value", first.metadata["key"])
        assertTrue(first.timestamp in beforeTime..afterTime)
    }

    @Test
    fun testFifoChronologicalOrdering() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        for (i in 1..5) {
            buffer.log(tag = "Tag", message = "Msg $i", timestamp = i * 1000L)
        }

        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals(5, crumbs.size)
        for (i in 0 until 5) {
            assertEquals("Msg ${i + 1}", crumbs[i].message)
            assertEquals((i + 1) * 1000L, crumbs[i].timestamp)
        }
    }

    @Test
    fun testCapacityEviction_dropsOldestAt25() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        for (i in 0 until 30) {
            buffer.log(tag = "Tag", message = "Event $i", timestamp = 1000L + i)
        }

        assertEquals(25, buffer.size)
        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals(25, crumbs.size)

        // Events 0..4 should have been evicted; remaining are 5..29
        assertEquals("Event 5", crumbs.first().message)
        assertEquals("Event 29", crumbs.last().message)

        for (i in 0 until 25) {
            assertEquals("Event ${i + 5}", crumbs[i].message)
        }
    }

    @Test
    fun testDefensiveCopy() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        buffer.log("Tag1", "Msg1")
        val snapshot = buffer.getRecentBreadcrumbs()

        buffer.log("Tag2", "Msg2")
        assertEquals(1, snapshot.size)
        assertEquals(2, buffer.size)
    }

    @Test
    fun testClear_resetsState() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        buffer.log("Tag1", "Msg1")
        buffer.log("Tag2", "Msg2")
        assertEquals(2, buffer.size)

        buffer.clear()
        assertEquals(0, buffer.size)
        assertTrue(buffer.getRecentBreadcrumbs().isEmpty())
    }

    @Test
    fun testProactivePiiScrubbingOnIngest() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        buffer.log(
            tag = "Network",
            message = "Sync with token ghp_1234567890abcdef1234567890abcdef failed for test@tennis.org",
            metadata = mapOf("path" to "/storage/emulated/10/app.log")
        )

        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals(1, crumbs.size)
        val crumb = crumbs[0]

        assertFalse(crumb.message.contains("ghp_"))
        assertTrue(crumb.message.contains("[REDACTED_GH_TOKEN]"))
        assertFalse(crumb.message.contains("test@tennis.org"))
        assertTrue(crumb.message.contains("[REDACTED_EMAIL]"))
        assertEquals("[REDACTED_PATH]", crumb.metadata["path"])
    }

    @Test
    fun testThreadSafety_concurrentWriters() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 25)
        val threadCount = 10
        val itemsPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        buffer.log("Thread-$t", "Item-$i")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(25, buffer.size)
        val crumbs = buffer.getRecentBreadcrumbs()
        assertEquals(25, crumbs.size)
    }

    @Test
    fun testJsonSerialization() {
        val buffer = DiagnosticBreadcrumbBuffer(capacity = 10)
        buffer.log("Tag1", "Message 1")
        buffer.log("Tag2", "Message 2")

        val jsonArray = buffer.toJson()
        assertEquals(2, jsonArray.length())
        assertEquals("Tag1", jsonArray.getJSONObject(0).getString("tag"))
        assertEquals("Message 2", jsonArray.getJSONObject(1).getString("message"))
    }

    @Test
    fun testSingletonLifecycleAndReset() {
        val instance1 = DiagnosticBreadcrumbBuffer.getInstance()
        val instance2 = DiagnosticBreadcrumbBuffer.getInstance()
        assertTrue(instance1 === instance2)

        instance1.log("Tag", "Msg")
        assertEquals(1, instance2.size)

        DiagnosticBreadcrumbBuffer.resetInstanceForTesting()
        val instance3 = DiagnosticBreadcrumbBuffer.getInstance()
        assertFalse(instance1 === instance3)
        assertEquals(0, instance3.size)
    }
}
