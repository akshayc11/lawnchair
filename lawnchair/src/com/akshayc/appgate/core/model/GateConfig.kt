package com.akshayc.appgate.core.model

import java.time.Duration

/**
 * How a Target is gated: severity ([tier]) and challenge kind(s), as separate
 * fields — a friction challenge, an auth challenge, or both stacked (auth
 * first, then friction).
 *
 * A schedule field is deliberately absent: schedule windows (for LOCKED and
 * others) are deferred, so LOCKED currently denies unconditionally.
 */
data class GateConfig(
    val tier: Tier,
    val frictionChallenge: FrictionChallenge? = null,
    val authChallenge: AuthChallenge? = null,
    val grace: Duration = Duration.ofSeconds(30),
    val budget: Budget? = null,
    /**
     * Opt in to escalating re-entry: coming back soon after a session raises
     * the Tier a step per re-entry, up to but never including [Tier.LOCKED] —
     * escalation adds friction, it never turns into denial. Off by default:
     * the Tier the user picked is the Tier they get.
     *
     * Ignored for auth-only gates, which opt out of escalation by design
     * (verify who, not slow when).
     */
    val escalation: Boolean = false,
) {
    init {
        require(tier == Tier.NUDGE || frictionChallenge != null || authChallenge != null) {
            "tier $tier requires at least one challenge"
        }
        require(!grace.isNegative) { "grace must not be negative" }
    }

    val isAuthOnly: Boolean get() = frictionChallenge == null && authChallenge != null
}
