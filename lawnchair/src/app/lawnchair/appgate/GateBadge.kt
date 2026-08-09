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
 * Colour carries the severity: [Tier.LOCKED] is red, every friction tier is
 * amber. A gate with a daily allowance shows the minutes it has left instead
 * of the padlock — the number is the useful thing to know at a glance, and the
 * padlock says nothing the icon's own presence does not. Once the allowance is
 * spent the gate behaves as LOCKED and the red padlock returns.
 *
 * Deliberately drawn in the top-left so it never collides with the
 * notification dot (top-right) or the taskbar running indicator (bottom).
 */
object GateBadge {

    private const val BADGE_SIZE_FRACTION = 0.36f
    private const val GLYPH_INSET_FRACTION = 0.22f
    private const val TEXT_SIZE_FRACTION = 0.62f

    /** Above this the number stops fitting; the exact figure stops mattering too. */
    private const val MAX_SHOWN_MINUTES = 99

    private val LOCKED_COLOR = Color.parseColor("#D32F2F")
    private val FRICTION_COLOR = Color.parseColor("#F9A825")

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FRICTION_COLOR
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

        // Minutes left only while there are some: a spent allowance is LOCKED,
        // and a red "0" would read as a countdown that is still running.
        val minutesLeft = badge.allowanceMinutesLeft?.takeIf { it > 0 && badge.tier != Tier.LOCKED }
        val color = if (badge.tier == Tier.LOCKED) LOCKED_COLOR else FRICTION_COLOR

        val glyph = if (minutesLeft == null) lockGlyph(context) ?: return else null

        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        if (minutesLeft != null) {
            val text = minutesLeft.coerceAtMost(MAX_SHOWN_MINUTES).toString()
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

    private fun lockGlyph(context: Context): Drawable? =
        lockDrawable ?: ContextCompat.getDrawable(context, R.drawable.ic_lock)
            ?.mutate()
            ?.also { lockDrawable = it }
}
