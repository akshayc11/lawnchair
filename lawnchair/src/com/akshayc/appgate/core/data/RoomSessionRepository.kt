package com.akshayc.appgate.core.data

import com.akshayc.appgate.core.data.db.SessionDao
import com.akshayc.appgate.core.data.db.toEntity
import com.akshayc.appgate.core.data.db.toSessionOrNull
import com.akshayc.appgate.core.model.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

internal class RoomSessionRepository(
    private val dao: SessionDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository {
    override fun observeRecentSessions(): Flow<List<Session>> =
        dao
            .observeRecent(RECENT_LIMIT)
            .map { rows -> rows.mapNotNull { it.toSessionOrNull() } }
            .flowOn(io)

    override suspend fun startSession(
        session: Session,
        intentText: String?,
    ): Long =
        withContext(io) {
            dao.insert(session.toEntity(intentText))
        }

    override suspend fun endSession(
        id: Long,
        at: Instant,
    ) = withContext(io) {
        dao.markEnded(id = id, endedAtMillis = at.toEpochMilli())
    }

    override suspend fun endActiveSessions(
        at: Instant,
        startedBefore: Instant,
    ) = withContext(io) {
        dao.endActive(atMillis = at.toEpochMilli(), startedBeforeMillis = startedBefore.toEpochMilli())
    }

    override suspend fun pruneBefore(before: Instant) =
        withContext(io) {
            dao.prune(before.toEpochMilli())
        }

    private companion object {
        const val RECENT_LIMIT = 500
    }
}
