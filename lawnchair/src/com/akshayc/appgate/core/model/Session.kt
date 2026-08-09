package com.akshayc.appgate.core.model

import java.time.Instant

/**
 * Time-boxed access grant after a passed Challenge. [endedAt] is set when the
 * session ends early (user left, gate re-armed) and takes precedence over the
 * planned [endsAt].
 */
data class Session(
    val target: Target,
    val startedAt: Instant,
    val endsAt: Instant,
    val endedAt: Instant? = null,
) {
    init {
        require(!endsAt.isBefore(startedAt)) { "endsAt must not precede startedAt" }
    }

    val effectiveEnd: Instant get() = endedAt ?: endsAt

    fun isActiveAt(instant: Instant): Boolean = !instant.isBefore(startedAt) && instant.isBefore(effectiveEnd)
}
