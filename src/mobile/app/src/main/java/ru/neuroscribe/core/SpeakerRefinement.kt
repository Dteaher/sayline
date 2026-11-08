package ru.neuroscribe.core

import kotlin.math.sqrt

/** Average-link clustering of independent turn embeddings. Times are never altered. */
fun refineSpeakers(turns: List<Turn>, embeddings: List<FloatArray?>, threshold: Float = .5f,
                   preserveOverlap: Boolean = false, globalShortAssignment: Boolean = false,
                   referenceDurations: List<Float>? = null,
                   checkpoint: () -> Unit = {}): List<Turn> {
    require(turns.size == embeddings.size && threshold.isFinite() && threshold in 0f..2f)
    require(referenceDurations == null || referenceDurations.size == turns.size && referenceDurations.all { it.isFinite() && it >= 0f })
    val vectors = embeddings.map { vector -> vector?.let {
        require(it.isNotEmpty() && it.all(Float::isFinite))
        val norm = sqrt(it.sumOf { value -> value.toDouble() * value })
        if (norm < 1e-9) null else FloatArray(it.size) { i -> (it[i] / norm).toFloat() }
    } }
    require(vectors.filterNotNull().map { it.size }.distinct().size <= 1)
    // Short turns can be classified against established references, but cannot seed a new voice.
    val indexes = vectors.indices.filter { vectors[it] != null &&
        (referenceDurations?.get(it) ?: (turns[it].end - turns[it].start)) >= 1.5f }
    if (indexes.size < 2) return turns
    require(indexes.map { vectors[it]!!.size }.distinct().size == 1)
    // Bound quadratic memory independently of the number of recording intervals.
    require(indexes.size <= 512)
    val n = indexes.size
    val distances = Array(n) { FloatArray(n) }
    for (i in 0 until n) for (j in 0 until i) {
        val a = vectors[indexes[i]]!!; val b = vectors[indexes[j]]!!
        var dot = 0f
        for (d in a.indices) dot += a[d] * b[d]
        distances[i][j] = (1f - dot).coerceIn(0f, 2f)
        distances[j][i] = distances[i][j]
    }
    val clustered = averageLinkage(distances, threshold, checkpoint)
    val labels = IntArray(turns.size) { -1 }
    indexes.forEachIndexed { i, original -> labels[original] = clustered[i] }
    val fallback = mutableMapOf<Int, Int>()
    val referenceGroups = indexes.groupBy { labels[it] }
    val centroids = referenceGroups.mapValues { (_, references) ->
        FloatArray(vectors[references.first()]!!.size) { d ->
            (references.sumOf { vectors[it]!![d].toDouble() } / references.size).toFloat()
        }
    }
    var next = clustered.distinct().size
    for (i in labels.indices) if (labels[i] < 0) {
        checkpoint()
        val eligible = indexes.filter { turns[it].speaker == turns[i].speaker }.map { labels[it] }.distinct()
        val vector = vectors[i]
        // Candidate policy: acoustic evidence can override an incorrect native
        // short-turn label, only with a clear gap. Otherwise preserve fallback.
        if (globalShortAssignment && vector != null && turns[i].end-turns[i].start < 1.5f && centroids.size >= 2) {
            val scores = centroids.map { (label, centroid) ->
                label to (1.0 - vector.indices.sumOf { d -> vector[d].toDouble()*centroid[d] })
            }.sortedBy { it.second }
            if (scores[0].second <= threshold && scores[1].second-scores[0].second >= .1) {
                labels[i] = scores[0].first
                continue
            }
        }
        labels[i] = if (vector != null && eligible.isNotEmpty()) {
            eligible.minBy { label ->
                val centroid = centroids.getValue(label)
                1.0 - vector.indices.sumOf { d -> vector[d].toDouble()*centroid[d] }
            }
        } else fallback.getOrPut(turns[i].speaker) {
            val duration = mutableMapOf<Int, Float>()
            for (j in indexes) if (turns[j].speaker == turns[i].speaker && labels[j] >= 0) {
                duration[labels[j]] = (duration[labels[j]] ?: 0f) + turns[j].end - turns[j].start
            }
            duration.maxByOrNull { it.value }?.key ?: next++
        }
    }
    val refined = turns.mapIndexed { i, turn -> turn.copy(speaker = labels[i]) }
    return if (preserveOverlap) preserveConcurrentIdentity(turns, refined) else refined
}
