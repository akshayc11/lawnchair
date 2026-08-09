package app.lawnchair.appgate

import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.dp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.views.ComposeBottomSheet
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.R
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.popup.SystemShortcut

/**
 * "Gate" item in an app icon's long-press menu, which opens that app's Gate
 * configuration.
 */
object GateShortcut {

    val GATE = SystemShortcut.Factory { launcher: LawnchairLauncher, itemInfo: ItemInfo, originalView: View ->
        val packageName = itemInfo.targetComponent?.packageName
        when {
            itemInfo.itemType != ITEM_TYPE_APPLICATION -> null
            packageName == null -> null
            // Safety invariant 1: the denylist is enforced here, not by hiding
            // the entry — dialer, emergency, system UI, Settings, and home are
            // never gateable.
            !AppGate.getInstance(launcher).isGateable(packageName) -> null
            else -> GateShortcutItem(launcher, itemInfo, originalView)
        }
    }

    class GateShortcutItem(
        private val launcher: LawnchairLauncher,
        itemInfo: ItemInfo,
        originalView: View,
    ) : SystemShortcut<LawnchairLauncher>(
        R.drawable.ic_lock,
        R.string.appgate_shortcut_label,
        launcher,
        itemInfo,
        originalView,
    ) {
        override fun onClick(v: View) {
            val packageName = mItemInfo.targetComponent?.packageName ?: return
            val target = AppGate.getInstance(launcher).targetFor(packageName, mItemInfo.user)
            val label = mItemInfo.title?.toString().orEmpty()

            AbstractFloatingView.closeAllOpenViews(launcher)
            ComposeBottomSheet.show(
                context = launcher,
                contentPaddings = PaddingValues(bottom = 64.dp),
            ) {
                val scrollState = rememberScrollState()
                // Hand the sheet the content's scroll position: a drag that
                // scrolls the form back up belongs to the form, and only a drag
                // that starts at the top should close the sheet.
                SideEffect { canContentScrollUp = { scrollState.value > 0 } }
                GateConfigSheet(
                    target = target,
                    appLabel = label,
                    scrollState = scrollState,
                    onClose = { close(true) },
                )
            }
        }
    }
}
