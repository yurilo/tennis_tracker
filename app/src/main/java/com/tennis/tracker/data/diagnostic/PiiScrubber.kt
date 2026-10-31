package com.tennis.tracker.data.diagnostic

/**
 * High-performance, zero-PII sanitization engine.
 * Strips API keys, GitHub tokens, passwords, bearer tokens, emails, IP addresses,
 * and filesystem paths while preserving version identifiers, tournament labels,
 * scores, and ISO timestamps.
 */
object PiiScrubber {

    private val GH_PAT_REGEX = Regex(
        """\b(gh[pousr]_[a-zA-Z0-9]{20,80}|github_pat_[a-zA-Z0-9_]{30,120})\b"""
    )

    private val BEARER_REGEX = Regex(
        """\bBearer\s+[a-zA-Z0-9\-._~+/]+=*""",
        RegexOption.IGNORE_CASE
    )

    private val URL_CRED_REGEX = Regex(
        """(https?://[^:\s/@]+:)([^@\s/]+)(@)""",
        RegexOption.IGNORE_CASE
    )

    private val PARAM_SECRET_REGEX = Regex(
        """\b(api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password)\s*=\s*([^\s&"']+)""",
        RegexOption.IGNORE_CASE
    )

    private val KEY_VALUE_SECRET_REGEX = Regex(
        """(?:"|')?\b(api[_-]?key|secret|token|password|auth|authorization|private[_-]?key)(?:"|')?\s*:\s*["']?([a-zA-Z0-9_\-\.+=/]{8,})["']?""",
        RegexOption.IGNORE_CASE
    )

    private val EMAIL_REGEX = Regex(
        """\b[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+\b"""
    )

    private val IPV4_REGEX = Regex(
        """\b(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\b"""
    )

    private val IPV6_REGEX = Regex(
        """\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\b|""" +
        """\b[0-9a-fA-F]{1,4}::(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}\b|""" +
        """\b(?:[0-9a-fA-F]{1,4}:){1,6}:(?:[0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{1,4}\b|""" +
        """(?<!\S)::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{1,4}\b|""" +
        """\b(?:[0-9a-fA-F]{1,4}:){1,7}:\b|""" +
        """\b(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}\b""",
        RegexOption.IGNORE_CASE
    )

    private val PATH_REGEX = Regex(
        """(?:/(?:storage|data(?:/user(?:/\d+)?|/data)?|home(?:/[^/\s]+)?|Users(?:/[^/\s]+)?|sdcard|private|var|tmp)/[a-zA-Z0-9_.\-/]+(?<![.,:;?!]))"""
    )

    const val REDACTED_GH_TOKEN = "[REDACTED_GH_TOKEN]"
    const val REDACTED_BEARER_TOKEN = "Bearer [REDACTED_TOKEN]"
    const val REDACTED_PASSWORD = "[REDACTED_PASSWORD]"
    const val REDACTED_SECRET = "[REDACTED_SECRET]"
    const val REDACTED_EMAIL = "[REDACTED_EMAIL]"
    const val REDACTED_IP = "[REDACTED_IP]"
    const val REDACTED_PATH = "[REDACTED_PATH]"

    /**
     * Sanitizes sensitive information from input string.
     */
    fun scrub(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        var result = input

        // 1. Scrub credentials embedded in URLs
        result = URL_CRED_REGEX.replace(result) { mr ->
            "${mr.groupValues[1]}$REDACTED_PASSWORD${mr.groupValues[3]}"
        }

        // 2. Scrub GitHub tokens FIRST before generic token/secret key-values
        result = GH_PAT_REGEX.replace(result, REDACTED_GH_TOKEN)

        // 3. Scrub Bearer tokens
        result = BEARER_REGEX.replace(result, REDACTED_BEARER_TOKEN)

        // 4. Scrub sensitive query parameter keys and key-values
        result = PARAM_SECRET_REGEX.replace(result) { mr ->
            "${mr.groupValues[1]}=$REDACTED_SECRET"
        }
        result = KEY_VALUE_SECRET_REGEX.replace(result) { mr ->
            val hasQuotes = mr.value.startsWith("\"") || mr.value.startsWith("'")
            val quote = if (hasQuotes) mr.value.first().toString() else ""
            "$quote${mr.groupValues[1]}$quote: $REDACTED_SECRET"
        }

        // 5. Scrub Email addresses
        result = EMAIL_REGEX.replace(result, REDACTED_EMAIL)

        // 6. Scrub IPv4 addresses
        result = IPV4_REGEX.replace(result, REDACTED_IP)

        // 7. Scrub IPv6 addresses
        result = IPV6_REGEX.replace(result, REDACTED_IP)

        // 8. Scrub Local filesystem paths
        result = PATH_REGEX.replace(result, REDACTED_PATH)

        return result
    }

    /**
     * Sanitizes all string values within a map.
     */
    fun scrub(map: Map<String, String>): Map<String, String> {
        return map.mapValues { (_, value) -> scrub(value) }
    }

    /**
     * Sanitizes all string items within a list.
     */
    fun scrub(list: List<String>): List<String> {
        return list.map { scrub(it) }
    }
}
