package com.akshayc.appgate.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted form of a [com.akshayc.appgate.core.model.Session].
 *
 * Sessions are what make grace windows and escalating re-entry survive the
 * launcher being killed, so they cannot live in memory only.
 *
 * [intentText] is the user's own words from a COMMITMENT challenge. It is kept
 * so a later session-end screen can quote it back ("You opened this to: ...").
 * On-device only, like everything else here — never logged or exported.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val userId: Long,
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    val endedAtMillis: Long?,
    val intentText: String?,
)
