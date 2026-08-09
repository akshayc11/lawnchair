package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Tier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GateBadgeContentTest {

    @Test
    fun `every tier has its own colour and locked is the darkest`() {
        assertThat(TIER_COLORS.keys).containsExactlyElementsIn(Tier.entries)
        val locked = TIER_COLORS.getValue(Tier.LOCKED)
        val commitment = TIER_COLORS.getValue(Tier.COMMITMENT)
        assertThat(luminance(locked)).isLessThan(luminance(commitment))
    }

    @Test
    fun `colour warms as the tier rises`() {
        // Green at the bottom, red at the top: across the friction tiers the
        // green channel loses ground to the red one step at a time. LOCKED sits
        // outside the ramp — it is darker rather than redder, covered above.
        val greenLead = Tier.entries
            .filter { it != Tier.LOCKED }
            .map { green(TIER_COLORS.getValue(it)) - red(TIER_COLORS.getValue(it)) }
        greenLead.zipWithNext { warmer, warmest -> assertThat(warmest).isLessThan(warmer) }
        assertThat(greenLead.first()).isGreaterThan(0)
        assertThat(greenLead.last()).isLessThan(0)

        // No two tiers look alike.
        assertThat(TIER_COLORS.values.toSet()).hasSize(Tier.entries.size)
    }

    @Test
    fun `shows the padlock when the app has no allowance`() {
        val content = gateBadgeContentFor(Tier.DELAY, allowanceMinutesLeft = null)

        assertThat(content).isEqualTo(GateBadgeContent.Padlock(TIER_COLORS.getValue(Tier.DELAY)))
    }

    @Test
    fun `shows the minutes left of an allowance`() {
        val content = gateBadgeContentFor(Tier.EFFORT, allowanceMinutesLeft = 12)

        assertThat(content).isEqualTo(GateBadgeContent.Minutes(12, TIER_COLORS.getValue(Tier.EFFORT)))
    }

    @Test
    fun `shows the padlock rather than a zero when the allowance is spent`() {
        assertThat(gateBadgeContentFor(Tier.DELAY, allowanceMinutesLeft = 0))
            .isInstanceOf(GateBadgeContent.Padlock::class.java)
    }

    @Test
    fun `shows the padlock when more minutes are left than fit in the badge`() {
        assertThat(gateBadgeContentFor(Tier.DELAY, MAX_SHOWN_MINUTES))
            .isEqualTo(GateBadgeContent.Minutes(MAX_SHOWN_MINUTES, TIER_COLORS.getValue(Tier.DELAY)))
        assertThat(gateBadgeContentFor(Tier.DELAY, MAX_SHOWN_MINUTES + 1))
            .isInstanceOf(GateBadgeContent.Padlock::class.java)
    }

    @Test
    fun `shows the padlock for a locked app whatever its allowance says`() {
        val content = gateBadgeContentFor(Tier.LOCKED, allowanceMinutesLeft = 30)

        assertThat(content).isEqualTo(GateBadgeContent.Padlock(TIER_COLORS.getValue(Tier.LOCKED)))
    }

    private fun red(color: Int) = (color shr 16) and 0xFF

    private fun green(color: Int) = (color shr 8) and 0xFF

    private fun luminance(color: Int) = red(color) + green(color) + (color and 0xFF)
}
