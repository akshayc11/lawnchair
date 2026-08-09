package app.lawnchair.appgate

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.DenyReason
import com.akshayc.appgate.core.model.FrictionChallenge
import com.akshayc.appgate.core.model.GateDecision
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.Tier
import com.android.launcher3.R
import java.time.Duration

/**
 * Presents the Gate for one Target and, only if it is passed, opens the app.
 *
 * A full-screen Activity rather than an overlay: it is the robust path
 * (CLAUDE.md, Overlays) and it works the same way if a detection layer ever
 * raises the Gate from outside the launcher. Leaving — back, home, "Not now" —
 * finishes without launching, which returns the user to the launcher.
 */
class GateChallengeActivity : ComponentActivity() {

    private val appGate by lazy { AppGate.getInstance(this) }

    private lateinit var target: Target
    private lateinit var user: UserHandle
    private var component: ComponentName? = null
    private var appLabel: String = ""
    private var friction: FrictionChallenge? = null
    private var allowanceResetHour: Int = DAILY_RESET_HOUR

    private val credentialResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            showChallenge(pendingTier ?: Tier.NUDGE)
        } else {
            finish()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingTier: Tier? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val userHandle = IntentCompat.getUser(intent)
        if (packageName == null || userHandle == null) {
            finish()
            return
        }
        user = userHandle
        target = appGate.targetFor(packageName, userHandle)
        component = IntentCompat.getComponent(intent)
        appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: packageName

        val gate = appGate.gateFor(packageName, userHandle)
        friction = gate?.config?.frictionChallenge
        allowanceResetHour = (gate?.config?.budget as? Budget.DailyTime)?.resetHour ?: DAILY_RESET_HOUR

        askForNotificationPermissionIfNeeded()

        when (val decision = appGate.evaluate(packageName, userHandle)) {
            is GateDecision.Allow -> {
                launchTarget()
                finish()
            }
            is GateDecision.Deny -> showDenied(decision.reason)
            is GateDecision.Challenge -> {
                pendingTier = decision.tier
                if (gate?.config?.authChallenge != null) {
                    requestDeviceCredential(decision.tier)
                } else {
                    showChallenge(decision.tier)
                }
            }
        }
    }

    /**
     * The one-minute session warning needs this on API 33+. Asked for once, and
     * the answer changes nothing about gating itself — a refused prompt only
     * means no warning.
     */
    private fun askForNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !GateNotifications.canPostNotifications(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Auth challenge. Device credential only for now — a biometric prompt needs
     * the `USE_BIOMETRIC` permission, which has not been agreed yet. Degrades
     * to the friction challenge when the device has no secure lock: never fail
     * closed (safety invariant 2).
     */
    private fun requestDeviceCredential(tier: Tier) {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val credentialIntent = keyguardManager?.createConfirmDeviceCredentialIntent(
            getString(R.string.appgate_auth_title),
            getString(R.string.appgate_auth_description, appLabel),
        )
        if (credentialIntent == null) {
            showChallenge(tier)
            return
        }
        runCatching { credentialResult.launch(credentialIntent) }
            .onFailure { showChallenge(tier) }
    }

    private fun showChallenge(tier: Tier) {
        setContent {
            GateScreenTheme {
                GateChallengeScreen(
                    appLabel = appLabel,
                    tier = tier,
                    friction = friction,
                    onPass = ::onChallengePassed,
                    onCancel = ::finish,
                )
            }
        }
    }

    private fun showDenied(reason: DenyReason) {
        setContent {
            GateScreenTheme {
                GateDeniedScreen(
                    appLabel = appLabel,
                    reason = reason,
                    resetHour = allowanceResetHour,
                    onClose = ::finish,
                )
            }
        }
    }

    /**
     * [LawnchairTheme] only supplies the Material colours — it draws no
     * background, and the activity's window background is a fixed light colour
     * whatever the colour scheme is. Without an explicit Surface, a dark scheme
     * renders near-white text onto that light window and the copy disappears.
     */
    @Composable
    private fun GateScreenTheme(content: @Composable () -> Unit) {
        LawnchairTheme {
            EdgeToEdge()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                content = content,
            )
        }
    }

    private fun onChallengePassed(sessionMinutes: Int, intentText: String?) {
        val session = appGate.startSession(
            target = target,
            length = Duration.ofMinutes(sessionMinutes.toLong()),
            intentText = intentText,
        )
        GateNotifications.scheduleSessionWarning(
            context = applicationContext,
            target = target,
            appLabel = appLabel,
            endsAt = session.endsAt,
        )
        launchTarget()
        finish()
    }

    private fun launchTarget() {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val component = component
        val fallback = runCatching {
            launcherApps.getActivityList(target.packageName, user).firstOrNull()?.componentName
        }.getOrNull()
        val toLaunch = component ?: fallback ?: return
        runCatching { launcherApps.startMainActivity(toLaunch, user, null, null) }
            .recoverCatching {
                if (fallback != null && fallback != toLaunch) {
                    launcherApps.startMainActivity(fallback, user, null, null)
                } else {
                    throw it
                }
            }
    }

    private object IntentCompat {
        fun getUser(intent: Intent): UserHandle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_USER, UserHandle::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_USER)
            }

        fun getComponent(intent: Intent): ComponentName? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_COMPONENT)
            }
    }

    companion object {
        private const val EXTRA_PACKAGE = "app.lawnchair.appgate.PACKAGE"
        private const val EXTRA_USER = "app.lawnchair.appgate.USER"
        private const val EXTRA_COMPONENT = "app.lawnchair.appgate.COMPONENT"
        private const val EXTRA_APP_LABEL = "app.lawnchair.appgate.APP_LABEL"

        @JvmStatic
        fun createIntent(
            context: Context,
            packageName: String,
            user: UserHandle,
            component: ComponentName?,
            appLabel: CharSequence?,
        ): Intent = Intent(context, GateChallengeActivity::class.java)
            .putExtra(EXTRA_PACKAGE, packageName)
            .putExtra(EXTRA_USER, user)
            .putExtra(EXTRA_COMPONENT, component)
            .putExtra(EXTRA_APP_LABEL, appLabel?.toString())
    }
}
