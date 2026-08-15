package com.akshayc.appgate.core.model

import java.time.Duration

/**
 * Optional cap on access. [SessionCount] and [TimeBudget] measure a rolling
 * window ending at evaluation time; [DailyTime] measures a calendar day that
 * begins at a chosen hour.
 */
sealed interface Budget {
    val period: Duration

    /** At most [maxSessions] sessions started within [period]. */
    data class SessionCount(
        val maxSessions: Int,
        override val period: Duration,
    ) : Budget {
        init {
            require(maxSessions > 0) { "maxSessions must be positive" }
            require(!period.isNegative && !period.isZero) { "period must be positive" }
        }
    }

    /** At most [maxTotal] of accumulated session time within [period]. */
    data class TimeBudget(
        val maxTotal: Duration,
        override val period: Duration,
    ) : Budget {
        init {
            require(!maxTotal.isNegative && !maxTotal.isZero) { "maxTotal must be positive" }
            require(!period.isNegative && !period.isZero) { "period must be positive" }
        }
    }

    /**
     * At most [maxTotal] of accumulated session time per day, where the day
     * starts at [resetHour] local time — not a rolling 24 hours, so the
     * allowance refills at a predictable moment the user picked.
     */
    data class DailyTime(
        val maxTotal: Duration,
        val resetHour: Int,
    ) : Budget {
        override val period: Duration = Duration.ofDays(1)

        init {
            require(!maxTotal.isNegative && !maxTotal.isZero) { "maxTotal must be positive" }
            require(resetHour in 0..23) { "resetHour must be an hour of the day" }
        }
    }
}
