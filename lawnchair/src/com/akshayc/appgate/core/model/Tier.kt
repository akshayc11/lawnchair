package com.akshayc.appgate.core.model

/**
 * Gating severity, ordered and additive in friction. Declaration order is the
 * severity order — policy escalation steps through ordinals, so reordering or
 * inserting a value is a schema change, not a refactor.
 */
enum class Tier {
    NUDGE,
    DELAY,
    EFFORT,
    COMMITMENT,
    LOCKED,
}
