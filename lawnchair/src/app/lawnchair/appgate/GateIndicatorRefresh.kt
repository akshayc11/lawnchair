package app.lawnchair.appgate

import android.view.View
import android.view.ViewGroup
import com.android.launcher3.BubbleTextView
import com.android.launcher3.Launcher

/**
 * Repaints every app icon after the set of gated apps changes.
 *
 * `BubbleTextView` reads its gated state during `onDraw`, so a Gate that was
 * just saved or removed only becomes visible once the affected views are
 * invalidated. Invalidating the container alone is not enough — each icon is
 * its own view — so the tree is walked.
 */
fun Launcher.invalidateGateIndicators() {
    dragLayer?.invalidateIconsRecursively()
}

private fun View.invalidateIconsRecursively() {
    if (this is BubbleTextView) {
        invalidate()
        return
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).invalidateIconsRecursively()
        }
    }
}
