package com.usopen.tracker.domain.model

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolver engine for tennis tournaments.
 * Provides two-stage resolution:
 * 1. High-precision canonical matching across official ATP & WTA registries by name and aliases.
 * 2. Dynamic discovery and tier/surface inference for live events not present in canonical definitions.
 * Thread-safe with in-memory caching.
 */
object TournamentResolver {

    private val discoveredCache = ConcurrentHashMap<String, Tournament>()

    /**
     * Resolves a tournament from raw event data.
     *
     * @param rawName The raw tournament name (e.g. "Rolex Shanghai Masters", "China Open", "Samsun Open").
     * @param groupingName The competition grouping (e.g. "Men's Singles", "Women's Singles", "Men's Doubles").
     * @param eventJson Optional raw ESPN/API event JSON for extracting 'major' flag, venue, etc.
     */
    fun resolve(
        rawName: String,
        groupingName: String = "",
        eventJson: JSONObject? = null
    ): Tournament {
        val trimmedName = rawName.trim()
        if (trimmedName.isEmpty() || trimmedName.equals("All", ignoreCase = true) || trimmedName.equals("All Tournaments", ignoreCase = true)) {
            return Tournaments.ALL
        }

        val cacheKey = buildCacheKey(trimmedName, groupingName)
        discoveredCache[cacheKey]?.let { return it }

        // Stage 1: Check canonical registry
        val canonical = findCanonical(trimmedName)
        if (canonical != null) {
            val resolved = refineForGrouping(canonical, groupingName)
            discoveredCache[cacheKey] = resolved
            return resolved
        }

        // Stage 2: Dynamic discovery and classification
        val discovered = discoverTournament(trimmedName, groupingName, eventJson)
        discoveredCache[cacheKey] = discovered
        return discovered
    }

    /**
     * Finds a canonical tournament matching the raw name.
     */
    fun findCanonical(rawName: String): Tournament? {
        val clean = rawName.trim()
        if (clean.isEmpty()) return null

        // 1. Exact or case-insensitive displayName match
        Tournaments.ALL_CANONICAL_TOURNAMENTS.firstOrNull {
            it.displayName.equals(clean, ignoreCase = true)
        }?.let { return it }

        // 2. Exact match against aliases
        Tournaments.ALL_CANONICAL_TOURNAMENTS.firstOrNull { tournament ->
            tournament.aliases.any { alias -> alias.equals(clean, ignoreCase = true) }
        }?.let { return it }

        // 3. Substring match via tournament.matches()
        Tournaments.ALL_CANONICAL_TOURNAMENTS.firstOrNull {
            it.matches(clean)
        }?.let { return it }

        return null
    }

    /**
     * For combined/joint events, refines category and tour according to eventGrouping.
     * For example, China Open is ATP 500 for Men's and WTA 1000 for Women's.
     */
    private fun refineForGrouping(canonical: Tournament, groupingName: String): Tournament {
        val groupingLower = groupingName.lowercase()
        val isWomen = groupingLower.contains("women") || groupingLower.contains("ladies")
        val isMen = !isWomen && groupingLower.contains("men")

        if (canonical.id == "china_open") {
            return if (isMen) {
                canonical.copy(tour = TourType.ATP, category = TournamentCategory.ATP_500)
            } else if (isWomen) {
                canonical.copy(tour = TourType.WTA, category = TournamentCategory.WTA_1000)
            } else canonical
        }

        if (canonical.id == "dubai_tennis_championships") {
            return if (isMen) {
                canonical.copy(tour = TourType.ATP, category = TournamentCategory.ATP_500)
            } else if (isWomen) {
                canonical.copy(tour = TourType.WTA, category = TournamentCategory.WTA_1000)
            } else canonical
        }

        if (canonical.id == "washington_open") {
            return if (isMen) {
                canonical.copy(tour = TourType.ATP, category = TournamentCategory.ATP_500)
            } else if (isWomen) {
                canonical.copy(tour = TourType.WTA, category = TournamentCategory.WTA_500)
            } else canonical
        }

        return canonical
    }

