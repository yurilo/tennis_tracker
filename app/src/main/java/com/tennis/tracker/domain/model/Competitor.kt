package com.tennis.tracker.domain.model

data class Competitor(
    val id: String,
    val name: String,
    val seed: Int? = null,
    val country: String? = null,
    val isWinner: Boolean = false,
    val setScores: List<Int> = emptyList(),
    val tiebreakScores: List<Int?> = emptyList(),
    val currentGameScore: String? = null,
    val isServing: Boolean = false,
    val photoUrl: String? = null,
    val players: List<Competitor> = emptyList()
) {
    val isTbdOrPlaceholder: Boolean
        get() {
            val n = name.trim()
            if (n.isEmpty()) return true
            if (n.equals("TBD", ignoreCase = true)) return true
            if (n.equals("To Be Determined", ignoreCase = true)) return true
            if (n.equals("Unknown", ignoreCase = true)) return true
            if (n.equals("BYE", ignoreCase = true)) return true
            if (n.matches(Regex("(?i)^Competitor(\\s*\\d+)?$"))) return true
            return false
        }

    val isDoubles: Boolean
        get() = players.size >= 2 || name.contains(" / ") || (name.contains("/") && !name.contains("http"))

    val individualPlayers: List<Competitor>
        get() {
            if (players.size >= 2) return players
            if (isDoubles) {
                val parts = name.split(Regex("\\s*/\\s*")).filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    val countries = country?.split(Regex("\\s*/\\s*")) ?: emptyList()
                    return parts.mapIndexed { idx, partName ->
                        val trimmedName = partName.trim()
                        val partnerCountry = countries.getOrNull(idx)?.trim() ?: country
                        Competitor(
                            id = "${id}_$idx",
                            name = trimmedName,
                            seed = seed,
                            country = partnerCountry,
                            photoUrl = PlayerPhotoProvider.getPhotoUrl(name = trimmedName)
                        )
                    }
                }
            }
            return listOf(this)
        }

    val displayNameWithSeed: String
        get() = if (seed != null && seed > 0) "($seed) $name" else name

    val initials: String
        get() {
            if (isDoubles) {
                val ind = individualPlayers
                if (ind.size >= 2) {
                    val p1 = ind[0].name.trim().split(" ").lastOrNull()?.firstOrNull()?.uppercase()
                        ?: ind[0].name.firstOrNull()?.uppercase() ?: "?"
                    val p2 = ind[1].name.trim().split(" ").lastOrNull()?.firstOrNull()?.uppercase()
                        ?: ind[1].name.firstOrNull()?.uppercase() ?: "?"
                    return "$p1/$p2"
                }
            }
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            return when {
                parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
                parts.isNotEmpty() && parts[0].length >= 2 -> parts[0].take(2).uppercase()
                parts.isNotEmpty() -> parts[0].take(1).uppercase()
                else -> "?"
            }
        }

    val lastNameOrShortName: String
        get() {
            if (isDoubles) {
                val ind = individualPlayers
                if (ind.size >= 2) {
                    val l1 = ind[0].name.trim().split(" ").lastOrNull() ?: ind[0].name
                    val l2 = ind[1].name.trim().split(" ").lastOrNull() ?: ind[1].name
                    return "$l1 / $l2"
                }
            }
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            return parts.lastOrNull() ?: name
        }

    val countryFlag: String?
        get() {
            if (isDoubles) {
                val flags = individualPlayers.mapNotNull { flagForCode(it.country) }.distinct()
                if (flags.isNotEmpty()) return flags.joinToString(" ")
                if (country != null && country.contains("/")) {
                    val directFlags = country.split("/").mapNotNull { flagForCode(it.trim()) }.distinct()
                    if (directFlags.isNotEmpty()) return directFlags.joinToString(" ")
                }
            }
            return flagForCode(country)
        }

    companion object {
        fun flagForCode(code: String?): String? = when (code?.trim()?.uppercase()) {
            "USA" -> "🇺🇸"
            "ESP" -> "🇪🇸"
            "ITA" -> "🇮🇹"
            "SRB" -> "🇷🇸"
            "GER" -> "🇩🇪"
            "POL" -> "🇵🇱"
            "BLR" -> "🇧🇾"
            "CHN" -> "🇨🇳"
            "CZE" -> "🇨🇿"
            "GBR" -> "🇬🇧"
            "FRA" -> "🇫🇷"
            "GRE" -> "🇬🇷"
            "NOR" -> "🇳🇴"
            "CAN" -> "🇨🇦"
            "AUS" -> "🇦🇺"
            "JPN" -> "🇯🇵"
            "KAZ" -> "🇰🇿"
            "LAT" -> "🇱🇻"
            "UKR" -> "🇺🇦"
            "ARG" -> "🇦🇷"
            "BRA" -> "🇧🇷"
            "SUI" -> "🇨🇭"
            "NED" -> "🇳🇱"
            "BEL" -> "🇧🇪"
            "CRO" -> "🇭🇷"
            "DEN" -> "🇩🇰"
            "MEX" -> "🇲🇽"
            "COL" -> "🇨🇴"
            "RUS" -> "🇷🇺"
            "NZL" -> "🇳🇿"
            "IND" -> "🇮🇳"
            else -> null
        }
    }
}
