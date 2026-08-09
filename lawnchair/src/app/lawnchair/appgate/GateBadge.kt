package app.lawnchair.appgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.akshayc.appgate.core.model.Tier
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo

/**
 * Draws the "this app is gated" indicator on an app icon.
 *
 * Visual only — it reports state, it is not a tap target. Configuring a Gate
 * happens through the Gate item in the icon's long-press menu.
 *
 * Colour carries the severity, warming as the friction rises: green at
 * [Tier.NUDGE], through pale yellow and orange, to red at [Tier.COMMITMENT] —
 * the last tier before the app stops opening. [Tier.LOCKED] goes darker red
 * still and greys the whole icon out on top, so the state reads from across
 * the screen and not just from the badge.
 *
 * A gate with a daily allowance shows the minutes it has left instead of the
 * padlock — the number is the useful thing to know at a glance, and the padlock
 * says nothing the icon's own presence does not. Once the allowance is spent
 * the gate behaves as LOCKED and the red padlock returns.
 *
 * Deliberately drawn in the top-left so it never collides with the
 * notification dot (top-right) or the taskbar running indicator (bottom).
 */
object GateBadge {

    private const val BADGE_SIZE_FRACTION = 0.36f
    private const val GLYPH_INSET_FRACTION = 0.22f
    private const val TEXT_SIZE_FRACTION = 0.62f

    /**
     * Above this a number stops fitting in the badge, and the exact figure
     * stops mattering: the padlock is drawn instead.
     */
    private const val MAX_SHOWN_MINUTES = 99

    /**
     * One colour per Tier, green through red. Shades are picked to stay legible
     * on the badge's white circle, so "pale yellow" is a lime that reads pale
     * rather than a true pastel, which would all but vanish.
     */
    private val TIER_COLORS = mapOf(
        Tier.NUDGE to Color.parseColor("#2E7D32"),
        Tier.DELAY to Color.parseColor("#C0CA33"),
        Tier.EFFORT to Color.parseColor("#EF6C00"),
        Tier.COMMITMENT to Color.parseColor("#D32F2F"),
        // Darker than the red above it, so locked is not mistaken for the tier
        // below at a glance; the greyed icon is the other half of that signal.
        Tier.LOCKED to Color.parseColor("#8B0000"),
    )
    private val DEFAULT_COLOR = Color.parseColor("#F9A825")

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    private var lockDrawable: Drawable? = null

    /**
     * Called from `BubbleTextView.onDraw`. Returns without drawing when the app
     * is not gated, which is the common case, so this stays cheap: the lookup
     * is against an in-memory snapshot held by [AppGate], never the database.
     */
    @JvmStatic
    fun draw(
        canvas: Canvas,
        context: Context,
        itemInfo: ItemInfo?,
        iconBounds: Rect,
        scrollX: Int,
        scrollY: Int,
    ) {
        val packageName = itemInfo?.targetComponent?.packageName ?: return
        val user = itemInfo.user ?: return
        val badge = AppGate.getInstance(context).badgeFor(packageName, user) ?: return

        val diameter = (iconBounds.width() * BADGE_SIZE_FRACTION).toInt()
        if (diameter <= 0) return

        val radius = diameter / 2f
        val centerX = iconBounds.left + radius
        val centerY = iconBounds.top + radius

        // Minutes left only while there are some, and only while they fit: a
        // spent allowance is LOCKED, and a red "0" would read as a countdown
        // that is still running.
        val minutesLeft = badge.allowanceMinutesLeft
            ?.takeIf { it in 1..MAX_SHOWN_MINUTES && badge.tier != Tier.LOCKED }
        val color = TIER_COLORS[badge.tier] ?: DEFAULT_COLOR

        val glyph = if (minutesLeft == null) lockGlyph(context) ?: return else null

        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        if (minutesLeft != null) {
            val text = minutesLeft.toString()
            textPaint.color = color
            textPaint.textSize = diameter * TEXT_SIZE_FRACTION
            // Centre on the glyph box rather than the baseline, so one and two
            // digits sit the same way inside the circle.
            textPaint.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(text, centerX, centerY + bounds.height() / 2f, textPaint)
        } else if (glyph != null) {
            DrawableCompat.setTint(glyph, color)
            val inset = (diameter * GLYPH_INSET_FRACTION).toInt()
            bounds.set(
                iconBounds.left + inset,
                iconBounds.top + inset,
                iconBounds.left + diameter - inset,
                iconBounds.top + diameter - inset,
            )
            glyph.bounds = bounds
            glyph.draw(canvas)
        }
        canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
    }

    /**
     * Whether this app is gated as locked right now — either configured that
     * way, or a daily allowance that has run out, which behaves the same. The
     * icon is greyed out while it is true.
     *
     * Resolved exactly as [draw] resolves its badge, so what is greyed and what
     * is badged can never disagree.
     */
    @JvmStatic
    fun isLockedOut(context: Context, itemInfo: ItemInfo?): Boolean {
        val packageName = itemInfo?.targetComponent?.packageName ?: return false
        val user = itemInfo.user ?: return false
        return AppGate.getInstance(context).badgeFor(packageName, user)?.tier == Tier.LOCKED
    }

    private fun lockGlyph(context: Context): Drawable? =
        lockDrawable ?: ContextCompat.getDrawable(context, R.drawable.ic_lock)
            ?.mutate()
            ?.also { lockDrawable = it }
}
