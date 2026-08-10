package app.lawnchair.ui.preferences.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.Layout
import app.lawnchair.ui.preferences.navigation.PreferenceRoute

/**
 * Process-wide index of every setting that has been composed at least once.
 *
 * The index is filled by [RegisterSearchableSetting], which the shared
 * preference controls call with the label they are already given. Entries are
 * never removed: a setting that is currently hidden behind a toggle stays
 * findable, and tapping it lands on the screen that owns it.
 */
object SettingsSearchIndex {

    private val entries = mutableStateMapOf<String, SettingsSearchEntry>()

    /** Set once the off-screen indexing pass has run in this process. */
    var primed: Boolean = false

    fun register(entry: SettingsSearchEntry) {
        if (entries[entry.key] != entry) {
            entries[entry.key] = entry
        }
    }

    fun snapshot(): List<SettingsSearchEntry> = entries.values.toList()
}

/** The route of the preference screen currently being composed, if it has one. */
val LocalSettingsSearchRoute = compositionLocalOf<PreferenceRoute?> { null }

/** The title of the preference screen currently being composed. */
val LocalSettingsSearchScreenLabel = compositionLocalOf<String?> { null }

/** The heading of the preference group currently being composed, if any. */
val LocalSettingsSearchGroup = compositionLocalOf<String?> { null }

/**
 * True while screens are being composed only to harvest their labels. Heavy
 * content that has nothing to index can skip itself when this is set.
 */
val LocalIsSettingsIndexingPass = compositionLocalOf { false }

/**
 * Adds the preference to the search index. Called by the shared controls, so
 * individual screens never have to think about search.
 */
@Composable
fun RegisterSearchableSetting(
    label: String,
    description: String? = null,
) {
    val route = LocalSettingsSearchRoute.current ?: return
    val screenLabel = LocalSettingsSearchScreenLabel.current ?: return
    val group = LocalSettingsSearchGroup.current
    DisposableEffect(route, screenLabel, group, label, description) {
        SettingsSearchIndex.register(
            SettingsSearchEntry(
                label = label,
                description = description,
                screenLabel = screenLabel,
                groupHeading = group,
                route = route,
            ),
        )
        onDispose { }
    }
}

/**
 * Composes and measures [content], then reports no size and places nothing, so
 * it is never drawn.
 *
 * Measuring is not optional: [androidx.compose.material3.Scaffold] lays its
 * content out with a `SubcomposeLayout`, so a screen that is composed but never
 * measured stops at the scaffold and registers nothing.
 */
@Composable
fun ComposeWithoutDrawing(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        measurables.forEach { it.measure(constraints) }
        layout(0, 0) { }
    }
}
