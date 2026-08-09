package app.lawnchair.appgate

import android.content.Intent
import com.akshayc.appgate.core.model.GateDecision
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.model.data.ItemInfo

/**
 * Interception on the launcher's own launch path.
 *
 * Holding ROLE_HOME means every icon tap goes through
 * `Launcher.startActivitySafely`, so a Gate can be raised there with no
 * accessibility service and no extra permission. It only covers what the
 * launcher itself starts: opening the same app from recents, a notification, a
 * deep link or a widget does not pass through here.
 *
 * Returns the Intent for the Gate screen, or null when the launch should
 * proceed untouched — either the app is not gated, or the policy engine
 * allowed it (active Session, grace window, protected package, or a
 * fail-open error).
 */
fun Launcher.gateChallengeIntentOrNull(item: ItemInfo?): Intent? {
    if (item == null || item.itemType != ITEM_TYPE_APPLICATION) return null
    val component = item.targetComponent ?: return null
    val user = item.user ?: return null

    val appGate = AppGate.getInstance(this)
    if (!appGate.isGated(component.packageName, user)) return null

    return when (appGate.evaluate(component.packageName, user)) {
        is GateDecision.Allow -> null
        is GateDecision.Challenge,
        is GateDecision.Deny,
        -> GateChallengeActivity.createIntent(
            context = this,
            packageName = component.packageName,
            user = user,
            component = component,
            appLabel = item.title,
        )
    }
}
