package app.lawnchair.appgate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.lawnchair.ui.theme.EdgeToEdge
import app.lawnchair.ui.theme.LawnchairTheme
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.UserId
import com.android.launcher3.R
import kotlinx.coroutines.launch

/**
 * The screen that says a Session is over, drawn over the app the user is still
 * in — session end has to be felt, not filed away in a notification (CLAUDE.md).
 *
 * It is not a lock: every way out of it goes home, which is the whole
 * enforcement. AppGate cannot stop another app's processes without privileged
 * permissions it will never hold, so "the app stops" means the user is put back
 * on the launcher and coming back has to pass the Gate again.
 */
class SessionExpiredActivity : ComponentActivity() {

    private lateinit var target: Target
    private var appLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val userId = intent.getLongExtra(EXTRA_USER_ID, -1L)
        if (packageName == null || userId < 0) {
            finish()
            return
        }
        target = Target(packageName = packageName, user = UserId(userId))
        appLabel = intent.getStringExtra(EXTRA_APP_LABEL) ?: packageName
        val intentText = intent.getStringExtra(EXTRA_INTENT_TEXT)
        val wrapUpAvailable = intent.getBooleanExtra(EXTRA_WRAP_UP_AVAILABLE, false)

        // Back would otherwise drop the user straight back into the app this is
        // drawn over, which is exactly what the screen exists to end.
        onBackPressedDispatcher.addCallback(this) { goHome() }

        setContent {
            LawnchairTheme {
                EdgeToEdge()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    SessionExpiredScreen(
                        appLabel = appLabel,
                        intentText = intentText,
                        wrapUpAvailable = wrapUpAvailable,
                        onWrapUp = ::wrapUp,
                        onDone = ::goHome,
                    )
                }
            }
        }
    }

    /**
     * One extra minute, once, so the user is not cut off mid-reply. It is
     * persisted as an extension of the Session itself, so it counts against the
     * daily allowance and the watchdog comes back for it.
     */
    private fun wrapUp() {
        lifecycleScope.launch {
            val extended = AppGate.getInstance(this@SessionExpiredActivity).wrapUpSession(target)
            // Finishing returns to the app underneath, which is where the user
            // was. If the extension was already spent, there is nothing to go
            // back to.
            if (extended) finish() else goHome()
        }
    }

    private fun goHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "app.lawnchair.appgate.PACKAGE"
        private const val EXTRA_USER_ID = "app.lawnchair.appgate.USER_ID"
        private const val EXTRA_APP_LABEL = "app.lawnchair.appgate.APP_LABEL"
        private const val EXTRA_INTENT_TEXT = "app.lawnchair.appgate.INTENT_TEXT"
        private const val EXTRA_WRAP_UP_AVAILABLE = "app.lawnchair.appgate.WRAP_UP_AVAILABLE"

        fun createIntent(
            context: Context,
            target: Target,
            appLabel: String,
            intentText: String?,
            wrapUpAvailable: Boolean,
        ): Intent = Intent(context.applicationContext, SessionExpiredActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_PACKAGE, target.packageName)
            .putExtra(EXTRA_USER_ID, target.user.value)
            .putExtra(EXTRA_APP_LABEL, appLabel)
            .putExtra(EXTRA_INTENT_TEXT, intentText)
            .putExtra(EXTRA_WRAP_UP_AVAILABLE, wrapUpAvailable)
    }
}

@Composable
private fun SessionExpiredScreen(
    appLabel: String,
    intentText: String?,
    wrapUpAvailable: Boolean,
    onWrapUp: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.appgate_session_over_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.appgate_session_over_body, appLabel),
            style = MaterialTheme.typography.bodyLarge,
        )
        // The user's own words are the friction that works, so they are quoted
        // back plainly, with no comment on how they did.
        if (!intentText.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.appgate_session_over_intent, appLabel, intentText),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.appgate_session_over_done))
        }
        if (wrapUpAvailable) {
            TextButton(onClick = onWrapUp, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.appgate_session_over_wrap_up))
            }
        }
    }
}
