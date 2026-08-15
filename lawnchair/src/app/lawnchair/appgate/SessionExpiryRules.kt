package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import java.time.Duration
import java.time.Instant

/**
 * When a running Session runs out, and which ones are still worth acting on.
 *
 * Pure Kotlin, deliberately: this decides whether the user's screen is taken
 * over, so it is the part that has to be unit tested, and nothing in
 * `lawnchair/tests` may touch `android.*`.
 */
internal object SessionExpiryRules {

    /**
     * How far back an expiry is still enforced. The observed session list holds
     * a week of history, so without this every launcher start would take the
     * screen over for a session that ran out days ago.
     */
    val ENFORCEMENT_WINDOW: Duration = Duration.ofMinutes(2)

    /** How long the one-off wrap-up extension buys. */
    val WRAP_UP: Duration = Duration.ofMinutes(1)

    /**
     * The next moment a running Session runs out, or null if none is running.
     * Sessions already marked ended are over regardless of their planned end,
     * and a paused Session has no expiry to wait for: its clock is stopped, so
     * its planned end will have moved by the time it matters.
     */
    fun nextExpiryAt(sessions: List<Session>, now: Instant): Instant? = sessions
        .filter { it.endedAt == null && it.pausedAt == null && it.endsAt.isAfter(now) }
        .minOfOrNull { it.endsAt }

    /**
     * Sessions whose time is up and which ran out recently enough that the user
     * is plausibly still inside the app.
     *
     * A paused Session can appear here, with a planned end that stopped meaning
     * anything when its clock stopped. The caller settles those instead of
     * taking the screen over — being away is why they are paused.
     */
    fun justExpired(sessions: List<Session>, now: Instant): List<Session> {
        val since = now.minus(ENFORCEMENT_WINDOW)
        return sessions.filter { it.endedAt == null && !it.endsAt.isAfter(now) && it.endsAt.isAfter(since) }
    }

    /**
     * The newest Session for [target] whose time is up, at any age.
     *
     * No window here, unlike [justExpired]: this answers the backstop alarm,
     * which is inexact and can arrive long after the moment it was set for. A
     * late alarm with the user still inside the app is precisely when the
     * takeover is still owed, and whether they are still there is decided by
     * the caller, not by the clock.
     */
    fun expired(sessions: List<Session>, target: Target, now: Instant): Session? = sessions
        .filter { it.target == target && it.endedAt == null && !it.endsAt.isAfter(now) }
        .maxByOrNull { it.endsAt }

    /**
     * Identifies one expiry, so the same one is never enforced twice — the
     * in-process watchdog and the alarm backstop both aim at the same instant,
     * and whichever arrives second must do nothing.
     *
     * The planned end is part of the key, so a wrap-up extension counts as a
     * new expiry to enforce.
     */
    fun enforcementKey(session: Session): String =
        session.target.packageName + "@" + session.target.user.value + "#" + session.endsAt.toEpochMilli()
}
