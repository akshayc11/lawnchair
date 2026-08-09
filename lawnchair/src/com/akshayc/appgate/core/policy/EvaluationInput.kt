package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target

/**
 * Everything the engine needs to decide one Interception. The detection layer
 * only reports; all decisions happen in [GatePolicyEngine].
 *
 * @property recentSessions ended sessions for this target, any order; the
 *   engine sorts and windows them itself.
 * @property runtimeProtectedPackages device-resolved protected packages
 *   (default launcher/dialer via RoleManager), see [ProtectedPackages].
 */
data class EvaluationInput(
    val target: Target,
    val gate: Gate?,
    val activeSession: Session? = null,
    val recentSessions: List<Session> = emptyList(),
    val runtimeProtectedPackages: Set<String> = emptySet(),
)
