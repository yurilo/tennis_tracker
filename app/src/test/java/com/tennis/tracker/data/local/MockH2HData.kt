package com.tennis.tracker.data.local

import com.tennis.tracker.domain.model.H2HSummary
import com.tennis.tracker.domain.model.Match

object MockH2HData {
    fun generateH2H(match: Match): H2HSummary = H2HDataProvider.generateH2H(match)
}
