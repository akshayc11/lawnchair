package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Session
import java.time.Duration
import java.time.Instant

/**
 * When a Session's clock stops, and what that does to its end.
 *
 * A granted session is time *in* the app: the screen going off, or another app
 * coming to the front, stops it. So the planned end moves by however long the
 * user was away, and the daily allowance is only charged for what was used.
 *
 * Pure Kotlin, like [SessionExpiryRules] — this arithmetic decides how much of
 * the user's allowance is spent, and nothing in `lawnchair/tests` may touch
 * `android.*`.
 */
internal object SessionPauseRules {

    /**
     * How long a pause can run before the Session is treated as abandoned and
     * ended outright. Without this, a phone put down inside a gated app leaves a
     * paused Session sitting there for days, waking the device to check on it.
     * Coming back after this long has to pass the Gate again, which is the right
     * answer anyway.
     */
    val ABANDON_AFTER: Duration = Duration.ofMinutes(30)

    /**
     * How far out the backstop alarm is set while a Session is paused. Its
     * planned end says nothing useful then — the clock is stopped — so the alarm
     * is only there to notice abandonment if this process does not survive.
     */
    val PAUSE_RECHECK: Duration = Duration.ofMinutes(5)

    /** Sessions whose clock is stopped right now. */
    fun paused(sessions: List<Session>): List<Session> =
        sessions.filter { it.endedAt == null && it.pausedAt != null }

    /**
     * Sessions whose clock is running, and so can be stopped. A Session past its
     * planned end has nothing left to pause.
     */
    fun running(sessions: List<Session>, now: Instant): List<Session> =
        sessions.filter { it.endedAt == null && it.pausedAt == null && it.endsAt.isAfter(now) }

    /** Pause time not yet settled onto the row, as of [now]. */
    fun openPause(session: Session, now: Instant): Duration {
        val start = session.pausedAt ?: return Duration.ZERO
        if (session.endedAt != null) return Duration.ZERO
        return if (now.isAfter(start)) Duration.between(start, now) else Duration.ZERO
    }

    /**
     * Where the planned end lands once the open pause is settled: time away is
     * given back, so a session still gets the length it was granted.
     */
    fun settledEnd(session: Session, now: Instant): Instant = session.endsAt.plus(openPause(session, now))

    /** Whether the user has been away long enough that this Session is over. */
    fun isAbandoned(session: Session, now: Instant): Boolean =
        openPause(session, now) >= ABANDON_AFTER
}
