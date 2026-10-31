package com.tennis.tracker.data.diagnostic

import androidx.annotation.VisibleForTesting
import com.tennis.tracker.domain.model.bugreport.DiagnosticBreadcrumb
import org.json.JSONArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe, bounded circular ring buffer holding recent diagnostic events.
 * Capacity defaults to 25 items; older items are automatically evicted (FIFO).
 * Proactively scrubs all inputs on ingest using [PiiScrubber].
 */
class DiagnosticBreadcrumbBuffer(
    val capacity: Int = DEFAULT_CAPACITY
) {
    companion object {
        const val DEFAULT_CAPACITY = 25

        @Volatile
        private var instance: DiagnosticBreadcrumbBuffer? = null

        fun getInstance(): DiagnosticBreadcrumbBuffer {
            return instance ?: synchronized(this) {
                instance ?: DiagnosticBreadcrumbBuffer().also { instance = it }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }
    }

    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<DiagnosticBreadcrumb>(capacity)

    /**
     * Logs a new diagnostic breadcrumb. Automatically sanitizes tag, message, and metadata on ingest.
     */
    fun log(
        tag: String,
        message: String,
        metadata: Map<String, String> = emptyMap(),
        timestamp: Long = System.currentTimeMillis()
    ) {
        val cleanTag = PiiScrubber.scrub(tag)
        val cleanMessage = PiiScrubber.scrub(message)
        val cleanMetadata = PiiScrubber.scrub(metadata)

        val breadcrumb = DiagnosticBreadcrumb(
            timestamp = timestamp,
            tag = cleanTag,
            message = cleanMessage,
            metadata = cleanMetadata
        )

        lock.withLock {
            if (buffer.size >= capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(breadcrumb)
        }
    }

    /**
     * Returns an immutable copy of recent breadcrumbs in chronological order (oldest to newest).
     */
    fun getRecentBreadcrumbs(): List<DiagnosticBreadcrumb> {
        lock.withLock {
            return buffer.toList()
        }
    }

    /**
     * Clears all breadcrumbs from the buffer.
     */
    fun clear() {
        lock.withLock {
            buffer.clear()
        }
    }

    /**
     * Returns the current number of breadcrumbs in the buffer.
     */
    val size: Int
        get() = lock.withLock { buffer.size }

    /**
     * Functional size accessor.
     */
    fun size(): Int = size

    /**
     * Converts current breadcrumbs to a [JSONArray].
     */
    fun toJson(): JSONArray {
        val array = JSONArray()
        for (crumb in getRecentBreadcrumbs()) {
            array.put(crumb.toJson())
        }
        return array
    }
}
