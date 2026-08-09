package app.lawnchair.appgate

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akshayc.appgate.core.model.DenyReason
import com.akshayc.appgate.core.model.FrictionChallenge
import com.akshayc.appgate.core.model.Tier
import com.android.launcher3.R
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The Challenge the user completes to pass a Gate.
 *
 * Two axes, per CLAUDE.md: the Tier says *how much* friction — and so which
 * steps appear, which is how escalating re-entry costs more — while the
 * configured [FrictionChallenge] says *what kind* and always contributes its
 * own step. Backing out simply closes the screen and returns to the launcher;
 * that is the intended way out, because this is friction, not a lock.
 */
@Composable
internal fun GateChallengeScreen(
    appLabel: String,
    tier: Tier,
    friction: FrictionChallenge?,
    onPass: (sessionMinutes: Int, intentText: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val effortTask = rememberEffortTask()
    val steps = remember(tier, friction, effortTask) { challengeSteps(tier, friction, effortTask) }

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var intentText by rememberSaveable { mutableStateOf("") }
    var sessionMinutes by rememberSaveable { mutableIntStateOf(DEFAULT_SESSION_MINUTES) }

    LaunchedEffect(stepIndex) {
        if (stepIndex >= steps.size) {
            onPass(sessionMinutes, intentText.takeIf { it.isNotBlank() })
        }
    }
    if (stepIndex >= steps.size) return

    val step = steps[stepIndex]
    val advance = { stepIndex += 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.appgate_challenge_opening, appLabel),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (steps.size > 1) {
            Text(
                text = stringResource(R.string.appgate_challenge_step, stepIndex + 1, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))

        when (step) {
            is ChallengeStep.Nudge -> NudgeStep(onContinue = advance)
            is ChallengeStep.Delay -> DelayStep(seconds = step.seconds, onContinue = advance)
            is ChallengeStep.Effort -> EffortStep(task = step.task, onContinue = advance)
            is ChallengeStep.Commitment ->
                CommitmentStep(
                    appLabel = appLabel,
                    intentText = intentText,
                    onIntentChange = { intentText = it },
                    sessionMinutes = sessionMinutes,
                    onSessionMinutesChange = { sessionMinutes = it },
                    onContinue = advance,
                )
        }

        Spacer(Modifier.size(8.dp))
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.appgate_challenge_not_now))
        }
    }
}

@Composable
private fun NudgeStep(onContinue: () -> Unit) {
    Text(
        text = stringResource(R.string.appgate_challenge_nudge_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.appgate_challenge_continue))
    }
}

@Composable
private fun DelayStep(seconds: Int, onContinue: () -> Unit) {
    var remaining by rememberSaveable(seconds) { mutableIntStateOf(seconds) }
    LaunchedEffect(seconds) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (seconds <= 0) 1f else (seconds - remaining).toFloat() / seconds,
        label = "delayProgress",
    )

    Text(
        text = stringResource(R.string.appgate_challenge_delay_body),
        style = MaterialTheme.typography.bodyLarge,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(120.dp),
        )
        Text(
            text = remaining.toString(),
            style = MaterialTheme.typography.headlineLarge,
        )
    }
    Button(
        onClick = onContinue,
        enabled = remaining <= 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.appgate_challenge_continue))
    }
}

@Composable
private fun EffortStep(task: EffortTask, onContinue: () -> Unit) {
    var answer by rememberSaveable { mutableStateOf("") }
    val expected = when (task) {
        is EffortTask.Sum -> (task.first + task.second).toString()
        is EffortTask.Phrase -> task.text
    }
    val prompt = when (task) {
        is EffortTask.Sum -> stringResource(R.string.appgate_challenge_effort_sum, task.first, task.second)
        is EffortTask.Phrase -> stringResource(R.string.appgate_challenge_effort_phrase)
    }

    Text(text = prompt, style = MaterialTheme.typography.bodyLarge)
    if (task is EffortTask.Phrase) {
        Text(
            text = task.text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    OutlinedTextField(
        value = answer,
        onValueChange = { answer = it },
        singleLine = task is EffortTask.Sum,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (task is EffortTask.Sum) KeyboardType.Number else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onContinue,
        enabled = answer.trim().equals(expected, ignoreCase = task is EffortTask.Phrase),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.appgate_challenge_continue))
    }
}

