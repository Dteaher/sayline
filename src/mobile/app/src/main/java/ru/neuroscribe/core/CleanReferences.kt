package ru.neuroscribe.core

/** Recover contiguous clean evidence, not a waveform made by splicing syllables.
 * Subtract foreign native-speaker activity; the original transcript intervals
 * remain untouched. At most two candidates/ten seconds each per model turn.
 */
fun cleanReferenceWindows(turns: List<Turn>): List<List<Turn>> {
    require(turns.all { it.start.isFinite() && it.end.isFinite() && it.start >= 0f && it.end > it.start && it.speaker >= 0 })
    val blocked = Array(turns.size) { mutableListOf<Pair<Float, Float>>() }
    val active = mutableListOf<Int>()
    for (i in turns.indices.sortedBy { turns[it].start }) {
        val turn = turns[i]
        active.removeAll { turns[it].end <= turn.start }
        for (j in active) if (turn.speaker != turns[j].speaker) {
            val a = maxOf(turn.start, turns[j].start); val b = minOf(turn.end, turns[j].end)
            if (b > a) { blocked[i] += a to b; blocked[j] += a to b }
        }
        active += i
    }
    return turns.mapIndexed { i, turn ->
        val candidates = mutableListOf<Turn>()
        fun add(a: Float, b: Float) {
            if (b-a >= .2f) {
                val start = if (b-a > 10f) a+(b-a-10f)/2 else a
                candidates += Turn(start, minOf(b,start+10f),turn.speaker)
            }
        }
        var cursor = turn.start
        for ((a,b) in blocked[i].sortedBy { it.first }) {
            if (a > cursor) add(cursor,a)
            cursor = maxOf(cursor,b)
        }
        if (cursor < turn.end) add(cursor,turn.end)
        candidates.sortedWith(compareByDescending<Turn> { it.end-it.start }.thenBy { it.start })
            .take(2).sortedBy { it.start }
    }
}

fun bestCleanReference(turns: List<Turn>, index: Int): Turn? =
    cleanReferenceWindows(turns)[index].maxByOrNull { it.end-it.start }

data class SpeakerReferenceSamples(val start:Int,val end:Int)

/** Share exact float-to-int rounding with host evaluation; no half-sample drift. */
fun speakerReferenceSamples(turn:Turn,sampleCount:Int):SpeakerReferenceSamples {
    require(sampleCount>=0 && turn.start.isFinite() && turn.end.isFinite() && turn.end>turn.start)
    val start=(turn.start*16000).toInt().coerceIn(0,sampleCount)
    val stop=(turn.end*16000).toInt().coerceIn(start,sampleCount)
    val begin=if(stop-start>160000)start+(stop-start-160000)/2 else start
    return SpeakerReferenceSamples(begin,minOf(stop,begin+160000))
}
