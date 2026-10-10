package com.usopen.tracker.data.remote

import com.usopen.tracker.domain.model.Match
import com.usopen.tracker.domain.model.MatchState
import java.text.Normalizer
import java.util.Locale

object EspnWatchMatcher {

    private val SEED_PARENS_REGEX = Regex("""\(\d+\)""")
    private val TOKEN_SPLIT_REGEX = Regex("[\\s-]+")
    private val DIACRITICAL_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-zA-Z0-9]")

    /**
     * Pre-normalized stream data structure to eliminate redundant string allocations
     * and NFD normalizations during batch matching operations.
     */
    data class NormalizedStream(
        val stream: EspnWatchStream,
        val normalizedTitle: String,
        val normalizedCourt: String?,
        val normalizedName: String
    )

    /**
     * Pre-normalizes a list of ESPN Watch streams for fast matching across multiple matches.
     */
    fun preNormalize(streams: List<EspnWatchStream>): List<NormalizedStream> {
        return streams.map { stream ->
            val cleanTitle = (stream.name + " " + stream.shortName)
                .replace(SEED_PARENS_REGEX, " ")
            NormalizedStream(
                stream = stream,
                normalizedTitle = normalize(cleanTitle),
                normalizedCourt = stream.court?.let { normalize(it) },
                normalizedName = normalize(stream.name)
            )
        }
    }

    /**
     * Finds the best matching ESPN Watch stream for a given match.
     */
    fun findBestStream(match: Match, streams: List<EspnWatchStream>): EspnWatchStream? {
        if (streams.isEmpty()) return null
        return findBestStreamNormalized(match, preNormalize(streams))
    }

    /**
     * Finds the best matching stream using pre-normalized stream structures.
     */
    fun findBestStreamNormalized(match: Match, normalizedStreams: List<NormalizedStream>): EspnWatchStream? {
        if (normalizedStreams.isEmpty()) return null

        val p1Names = extractSearchNames(match.player1.name)
        val p2Names = extractSearchNames(match.player2.name)

        // Tier 1: Exact / surname match on both competitors (or doubles teams)
        if (p1Names.isNotEmpty() && p2Names.isNotEmpty()) {
            val directMatch = normalizedStreams.firstOrNull { norm ->
                val p1Matches = p1Names.any { it.length >= 3 && norm.normalizedTitle.contains(it) }
                val p2Matches = p2Names.any { it.length >= 3 && norm.normalizedTitle.contains(it) }
                p1Matches && p2Matches
            }
            if (directMatch != null) {
                return directMatch.stream
            }
        }

        // Tier 2: Match by Court / Stadium Camera Stream
        val courtName = match.displayCourt
        if (!courtName.isNullOrBlank() && !courtName.equals("New York, USA", ignoreCase = true) && !courtName.equals("TBD", ignoreCase = true)) {
            val courtNorm = normalize(courtName)
            val courtMatch = normalizedStreams.firstOrNull { norm ->
                if (norm.normalizedCourt != null && norm.normalizedCourt == courtNorm) {
                    return@firstOrNull true
                }
                norm.normalizedName.contains(courtNorm) || (courtNorm.length >= 5 && norm.normalizedName == courtNorm)
            }
            if (courtMatch != null) {
                return courtMatch.stream
            }
        }

        // Tier 3: General Tournament Live Broadcast Fallback (for LIVE matches)
        if (match.state == MatchState.LIVE) {
            val tournamentKeyword = match.tournamentName.ifEmpty { "Tennis" }
            val mainCoverage = normalizedStreams.firstOrNull { norm ->
                val name = norm.stream.name
                val subtitle = norm.stream.subtitle
                (name.contains("Coverage", ignoreCase = true) || name.contains("Primetime", ignoreCase = true)) &&
                        (subtitle.contains(tournamentKeyword, ignoreCase = true) ||
                         subtitle.contains("Tennis", ignoreCase = true) ||
                         subtitle.contains("Open", ignoreCase = true))
            }
            if (mainCoverage != null) {
                return mainCoverage.stream
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
            // Split tokens by space and hyphens using pre-compiled regex
            val tokens = trimmed.split(TOKEN_SPLIT_REGEX).filter { it.isNotBlank() }
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
            .replace(DIACRITICAL_MARKS_REGEX, "")
            .replace(NON_ALPHANUMERIC_REGEX, "")
            .lowercase(Locale.ROOT)
    }
}
