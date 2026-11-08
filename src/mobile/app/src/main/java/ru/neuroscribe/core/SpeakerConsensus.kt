package ru.neuroscribe.core

import kotlin.math.sqrt

/** Equal-weight cosine evidence from two independently computed neural vectors.
 * Missing one model is unknown, not a synthetic zero-vector confirmation.
 */
fun consensusSpeakerEmbedding(first:FloatArray?,second:FloatArray?):FloatArray? {
    val a=first?.let(::usableSpeakerEmbedding)?:return null
    val b=second?.let(::usableSpeakerEmbedding)?:return null
    val an=sqrt(a.sumOf { it.toDouble()*it }*2)
    val bn=sqrt(b.sumOf { it.toDouble()*it }*2)
    return FloatArray(a.size+b.size) { i -> if(i<a.size)(a[i]/an).toFloat() else (b[i-a.size]/bn).toFloat() }
}
