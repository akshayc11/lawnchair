package com.akshayc.appgate.core.model

/**
 * A gating rule bound to one [Target].
 */
data class Gate(
    val target: Target,
    val config: GateConfig,
    val enabled: Boolean = true,
)
