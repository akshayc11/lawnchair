package com.akshayc.appgate.core.data

import com.akshayc.appgate.core.data.db.GateDao
import com.akshayc.appgate.core.data.db.toEntity
import com.akshayc.appgate.core.data.db.toGateOrNull
import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.Target
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Vendored from the AppGate repo. The only change is that Hilt injection was
 * removed — Lawnchair has no app-level Hilt, so this is constructed by hand in
 * [app.lawnchair.appgate.AppGate].
 */
internal class RoomGateRepository(
    private val dao: GateDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GateRepository {
    override fun observeGates(): Flow<List<Gate>> =
        dao
            .observeAll()
            .map { rows -> rows.mapNotNull { it.toGateOrNull() } }
            .flowOn(io)

    override suspend fun upsertGate(gate: Gate) =
        withContext(io) {
            dao.upsert(gate.toEntity())
        }

    override suspend fun removeGate(target: Target) =
        withContext(io) {
            dao.delete(packageName = target.packageName, userId = target.user.value)
        }
}
