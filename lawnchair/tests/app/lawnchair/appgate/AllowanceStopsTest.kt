package app.lawnchair.appgate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AllowanceStopsTest {

    @Test
    fun `unlimited is the last stop`() {
        assertThat(AllowanceStops.values.last()).isNull()
        assertThat(AllowanceStops.indexOf(null)).isEqualTo(AllowanceStops.lastIndex)
        assertThat(AllowanceStops.minutesAt(AllowanceStops.lastIndex)).isNull()
    }

    @Test
    fun `unlimited is the only stop without a limit`() {
        assertThat(AllowanceStops.values.dropLast(1)).doesNotContain(null)
    }

    @Test
    fun `every stop round trips through its index`() {
        AllowanceStops.values.forEachIndexed { index, minutes ->
            assertThat(AllowanceStops.indexOf(minutes)).isEqualTo(index)
            assertThat(AllowanceStops.minutesAt(index)).isEqualTo(minutes)
        }
    }

    @Test
    fun `stops run from the minimum to the maximum in whole steps`() {
        val limits = AllowanceStops.values.filterNotNull()
        assertThat(limits.first()).isEqualTo(AllowanceStops.MIN_MINUTES)
        assertThat(limits.last()).isEqualTo(AllowanceStops.MAX_MINUTES)
        assertThat(limits.zipWithNext { a, b -> b - a }.toSet())
            .containsExactly(AllowanceStops.STEP_MINUTES)
    }

    @Test
    fun `snaps an allowance that is off the grid to the nearest stop`() {
        // 12 is nearer 10, 13 is nearer 15; a value exactly between rounds up.
        assertThat(AllowanceStops.minutesAt(AllowanceStops.indexOf(12))).isEqualTo(10)
        assertThat(AllowanceStops.minutesAt(AllowanceStops.indexOf(13))).isEqualTo(15)
        assertThat(AllowanceStops.minutesAt(AllowanceStops.indexOf(17))).isEqualTo(15)
    }

    @Test
    fun `snaps an allowance beyond either end onto the grid`() {
        assertThat(AllowanceStops.minutesAt(AllowanceStops.indexOf(1)))
            .isEqualTo(AllowanceStops.MIN_MINUTES)
        assertThat(AllowanceStops.minutesAt(AllowanceStops.indexOf(600)))
            .isEqualTo(AllowanceStops.MAX_MINUTES)
    }

    @Test
    fun `a slider position off either end still resolves to a stop`() {
        assertThat(AllowanceStops.minutesAt(-3)).isEqualTo(AllowanceStops.MIN_MINUTES)
        assertThat(AllowanceStops.minutesAt(AllowanceStops.lastIndex + 3)).isNull()
    }
}
