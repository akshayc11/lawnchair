package app.lawnchair.ui.preferences.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceSearchScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.preferenceScreens
import com.android.launcher3.R

/**
 * Searches every setting that has been composed at least once, plus everything
 * the indexing pass below can reach.
 */
@Composable
fun SettingsSearchPreferences(
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    var query by rememberSaveable { mutableStateOf("") }
    var indexing by remember { mutableStateOf(!SettingsSearchIndex.primed) }

    PreferenceSearchScaffold(
        value = query,
        onValueChange = { query = it },
        modifier = modifier,
        placeholder = {
            Text(
                text = stringResource(R.string.settings_search_placeholder),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { scaffoldPadding ->
        if (indexing) {
            IndexingPass(onIndexed = { indexing = false })
        }

        val results = searchSettings(query, SettingsSearchIndex.snapshot())

        when {
            query.isBlank() -> SearchMessage(
                text = stringResource(R.string.settings_search_hint),
            )

            results.isEmpty() -> SearchMessage(
                text = stringResource(R.string.settings_search_no_results),
            )

            else -> PreferenceLazyColumn(contentPadding = scaffoldPadding) {
                item {
                    PreferenceGroup {
                        results.forEach { entry ->
                            key(entry.key) {
                                PreferenceTemplate(
                                    title = { Text(text = entry.label) },
                                    description = { Text(text = entry.path) },
                                    onClick = { navController.navigate(entry.route) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Composes every indexable screen once, off-screen, so their settings land in
 * the index before the user has ever visited them. [onIndexed] fires once the
 * pass has been through a full frame and the screens can be dropped again.
 */
@Composable
private fun IndexingPass(onIndexed: () -> Unit) {
    ComposeWithoutDrawing {
        CompositionLocalProvider(LocalIsSettingsIndexingPass provides true) {
            preferenceScreens
                .filter { it.indexable }
                .forEach { screen ->
                    key(screen.route.toString()) {
                        CompositionLocalProvider(LocalSettingsSearchRoute provides screen.route) {
                            screen.content()
                        }
                    }
                }
        }
    }
    LaunchedEffect(Unit) {
        // Screens behind a scaffold only register during the measure phase,
        // which runs after this effect is dispatched, so let a frame finish
        // before calling the index primed.
        withFrameNanos { }
        SettingsSearchIndex.primed = true
        onIndexed()
    }
}
