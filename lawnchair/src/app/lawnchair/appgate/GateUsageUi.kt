package app.lawnchair.appgate

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akshayc.appgate.core.model.Target
import com.android.launcher3.R
import java.time.Duration
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The usage figures for one Target, from the Sessions the launcher already has
 * in memory.
 *
 * Deliberately a snapshot rather than a ticking clock: these are settings
 * screens, and a number that counts up while being read is noise.
 */
@Composable
internal fun rememberTargetUsage(target: Target, resetHour: Int): TargetUsage {
    val context = LocalContext.current
    val appGate = remember { AppGate.getInstance(context) }
    val sessions by appGate.sessions.collectAsStateWithLifecycle()
    return remember(sessions, target, resetHour) {
        UsageStatsRules.forTarget(
            sessions = sessions.filter { it.target == target },
            now = appGate.now(),
            zone = appGate.zone,
            resetHour = resetHour,
        )
    }
}

/**
 * Time, sessions, trend — and nothing else. Stats copy stays neutral by design
 * (CLAUDE.md): guilt copy teaches people to avoid the stats screen, and then
 * the app.
 */
@Composable
internal fun UsageSummary(
    usage: TargetUsage,
    modifier: Modifier = Modifier,
    allowanceMinutes: Int? = null,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UsageFigure(
            modifier = Modifier.weight(1f),
            value = formatDuration(context, usage.today),
            label = if (allowanceMinutes == null) {
                stringResource(R.string.appgate_stats_today)
            } else {
                stringResource(
                    R.string.appgate_stats_today_of_allowance,
                    formatDuration(context, Duration.ofMinutes(allowanceMinutes.toLong())),
                )
            },
        )
        UsageFigure(
            modifier = Modifier.weight(1f),
            value = usage.todaySessions.toString(),
            label = stringResource(R.string.appgate_stats_sessions_today),
        )
        UsageFigure(
            modifier = Modifier.weight(1f),
            value = formatDuration(context, usage.dailyAverage),
            label = stringResource(R.string.appgate_stats_daily_average),
        )
    }
}

@Composable
private fun UsageFigure(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A bar per day, oldest on the left, today on the right. Days with nothing in
 * them are still drawn, because a chart that skips them would read as a week
 * with fewer days in it.
 *
 * Bars are all one colour: the chart reports, it does not grade.
 */
@Composable
internal fun UsageBarChart(
    usage: TargetUsage,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    allowanceMinutes: Int? = null,
) {
    val context = LocalContext.current
    // Scaled against the busiest day, not against the allowance: an allowance
    // far above what is actually used would flatten every bar to nothing.
    val scale = maxOf(usage.busiestDay, Duration.ofMinutes(1))
    val allowance = allowanceMinutes?.let { Duration.ofMinutes(it.toLong()) }
    // Drawn only when it lands inside the chart. The text below says what the
    // allowance is either way.
    val allowanceFraction = allowance
        ?.let { it.toMillis().toFloat() / scale.toMillis().toFloat() }
        ?.takeIf { it <= 1f }
    val chartHeight = 120.dp
    val summary = usage.days.joinToString(", ") { day ->
        dayLabel(day, zone) + " " + formatDuration(context, day.used)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .semantics { contentDescription = summary },
        ) {
            allowanceFraction?.let { fraction ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = chartHeight * fraction)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Row(
                // Full height, so bottom alignment is against the chart rather
                // than against the tallest bar.
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                usage.days.forEach { day ->
                    val fraction = day.used.toMillis().toFloat() / scale.toMillis().toFloat()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((chartHeight * fraction.coerceIn(0f, 1f)).coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (day.used.isZero) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            ),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            usage.days.forEach { day ->
                Text(
                    modifier = Modifier.weight(1f),
                    text = dayLabel(day, zone),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun dayLabel(day: DayUsage, zone: ZoneId): String = day.dayStart
    .atZone(zone)
    .dayOfWeek
    .getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** Whole minutes below an hour, hours and minutes above it. */
internal fun formatDuration(context: Context, duration: Duration): String {
    val minutes = duration.toMinutes()
    if (minutes < 60) return context.getString(R.string.appgate_stats_minutes, minutes)
    val hours = minutes / 60
    val rest = minutes % 60
    if (rest == 0L) return context.getString(R.string.appgate_stats_hours, hours)
    return context.getString(R.string.appgate_stats_hours_minutes, hours, rest)
}
