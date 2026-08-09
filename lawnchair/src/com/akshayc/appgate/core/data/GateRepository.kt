package com.akshayc.appgate.core.data

import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.Target
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the configured Gates. [observeGates] is the single
 * source of truth for both the settings UI and (later) the policy engine.
 */
interface GateRepository {
    fun observeGates(): Flow<List<Gate>>

    suspend fun upsertGate(gate: Gate)

    suspend fun removeGate(target: Target)
}
