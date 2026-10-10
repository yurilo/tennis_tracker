package com.usopen.tracker.domain.model

data class DeepLink(
    val appUri: String,          // e.g. sportscenter://x-callback-url/showEvent?eventId=...
    val webFallbackUrl: String,  // e.g. https://www.espn.com/watch/... or https://www.espn.com/tennis/...
    val isLiveBroadcast: Boolean = false,
    val buttonLabel: String = "Watch on ESPN"
)
