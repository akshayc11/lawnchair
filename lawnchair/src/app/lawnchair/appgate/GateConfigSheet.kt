package app.lawnchair.appgate

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.akshayc.appgate.core.model.AuthChallenge
import com.akshayc.appgate.core.model.Budget
import com.akshayc.appgate.core.model.FrictionChallenge
import com.akshayc.appgate.core.model.Gate
import com.akshayc.appgate.core.model.GateConfig
import com.akshayc.appgate.core.model.Target
import com.akshayc.appgate.core.model.Tier
import com.akshayc.appgate.core.policy.EscalationPolicy
import com.android.launcher3.R
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The hour a daily allowance refills at. */
internal const val DAILY_RESET_HOUR = 5

/**
 * Read off the policy the engine actually runs, so the copy cannot drift from
 * the behaviour it describes.
 */
private val ESCALATION_POLICY = EscalationPolicy()
private val ESCALATION_CAP = ESCALATION_POLICY.cap
private val ESCALATION_WINDOW_MINUTES = ESCALATION_POLICY.window.toMinutes().toInt()

/**
 * Gate configuration for one Target, shown in a bottom sheet from the icon's
 * long-press menu.
 *
 * Asks the two questions a Gate needs (CLAUDE.md): which Tier, and which
 * Challenge type(s) — friction and auth are separate, stackable controls, never
 * collapsed into one enum. Save stays disabled until [GateConfig]'s own
 * constraint is satisfied, so the model's `require` can never be tripped.
 */
@Composable
fun GateConfigSheet(
    target: Target,
    appLabel: String,
    scrollState: ScrollState,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            // The sheet is tall enough to reach the top of the screen, so the
            // content is inset out from under the status bar. Applied before
            // the scroll modifier so the inset is the viewport edge and does
            // not scroll away with the content.
            .statusBarsPadding()
            // Also before verticalScroll, so the scrollbar measures against the
            // viewport and stays put instead of scrolling with the content.
            .verticalScrollbar(scrollState, color = MaterialTheme.colorScheme.onSurfaceVariant)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = appLabel.ifBlank { target.packageName },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.appgate_sheet_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        GateConfigControls(
            target = target,
            showUsage = true,
            onSaved = onClose,
            onRemoved = onClose,
            onCancel = onClose,
        )
    }
}

/**
 * The Gate's own controls, without a container: the sheet wraps them in its own
 * scrolling column, the stats screen drops them into a preference group. Both
 * edit the same Gate, so there is one copy of the form.
 */
