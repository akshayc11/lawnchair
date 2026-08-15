package app.lawnchair.appgate

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import java.time.Instant

/**
 * The alarm that fires when a Session runs out.
 *
 * The launcher watches its own sessions while it is alive; this exists for when
 * it is not — the process is killed while the user is inside the gated app, and
 * nothing in memory is left to notice the session ending. It is an inexact
 * alarm for the same reason the warning is: an exact one needs
 * `SCHEDULE_EXACT_ALARM`, and the phone is not dozing while the user is
 * actively using the app.
 */
object SessionExpiryAlarm {

    fun schedule(context: Context, target: Target, endsAt: Instant) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = pendingIntent(context, target)
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endsAt.toEpochMilli(),
                pendingIntent,
            )
        }
    }

    fun cancel(context: Context, target: Target) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context, target))
    }

    private fun pendingIntent(context: Context, target: Target): PendingIntent {
        val intent = Intent(context.applicationContext, SessionExpiryReceiver::class.java).apply {
            putExtra(SessionExpiryReceiver.EXTRA_PACKAGE, target.packageName)
            putExtra(SessionExpiryReceiver.EXTRA_USER_ID, target.user.value)
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            requestCode(target),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Stable per Target and distinct from the warning's request code, so the
     * two alarms for the same Session never replace one another.
     */
    private fun requestCode(target: Target): Int =
        ("expiry:" + target.packageName + "@" + target.user.value).hashCode()
}

/** Enforces an expiry the launcher process was not alive to see. Registered `exported="false"`. */
class SessionExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val userId = intent.getLongExtra(EXTRA_USER_ID, -1L)
        if (userId < 0) return
        // This is the cold-start path by definition: the database may still be
        // opening, so the broadcast is held rather than answered from an empty
        // snapshot and dropped.
        val pendingResult = goAsync()
        AppGate.getInstance(context).enforceExpiry(
            target = Target(packageName = packageName, user = UserId(userId)),
            onDone = { runCatching { pendingResult.finish() } },
        )
    }

    companion object {
        const val EXTRA_PACKAGE = "app.lawnchair.appgate.PACKAGE"
        const val EXTRA_USER_ID = "app.lawnchair.appgate.USER_ID"
    }
}

/**
 * Which app is in front, when the user has granted usage access.
 *
 * Used only to avoid taking the screen over when the user has already left the
 * gated app. Nothing is stored and nothing is logged — the one package name
 * read here is compared and dropped (Privacy, CLAUDE.md).
 */
internal object ForegroundApp {

    /** The foreground package, or null when it cannot be known. */
    fun current(context: Context): String? {
        if (!hasUsageAccess(context)) return null
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching {
            usageStatsManager.queryEvents(now - LOOKBACK_MILLIS, now + 1_000)
        }.getOrNull() ?: return null

        var packageName: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                packageName = event.packageName
            }
        }
        return packageName
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    private const val LOOKBACK_MILLIS = 60_000L
}
