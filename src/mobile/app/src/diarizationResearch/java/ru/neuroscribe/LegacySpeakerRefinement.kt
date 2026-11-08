package ru.neuroscribe

import ru.neuroscribe.core.Turn

import kotlin.math.sqrt

/** Average-link clustering of independent turn embeddings. Times are never altered. */
fun legacyRefineSpeakers(turns: List<Turn>, embeddings: List<FloatArray?>, threshold: Float = .5f): List<Turn> {
    require(turns.size == embeddings.size && threshold.isFinite() && threshold in 0f..2f)
    val vectors = embeddings.map { vector -> vector?.let {
        require(it.isNotEmpty() && it.all(Float::isFinite))
        val norm = sqrt(it.sumOf { value -> value.toDouble() * value })
        if (norm < 1e-9) null else FloatArray(it.size) { i -> (it[i] / norm).toFloat() }
    } }
    // Short turns can be classified against established references, but cannot seed a new voice.
    val indexes = vectors.indices.filter { vectors[it] != null && turns[it].end - turns[it].start >= 1.5f }
    if (indexes.size < 2) return turns
    require(indexes.map { vectors[it]!!.size }.distinct().size == 1)
    // Bounded quadratic matrix. The engine only refines at most 128 model intervals.
    require(indexes.size <= 128)
    val n = indexes.size
    val distances = Array(n) { FloatArray(n) }
    for (i in 0 until n) for (j in 0 until i) {
        val a = vectors[indexes[i]]!!; val b = vectors[indexes[j]]!!
        var dot = 0f
        for (d in a.indices) dot += a[d] * b[d]
        distances[i][j] = (1f - dot).coerceIn(0f, 2f)
        distances[j][i] = distances[i][j]
    }
    val groups = Array(n) { mutableListOf(indexes[it]) }
    val active = BooleanArray(n) { true }
    while (true) {
        var left = -1; var right = -1; var best = threshold
        for (i in 0 until n) if (active[i]) for (j in 0 until i) if (active[j] && distances[i][j] <= best) {
            if (left < 0 || distances[i][j] < best) { left = i; right = j; best = distances[i][j] }
        }
        if (left < 0) break
        val leftSize = groups[left].size; val rightSize = groups[right].size
        for (k in 0 until n) if (active[k] && k != left && k != right) {
            val combined = (distances[left][k] * leftSize + distances[right][k] * rightSize) / (leftSize + rightSize)
            distances[right][k] = combined; distances[k][right] = combined
        }
        groups[right].addAll(groups[left]); active[left] = false
    }
    val ordered = (0 until n).filter { active[it] }.sortedBy { groups[it].min() }
    val labels = IntArray(turns.size) { -1 }
    ordered.forEachIndexed { label, group -> groups[group].forEach { labels[it] = label } }
    val fallback = mutableMapOf<Int, Int>()
    var next = ordered.size
    for (i in labels.indices) if (labels[i] < 0) {
        val eligible = indexes.filter { turns[it].speaker == turns[i].speaker }.map { labels[it] }.distinct()
        val vector = vectors[i]
        labels[i] = if (vector != null && eligible.isNotEmpty()) {
            eligible.minBy { label ->
                val references = indexes.filter { labels[it] == label }
                references.sumOf { reference ->
                    val other = vectors[reference]!!
                    var dot = 0.0
                    for (d in vector.indices) dot += vector[d] * other[d]
                    1.0 - dot
                } / references.size
            }
        } else fallback.getOrPut(turns[i].speaker) {
            val duration = mutableMapOf<Int, Float>()
            for (j in indexes) if (turns[j].speaker == turns[i].speaker && labels[j] >= 0) {
                duration[labels[j]] = (duration[labels[j]] ?: 0f) + turns[j].end - turns[j].start
            }
            duration.maxByOrNull { it.value }?.key ?: next++
        }
    }
    return turns.mapIndexed { i, turn -> turn.copy(speaker = labels[i]) }
}
