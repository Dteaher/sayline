package ru.neuroscribe.core

/** Mixed model intervals are not independent clean references. Sorted sweep,
 * rather than checking every pair in a fragmented recording. */
fun mixedReferenceTurns(turns: List<Turn>): BooleanArray {
    val mixed = BooleanArray(turns.size)
    val active = mutableListOf<Int>()
    for (i in turns.indices.sortedBy { turns[it].start }) {
        val turn = turns[i]
        active.removeAll { turns[it].end <= turn.start + .05f }
        for (j in active) if (turn.speaker != turns[j].speaker &&
            minOf(turn.end, turns[j].end) - maxOf(turn.start, turns[j].start) > .05f) {
            mixed[i] = true; mixed[j] = true
        }
        active += i
    }
    return mixed
}

/** Decide the inference budget BEFORE extracting embeddings. Native groups are
 * weak coverage evidence, never a forced participant count. Include late voices
 * before spending the rest of the budget evenly through the recording. */
fun selectRefinementSamples(turns: List<Turn>, maxSamples: Int = 256, maxSeconds: Float = 600f): List<Int> {
    require(maxSamples in 1..512 && maxSeconds.isFinite() && maxSeconds > 0)
    val mixed = mixedReferenceTurns(turns)
    val eligible = turns.indices.filter { !mixed[it] && turns[it].end > turns[it].start }
    val selected = linkedSetOf<Int>()
    var seconds = 0f
    fun add(index: Int) {
        if (index in selected) return
        val cost = minOf(10f, turns[index].end - turns[index].start)
        if (selected.size < maxSamples && seconds + cost <= maxSeconds) { selected += index; seconds += cost }
    }
    if (eligible.size <= 128 && eligible.sumOf { minOf(10f, turns[it].end-turns[it].start).toDouble() } <= maxSeconds) {
        eligible.forEach(::add)
    } else {
        val anchors = eligible.filter { turns[it].end - turns[it].start >= 1.5f }
        anchors.groupBy { turns[it].speaker }.values.forEach { group ->
            add(group.maxBy { minOf(10f, turns[it].end-turns[it].start) })
        }
        // Coarse-to-fine temporal coverage avoids selecting only the beginning.
        var step = 1
        while (step <= anchors.size && selected.size < maxSamples) {
            for (i in 0 until step) add(anchors[((i + .5) * anchors.size / step).toInt().coerceAtMost(anchors.lastIndex)])
            step *= 2
        }
        anchors.forEach(::add)
        eligible.filter { turns[it].end-turns[it].start < 1.5f }.forEach(::add)
    }
    return selected.sorted()
}

data class AnchoredReferencePlan(val references:List<Turn?>,val baselineIndices:Set<Int>)

/** Existing references get priority. Supplement only from remaining budget,
 * ensuring recovered overlap evidence never displaces a reliable old anchor. */
fun anchoredReferencePlan(turns:List<Turn>):AnchoredReferencePlan {
    val baseline=selectRefinementSamples(turns).toSet()
    val references=MutableList<Turn?>(turns.size){i->turns[i].takeIf { i in baseline }}
    val remainingSeconds=600f-baseline.sumOf { minOf(10f,turns[it].end-turns[it].start).toDouble() }.toFloat()
    if(baseline.size>=256 || remainingSeconds<=0)return AnchoredReferencePlan(references,baseline)
    val clean=cleanReferenceWindows(turns).map { windows->windows.maxByOrNull { it.end-it.start } }
    val candidates=clean.mapIndexedNotNull { i,t-> t?.takeIf { i !in baseline }?.let { i to it } }
    if(candidates.isNotEmpty()) {
        val chosen=selectRefinementSamples(candidates.map { it.second },256-baseline.size,remainingSeconds)
        for(j in chosen)references[candidates[j].first]=candidates[j].second
    }
    return AnchoredReferencePlan(references,baseline)
}
