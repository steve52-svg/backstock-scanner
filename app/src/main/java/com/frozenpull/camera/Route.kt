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
    /** Later wall checks line up with this morning's wide shot of the same wall. */
    val refKey: String get() = if (section.id.startsWith("c")) "w${section.id.drop(1)}-$step" else key
    val isWide: Boolean get() = section.id.startsWith("w") || section.id.startsWith("c")
    val label: String get() =
        if (step < section.steps) "${section.title} · spot ${step + 1} of ${section.steps}" else "${section.title} · spot ${step + 1}"
}

object Route {
    val sections = listOf(
        Section("wl", "Left shelf · wide shot", "Stand on the tape by the door. Use the 0.5× lens and get the whole shelf", 1),
        Section("lt", "Left shelf · top row", "Start at the door end, step toward the back", 7),
        Section("lm", "Left shelf · middle row", "Start at the door end, step toward the back", 7),
        Section("lb", "Left shelf · bottom row", "Start at the door end, step toward the back", 6),
        Section("wb", "Back wall · wide shot", "Stand on the tape facing the back wall, whole wall in the picture", 1),
        Section("bw", "Back wall", "Left to right", 3),
        Section("wr", "Right shelf · wide shot", "Stand on the tape by the back wall. Use the 0.5× lens", 1),
        Section("rt", "Right shelf · top row", "Start at the back wall, step toward the door", 6),
        Section("rm", "Right shelf · middle row", "Start at the back wall, step toward the door", 7),
        Section("rb", "Right shelf · bottom row & floor", "Back wall to door, flash on", 6),
        Section("wf", "Front wall · wide shot", "Stand on the tape facing the door, whole wall in the picture", 1),
        Section("fw", "Front wall", "Left to right", 2),
    )

    /** The quick later check: one wide shot per wall, from the same spots as this morning. */
    val checks = listOf(
        Section("cl", "Check · left shelf", "Same spot as this morning's wide shot", 1),
        Section("cb", "Check · back wall", "Same spot as this morning's wide shot", 1),
        Section("cr", "Check · right shelf", "Same spot as this morning's wide shot", 1),
        Section("cf", "Check · front wall", "Same spot as this morning's wide shot", 1),
    )
    val all: List<Section> get() = sections + checks

    fun checkFirst(): Spot = Spot(checks.first(), 0)
    private fun listFor(spot: Spot) = if (spot.section in checks) checks else sections

    fun first(): Spot = Spot(sections.first(), 0)

    /** Next spot in the same row, or the first spot of the next section. Null at the end. */
    fun next(spot: Spot): Spot? =
        if (spot.step + 1 < spot.section.steps) spot.copy(step = spot.step + 1) else nextSection(spot)

    fun nextSection(spot: Spot): Spot? {
        val list = listFor(spot)
        val i = list.indexOf(spot.section)
        return list.getOrNull(i + 1)?.let { Spot(it, 0) }
    }
}
