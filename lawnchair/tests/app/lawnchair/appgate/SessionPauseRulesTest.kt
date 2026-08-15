package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

/**
 * Session time only runs while the app is being used. These are the sums that
 * decide how much of the user's allowance a session actually costs.
 */
class SessionPauseRulesTest {

    private val now: Instant = Instant.parse("2026-08-12T10:00:00Z")
    private val target = Target(packageName = "com.example.social", user = UserId(0))

    @Test
    fun `a session that was never paused owes nothing`() {
        val session = session(startedAt = now.minusSeconds(300), endsAt = now.plusSeconds(300))
        assertThat(SessionPauseRules.openPause(session, now)).isEqualTo(Duration.ZERO)
        assertThat(SessionPauseRules.settledEnd(session, now)).isEqualTo(session.endsAt)
        assertThat(session.pausedTimeUpTo(now)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `an open pause grows with the time the user is away`() {
        val session = session(
            startedAt = now.minusSeconds(600),
            endsAt = now.plusSeconds(300),
            pausedAt = now.minusSeconds(120),
        )
        assertThat(SessionPauseRules.openPause(session, now)).isEqualTo(Duration.ofSeconds(120))
    }

    @Test
    fun `settling gives back exactly the time away`() {
        val session = session(
            startedAt = now.minusSeconds(600),
            endsAt = now.plusSeconds(60),
            pausedAt = now.minusSeconds(180),
        )
        assertThat(SessionPauseRules.settledEnd(session, now)).isEqualTo(now.plusSeconds(240))
    }

    @Test
    fun `settled pause time is counted without an open pause`() {
        val session = session(
            startedAt = now.minusSeconds(600),
            endsAt = now.plusSeconds(300),
            pausedMillis = Duration.ofMinutes(2).toMillis(),
        )
        assertThat(session.pausedTimeUpTo(now)).isEqualTo(Duration.ofMinutes(2))
    }

    @Test
    fun `settled and open pause add up`() {
        val session = session(
            startedAt = now.minusSeconds(900),
            endsAt = now.plusSeconds(300),
            pausedMillis = Duration.ofMinutes(2).toMillis(),
            pausedAt = now.minusSeconds(60),
        )
        assertThat(session.pausedTimeUpTo(now)).isEqualTo(Duration.ofSeconds(180))
    }

    @Test
    fun `an open pause on an ended session stops at the end`() {
        val session = session(
            startedAt = now.minusSeconds(600),
            endsAt = now.plusSeconds(300),
            endedAt = now.minusSeconds(60),
            pausedAt = now.minusSeconds(180),
        )
        // Paused for two minutes before the session was ended; the minute since
        // the end is not the session's time to give back.
        assertThat(session.pausedTimeUpTo(now)).isEqualTo(Duration.ofSeconds(120))
        assertThat(SessionPauseRules.openPause(session, now)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a long pause means the session was abandoned`() {
        val away = session(
            startedAt = now.minus(Duration.ofHours(1)),
            endsAt = now.plusSeconds(300),
            pausedAt = now.minus(SessionPauseRules.ABANDON_AFTER),
        )
        val steppedAway = away.copy(pausedAt = now.minus(Duration.ofMinutes(5)))
        assertThat(SessionPauseRules.isAbandoned(away, now)).isTrue()
        assertThat(SessionPauseRules.isAbandoned(steppedAway, now)).isFalse()
    }

    @Test
    fun `running sessions are the ones whose clock can be stopped`() {
        val running = session(startedAt = now.minusSeconds(60), endsAt = now.plusSeconds(300))
        val paused = session(
            startedAt = now.minusSeconds(60),
            endsAt = now.plusSeconds(300),
            pausedAt = now.minusSeconds(30),
        )
        val over = session(startedAt = now.minusSeconds(600), endsAt = now.minusSeconds(1))
        val ended = session(
            startedAt = now.minusSeconds(600),
            endsAt = now.plusSeconds(60),
            endedAt = now.minusSeconds(10),
        )
        val all = listOf(running, paused, over, ended)
        assertThat(SessionPauseRules.running(all, now)).containsExactly(running)
        assertThat(SessionPauseRules.paused(all)).containsExactly(paused)
    }

    @Test
    fun `a paused session is not an expiry to wait for`() {
        val paused = session(
            startedAt = now.minusSeconds(60),
            endsAt = now.plusSeconds(60),
            pausedAt = now.minusSeconds(30),
        )
        assertThat(SessionExpiryRules.nextExpiryAt(listOf(paused), now)).isNull()
    }

    private fun session(
        startedAt: Instant,
        endsAt: Instant,
        endedAt: Instant? = null,
        pausedMillis: Long = 0,
        pausedAt: Instant? = null,
    ) = Session(
        target = target,
        startedAt = startedAt,
        endsAt = endsAt,
        endedAt = endedAt,
        pausedMillis = pausedMillis,
        pausedAt = pausedAt,
    )
}
