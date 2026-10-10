package com.usopen.tracker.data.local

import com.usopen.tracker.domain.model.H2HSummary
import com.usopen.tracker.domain.model.Match

object MockH2HData {
    fun generateH2H(match: Match): H2HSummary = H2HDataProvider.generateH2H(match)
}
