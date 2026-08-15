package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.Session
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Rolling window ending at [now], or — for [Budget.DailyTime] — the calendar
 * day so far, measured in [zone]. The active session counts toward every
 * budget kind; time spent before the window start is excluded.
 */
internal fun isBudgetExhausted(
    budget: Budget,
    recentSessions: List<Session>,
    activeSession: Session?,
    now: Instant,
    zone: ZoneId,
): Boolean {
    val sessions = (recentSessions + listOfNotNull(activeSession))
    return when (budget) {
        is Budget.SessionCount -> {
            val windowStart = now.minus(budget.period)
            val count = sessions.count { !it.startedAt.isBefore(windowStart) }
            count >= budget.maxSessions
        }
        is Budget.TimeBudget -> timeUsedSince(sessions, now.minus(budget.period), now) >= budget.maxTotal
        is Budget.DailyTime ->
            timeUsedSince(sessions, dayStart(now, zone, budget.resetHour), now) >= budget.maxTotal
    }
}

/** Accumulated session time between [windowStart] and [now]. */
internal fun timeUsedSince(
    sessions: List<Session>,
    windowStart: Instant,
    now: Instant,
): Duration =
    sessions.fold(Duration.ZERO) { acc, session ->
        acc.plus(timeInWindow(session, windowStart, now))
    }

/** The most recent [resetHour] boundary at or before [now], in [zone]. */
internal fun dayStart(
    now: Instant,
    zone: ZoneId,
    resetHour: Int,
): Instant {
    val local = now.atZone(zone)
    val boundary = local.toLocalDate().atStartOfDay(zone).plusHours(resetHour.toLong())
    return if (boundary.toInstant().isAfter(now)) boundary.minusDays(1).toInstant() else boundary.toInstant()
}

/**
 * Time the session was actually being used inside the window: wall clock less
 * the stretches it was paused for, never below zero.
 *
 * Settled pause time is a single total with no timestamps of its own, so for a
 * session straddling the window start the whole of it is subtracted. That can
 * only under-count use, which is the direction to be wrong in (Safety
 * invariants, fail open) and only matters for a session running across a daily
 * reset hour.
 */
private fun timeInWindow(
    session: Session,
    windowStart: Instant,
    now: Instant,
): Duration {
    val start = maxOf(session.startedAt, windowStart)
    val end = minOf(session.effectiveEnd, now)
    if (end.isBefore(start)) return Duration.ZERO
    val used = Duration.between(start, end).minus(session.pausedTimeUpTo(end))
    return if (used.isNegative) Duration.ZERO else used
}
