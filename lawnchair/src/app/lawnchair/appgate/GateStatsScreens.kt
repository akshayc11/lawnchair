package app.lawnchair.appgate

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.navigation.AppGateAppStats
import app.lawnchair.ui.preferences.LocalNavController
import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.Tier
import com.akshayc.appgate.core.model.UserId
import com.android.launcher3.R
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every configured Gate, with how long has gone into that app today.
 *
 * Tapping one opens its own screen of daily figures. Neutral throughout: time
 * and counts, no grading (CLAUDE.md — guilt copy teaches people to avoid the
 * stats screen, and then the app).
 */
@Composable
fun AppGateStatsPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appGate = remember { AppGate.getInstance(context) }
    val navController = LocalNavController.current
    val gates by appGate.gates.collectAsStateWithLifecycle()

    PreferenceScaffold(
        label = stringResource(R.string.appgate_stats_title),
        isExpandedScreen = LocalIsExpandedScreen.current,
        modifier = modifier,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            if (gates.isEmpty()) {
                item {
                    PreferenceGroup {
                        PreferenceTemplate(
                            title = { Text(stringResource(R.string.appgate_stats_none)) },
                            description = { Text(stringResource(R.string.appgate_stats_none_body)) },
                        )
                    }
                }
                return@PreferenceLazyColumn
            }
            preferenceGroupItems(
                items = gates,
                isFirstChild = true,
                key = { _, gate -> gate.target.packageName + "@" + gate.target.user.value },
            ) { _, gate ->
                GateStatsRow(
                    gate = gate,
                    onClick = {
                        navController.navigate(
                            AppGateAppStats(
                                packageName = gate.target.packageName,
                                userId = gate.target.user.value,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun GateStatsRow(
    gate: Gate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appGate = remember { AppGate.getInstance(context) }
    val usage = rememberTargetUsage(gate.target, resetHourOf(gate))
    val icon by rememberAppIcon(gate.target)
    val label = remember(gate.target) { appGate.labelFor(gate.target) }

    PreferenceTemplate(
        modifier = modifier,
        onClick = onClick,
        title = { Text(text = label) },
        description = {
            Text(
                text = stringResource(
                    R.string.appgate_stats_row_summary,
                    formatDuration(context, usage.today),
                    usage.todaySessions,
                    tierLabel(gate.config.tier),
                ),
            )
        },
        startWidget = {
            icon?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
    )
}

/**
 * Daily figures for one gated app.
 *
 * The Target is passed as package plus profile id, never a bare package name —
 * the same app in another profile is a different Target.
 */
@Composable
fun AppGateAppStatsPreferences(
    packageName: String,
    userId: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appGate = remember { AppGate.getInstance(context) }
    val navController = LocalNavController.current
    val target = remember(packageName, userId) { Target(packageName = packageName, user = UserId(userId)) }
    val gates by appGate.gates.collectAsStateWithLifecycle()
    val gate = gates.firstOrNull { it.target == target }
    val usage = rememberTargetUsage(target, gate?.let { resetHourOf(it) } ?: DAILY_RESET_HOUR)
    val allowanceMinutes = (gate?.config?.budget as? Budget.DailyTime)?.maxTotal?.toMinutes()?.toInt()
    val label = remember(target) { appGate.labelFor(target) }

    PreferenceScaffold(
        label = label,
        isExpandedScreen = LocalIsExpandedScreen.current,
        modifier = modifier,
    ) { paddingValues ->
        PreferenceLazyColumn(paddingValues) {
            item {
                PreferenceGroup(heading = stringResource(R.string.appgate_stats_week_heading)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        UsageSummary(usage = usage, allowanceMinutes = allowanceMinutes)
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            UsageBarChart(
                                usage = usage,
                                zone = appGate.zone,
                                allowanceMinutes = allowanceMinutes,
                            )
                        }
                        if (allowanceMinutes != null) {
                            Text(
                                modifier = Modifier.padding(top = 12.dp),
                                text = stringResource(
                                    R.string.appgate_stats_allowance_line,
                                    formatDuration(context, Duration.ofMinutes(allowanceMinutes.toLong())),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                PreferenceGroup(heading = stringResource(R.string.appgate_stats_totals_heading)) {
                    PreferenceTemplate(
                        title = { Text(stringResource(R.string.appgate_stats_week_total)) },
                        endWidget = { Text(formatDuration(context, usage.total)) },
                    )
                    PreferenceTemplate(
                        title = { Text(stringResource(R.string.appgate_stats_longest_session)) },
                        endWidget = { Text(formatDuration(context, usage.longestSession)) },
                    )
                    PreferenceTemplate(
                        title = { Text(stringResource(R.string.appgate_stats_week_sessions)) },
                        endWidget = { Text(usage.days.sumOf { it.sessions }.toString()) },
                    )
                }
            }
            item {
                PreferenceGroup(heading = stringResource(R.string.appgate_stats_gate_heading)) {
                    // The same form the long-press sheet uses, so the gate is
                    // editable from here rather than only readable.
                    GateConfigControls(
                        target = target,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        onRemoved = { navController.popBackStack() },
                    )
                }
            }
            item {
                PreferenceGroup {
                    PreferenceTemplate(
                        title = { Text(stringResource(R.string.appgate_stats_window_note)) },
                    )
                }
            }
        }
    }
}

/** The reset hour this gate's day is measured from. */
internal fun resetHourOf(gate: Gate): Int =
    (gate.config.budget as? Budget.DailyTime)?.resetHour ?: DAILY_RESET_HOUR

@Composable
internal fun tierLabel(tier: Tier): String = stringResource(
    when (tier) {
        Tier.NUDGE -> R.string.appgate_tier_nudge
        Tier.DELAY -> R.string.appgate_tier_delay
        Tier.EFFORT -> R.string.appgate_tier_effort
        Tier.COMMITMENT -> R.string.appgate_tier_commitment
        Tier.LOCKED -> R.string.appgate_tier_locked
    },
)

/**
 * The app's icon, or null while it loads and for a Target whose profile cannot
 * be resolved right now — a locked Private Space. The row itself still shows: a
 * gate must not disappear from the screen that manages it.
 */
@Composable
private fun rememberAppIcon(target: Target): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, target) {
        value = withContext(Dispatchers.IO) { loadIcon(context, target) }
    }
}

private fun loadIcon(context: Context, target: Target): Bitmap? {
    val user = AppGate.getInstance(context).userHandleFor(target) ?: return null
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
    val info = runCatching { launcherApps.getActivityList(target.packageName, user).firstOrNull() }
        .getOrNull() ?: return null
    // The activity's own badged icon rather than the launcher's icon cache:
    // the cache wants the model executor, and one settings row is not worth
    // hopping threads for.
    return runCatching {
        launcherApps.getActivityList(target.packageName, user)
        info.getBadgedIcon(0).toBitmap(ICON_PX, ICON_PX)
    }.getOrNull()
}

private const val ICON_PX = 128
