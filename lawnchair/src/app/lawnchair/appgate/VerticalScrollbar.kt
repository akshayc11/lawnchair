package app.lawnchair.appgate

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a scrollbar thumb along the right edge, but only when the content is
 * actually taller than the viewport — a scrollable sheet that fits shows
 * nothing. The thumb fades in while scrolling and fades back out when idle.
 *
 * Compose has no scrollbar for [androidx.compose.foundation.verticalScroll], so
 * this is drawn as an overlay rather than taking part in layout; it never
 * shifts the content it sits over.
 */
fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    width: Dp = 4.dp,
    color: Color = Color.Gray,
    minThumbHeight: Dp = 24.dp,
): Modifier = composed {
    val hasOverflow = scrollState.maxValue > 0 && scrollState.maxValue != Int.MAX_VALUE
    val targetAlpha = if (scrollState.isScrollInProgress) 0.6f else if (hasOverflow) 0.28f else 0f
    val alpha by animateFloatAsState(targetValue = targetAlpha, label = "scrollbarAlpha")

    drawWithContent {
        drawContent()
        if (!hasOverflow || alpha <= 0f) return@drawWithContent

        val viewportHeight = size.height
        val contentHeight = viewportHeight + scrollState.maxValue

        val rawThumbHeight = viewportHeight * (viewportHeight / contentHeight)
        val thumbHeight = rawThumbHeight.coerceAtLeast(minThumbHeight.toPx())

        val scrollFraction = scrollState.value.toFloat() / scrollState.maxValue
        val thumbTop = (viewportHeight - thumbHeight) * scrollFraction

        val thumbWidth = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(x = size.width - thumbWidth, y = thumbTop),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f),
            alpha = alpha,
        )
    }
}