@Composable
private fun CommitmentStep(
    appLabel: String,
    intentText: String,
    onIntentChange: (String) -> Unit,
    sessionMinutes: Int,
    onSessionMinutesChange: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    Text(
        text = stringResource(R.string.appgate_challenge_intent_prompt, appLabel),
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedTextField(
        value = intentText,
        onValueChange = onIntentChange,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.appgate_challenge_length_prompt),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    SESSION_LENGTH_CHOICES.forEach { minutes ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = sessionMinutes == minutes, onClick = { onSessionMinutesChange(minutes) })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = sessionMinutes == minutes, onClick = null)
            Text(
                text = stringResource(R.string.appgate_challenge_length_minutes, minutes),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    Button(
        onClick = onContinue,
        enabled = intentText.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.appgate_challenge_continue))
    }
}

/** Shown for [com.akshayc.appgate.core.model.GateDecision.Deny]. There is no way past it here. */
@Composable
internal fun GateDeniedScreen(
    appLabel: String,
    reason: DenyReason,
    resetHour: Int,
    onClose: () -> Unit,
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
            text = stringResource(R.string.appgate_challenge_opening, appLabel),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = when (reason) {
                DenyReason.LOCKED -> stringResource(R.string.appgate_denied_locked)
                DenyReason.BUDGET_EXHAUSTED -> stringResource(R.string.appgate_denied_budget, resetHour)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.appgate_denied_back))
        }
    }
}

internal sealed interface ChallengeStep {
    data object Nudge : ChallengeStep

    data class Delay(val seconds: Int) : ChallengeStep

    data class Effort(val task: EffortTask) : ChallengeStep

    data object Commitment : ChallengeStep
}

internal sealed interface EffortTask {
    data class Sum(val first: Int, val second: Int) : EffortTask

    data class Phrase(val text: String) : EffortTask
}

/**
 * Steps are the union of what the Tier demands and what the configured
 * challenge kind is, so raising the Tier — including by escalation — can only
 * add friction, never swap it for something cheaper.
 */
internal fun challengeSteps(
    tier: Tier,
    friction: FrictionChallenge?,
    effortTask: EffortTask,
): List<ChallengeStep> {
    val steps = buildList {
        if (tier >= Tier.DELAY || friction == FrictionChallenge.BREATHING_DELAY) {
            add(ChallengeStep.Delay(delaySecondsFor(tier)))
        }
        if (tier >= Tier.EFFORT || friction == FrictionChallenge.EFFORT_TASK) {
            add(ChallengeStep.Effort(effortTask))
        }
        if (tier >= Tier.COMMITMENT || friction == FrictionChallenge.STATED_INTENT) {
            add(ChallengeStep.Commitment)
        }
    }
    return steps.ifEmpty { listOf(ChallengeStep.Nudge) }
}

private fun delaySecondsFor(tier: Tier): Int {
    val stepsAboveDelay = (tier.ordinal - Tier.DELAY.ordinal).coerceAtLeast(0)
    return BASE_DELAY_SECONDS + EXTRA_DELAY_SECONDS * stepsAboveDelay
}

/**
 * A fresh task each time the screen opens: muscle memory is exactly what
 * EFFORT exists to defeat, so neither the sum nor the phrase repeats
 * predictably.
 */
@Composable
private fun rememberEffortTask(): EffortTask {
    val phrases = stringArrayResource(R.array.appgate_effort_phrases)
    return remember {
        if (Random.nextBoolean()) {
            EffortTask.Sum(Random.nextInt(11, 90), Random.nextInt(11, 90))
        } else {
            EffortTask.Phrase(phrases.random())
        }
    }
}

private const val BASE_DELAY_SECONDS = 15
private const val EXTRA_DELAY_SECONDS = 5
private const val DEFAULT_SESSION_MINUTES = 15
private val SESSION_LENGTH_CHOICES = listOf(5, 15, 30)
