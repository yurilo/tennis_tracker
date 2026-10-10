package com.usopen.tracker.data.remote

import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import java.text.Normalizer
import java.util.Locale

object EspnWatchMatcher {

    /**
     * Finds the best matching ESPN Watch stream for a given match.
     */
    fun findBestStream(match: Match, streams: List<EspnWatchStream>): EspnWatchStream? {
        if (streams.isEmpty()) return null

        val p1Names = extractSearchNames(match.player1.name)
        val p2Names = extractSearchNames(match.player2.name)

        // Tier 1: Exact / surname match on both competitors (or doubles teams)
        if (p1Names.isNotEmpty() && p2Names.isNotEmpty()) {
            val directMatch = streams.firstOrNull { stream ->
                val cleanTitle = (stream.name + " " + stream.shortName)
                    .replace(Regex("""\(\d+\)"""), " ")
                val streamTitleNorm = normalize(cleanTitle)

                val p1Matches = p1Names.any { it.length >= 3 && streamTitleNorm.contains(it) }
                val p2Matches = p2Names.any { it.length >= 3 && streamTitleNorm.contains(it) }
                p1Matches && p2Matches
            }
            if (directMatch != null) {
                return directMatch
            }
        }

        // Tier 2: Match by Court / Stadium Camera Stream
        val courtName = match.displayCourt
        if (!courtName.isNullOrBlank() && !courtName.equals("New York, USA", ignoreCase = true) && !courtName.equals("TBD", ignoreCase = true)) {
            val courtNorm = normalize(courtName)
            val courtMatch = streams.firstOrNull { stream ->
                val streamCourt = stream.court
                if (streamCourt != null && normalize(streamCourt) == courtNorm) {
                    return@firstOrNull true
                }
                val streamNameNorm = normalize(stream.name)
                streamNameNorm.contains(courtNorm) || (courtNorm.length >= 5 && streamNameNorm == courtNorm)
            }
            if (courtMatch != null) {
                return courtMatch
            }
        }

        // Tier 3: General Tournament Live Broadcast Fallback (for LIVE matches)
        if (match.state == MatchState.LIVE) {
            val mainCoverage = streams.firstOrNull { stream ->
                val name = stream.name
                (name.contains("Coverage", ignoreCase = true) || name.contains("Primetime", ignoreCase = true)) &&
                        (stream.subtitle.contains(match.tournamentName.ifEmpty { "Tennis" }, ignoreCase = true) ||
                         stream.subtitle.contains("Tennis", ignoreCase = true) ||
                         stream.subtitle.contains("Open", ignoreCase = true))
            }
            if (mainCoverage != null) {
                return mainCoverage
            }
        }

        return null
    }

    fun extractSearchNames(fullName: String): List<String> {
        val names = mutableListOf<String>()
        // Handle doubles team format: "PlayerA / PlayerB" or "PlayerA/PlayerB"
        val players = fullName.split("/")
        val skipWords = setOf("van", "von", "der", "del", "den", "de", "di", "san", "da", "dos", "das")

        for (p in players) {
            val trimmed = p.trim()
            if (trimmed.isEmpty()) continue
            // Split tokens by space and hyphens
            val tokens = trimmed.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) continue

            // Primary surname (last token)
            val lastToken = normalize(tokens.last())
            if (lastToken.length >= 3) {
                names.add(lastToken)
            }

            // Secondary surname tokens for composite names (e.g. "Carballes Baena", "Auger-Aliassime", "Van De Zandschulp")
            if (tokens.size >= 2) {
                val secondToLast = normalize(tokens[tokens.size - 2])
                if (secondToLast.length >= 3 && !skipWords.contains(secondToLast)) {
                    names.add(secondToLast)
                }
                val joined = normalize(tokens[tokens.size - 2] + tokens.last())
                if (joined.length >= 5) {
                    names.add(joined)
                }
            }
        }
        return names.distinct()
    }

    fun normalize(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .lowercase(Locale.ROOT)
    }
}
