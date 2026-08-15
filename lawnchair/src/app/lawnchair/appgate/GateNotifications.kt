package app.lawnchair.appgate

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import com.android.launcher3.R
import java.time.Duration
import java.time.Instant

/**
 * The one-minute warning before a Session runs out.
 *
 * Without a detection layer AppGate cannot take the screen over when a Session
 * ends, so this is the honest substitute: a heads-up while the user is still
 * inside the app, carrying the time left. It is scheduled with an inexact
 * alarm on purpose — exact alarms need `SCHEDULE_EXACT_ALARM`, and the phone
 * is not dozing while the user is actively using the app anyway.
 *
 * Privacy: the app's own label is in the notification because the user asked
 * for it, but the notification is `VISIBILITY_PRIVATE` so it is not spelled
 * out on a locked screen, and nothing is written to logs.
 */
object GateNotifications {

    private const val CHANNEL_ID = "appgate_session"
    private val WARNING_BEFORE_END: Duration = Duration.ofMinutes(1)

    fun scheduleSessionWarning(
        context: Context,
        target: Target,
        appLabel: String,
        endsAt: Instant,
    ) {
        val triggerAt = endsAt.minus(WARNING_BEFORE_END)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = warningPendingIntent(context, target, appLabel, endsAt)
        if (!triggerAt.isAfter(Instant.now())) {
            // Session shorter than the warning window: nothing useful to warn about.
            alarmManager.cancel(pendingIntent)
            return
        }
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt.toEpochMilli(),
                pendingIntent,
            )
        }
    }

    /** Drops a warning that is no longer coming — the Session it belonged to ended. */
    fun cancelSessionWarning(context: Context, target: Target) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(warningPendingIntent(context, target, appLabel = "", endsAt = Instant.EPOCH))
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(target))
    }

    private fun warningPendingIntent(
        context: Context,
        target: Target,
        appLabel: String,
        endsAt: Instant,
    ): PendingIntent {
        val intent = Intent(context, SessionWarningReceiver::class.java).apply {
            putExtra(SessionWarningReceiver.EXTRA_APP_LABEL, appLabel)
            putExtra(SessionWarningReceiver.EXTRA_ENDS_AT, endsAt.toEpochMilli())
            putExtra(SessionWarningReceiver.EXTRA_NOTIFICATION_ID, notificationId(target))
            putExtra(SessionWarningReceiver.EXTRA_PACKAGE, target.packageName)
            putExtra(SessionWarningReceiver.EXTRA_USER_ID, target.user.value)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(target),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable per Target, so a new Session replaces the previous warning. */
    private fun notificationId(target: Target): Int = (target.packageName + "@" + target.user.value).hashCode()

    internal fun notifySessionEndingSoon(
        context: Context,
        notificationId: Int,
        appLabel: String,
        minutesLeft: Int,
    ) {
        if (!canPostNotifications(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager, context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(context.getString(R.string.appgate_session_ending_title, minutesLeft))
            .setContentText(context.getString(R.string.appgate_session_ending_text, appLabel, minutesLeft))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setTimeoutAfter(WARNING_BEFORE_END.toMillis() * 2)
            .build()

        runCatching { manager.notify(notificationId, notification) }
    }

    private fun ensureChannel(manager: NotificationManager, context: Context) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.appgate_session_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.appgate_session_channel_description)
                setShowBadge(false)
            },
        )
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

/** Posts the warning the alarm was set for. Registered `exported="false"`. */
class SessionWarningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: return
        val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        val millisLeft = endsAt - System.currentTimeMillis()
        if (millisLeft <= 0) return

        // The Session may already be over — leaving the app ends it — in which
        // case there is nothing left to warn about.
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val userId = intent.getLongExtra(EXTRA_USER_ID, -1L)
        if (packageName != null && userId >= 0) {
            val target = Target(packageName = packageName, user = UserId(userId))
            if (!AppGate.getInstance(context).hasActiveSession(target)) return
        }

        val minutesLeft = Math.ceil(millisLeft / 60_000.0).toInt().coerceAtLeast(1)

        GateNotifications.notifySessionEndingSoon(
            context = context,
            notificationId = notificationId,
            appLabel = appLabel,
            minutesLeft = minutesLeft,
        )
    }

    companion object {
        const val EXTRA_APP_LABEL = "app.lawnchair.appgate.APP_LABEL"
        const val EXTRA_ENDS_AT = "app.lawnchair.appgate.ENDS_AT"
        const val EXTRA_NOTIFICATION_ID = "app.lawnchair.appgate.NOTIFICATION_ID"
        const val EXTRA_PACKAGE = "app.lawnchair.appgate.PACKAGE"
        const val EXTRA_USER_ID = "app.lawnchair.appgate.USER_ID"
    }
}
