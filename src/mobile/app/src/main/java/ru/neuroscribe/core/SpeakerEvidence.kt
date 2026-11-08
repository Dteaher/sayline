package ru.neuroscribe.core

import kotlin.math.sqrt

data class SpeakerEvidence(val interval: Int, val ownDistance: Float?, val otherDistance: Float?,
                           val silhouette: Float?, val provisional: Boolean)

/** Some very short model-ready inputs still yield NaN/zero vectors. They carry
 * no identity evidence; retain native fallback instead of failing the recording. */
fun usableSpeakerEmbedding(vector: FloatArray): FloatArray? = vector.takeIf {
    it.isNotEmpty() && it.all(Float::isFinite) && it.sumOf { v -> v.toDouble()*v } > 1e-18
}

/** Diagnostic only: no probability claims or automatic deletion of rare voices.
 * Recomputed on FINAL labels; native silhouettes describe a different clustering.
 */
fun speakerEvidence(turns: List<Turn>, embeddings: List<FloatArray?>): List<SpeakerEvidence> {
    require(turns.size == embeddings.size)
    val vectors = embeddings.map { v -> v?.let {
        require(it.isNotEmpty() && it.all(Float::isFinite))
        val norm = sqrt(it.sumOf { x -> x.toDouble()*x })
        if (norm < 1e-9) null else FloatArray(it.size) { i -> (it[i]/norm).toFloat() }
    } }
    require(vectors.filterNotNull().map { it.size }.distinct().size <= 1)
    val groups = vectors.indices.filter { vectors[it] != null }.groupBy { turns[it].speaker }
    return turns.indices.map { i ->
        val v = vectors[i]
        val own = groups[turns[i].speaker].orEmpty().filter { it != i }
        fun distance(j: Int): Double {
            val other = vectors[j]!!
            return (1.0 - v!!.indices.sumOf { d -> v[d].toDouble()*other[d] }).coerceIn(0.0, 2.0)
        }
        val a = if (v != null && own.isNotEmpty()) own.map(::distance).average().toFloat() else null
        val b = if (v != null) groups.filterKeys { it != turns[i].speaker }.values
            .map { g -> g.map(::distance).average().toFloat() }.minOrNull() else null
        val silhouette = if (a != null && b != null) if (maxOf(a,b) > 1e-9) (b-a)/maxOf(a,b) else 0f else null
        SpeakerEvidence(i, a, b, silhouette, a == null)
    }
}
