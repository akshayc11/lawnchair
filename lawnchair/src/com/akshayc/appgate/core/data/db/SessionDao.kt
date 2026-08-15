package com.akshayc.appgate.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SessionDao {
    /**
     * Newest first and bounded, so the observed set cannot grow without limit
     * however long the launcher lives. The policy engine windows what it needs
     * out of this (grace, escalation, budget) itself.
     */
    @Query("SELECT * FROM sessions ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionEntity>>

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMillis = :endedAtMillis WHERE id = :id")
    suspend fun markEnded(
        id: Long,
        endedAtMillis: Long,
    )

    /**
     * Ends every Session still running, as one statement so no row id has to be
     * held in memory. [startedBeforeMillis] excludes sessions too young to have
     * been left yet — the launcher is briefly resumed while handing off to the
     * app it just launched.
     */
    @Query(
        "UPDATE sessions SET endedAtMillis = :atMillis " +
            "WHERE endedAtMillis IS NULL AND endsAtMillis > :atMillis AND startedAtMillis <= :startedBeforeMillis",
    )
    suspend fun endActive(
        atMillis: Long,
        startedBeforeMillis: Long,
    )

    /**
     * Extends the newest not-yet-ended session for this Target by moving its
     * planned end, and marks the extension as spent. Returns the number of rows
     * changed, which is zero when the wrap-up has already been taken — that
     * guard is in SQL so it survives the launcher being killed and restarted.
     */
    @Query(
        "UPDATE sessions SET endsAtMillis = :newEndsAtMillis, wrapUpUsed = 1 " +
            "WHERE wrapUpUsed = 0 AND endedAtMillis IS NULL AND id = (" +
            "SELECT id FROM sessions WHERE packageName = :packageName AND userId = :userId " +
            "AND endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1)",
    )
    suspend fun wrapUpNewest(
        packageName: String,
        userId: Long,
        newEndsAtMillis: Long,
    ): Int

    /**
     * The user's own words from the newest COMMITMENT session for this Target,
     * for quoting back on the session-end screen. On-device only, like the rest
     * of this table.
     */
    @Query(
        "SELECT intentText FROM sessions WHERE packageName = :packageName AND userId = :userId " +
            "AND intentText IS NOT NULL ORDER BY startedAtMillis DESC LIMIT 1",
    )
    suspend fun newestIntentText(
        packageName: String,
        userId: Long,
    ): String?

    /**
     * Opens a pause on the newest running Session for this Target: the user is
     * not in the app right now, so its time stops. Already-paused rows are left
     * alone, so a second screen-off cannot restart the pause and lose time.
     */
    @Query(
        "UPDATE sessions SET pausedAtMillis = :atMillis " +
            "WHERE pausedAtMillis IS NULL AND endedAtMillis IS NULL AND id = (" +
            "SELECT id FROM sessions WHERE packageName = :packageName AND userId = :userId " +
            "AND endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1)",
    )
    suspend fun pauseNewest(
        packageName: String,
        userId: Long,
        atMillis: Long,
    ): Int

    /**
     * Settles the open pause on the newest running Session for this Target: the
     * time it covers is added to the total paused and the planned end moves by
     * the same amount, so the session still gets the length it was granted.
     *
     * The arithmetic is in SQL on purpose — the stored row is the only thing
     * that has to be right, and doing it here means no in-memory snapshot can
     * be stale by the time the write lands. [newPausedAtMillis] is null when the
     * user is back in the app, or the moment being settled to when they are
     * still away and the pause carries on.
     */
    @Query(
        "UPDATE sessions SET " +
            "endsAtMillis = endsAtMillis + (:atMillis - pausedAtMillis), " +
            "pausedMillis = pausedMillis + (:atMillis - pausedAtMillis), " +
            "pausedAtMillis = :newPausedAtMillis " +
            "WHERE pausedAtMillis IS NOT NULL AND pausedAtMillis <= :atMillis " +
            "AND endedAtMillis IS NULL AND id = (" +
            "SELECT id FROM sessions WHERE packageName = :packageName AND userId = :userId " +
            "AND endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1)",
    )
    suspend fun settleNewest(
        packageName: String,
        userId: Long,
        atMillis: Long,
        newPausedAtMillis: Long?,
    ): Int

    /**
     * Ends the newest running Session for this Target whatever its planned end
     * says, for a session the user has plainly abandoned. [endActive] cannot do
     * this: it spares rows whose planned end has already passed, which is
     * exactly what a long-paused session looks like.
     */
    @Query(
        "UPDATE sessions SET endedAtMillis = :atMillis " +
            "WHERE endedAtMillis IS NULL AND id = (" +
            "SELECT id FROM sessions WHERE packageName = :packageName AND userId = :userId " +
            "AND endedAtMillis IS NULL ORDER BY startedAtMillis DESC LIMIT 1)",
    )
    suspend fun endNewest(
        packageName: String,
        userId: Long,
        atMillis: Long,
    ): Int

    @Query("DELETE FROM sessions WHERE startedAtMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
