package ru.neuroscribe.core

data class SpeechSpan(val start: Float, val end: Float)
data class NativeVoiceReference(val window: Int, val localSpeaker: Int, val nativeSpeaker: Int,
    val embedding: FloatArray, val clean: List<SpeechSpan>, val active: List<SpeechSpan>)
data class NativeDiarizationTrace(val turns: List<Turn>, val confidence: List<Float?>,
    val references: List<NativeVoiceReference>)

/** Versioned bounded JNI protocol; activity is a model label, never a probability. */
fun decodeNativeDiarizationTrace(values: FloatArray, duration: Float): NativeDiarizationTrace {
    require(duration.isFinite() && duration > 0f && duration <= 1200f)
    require(values.size in 5..16_000_000)
    var position = 0
    fun number(): Float { require(position < values.size); return values[position++].also { require(it.isFinite()) } }
    fun integer(maximum: Int): Int = number().let { require(it >= 0f && it <= maximum && it == it.toInt().toFloat()); it.toInt() }
    require(integer(1) == 1 && integer(16000) == 16000)
    val dimension = integer(1024); val count = integer(4096); val segmentCount = integer(100000)
    require(count == 0 || dimension > 0)
    fun span(): SpeechSpan {
        val a=number();val b=number()
        require(a >= 0f && b > a && b <= duration + .01f)
        return SpeechSpan(a,b.coerceAtMost(duration))
    }
    val confidence=ArrayList<Float?>(segmentCount)
    val turns=List(segmentCount) {
        val s=span();val speaker=integer(100000);val c=number()
        require(c == -2f || c in -1f..1f)
        confidence += c.takeIf { it in -1f..1f }
        Turn(s.start,s.end,speaker)
    }
    val references=List(count) {
        val window=integer(1200);val local=integer(2);val speaker=integer(100000)
        val cleanCount=integer(1000);val activeCount=integer(1000)
        val vector=FloatArray(dimension) { number() }
        require(usableSpeakerEmbedding(vector) != null)
        NativeVoiceReference(window,local,speaker,vector,List(cleanCount) { span() },List(activeCount) { span() })
    }
    require(position == values.size)
    return NativeDiarizationTrace(turns,confidence,references)
}
