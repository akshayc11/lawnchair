package com.akshayc.appgate.core.data

import com.akshayc.appgate.core.model.Session
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Read/write access to Sessions — the record of granted access that grace
 * windows, escalating re-entry and Budgets are all computed from.
 */
interface SessionRepository {
    fun observeRecentSessions(): Flow<List<Session>>

    /** Records a granted Session and returns its row id. */
    suspend fun startSession(
        session: Session,
        intentText: String? = null,
    ): Long

    suspend fun endSession(
        id: Long,
        at: Instant,
    )

    /**
     * Ends every Session still running. [startedBefore] excludes ones too young
     * to have been left yet.
     */
    suspend fun endActiveSessions(
        at: Instant,
        startedBefore: Instant,
    )

    /** Drops sessions older than [before]; history beyond that decides nothing. */
    suspend fun pruneBefore(before: Instant)
}
