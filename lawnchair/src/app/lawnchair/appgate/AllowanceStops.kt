package app.lawnchair.appgate

/**
 * The stops of the daily-allowance slider, in minutes, with unlimited as the
 * last stop: sliding right buys more time, and sliding all the way right buys
 * as much as you like.
 *
 * Pure Kotlin and free of Compose so the snapping can be unit tested — an
 * allowance that lands on the wrong stop is a gate that behaves differently
 * from what the sheet said.
 */
object AllowanceStops {

    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 180
    const val STEP_MINUTES = 5

    /** Every stop; null is unlimited and is always the last one. */
    val values: List<Int?> = (MIN_MINUTES..MAX_MINUTES step STEP_MINUTES).toList() + null

    val lastIndex: Int get() = values.lastIndex

    /**
     * The stop for a stored allowance. Values are snapped to the grid, so an
     * allowance saved before the grid existed still puts the thumb somewhere
     * sane; null — unlimited — is the last stop.
     */
    fun indexOf(minutes: Int?): Int {
        if (minutes == null) return values.lastIndex
        val snapped = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        return (snapped - MIN_MINUTES + STEP_MINUTES / 2) / STEP_MINUTES
    }

    /** The allowance at a slider position, which may be off the ends. */
    fun minutesAt(index: Int): Int? = values[index.coerceIn(values.indices)]
}
