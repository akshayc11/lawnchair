package com.akshayc.appgate.core.data.db

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import java.time.Instant

/**
 * Returns null for a row that cannot be a valid [Session] (an end before its
 * start), so one bad row drops itself instead of crashing the whole list.
 */
internal fun SessionEntity.toSessionOrNull(): Session? =
    runCatching {
        Session(
            target = Target(packageName = packageName, user = UserId(userId)),
            startedAt = Instant.ofEpochMilli(startedAtMillis),
            endsAt = Instant.ofEpochMilli(endsAtMillis),
            endedAt = endedAtMillis?.let(Instant::ofEpochMilli),
        )
    }.getOrNull()

internal fun Session.toEntity(intentText: String?): SessionEntity =
    SessionEntity(
        packageName = target.packageName,
        userId = target.user.value,
        startedAtMillis = startedAt.toEpochMilli(),
        endsAtMillis = endsAt.toEpochMilli(),
        endedAtMillis = endedAt?.toEpochMilli(),
        intentText = intentText,
    )
