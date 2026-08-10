package app.lawnchair.ui.preferences.search

import app.lawnchair.ui.preferences.navigation.AppDrawer
import app.lawnchair.ui.preferences.navigation.ExperimentalFeatures
import app.lawnchair.ui.preferences.navigation.Predictions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsSearchTest {

    private val suggestions = entry(
        label = "Show suggestion features",
        screenLabel = "Suggestions",
        description = null,
    )
    private val provider = entry(
        label = "Suggestions provider",
        screenLabel = "Suggestions",
        groupHeading = "App suggestions",
    )
    private val hiddenApps = entry(
        label = "Hidden apps",
        screenLabel = "App drawer",
        groupHeading = "General",
        route = AppDrawer,
    )
    private val gate = entry(
        label = "Gate apps",
        screenLabel = "Experimental features",
        groupHeading = "App gating",
        description = "Put a challenge between tapping an app and opening it",
        route = ExperimentalFeatures,
    )
    private val all = listOf(suggestions, provider, hiddenApps, gate)

    @Test
    fun `blank query returns nothing`() {
        assertThat(searchSettings("", all)).isEmpty()
        assertThat(searchSettings("   ", all)).isEmpty()
    }

    @Test
    fun `finds the suggestions settings by a partial word`() {
        assertThat(searchSettings("suggest", all)).containsExactly(provider, suggestions).inOrder()
    }

    @Test
    fun `matches a label prefix`() {
        assertThat(searchSettings("gate", all)).containsExactly(gate)
    }

    @Test
    fun `matching is case insensitive`() {
        assertThat(searchSettings("HIDDEN", all)).containsExactly(hiddenApps)
    }

    @Test
    fun `screen and group are searchable, ranked below labels`() {
        val results = searchSettings("app", all)
        // "Gate apps" and "Hidden apps" match on their label, the rest on their breadcrumb.
        assertThat(results.take(2)).containsExactly(gate, hiddenApps).inOrder()
        assertThat(results).contains(provider)
    }

    @Test
    fun `description is the weakest match`() {
        val results = searchSettings("challenge", all)
        assertThat(results).containsExactly(gate)
    }

    @Test
    fun `unmatched query returns nothing`() {
        assertThat(searchSettings("wallpaper", all)).isEmpty()
    }

    @Test
    fun `breadcrumb joins screen and group`() {
        assertThat(provider.breadcrumb).isEqualTo("Suggestions › App suggestions")
        assertThat(suggestions.breadcrumb).isEqualTo("Suggestions")
    }

    @Test
    fun `path ends with the setting itself`() {
        assertThat(provider.path).isEqualTo("Suggestions › App suggestions › Suggestions provider")
        assertThat(suggestions.path).isEqualTo("Suggestions › Show suggestion features")
    }

    @Test
    fun `entries on the same screen with the same label share a key`() {
        assertThat(entry(label = "Show labels").key).isEqualTo(entry(label = "Show labels").key)
        assertThat(entry(label = "Show labels").key).isNotEqualTo(entry(label = "Show icons").key)
    }

    private fun entry(
        label: String,
        screenLabel: String = "Suggestions",
        groupHeading: String? = null,
        description: String? = null,
        route: app.lawnchair.ui.preferences.navigation.PreferenceRoute = Predictions,
    ) = SettingsSearchEntry(
        label = label,
        description = description,
        screenLabel = screenLabel,
        groupHeading = groupHeading,
        route = route,
    )
}
