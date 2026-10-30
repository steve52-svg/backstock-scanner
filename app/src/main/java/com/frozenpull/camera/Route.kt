package com.frozenpull.camera

/**
 * The photo route through the walk-in, in walking order.
 * Section ids match the "Update walk-in" sections on the Frozen Pull List page,
 * so a day's photos can be added there section by section.
 *
 * steps = how many overlapping shots the section usually takes. It is a starting
 * point only: the person can end a row early or keep adding shots.
 */
data class Section(
    val id: String,
    val title: String,
    val direction: String,
    val steps: Int,
)

data class Spot(val section: Section, val step: Int) {
    /** Key used for the saved reference of this exact spot. */
    val key: String get() = "${section.id}-$step"
    val label: String get() = "${section.title} · spot ${step + 1} of ${section.steps}"
}

object Route {
    val sections = listOf(
        Section("lt", "Left shelf · top row", "Start at the door end, step toward the back", 7),
        Section("lm", "Left shelf · middle row", "Start at the door end, step toward the back", 7),
        Section("lb", "Left shelf · bottom row", "Start at the door end, step toward the back", 6),
        Section("bw", "Back wall", "Left to right", 3),
        Section("rt", "Right shelf · top row", "Start at the back wall, step toward the door", 6),
        Section("rm", "Right shelf · middle row", "Start at the back wall, step toward the door", 7),
        Section("rb", "Right shelf · bottom row & floor", "Back wall to door, flash on", 6),
        Section("fw", "Front wall", "Left to right", 2),
    )

    fun first(): Spot = Spot(sections.first(), 0)

    /** Next spot in the same row, or the first spot of the next section. Null at the end. */
    fun next(spot: Spot): Spot? =
        if (spot.step + 1 < spot.section.steps) spot.copy(step = spot.step + 1) else nextSection(spot)

    fun nextSection(spot: Spot): Spot? {
        val i = sections.indexOf(spot.section)
        return sections.getOrNull(i + 1)?.let { Spot(it, 0) }
    }
}