@Composable
internal fun GateConfigControls(
    target: Target,
    modifier: Modifier = Modifier,
    showUsage: Boolean = false,
    onSaved: () -> Unit = {},
    onRemoved: () -> Unit = {},
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val appGate = remember { AppGate.getInstance(context) }
    val scope = rememberCoroutineScope()

    var tier by rememberSaveable { mutableStateOf(Tier.DELAY) }
    var friction by rememberSaveable { mutableStateOf<FrictionChallenge?>(FrictionChallenge.BREATHING_DELAY) }
    var requireAuth by rememberSaveable { mutableStateOf(false) }
    var escalation by rememberSaveable { mutableStateOf(false) }
    // null is unlimited, which is the default.
    var dailyMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var existing by remember { mutableStateOf<Gate?>(null) }

    // Prefill from the stored Gate, if this app already has one.
    LaunchedEffect(target) {
        val gate = appGate.repository.observeGates().first().firstOrNull { it.target == target }
        existing = gate
        if (gate != null) {
            tier = gate.config.tier
            friction = gate.config.frictionChallenge
            requireAuth = gate.config.authChallenge != null
            escalation = gate.config.escalation
            dailyMinutes = (gate.config.budget as? Budget.DailyTime)?.maxTotal?.toMinutes()?.toInt()
        }
    }

    val satisfiesConstraint = tier == Tier.NUDGE || friction != null || requireAuth

    // Escalation has nothing to raise once the Tier is already at the cap, and
    // auth-only gates opt out of it entirely, so the control is only offered
    // where it would actually do something.
    val escalationApplies = tier < ESCALATION_CAP && !(friction == null && requireAuth)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Only once this app has a Gate: before that every figure is zero, and
        // an empty chart says nothing worth the space.
        existing?.takeIf { showUsage }?.let { gate ->
            val usage = rememberTargetUsage(target, resetHourOf(gate))
            Heading(R.string.appgate_stats_sheet_heading)
            UsageSummary(
                usage = usage,
                allowanceMinutes = dailyMinutes,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            UsageBarChart(
                usage = usage,
                zone = appGate.zone,
                allowanceMinutes = dailyMinutes,
                modifier = Modifier.padding(top = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }

        Heading(R.string.appgate_configure_tier_heading)
        Tier.entries.forEach { entry ->
            ChoiceRow(
                selected = tier == entry,
                title = stringResource(entry.titleRes),
                body = stringResource(entry.bodyRes),
                onSelect = { tier = entry },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Heading(R.string.appgate_configure_friction_heading)
        ChoiceRow(
            selected = friction == null,
            title = stringResource(R.string.appgate_configure_friction_none),
            body = null,
            onSelect = { friction = null },
        )
        FrictionChallenge.entries.forEach { entry ->
            ChoiceRow(
                selected = friction == entry,
                title = stringResource(entry.titleRes),
                body = null,
                onSelect = { friction = entry },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Heading(R.string.appgate_configure_auth_heading)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = requireAuth, onValueChange = { requireAuth = it })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = requireAuth, onCheckedChange = null)
            Text(
                text = stringResource(R.string.appgate_configure_auth_biometric),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (escalationApplies) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Heading(R.string.appgate_configure_escalation_heading)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = escalation, onValueChange = { escalation = it })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = escalation, onCheckedChange = null)
                Column {
                    Text(
                        text = stringResource(R.string.appgate_configure_escalation),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.appgate_configure_escalation_body,
                            ESCALATION_WINDOW_MINUTES,
                            stringResource(ESCALATION_CAP.titleRes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Heading(R.string.appgate_configure_allowance_heading)
        Text(
            text = dailyMinutes
                ?.let { stringResource(R.string.appgate_configure_allowance_minutes, it) }
                ?: stringResource(R.string.appgate_configure_allowance_unlimited),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Continuous slider snapped to the stop list rather than a stepped one:
        // three dozen tick marks would be clutter, and the label above already
        // says exactly where the thumb is. A sideways drag cannot close the
        // sheet — the dismiss detector only arms when the vertical component of
        // a drag exceeds the horizontal one.
        Slider(
            value = AllowanceStops.indexOf(dailyMinutes).toFloat(),
            onValueChange = { position -> dailyMinutes = AllowanceStops.minutesAt(position.roundToInt()) },
            valueRange = 0f..AllowanceStops.lastIndex.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (dailyMinutes != null) {
            Text(
                text = stringResource(R.string.appgate_configure_allowance_reset, DAILY_RESET_HOUR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!satisfiesConstraint) {
            Text(
                text = stringResource(R.string.appgate_configure_needs_challenge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = stringResource(R.string.appgate_detection_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (existing != null) {
                TextButton(
                    onClick = {
                        scope.launch {
                            appGate.repository.removeGate(target)
                            onRemoved()
                        }
                    },
                ) {
                    Text(stringResource(R.string.appgate_remove))
                }
            }
            onCancel?.let { cancel ->
                TextButton(onClick = cancel) {
                    Text(stringResource(R.string.appgate_configure_cancel))
                }
            }
            TextButton(
                enabled = satisfiesConstraint,
                onClick = {
                    val config = GateConfig(
                        tier = tier,
                        frictionChallenge = friction,
                        authChallenge = if (requireAuth) AuthChallenge.BIOMETRIC else null,
                        // No grace: the Gate is raised on every open, so
                        // coming straight back is not waved through.
                        grace = Duration.ZERO,
                        escalation = escalation && escalationApplies,
                        budget = dailyMinutes?.let {
                            Budget.DailyTime(
                                maxTotal = Duration.ofMinutes(it.toLong()),
                                resetHour = DAILY_RESET_HOUR,
                            )
                        },
                    )
                    scope.launch {
                        appGate.repository.upsertGate(Gate(target = target, config = config))
                        onSaved()
                    }
                },
            ) {
                Text(stringResource(R.string.appgate_configure_save))
            }
        }
    }
}

@Composable
private fun Heading(
    @StringRes textRes: Int,
) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    body: String?,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@get:StringRes
private val Tier.titleRes: Int
    get() = when (this) {
        Tier.NUDGE -> R.string.appgate_tier_nudge
        Tier.DELAY -> R.string.appgate_tier_delay
        Tier.EFFORT -> R.string.appgate_tier_effort
        Tier.COMMITMENT -> R.string.appgate_tier_commitment
        Tier.LOCKED -> R.string.appgate_tier_locked
    }

@get:StringRes
private val Tier.bodyRes: Int
    get() = when (this) {
        Tier.NUDGE -> R.string.appgate_tier_nudge_body
        Tier.DELAY -> R.string.appgate_tier_delay_body
        Tier.EFFORT -> R.string.appgate_tier_effort_body
        Tier.COMMITMENT -> R.string.appgate_tier_commitment_body
        Tier.LOCKED -> R.string.appgate_tier_locked_body
    }

@get:StringRes
private val FrictionChallenge.titleRes: Int
    get() = when (this) {
        FrictionChallenge.BREATHING_DELAY -> R.string.appgate_friction_breathing
        FrictionChallenge.EFFORT_TASK -> R.string.appgate_friction_effort
        FrictionChallenge.STATED_INTENT -> R.string.appgate_friction_intent
    }
