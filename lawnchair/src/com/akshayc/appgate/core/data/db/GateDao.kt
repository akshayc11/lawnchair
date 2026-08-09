package com.akshayc.appgate.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface GateDao {
    @Query("SELECT * FROM gates ORDER BY packageName")
    fun observeAll(): Flow<List<GateEntity>>

    @Upsert
    suspend fun upsert(gate: GateEntity)

    @Query("DELETE FROM gates WHERE packageName = :packageName AND userId = :userId")
    suspend fun delete(
        packageName: String,
        userId: Long,
    )
}
