package com.akshayc.appgate.core.model

import java.time.Duration
import java.time.Instant

/**
 * Time-boxed access grant after a passed Challenge. [endedAt] is set when the
 * session ends early (user left, gate re-armed) and takes precedence over the
 * planned [endsAt].
 *
 * [wrapUpUsed] records that the one-off wrap-up extension has already been
 * spent on this session, so it cannot be taken twice.
 *
 * A session's time only runs while the user is actually in the app. Stretches
 * where they are not — screen off, or another app in front — are pauses:
 * [pausedMillis] is what has been settled, and [pausedAt] is a pause that is
 * still open. [endsAt] is moved forward as pauses settle, so what it means is
 * "when this ends if nothing else interrupts".
 */
data class Session(
    val target: Target,
    val startedAt: Instant,
    val endsAt: Instant,
    val endedAt: Instant? = null,
    val wrapUpUsed: Boolean = false,
    val pausedMillis: Long = 0,
    val pausedAt: Instant? = null,
) {
    init {
        require(!endsAt.isBefore(startedAt)) { "endsAt must not precede startedAt" }
    }

    val effectiveEnd: Instant get() = endedAt ?: endsAt

    fun isActiveAt(instant: Instant): Boolean = !instant.isBefore(startedAt) && instant.isBefore(effectiveEnd)

    /**
     * Time this session was not being used, up to [instant]. An open pause is
     * counted no further than [effectiveEnd], so a session ended while paused
     * needs no tidying up first.
     */
    fun pausedTimeUpTo(instant: Instant): Duration {
        val open = pausedAt?.let { start ->
            val until = minOf(instant, effectiveEnd)
            if (until.isAfter(start)) Duration.between(start, until) else Duration.ZERO
        } ?: Duration.ZERO
        return Duration.ofMillis(pausedMillis).plus(open)
    }
}