    /**
     * Dynamically classifies an unknown tournament based on keywords, grouping, and JSON metadata.
     */
    private fun discoverTournament(
        rawName: String,
        groupingName: String,
        eventJson: JSONObject?
    ): Tournament {
        val clean = rawName.trim()
        val lower = clean.lowercase()

        val isMajor = eventJson?.optBoolean("major", false) == true ||
            lower.contains("grand slam") ||
            lower.contains("us open") ||
            lower.contains("australian open") ||
            lower.contains("roland garros") ||
            lower.contains("wimbledon")

        val groupingLower = groupingName.lowercase()
        val isGroupingWomen = groupingLower.contains("women") || groupingLower.contains("ladies")
        val isGroupingMen = !isGroupingWomen && groupingLower.contains("men")

        val isTitleWomen = lower.contains("wta") || lower.contains("ladies") || lower.contains("women")
        val isTitleMen = lower.contains("atp") || (!isTitleWomen && (lower.contains(" men") || lower.startsWith("men ")))

        val isWomen = isGroupingWomen || (isTitleWomen && !isGroupingMen)
        val isMen = isGroupingMen || (isTitleMen && !isGroupingWomen)

        val tour = when {
            isWomen && isMen -> TourType.COMBINED
            isWomen -> TourType.WTA
            isMen -> TourType.ATP
            else -> TourType.COMBINED
        }

        val category = when {
            isMajor -> TournamentCategory.GRAND_SLAM
            lower.contains("finals") || (lower.contains("championships") && (lower.contains("tour") || lower.contains("season"))) ->
                TournamentCategory.FINALS
            lower.contains("1000") || lower.contains("masters") ->
                if (tour == TourType.WTA) TournamentCategory.WTA_1000 else TournamentCategory.MASTERS_1000
            lower.contains("500") ->
                if (tour == TourType.WTA) TournamentCategory.WTA_500 else TournamentCategory.ATP_500
            else ->
                if (tour == TourType.WTA) TournamentCategory.WTA_250 else TournamentCategory.ATP_250
        }

        // Infer surface
        val venueName = eventJson?.optJSONObject("venue")?.optString("displayName", "") ?: ""
        val surface = inferSurface(lower, venueName.lowercase())

        val id = generateId(clean)

        return Tournament(
            id = id,
            displayName = clean,
            aliases = setOf(clean),
            defaultVenue = if (venueName.isNotBlank()) venueName else "Center Court",
            isGrandSlam = isMajor,
            tour = tour,
            category = category,
            surface = surface,
            city = extractCity(venueName),
            country = extractCountry(venueName)
        )
    }

    /**
     * Infers the court surface from tournament and venue name keywords.
     */
    fun inferSurface(tournamentLower: String, venueLower: String = ""): CourtSurface {
        val combined = "$tournamentLower $venueLower"
        return when {
            combined.contains("clay") || combined.contains("terre battue") ||
                combined.contains("roland") || combined.contains("rome") ||
                combined.contains("madrid") || combined.contains("monte-carlo") ||
                combined.contains("monte carlo") || combined.contains("barcelona") ||
                combined.contains("rio") || combined.contains("gstaad") ||
                combined.contains("kitzbuhel") || combined.contains("estoril") ||
                combined.contains("bastad") || combined.contains("charleston") ||
                (combined.contains("stuttgart") && combined.contains("porsche")) -> CourtSurface.CLAY

            combined.contains("grass") || combined.contains("gazon") ||
                combined.contains("wimbledon") || combined.contains("halle") ||
                combined.contains("queen's") || combined.contains("queens") ||
                combined.contains("mallorca") || combined.contains("eastbourne") ||
                combined.contains("berlin") || combined.contains("newport") ||
                combined.contains("s-hertogenbosch") -> CourtSurface.GRASS

            combined.contains("carpet") || combined.contains("indoor") -> CourtSurface.CARPET

            else -> CourtSurface.HARD
        }
    }

    private fun generateId(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "tournament_${System.currentTimeMillis()}" }
    }

    private fun extractCity(venueName: String): String? {
        if (venueName.isBlank()) return null
        val parts = venueName.split(",")
        return if (parts.isNotEmpty()) parts[0].trim() else null
    }

    private fun extractCountry(venueName: String): String? {
        if (venueName.isBlank()) return null
        val parts = venueName.split(",")
        return if (parts.size > 1) parts[1].trim() else null
    }

    private fun buildCacheKey(name: String, grouping: String): String {
        return "${name.lowercase()}_${grouping.lowercase()}"
    }

    fun clearCache() {
        discoveredCache.clear()
    }

    val cachedCount: Int
        get() = discoveredCache.size
}
