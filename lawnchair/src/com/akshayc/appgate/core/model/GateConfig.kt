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
) {
    init {
        require(tier == Tier.NUDGE || frictionChallenge != null || authChallenge != null) {
            "tier $tier requires at least one challenge"
        }
        require(!grace.isNegative) { "grace must not be negative" }
    }

    val isAuthOnly: Boolean get() = frictionChallenge == null && authChallenge != null
}
