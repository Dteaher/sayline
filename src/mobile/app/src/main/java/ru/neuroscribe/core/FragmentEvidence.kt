package ru.neuroscribe.core

import kotlin.math.sqrt

data class FragmentMerge(val source: Int, val target: Int, val similarity: Double, val margin: Double)

/** Frozen profiles: proposals never become evidence for another merge. */
fun fragmentMergeProposals(turns: List<Turn>, embeddings: List<FloatArray?>,
                           durations: List<Float>): List<FragmentMerge> {
    require(turns.size == embeddings.size && turns.size == durations.size)
    require(durations.all { it.isFinite() && it >= 0f })
    val vectors = embeddings.map { raw -> raw?.let(::usableSpeakerEmbedding)?.let { v ->
        val norm = sqrt(v.sumOf { it.toDouble() * it })
        DoubleArray(v.size) { v[it] / norm }
    } }
    require(vectors.filterNotNull().map { it.size }.distinct().size <= 1)
    val groups = turns.indices.groupBy { turns[it].speaker }
    fun union(spans: List<Pair<Float, Float>>): Double {
        var end = Double.NEGATIVE_INFINITY
        var total = 0.0
        for ((a,b) in spans.sortedBy { it.first }) {
            total += maxOf(0.0, b - maxOf(a.toDouble(), end)); end = maxOf(end, b.toDouble())
        }
        return total
    }
    fun overlap(a: Int, b: Int): Double = union(groups.getValue(a).flatMap { i ->
        groups.getValue(b).mapNotNull { j ->
            val start = maxOf(turns[i].start, turns[j].start)
            val end = minOf(turns[i].end, turns[j].end)
            if (end > start) start to end else null
        }
    })
    val refs = groups.mapValues { (_, indices) -> indices.filter { vectors[it] != null && durations[it] > 0f } }
    val cores = groups.keys.filter { g -> refs.getValue(g).size >= 2 &&
        union(groups.getValue(g).map { turns[it].start to turns[it].end }) >= 5.0 }
    if (cores.size < 2) return emptyList()
    val proposed = groups.keys.filter { it !in cores && refs.getValue(it).isNotEmpty() }.mapNotNull { source ->
        val ranked = cores.map { target ->
            var weight = 0.0; var sum = 0.0
            for (i in refs.getValue(source)) {
                val v = vectors[i]!!
                val best = refs.getValue(target).map { j -> v.indices.sumOf { d -> v[d] * vectors[j]!![d] } }
                    .sortedDescending().take(2).average()
                val w = minOf(2f, durations[i]).toDouble()
                sum += best * w; weight += w
            }
            target to sum / weight
        }.sortedWith(compareByDescending<Pair<Int,Double>> { it.second }.thenByDescending { it.first })
        val best = ranked[0]; val margin = best.second - ranked[1].second
        if (best.second >= .55 && margin >= .1 && overlap(source, best.first) <= .2)
            FragmentMerge(source, best.first, best.second, margin) else null
    }
    val rejected = mutableSetOf<Int>()
    for (i in proposed.indices) for (j in 0 until i) if (proposed[i].target == proposed[j].target &&
        overlap(proposed[i].source, proposed[j].source) > .2) {
        rejected += proposed[i].source; rejected += proposed[j].source
    }
    return proposed.filter { it.source !in rejected }
}

fun applyFragmentMerges(turns: List<Turn>, proposals: List<FragmentMerge>): List<Turn> {
    val mapping = proposals.associate { it.source to it.target }
    return turns.map { it.copy(speaker = mapping[it.speaker] ?: it.speaker) }
}
