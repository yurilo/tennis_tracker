package com.tennis.tracker.domain.telemetry

/**
 * Contract for dispatching anonymous telemetry and lifecycle heartbeats.
 */
interface TelemetryService {
    /**
     * Dispatches an anonymous startup heartbeat.
     * Throttled to avoid unnecessary network calls on rapid app reopens.
     */
    suspend fun sendStartupHeartbeat(force: Boolean = false): Boolean

    /**
     * Checks if telemetry is enabled by user preference.
     */
    fun isTelemetryEnabled(): Boolean

    /**
     * Sets whether telemetry is enabled (privacy opt-in / opt-out).
     */
    fun setTelemetryEnabled(enabled: Boolean)

    /**
     * Returns the persistent anonymous client ID (zero PII, random UUID).
     */
    fun getAnonymousId(): String

    /**
     * Returns the telemetry endpoint URL currently targeted.
     */
    fun getEndpointUrl(): String

    /**
     * Sets a custom telemetry endpoint URL (e.g. user-hosted dashboard or webhook).
     */
    fun setEndpointUrl(url: String)
}
