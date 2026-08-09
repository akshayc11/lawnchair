package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.AuthChallenge
import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.FrictionChallenge
import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.GateConfig
import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.Tier
import com.akshayc.appgate.core.model.UserId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Shared fixtures, kept in step with the ones in the AppGate repo so the tests
 * that live in both places read the same way.
 *
 * The zone is deliberately not UTC and not a whole-hour offset: day boundaries
 * are resolved in the clock's zone, and only an offset like this catches the
 * arithmetic being done in UTC by accident.
 */
val zone: ZoneId = ZoneId.of("Asia/Kolkata")

/** 12:00 local, comfortably after the 5 AM allowance reset. */
val now: Instant = Instant.parse("2026-08-04T06:30:00Z")

val fixedClock: Clock = Clock.fixed(now, zone)

val target = Target("com.example.social", UserId(0L))

fun gate(
    tier: Tier = Tier.EFFORT,
    frictionChallenge: FrictionChallenge? = FrictionChallenge.EFFORT_TASK,
    authChallenge: AuthChallenge? = null,
    grace: Duration = Duration.ZERO,
    budget: Budget? = null,
    escalation: Boolean = false,
    enabled: Boolean = true,
): Gate = Gate(
    target,
    GateConfig(tier, frictionChallenge, authChallenge, grace, budget, escalation),
    enabled,
)

/** Session that ended [endedAgo] before [now], having lasted [length]. */
fun endedSession(
    endedAgo: Duration,
    length: Duration = Duration.ofMinutes(10),
    at: Instant = now,
): Session {
    val end = at.minus(endedAgo)
    return Session(target, startedAt = end.minus(length), endsAt = end)
}
