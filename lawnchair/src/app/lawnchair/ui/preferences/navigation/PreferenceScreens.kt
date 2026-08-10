package app.lawnchair.ui.preferences.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavDeepLink
import app.lawnchair.ui.preferences.about.About as AboutScreen
import app.lawnchair.ui.preferences.destinations.AppDrawerFoldersPreference
import app.lawnchair.ui.preferences.destinations.AppDrawerPreferences
import app.lawnchair.ui.preferences.destinations.BackupAndRestorePreference
import app.lawnchair.ui.preferences.destinations.DockPreferences
import app.lawnchair.ui.preferences.destinations.ExperimentalFeaturesPreferences
import app.lawnchair.ui.preferences.destinations.FolderPreferences
import app.lawnchair.ui.preferences.destinations.GeneralPreferences
import app.lawnchair.ui.preferences.destinations.GesturePreferences
import app.lawnchair.ui.preferences.destinations.HomeScreenGridPreferences
import app.lawnchair.ui.preferences.destinations.HomeScreenPreferences
import app.lawnchair.ui.preferences.destinations.IconPackPreferences
import app.lawnchair.ui.preferences.destinations.LauncherPopupPreference
import app.lawnchair.ui.preferences.destinations.PredictionsPreferences
import app.lawnchair.ui.preferences.destinations.QuickstepPreferences
import app.lawnchair.ui.preferences.destinations.SearchProviderPreferences
import app.lawnchair.ui.preferences.destinations.SmartspacePreferences
import kotlin.reflect.KClass

/**
 * A preference screen that takes no arguments.
 *
 * These are declared once and used twice: [PreferenceNavigation] registers them
 * in the navigation graph, and the settings search indexer composes the
 * [indexable] ones off-screen to harvest their labels. Adding a screen here is
 * therefore all it takes for its settings to become searchable.
 */
class PreferenceScreen(
    val route: PreferenceRoute,
    val kClass: KClass<out PreferenceRoute>,
    val deepLinks: List<NavDeepLink>,
    val indexable: Boolean,
    val content: @Composable () -> Unit,
)

inline fun <reified T : PreferenceRoute> preferenceScreen(
    route: T,
    deepLinks: List<NavDeepLink> = emptyList(),
    indexable: Boolean = true,
    noinline content: @Composable () -> Unit,
) = PreferenceScreen(route, T::class, deepLinks, indexable, content)

/**
 * Screens that are marked `indexable = false` are still routed here, they are
 * only kept out of the indexing pass: their content is a list of data (icon
 * packs, folders, search providers) rather than settings, or loading them costs
 * more than the labels are worth.
 */
val preferenceScreens: List<PreferenceScreen> = listOf(
    preferenceScreen(General, getDeepLink(General)) { GeneralPreferences() },
    preferenceScreen(GeneralIconPack, getDeepLink(GeneralIconPack), indexable = false) { IconPackPreferences() },

    preferenceScreen(HomeScreen, getDeepLink(HomeScreen)) { HomeScreenPreferences() },
    preferenceScreen(HomeScreenGrid, getDeepLink(HomeScreenGrid)) { HomeScreenGridPreferences() },
    preferenceScreen(HomeScreenPopupEditor, getDeepLink(HomeScreenPopupEditor)) { LauncherPopupPreference() },

    preferenceScreen(Dock, getDeepLink(Dock)) { DockPreferences() },
    preferenceScreen(DockSearchProvider, getDeepLink(DockSearchProvider), indexable = false) { SearchProviderPreferences() },

    preferenceScreen(Smartspace, getDeepLink(Smartspace)) { SmartspacePreferences(fromWidget = false) },

    preferenceScreen(AppDrawer, getDeepLink(AppDrawer)) { AppDrawerPreferences() },
    preferenceScreen(AppDrawerFolder, getDeepLink(AppDrawerFolder), indexable = false) { AppDrawerFoldersPreference() },
    preferenceScreen(Predictions, getDeepLink(Predictions)) { PredictionsPreferences() },

    preferenceScreen(Folders, getDeepLink(Folders)) { FolderPreferences() },
    preferenceScreen(Gestures, getDeepLink(Gestures)) { GesturePreferences() },
    preferenceScreen(Quickstep, getDeepLink(Quickstep)) { QuickstepPreferences() },
    preferenceScreen(BackupAndRestore, getDeepLink(BackupAndRestore), indexable = false) { BackupAndRestorePreference() },
    preferenceScreen(ExperimentalFeatures, getDeepLink(ExperimentalFeatures)) { ExperimentalFeaturesPreferences() },

    // The about screen fetches contributor data, which an indexing pass has no
    // business triggering.
    preferenceScreen(About, getDeepLink(About), indexable = false) { AboutScreen() },
)
