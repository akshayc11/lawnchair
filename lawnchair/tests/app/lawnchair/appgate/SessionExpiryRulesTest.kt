package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

class SessionExpiryRulesTest {

    private val now: Instant = Instant.parse("2026-08-12T10:00:00Z")
    private val target = Target(packageName = "com.example.social", user = UserId(0))
    private val other = Target(packageName = "com.example.video", user = UserId(0))

    @Test
    fun `next expiry is the soonest running session`() {
        val sessions = listOf(
            session(target, startedAt = now.minusSeconds(60), endsAt = now.plusSeconds(300)),
            session(other, startedAt = now.minusSeconds(30), endsAt = now.plusSeconds(120)),
        )
        assertThat(SessionExpiryRules.nextExpiryAt(sessions, now)).isEqualTo(now.plusSeconds(120))
    }

    @Test
    fun `a session ended early is not waited for`() {
        val sessions = listOf(
            session(target, startedAt = now.minusSeconds(60), endsAt = now.plusSeconds(120), endedAt = now.minusSeconds(5)),
        )
        assertThat(SessionExpiryRules.nextExpiryAt(sessions, now)).isNull()
    }

    @Test
    fun `nothing running means nothing to wait for`() {
        assertThat(SessionExpiryRules.nextExpiryAt(emptyList(), now)).isNull()
    }

    @Test
    fun `a session that has just run out is enforced`() {
        val expired = session(target, startedAt = now.minusSeconds(120), endsAt = now.minusSeconds(1))
        assertThat(SessionExpiryRules.justExpired(listOf(expired), now)).containsExactly(expired)
    }

    @Test
    fun `an expiry at exactly now is enforced`() {
        val expired = session(target, startedAt = now.minusSeconds(60), endsAt = now)
        assertThat(SessionExpiryRules.justExpired(listOf(expired), now)).containsExactly(expired)
    }

    @Test
    fun `history is not re-enforced on a later launcher start`() {
        val yesterday = session(
            target,
            startedAt = now.minus(Duration.ofDays(1)),
            endsAt = now.minus(Duration.ofDays(1)).plusSeconds(600),
        )
        assertThat(SessionExpiryRules.justExpired(listOf(yesterday), now)).isEmpty()
    }

    @Test
    fun `a session the user left is not enforced`() {
        val left = session(
            target,
            startedAt = now.minusSeconds(120),
            endsAt = now.minusSeconds(1),
            endedAt = now.minusSeconds(30),
        )
        assertThat(SessionExpiryRules.justExpired(listOf(left), now)).isEmpty()
    }

    @Test
    fun `a running session is not enforced yet`() {
        val running = session(target, startedAt = now.minusSeconds(60), endsAt = now.plusSeconds(1))
        assertThat(SessionExpiryRules.justExpired(listOf(running), now)).isEmpty()
    }

    @Test
    fun `a late backstop alarm still finds its expiry`() {
        val old = session(
            target,
            startedAt = now.minus(Duration.ofMinutes(20)),
            endsAt = now.minus(Duration.ofMinutes(10)),
        )
        assertThat(SessionExpiryRules.justExpired(listOf(old), now)).isEmpty()
        assertThat(SessionExpiryRules.expired(listOf(old), target, now)).isEqualTo(old)
    }

    @Test
    fun `the backstop takes the newest expiry and ignores other targets`() {
        val older = session(target, startedAt = now.minusSeconds(600), endsAt = now.minusSeconds(300))
        val newer = session(target, startedAt = now.minusSeconds(120), endsAt = now.minusSeconds(1))
        val elsewhere = session(other, startedAt = now.minusSeconds(120), endsAt = now.minusSeconds(1))
        assertThat(SessionExpiryRules.expired(listOf(older, newer, elsewhere), target, now)).isEqualTo(newer)
    }

    @Test
    fun `the backstop ignores a session the user left and one still running`() {
        val left = session(
            target,
            startedAt = now.minusSeconds(600),
            endsAt = now.minusSeconds(300),
            endedAt = now.minusSeconds(400),
        )
        val running = session(target, startedAt = now.minusSeconds(60), endsAt = now.plusSeconds(60))
        assertThat(SessionExpiryRules.expired(listOf(left, running), target, now)).isNull()
    }

    @Test
    fun `the enforcement key separates targets and profiles`() {
        val main = session(target, startedAt = now.minusSeconds(60), endsAt = now)
        val work = session(
            Target(packageName = target.packageName, user = UserId(10)),
            startedAt = now.minusSeconds(60),
            endsAt = now,
        )
        assertThat(SessionExpiryRules.enforcementKey(main))
            .isNotEqualTo(SessionExpiryRules.enforcementKey(work))
        assertThat(SessionExpiryRules.enforcementKey(main))
            .isEqualTo(SessionExpiryRules.enforcementKey(main.copy()))
    }

    @Test
    fun `a wrap-up is a new expiry to enforce`() {
        val expired = session(target, startedAt = now.minusSeconds(120), endsAt = now)
        val extended = expired.copy(endsAt = now.plus(SessionExpiryRules.WRAP_UP), wrapUpUsed = true)
        assertThat(SessionExpiryRules.enforcementKey(extended))
            .isNotEqualTo(SessionExpiryRules.enforcementKey(expired))
    }

    private fun session(
        target: Target,
        startedAt: Instant,
        endsAt: Instant,
        endedAt: Instant? = null,
    ) = Session(target = target, startedAt = startedAt, endsAt = endsAt, endedAt = endedAt)
}
