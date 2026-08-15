package com.akshayc.appgate.core.data

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
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

    /**
     * Moves the newest running Session's planned end to [newEndsAt], once per
     * Session. Returns false when the wrap-up has already been spent or there
     * is nothing to extend.
     */
    suspend fun wrapUpSession(
        target: Target,
        newEndsAt: Instant,
    ): Boolean

    /**
     * Stops the clock on the newest running Session for [target] as of [at] —
     * the screen went off, or another app came to the front. A Session already
     * paused is left as it is. Returns false when there was nothing to pause.
     */
    suspend fun pauseSession(
        target: Target,
        at: Instant,
    ): Boolean

    /**
     * Settles the open pause on the newest running Session for [target] up to
     * [at], moving its planned end by the same amount so the granted length is
     * preserved. [stillPaused] keeps the pause open from [at] onwards, for when
     * the user has not come back yet. Returns false when nothing was paused.
     */
    suspend fun settlePause(
        target: Target,
        at: Instant,
        stillPaused: Boolean,
    ): Boolean

    /**
     * Ends the newest running Session for [target] whatever its planned end
     * says, for one the user has abandoned.
     */
    suspend fun endNewestSession(
        target: Target,
        at: Instant,
    ): Boolean

    /** The newest stated intent for [target], for quoting back when a Session ends. */
    suspend fun latestIntentText(target: Target): String?

    /** Drops sessions older than [before]; history beyond that decides nothing. */
    suspend fun pruneBefore(before: Instant)
}
