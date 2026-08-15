package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Session
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

/**
 * The figures behind the stats screens. Days run from the allowance reset hour,
 * so the chart and the allowance agree about which day time landed in.
 *
 * The zone is deliberately a half-hour offset: only that catches day boundaries
 * being resolved in UTC by accident.
 */
class UsageStatsRulesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val resetHour = 5
    private val target = Target(packageName = "com.example.social", user = UserId(0))

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun session(
        from: String,
        to: String,
        pausedMinutes: Long = 0,
    ) = Session(
        target = target,
        startedAt = at(from),
        endsAt = at(to),
        pausedMillis = Duration.ofMinutes(pausedMinutes).toMillis(),
    )

    @Test
    fun `a week is seven buckets, oldest first, including empty days`() {
        val usage = UsageStatsRules.forTarget(
            sessions = listOf(session("2026-08-12T09:00", "2026-08-12T09:30")),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        assertThat(usage.days).hasSize(7)
        assertThat(usage.days.first().dayStart).isEqualTo(at("2026-08-06T05:00"))
        assertThat(usage.days.last().dayStart).isEqualTo(at("2026-08-12T05:00"))
        assertThat(usage.days.dropLast(1).map { it.used }).containsExactlyElementsIn(List(6) { Duration.ZERO })
        assertThat(usage.today).isEqualTo(Duration.ofMinutes(30))
        assertThat(usage.todaySessions).isEqualTo(1)
    }

    @Test
    fun `time before the reset hour belongs to the previous day`() {
        // 3 AM Wednesday is still Tuesday's day, which starts 5 AM Tuesday.
        val usage = UsageStatsRules.forTarget(
            sessions = listOf(session("2026-08-12T03:00", "2026-08-12T03:20")),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        val yesterday = usage.days.first { it.dayStart == at("2026-08-11T05:00") }
        assertThat(yesterday.used).isEqualTo(Duration.ofMinutes(20))
        assertThat(yesterday.sessions).isEqualTo(1)
        assertThat(usage.today).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a session straddling the reset splits across the two days`() {
        val usage = UsageStatsRules.forTarget(
            sessions = listOf(session("2026-08-12T04:40", "2026-08-12T05:20")),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        val yesterday = usage.days.first { it.dayStart == at("2026-08-11T05:00") }
        assertThat(yesterday.used).isEqualTo(Duration.ofMinutes(20))
        assertThat(usage.today).isEqualTo(Duration.ofMinutes(20))
        // Counted where it started, so it is not counted twice.
        assertThat(usage.days.sumOf { it.sessions }).isEqualTo(1)
    }

    @Test
    fun `paused time is not counted as time in the app`() {
        val usage = UsageStatsRules.forTarget(
            sessions = listOf(session("2026-08-12T09:00", "2026-08-12T09:30", pausedMinutes = 20)),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        assertThat(usage.today).isEqualTo(Duration.ofMinutes(10))
        assertThat(usage.longestSession).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `today stops at now rather than running to the planned end`() {
        val running = Session(
            target = target,
            startedAt = at("2026-08-12T11:50"),
            endsAt = at("2026-08-12T12:20"),
        )

        val usage = UsageStatsRules.forTarget(
            sessions = listOf(running),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        assertThat(usage.today).isEqualTo(Duration.ofMinutes(10))
    }

    @Test
    fun `totals average over the whole window and pick out the longest session`() {
        val usage = UsageStatsRules.forTarget(
            sessions = listOf(
                session("2026-08-10T09:00", "2026-08-10T09:40"),
                session("2026-08-12T09:00", "2026-08-12T09:20"),
            ),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        assertThat(usage.total).isEqualTo(Duration.ofMinutes(60))
        assertThat(usage.dailyAverage).isEqualTo(Duration.ofMinutes(60).dividedBy(7))
        assertThat(usage.longestSession).isEqualTo(Duration.ofMinutes(40))
        assertThat(usage.busiestDay).isEqualTo(Duration.ofMinutes(40))
    }

    @Test
    fun `nothing recorded still produces a full week of empty days`() {
        val usage = UsageStatsRules.forTarget(
            sessions = emptyList(),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        assertThat(usage.days).hasSize(7)
        assertThat(usage.total).isEqualTo(Duration.ZERO)
        assertThat(usage.longestSession).isEqualTo(Duration.ZERO)
        assertThat(usage.busiestDay).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a session older than the window is not counted`() {
        val usage = UsageStatsRules.forTarget(
            sessions = listOf(session("2026-08-04T09:00", "2026-08-04T09:30")),
            now = at("2026-08-12T12:00"),
            zone = zone,
            resetHour = resetHour,
        )

        assertThat(usage.total).isEqualTo(Duration.ZERO)
        assertThat(usage.days.sumOf { it.sessions }).isEqualTo(0)
    }
}
