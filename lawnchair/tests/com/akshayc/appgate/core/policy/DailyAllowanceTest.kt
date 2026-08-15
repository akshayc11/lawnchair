package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.DenyReason
import com.akshayc.appgate.core.model.GateDecision
import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Tier
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Test

/**
 * The daily allowance is a calendar day that begins at a chosen hour, not a
 * rolling 24 hours: it refills at a moment the user can predict.
 */
class DailyAllowanceTest {

    private val resetHour = 5

    private fun localInstant(text: String): Instant =
        LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun `the day starts at the reset hour that has most recently passed`() {
        // Noon local: the boundary is this morning's 5 AM.
        assertThat(dayStart(localInstant("2026-08-04T12:00"), zone, resetHour))
            .isEqualTo(localInstant("2026-08-04T05:00"))
    }

    @Test
    fun `the small hours still belong to the previous day`() {
        // 2 AM has not reached the reset yet, so it counts against yesterday.
        assertThat(dayStart(localInstant("2026-08-04T02:00"), zone, resetHour))
            .isEqualTo(localInstant("2026-08-03T05:00"))
    }

    @Test
    fun `the reset hour itself opens the new day`() {
        assertThat(dayStart(localInstant("2026-08-04T05:00"), zone, resetHour))
            .isEqualTo(localInstant("2026-08-04T05:00"))
    }

    @Test
    fun `the boundary is resolved in the clock's own zone`() {
        // Asia/Kolkata is +05:30, so 5 AM local is 23:30 UTC the day before.
        // A UTC calculation would land on 2026-08-04T05:00Z and be wrong.
        assertThat(dayStart(localInstant("2026-08-04T12:00"), zone, resetHour))
            .isEqualTo(Instant.parse("2026-08-03T23:30:00Z"))
    }

    @Test
    fun `time spent before the reset does not count against today`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(30), resetHour = resetHour)
        val yesterday = Session(
            target,
            startedAt = localInstant("2026-08-04T03:00"),
            endsAt = localInstant("2026-08-04T04:00"),
        )

        val exhausted = isBudgetExhausted(
            budget = budget,
            recentSessions = listOf(yesterday),
            activeSession = null,
            now = localInstant("2026-08-04T12:00"),
            zone = zone,
        )

        assertThat(exhausted).isFalse()
    }

    @Test
    fun `time spent since the reset does count`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(30), resetHour = resetHour)
        val thisMorning = Session(
            target,
            startedAt = localInstant("2026-08-04T09:00"),
            endsAt = localInstant("2026-08-04T09:30"),
        )

        val exhausted = isBudgetExhausted(
            budget = budget,
            recentSessions = listOf(thisMorning),
            activeSession = null,
            now = localInstant("2026-08-04T12:00"),
            zone = zone,
        )

        assertThat(exhausted).isTrue()
    }

    @Test
    fun `only the part of a session that falls after the reset counts`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(30), resetHour = resetHour)
        // An hour straddling the boundary, of which 20 minutes are today's.
        val straddling = Session(
            target,
            startedAt = localInstant("2026-08-04T04:20"),
            endsAt = localInstant("2026-08-04T05:20"),
        )

        val exhausted = isBudgetExhausted(
            budget = budget,
            recentSessions = listOf(straddling),
            activeSession = null,
            now = localInstant("2026-08-04T12:00"),
            zone = zone,
        )

        assertThat(exhausted).isFalse()
    }

    @Test
    fun `time the session was paused for is not charged to the allowance`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(30), resetHour = resetHour)
        // Half an hour of wall clock, twenty minutes of it with the screen off
        // or another app in front: ten minutes used, allowance not spent.
        val halfAway = Session(
            target,
            startedAt = localInstant("2026-08-04T09:00"),
            endsAt = localInstant("2026-08-04T09:30"),
            pausedMillis = Duration.ofMinutes(20).toMillis(),
        )

        val exhausted = isBudgetExhausted(
            budget = budget,
            recentSessions = listOf(halfAway),
            activeSession = null,
            now = localInstant("2026-08-04T12:00"),
            zone = zone,
        )

        assertThat(exhausted).isFalse()
    }

    @Test
    fun `an open pause stops the allowance ticking down`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(30), resetHour = resetHour)
        // Started an hour ago, paused after ten minutes and still paused: only
        // those ten minutes are used, however long the screen stays off.
        val paused = Session(
            target,
            startedAt = localInstant("2026-08-04T11:00"),
            endsAt = localInstant("2026-08-04T13:00"),
            pausedAt = localInstant("2026-08-04T11:10"),
        )

        val exhausted = isBudgetExhausted(
            budget = budget,
            recentSessions = emptyList(),
            activeSession = paused,
            now = localInstant("2026-08-04T12:00"),
            zone = zone,
        )

        assertThat(exhausted).isFalse()
    }

    @Test
    fun `paused time cannot credit back more than the session used`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(30), resetHour = resetHour)
        // Pause longer than the part of the session inside the window: the
        // answer is zero used, never a negative that pays for other sessions.
        val straddling = Session(
            target,
            startedAt = localInstant("2026-08-04T04:00"),
            endsAt = localInstant("2026-08-04T05:10"),
            pausedMillis = Duration.ofMinutes(60).toMillis(),
        )
        val spendsItAll = Session(
            target,
            startedAt = localInstant("2026-08-04T09:00"),
            endsAt = localInstant("2026-08-04T09:30"),
        )

        val exhausted = isBudgetExhausted(
            budget = budget,
            recentSessions = listOf(straddling, spendsItAll),
            activeSession = null,
            now = localInstant("2026-08-04T12:00"),
            zone = zone,
        )

        assertThat(exhausted).isTrue()
    }

    @Test
    fun `a spent allowance denies the open rather than challenging it`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(15), resetHour = resetHour)
        val engine = GatePolicyEngine(Clock.fixed(localInstant("2026-08-04T12:00"), zone))
        val input = EvaluationInput(
            target,
            gate(tier = Tier.DELAY, budget = budget),
            recentSessions = listOf(
                Session(
                    target,
                    startedAt = localInstant("2026-08-04T10:00"),
                    endsAt = localInstant("2026-08-04T10:15"),
                ),
            ),
        )

        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Deny(DenyReason.BUDGET_EXHAUSTED))
    }

    @Test
    fun `the allowance refills after the reset`() {
        val budget = Budget.DailyTime(maxTotal = Duration.ofMinutes(15), resetHour = resetHour)
        // Same spent session, but the clock has crossed the next 5 AM.
        val engine = GatePolicyEngine(Clock.fixed(localInstant("2026-08-05T06:00"), zone))
        val input = EvaluationInput(
            target,
            gate(tier = Tier.DELAY, budget = budget),
            recentSessions = listOf(
                Session(
                    target,
                    startedAt = localInstant("2026-08-04T10:00"),
                    endsAt = localInstant("2026-08-04T10:15"),
                ),
            ),
        )

        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Challenge(Tier.DELAY))
    }
}
