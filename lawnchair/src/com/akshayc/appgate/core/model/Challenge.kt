package com.akshayc.appgate.core.model

/**
 * Self-control challenges. Escalating re-entry and intent recall apply to these.
 */
enum class FrictionChallenge {
    BREATHING_DELAY,
    EFFORT_TASK,
    STATED_INTENT,
}

/**
 * Identity challenges. Opt out of escalation — their job is to verify *who*,
 * not slow *when*. Kept as a separate type from [FrictionChallenge] so the two
 * can stack on one [GateConfig]; never collapse into one enum.
 */
enum class AuthChallenge {
    BIOMETRIC,
}
