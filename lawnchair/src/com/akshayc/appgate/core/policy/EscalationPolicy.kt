package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.Tier
import java.time.Duration

/**
 * Escalating re-entry: reopening a Target shortly after a session ends costs
 * more friction than the first open. Applies to friction challenges only —
 * auth challenges opt out (the engine never calls this for auth-only gates).
 *
 * [cap] never exceeds COMMITMENT: escalation raises friction, it never turns
 * into denial, so LOCKED is unreachable by escalation.
 */
data class EscalationPolicy(
    val window: Duration = Duration.ofMinutes(10),
    val cap: Tier = Tier.COMMITMENT,
) {
    init {
        require(cap != Tier.LOCKED) { "escalation must never reach LOCKED" }
        require(!window.isNegative && !window.isZero) { "window must be positive" }
    }

    fun escalatedTier(
        base: Tier,
        recentReentries: Int,
    ): Tier {
        require(recentReentries >= 0) { "recentReentries must not be negative" }
        if (recentReentries == 0 || base >= cap) return base
        val escalated = base.ordinal + recentReentries
        return Tier.entries[minOf(escalated, cap.ordinal)]
    }
}
