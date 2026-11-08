package ru.neuroscribe.core

/** A separate, testable guard AFTER ordinary linkage. It never changes intervals
 * or invents a voice absent from native output. Native overlap may itself be
 * wrong, so this guard is evaluated separately from the clustering optimization.
 */
fun preserveConcurrentIdentity(original: List<Turn>, refined: List<Turn>): List<Turn> {
    require(original.size == refined.size)
    val conflicts = mutableMapOf<Int, MutableMap<Int, MutableSet<Int>>>()
    val active = mutableListOf<Int>()
    for (i in original.indices.sortedBy { original[it].start }) {
        active.removeAll { original[it].end <= original[i].start + .05f }
        for (j in active) if (original[i].speaker != original[j].speaker && refined[i].speaker == refined[j].speaker &&
            minOf(original[i].end, original[j].end) - maxOf(original[i].start, original[j].start) > .05f) {
            val graph = conflicts.getOrPut(refined[i].speaker) { mutableMapOf() }
            graph.getOrPut(original[i].speaker) { mutableSetOf() } += original[j].speaker
            graph.getOrPut(original[j].speaker) { mutableSetOf() } += original[i].speaker
        }
        active += i
    }
    if (conflicts.isEmpty()) return refined
    val colors = mutableMapOf<Pair<Int, Int>, Int>()
    for (i in original.indices) {
        val cluster = refined[i].speaker; val native = original[i].speaker
        val key = cluster to native
        if (key !in colors) {
            val forbidden = conflicts[cluster]?.get(native).orEmpty().mapNotNull { colors[cluster to it] }.toSet()
            var color = 0
            while (color in forbidden) color++
            colors[key] = color
        }
    }
    val canonical = mutableMapOf<Pair<Int, Int>, Int>()
    return refined.mapIndexed { i, turn ->
        val key = turn.speaker to colors.getValue(turn.speaker to original[i].speaker)
        turn.copy(speaker = canonical.getOrPut(key) { canonical.size })
    }
}
