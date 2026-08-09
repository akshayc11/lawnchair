package com.akshayc.appgate.core.policy

import com.akshayc.appgate.core.model.AuthChallenge
import com.akshayc.appgate.core.model.FrictionChallenge
import com.akshayc.appgate.core.model.GateDecision
import com.akshayc.appgate.core.model.Tier
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

/**
 * Escalating re-entry is opt-in per Gate. Off, the Tier the user picked is the
 * Tier they get, however often they come back; on, each return inside the
 * window costs one Tier more, and never a lock.
 */
class GatePolicyEngineEscalationToggleTest {

    private val engine = GatePolicyEngine(fixedClock)

    @Test
    fun `does not escalate when the gate has not opted in`() {
        val input = EvaluationInput(
            target,
            gate(tier = Tier.DELAY, escalation = false),
            recentSessions = listOf(
                endedSession(endedAgo = Duration.ofMinutes(2)),
                endedSession(endedAgo = Duration.ofMinutes(5)),
            ),
        )

        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Challenge(Tier.DELAY))
    }

    @Test
    fun `escalates one tier per re-entry when the gate has opted in`() {
        fun decisionAfter(reentries: Int): GateDecision = engine.evaluate(
            EvaluationInput(
                target,
                gate(tier = Tier.NUDGE, frictionChallenge = FrictionChallenge.BREATHING_DELAY, escalation = true),
                recentSessions = (1..reentries).map { endedSession(endedAgo = Duration.ofMinutes(it.toLong())) },
            ),
        )

        assertThat(decisionAfter(0)).isEqualTo(GateDecision.Challenge(Tier.NUDGE))
        assertThat(decisionAfter(1)).isEqualTo(GateDecision.Challenge(Tier.DELAY))
        assertThat(decisionAfter(2)).isEqualTo(GateDecision.Challenge(Tier.EFFORT))
        assertThat(decisionAfter(3)).isEqualTo(GateDecision.Challenge(Tier.COMMITMENT))
    }

    @Test
    fun `never escalates as far as locked`() {
        val relentless = (1..9).map { endedSession(endedAgo = Duration.ofMinutes(it.toLong())) }
        val input = EvaluationInput(
            target,
            gate(tier = Tier.EFFORT, escalation = true),
            recentSessions = relentless,
        )

        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Challenge(Tier.COMMITMENT))
    }

    @Test
    fun `does not escalate auth-only gates even when they have opted in`() {
        val input = EvaluationInput(
            target,
            gate(
                tier = Tier.EFFORT,
                frictionChallenge = null,
                authChallenge = AuthChallenge.BIOMETRIC,
                escalation = true,
            ),
            recentSessions = listOf(endedSession(endedAgo = Duration.ofMinutes(2))),
        )

        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Challenge(Tier.EFFORT))
    }

    @Test
    fun `escalates only the friction half of a stacked gate`() {
        val input = EvaluationInput(
            target,
            gate(
                tier = Tier.DELAY,
                frictionChallenge = FrictionChallenge.BREATHING_DELAY,
                authChallenge = AuthChallenge.BIOMETRIC,
                escalation = true,
            ),
            recentSessions = listOf(endedSession(endedAgo = Duration.ofMinutes(2))),
        )

        // The auth requirement lives in config and is read by the UI unchanged.
        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Challenge(Tier.EFFORT))
    }

    @Test
    fun `settles back once the app has been left alone`() {
        val window = EscalationPolicy().window
        val input = EvaluationInput(
            target,
            gate(tier = Tier.DELAY, escalation = true),
            recentSessions = listOf(endedSession(endedAgo = window.plusMinutes(1))),
        )

        assertThat(engine.evaluate(input)).isEqualTo(GateDecision.Challenge(Tier.DELAY))
    }
}
