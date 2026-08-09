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

    @Query("DELETE FROM sessions WHERE startedAtMillis < :beforeMillis")
    suspend fun prune(beforeMillis: Long)
}
