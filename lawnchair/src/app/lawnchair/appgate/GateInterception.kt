package app.lawnchair.appgate

import android.content.Intent
import com.akshayc.appgate.core.model.GateDecision
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemInfo

/**
 * Interception on the launcher's own launch path.
 *
 * Holding ROLE_HOME means every icon tap goes through
 * `Launcher.startActivitySafely`, so a Gate can be raised there with no
 * accessibility service and no extra permission. It only covers what the
 * launcher itself starts: opening the same app from recents, a notification, a
 * deep link or a widget does not pass through here.
 *
 * Shortcuts count as opening the app. A gated app's own shortcuts — Spotify's
 * "Search", a chat app's "Message X" — land the user inside the app just as an
 * icon tap does, so they raise the Gate too and the shortcut itself is what
 * runs once it is passed.
 *
 * Returns the Intent for the Gate screen, or null when the launch should
 * proceed untouched — either the app is not gated, or the policy engine
 * allowed it (active Session, grace window, protected package, or a
 * fail-open error).
 */
fun Launcher.gateChallengeIntentOrNull(item: ItemInfo?, intent: Intent?): Intent? {
    if (item == null) return null
    if (item.itemType !in GATEABLE_ITEM_TYPES) return null
    val packageName = item.targetPackage ?: return null
    val user = item.user ?: return null

    val appGate = AppGate.getInstance(this)
    if (!appGate.isGated(packageName, user)) return null

    return when (appGate.evaluate(packageName, user)) {
        is GateDecision.Allow -> null
        is GateDecision.Challenge,
        is GateDecision.Deny,
        -> GateChallengeActivity.createIntent(
            context = this,
            packageName = packageName,
            user = user,
            component = item.targetComponent,
            appLabel = item.title,
            // A deep shortcut has to be started through LauncherApps by id; a
            // legacy pinned shortcut is just its Intent. Either way the Gate
            // screen carries what it needs to finish the launch the user asked
            // for, rather than dropping them on the app's main screen.
            deepShortcutId = item.deepShortcutIdOrNull(),
            shortcutIntent = intent.takeIf { item.itemType != ITEM_TYPE_APPLICATION },
        )
    }
}

private val GATEABLE_ITEM_TYPES = setOf(ITEM_TYPE_APPLICATION, ITEM_TYPE_SHORTCUT, ITEM_TYPE_DEEP_SHORTCUT)

/**
 * Null for anything that is not a real deep shortcut. A promise shortcut is
 * still restoring and is launched as a plain Intent, exactly as
 * `ActivityContext.startActivitySafely` decides.
 */
private fun ItemInfo.deepShortcutIdOrNull(): String? =
    (this as? WorkspaceItemInfo)
        ?.takeIf { itemType == ITEM_TYPE_DEEP_SHORTCUT && !it.isPromise }
        ?.deepShortcutId
