package ru.neuroscribe.core

import kotlin.math.sqrt

/** Add previously missing evidence without re-clustering already reliable turns.
 * A novel/ambiguous native participant is retained unless clear acoustic evidence
 * supports an existing reference group; duration alone never removes a voice.
 */
fun anchoredRefineSpeakers(turns:List<Turn>,baseline:List<FloatArray?>,clean:List<FloatArray?>,
                          durations:List<Float>,confirmation:List<FloatArray?>?=null,
                          checkpoint:()->Unit={}):List<Turn> {
    require(turns.size==baseline.size && turns.size==clean.size && turns.size==durations.size)
    require(durations.all { it.isFinite() && it>=0f })
    require(confirmation==null || confirmation.size==turns.size)
    fun normalized(values:List<FloatArray?>):List<FloatArray?> = values.map { raw -> raw?.let(::usableSpeakerEmbedding)?.let { v ->
        val norm=sqrt(v.sumOf { it.toDouble()*it });FloatArray(v.size) { (v[it]/norm).toFloat() }
    } }
    val b=normalized(baseline);val c=normalized(clean);val other=confirmation?.let(::normalized)
    require((b+c).filterNotNull().map { it.size }.distinct().size<=1)
    require(other==null || other.filterNotNull().map { it.size }.distinct().size<=1)
    val anchors=turns.indices.filter { b[it]!=null && turns[it].end-turns[it].start>=1.5f }
    if(anchors.size<2) return refineSpeakers(turns,clean,.6f,referenceDurations=durations,checkpoint=checkpoint)
    val result=refineSpeakers(turns,baseline,.6f,checkpoint=checkpoint).toMutableList()
    val groups=anchors.groupBy { result[it].speaker }
    if(groups.size<2)return result
    fun rank(vector:FloatArray?,references:List<FloatArray?>):List<Pair<Int,Double>> {
        if(vector==null)return emptyList()
        return groups.mapNotNull { (label,indices) ->
            val available=indices.mapNotNull { references[it] }
            if(available.isEmpty())null else label to available.map { v ->
                1.0-vector.indices.sumOf { d -> vector[d].toDouble()*v[d] }
            }.average()
        }.sortedBy { it.second }
    }
    fun certain(scores:List<Pair<Int,Double>>):Int? = if(scores.size>=2 && scores[0].second<=.4 &&
        scores[1].second-scores[0].second>=.1)scores[0].first else null
    for(i in turns.indices) if(b[i]==null && c[i]!=null) {
        checkpoint()
        val chosen=certain(rank(c[i],b))?:continue
        if(other!=null && certain(rank(other[i],other))!=chosen)continue
        result[i]=turns[i].copy(speaker=chosen)
    }
    return result
}
