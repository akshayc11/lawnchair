package com.akshayc.appgate.core.model

/**
 * Outcome of evaluating an Interception. Always one of these — never a
 * boolean — so callers must handle every case explicitly.
 */
sealed interface GateDecision {
    data object Allow : GateDecision

    data class Challenge(
        val tier: Tier,
    ) : GateDecision

    data class Deny(
        val reason: DenyReason,
    ) : GateDecision
}

enum class DenyReason {
    LOCKED,
    BUDGET_EXHAUSTED,
}
