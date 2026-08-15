package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.policy.dayStart
import com.akshayc.appgate.core.policy.timeUsedSince
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** One day's worth of time in an app, for the chart and the numbers beside it. */
internal data class DayUsage(
    val dayStart: Instant,
    val used: Duration,
    val sessions: Int,
)

/**
 * What the stats screens show for one Target. Plain numbers, no judgement:
 * time, sessions, trend (CLAUDE.md — stats copy stays neutral).
 */
internal data class TargetUsage(
    /** Oldest first, one entry per day in the window, including days with nothing. */
    val days: List<DayUsage>,
    val today: Duration,
    val todaySessions: Int,
    val total: Duration,
    val dailyAverage: Duration,
    val longestSession: Duration,
) {
    val busiestDay: Duration get() = days.maxOfOrNull { it.used } ?: Duration.ZERO
}

/**
 * Turns Session rows into per-day totals.
 *
 * Days run from the allowance reset hour, not from midnight, so the chart and
 * the daily allowance agree about which day time landed in. Pure Kotlin: this
 * is arithmetic worth testing, and nothing in `lawnchair/tests` may touch
 * `android.*`.
 */
internal object UsageStatsRules {

    /**
     * Sessions are pruned after a week, so a week is all there is to show.
     */
    const val DEFAULT_DAYS = 7

    fun forTarget(
        sessions: List<Session>,
        now: Instant,
        zone: ZoneId,
        resetHour: Int,
        days: Int = DEFAULT_DAYS,
    ): TargetUsage {
        val todayStart = dayStart(now, zone, resetHour)
        val buckets = (days - 1 downTo 0).map { daysBack ->
            val start = todayStart.atZone(zone).minusDays(daysBack.toLong()).toInstant()
            val end = todayStart.atZone(zone).minusDays(daysBack - 1L).toInstant()
            DayUsage(
                dayStart = start,
                // Today's bucket stops at now; a day that has not finished
                // should not read as a full one.
                used = timeUsedSince(sessions, start, minOf(end, now)),
                sessions = sessions.count { !it.startedAt.isBefore(start) && it.startedAt.isBefore(minOf(end, now)) },
            )
        }
        val windowStart = buckets.first().dayStart
        val total = buckets.fold(Duration.ZERO) { acc, day -> acc.plus(day.used) }
        return TargetUsage(
            days = buckets,
            today = buckets.last().used,
            todaySessions = buckets.last().sessions,
            total = total,
            dailyAverage = total.dividedBy(days.toLong()),
            longestSession = sessions
                .maxOfOrNull { timeUsedSince(listOf(it), windowStart, now) }
                ?: Duration.ZERO,
        )
    }
}
