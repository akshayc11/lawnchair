package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.DenyReason
import com.akshayc.appgate.core.model.GateDecision
import com.akshayc.appgate.core.model.Tier
import java.time.Clock
import java.time.Duration

/**
 * Decides every Interception. Pure and synchronous; the only time source is
 * the injected [clock].
 *
 * Fail-open guard (safety invariant 2): if evaluation throws, the user gets
 * their app — unless the gate is provably LOCKED, which fails closed.
 */
class GatePolicyEngine(
    private val clock: Clock,
    private val escalation: EscalationPolicy = EscalationPolicy(),
) {
    fun evaluate(input: EvaluationInput): GateDecision =
        try {
            decide(input)
        } catch (_: Exception) {
            if (input.gate?.enabled == true && input.gate.config.tier == Tier.LOCKED) {
                GateDecision.Deny(DenyReason.LOCKED)
            } else {
                GateDecision.Allow
            }
        }

    private fun decide(input: EvaluationInput): GateDecision {
        // Protected packages win over everything, including LOCKED.
        if (ProtectedPackages.isProtected(input.target.packageName, input.runtimeProtectedPackages)) {
            return GateDecision.Allow
        }
        val gate = input.gate
        if (gate == null || !gate.enabled) return GateDecision.Allow
        val config = gate.config
        if (config.tier == Tier.LOCKED) return GateDecision.Deny(DenyReason.LOCKED)

        val now = clock.instant()
        if (input.activeSession?.isActiveAt(now) == true) return GateDecision.Allow

        val lastEnd = input.recentSessions.maxOfOrNull { it.effectiveEnd }
        if (lastEnd != null && Duration.between(lastEnd, now) <= config.grace) {
            return GateDecision.Allow
        }

        val budget = config.budget
        if (budget != null && isBudgetExhausted(budget, input.recentSessions, input.activeSession, now, clock.zone)) {
            return GateDecision.Deny(DenyReason.BUDGET_EXHAUSTED)
        }

        // Escalation is opt-in per Gate, and auth-only gates opt out of it
        // whatever the setting says: verify who, not slow when. Stacked gates
        // escalate only the friction half — the auth requirement is read from
        // config by the UI, unchanged by tier.
        val tier =
            if (!config.escalation || config.isAuthOnly) {
                config.tier
            } else {
                val reentries =
                    input.recentSessions.count {
                        Duration.between(it.effectiveEnd, now) <= escalation.window
                    }
                escalation.escalatedTier(config.tier, reentries)
            }
        return GateDecision.Challenge(tier)
    }
}
