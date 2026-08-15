package app.lawnchair.appgate

import com.akshayc.appgate.core.model.Tier

/**
 * What the gate indicator says, decided apart from how it is drawn.
 *
 * Pure Kotlin — no `android.graphics` — so the rules can be unit tested; the
 * colours are plain ARGB ints for the same reason.
 */
sealed interface GateBadgeContent {

    val color: Int

    /** Minutes left of a daily allowance, small enough to fit in the badge. */
    data class Minutes(val minutes: Int, override val color: Int) : GateBadgeContent

    /** The padlock: no allowance, more minutes than fit, or locked outright. */
    data class Padlock(override val color: Int) : GateBadgeContent
}

/**
 * Above this a number stops fitting in the badge, and the exact figure stops
 * mattering: the padlock is shown instead.
 */
const val MAX_SHOWN_MINUTES = 99

/**
 * One colour per Tier, warming as the friction rises: green at [Tier.NUDGE],
 * through pale yellow and orange, to red at [Tier.COMMITMENT] — the last tier
 * before the app stops opening — and darker red at [Tier.LOCKED], whose icon
 * is greyed out as well.
 *
 * Shades are picked to stay legible on the badge's white circle, so the pale
 * yellow is a lime rather than a true pastel, which would all but vanish.
 */
val TIER_COLORS: Map<Tier, Int> = mapOf(
    Tier.NUDGE to 0xFF2E7D32.toInt(),
    Tier.DELAY to 0xFFC0CA33.toInt(),
    Tier.EFFORT to 0xFFEF6C00.toInt(),
    Tier.COMMITMENT to 0xFFD32F2F.toInt(),
    Tier.LOCKED to 0xFF8B0000.toInt(),
)

private val DEFAULT_COLOR = 0xFFF9A825.toInt()

/**
 * The indicator for a gated app.
 *
 * Minutes are shown only while there are some and only while they fit: a spent
 * allowance is [Tier.LOCKED], and a "0" would read as a countdown that is still
 * running.
 */
fun gateBadgeContentFor(
    tier: Tier,
    allowanceMinutesLeft: Int?,
): GateBadgeContent {
    val color = TIER_COLORS[tier] ?: DEFAULT_COLOR
    val minutes = allowanceMinutesLeft?.takeIf { it in 1..MAX_SHOWN_MINUTES && tier != Tier.LOCKED }
    return if (minutes != null) GateBadgeContent.Minutes(minutes, color) else GateBadgeContent.Padlock(color)
}
