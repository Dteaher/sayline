package ru.neuroscribe.core

/** Recover evidence hidden by contradictory final overlap labels. Require two
 * different native windows to agree on clean speech of the SAME native ID.
 * Existing references retain priority and the shared256/600s budget.
 */
fun nativeReferenceRecovery(turns: List<Turn>, trace: List<NativeVoiceReference>): AnchoredReferencePlan {
    val prior=anchoredReferencePlan(turns)
    if(trace.isEmpty())return prior
    val references=prior.references.toMutableList()
    var seconds=references.filterNotNull().sumOf { minOf(10f,it.end-it.start).toDouble() }
    var count=references.count { it != null }
    if(count>=256 || seconds>=600)return prior
    data class Event(val time:Float,val window:Int,val delta:Int)
    val bySpeaker=trace.groupBy { it.nativeSpeaker }
    val candidates=turns.indices.filter { references[it]==null }.mapNotNull { index ->
        val turn=turns[index]
        val events=bySpeaker[turn.speaker].orEmpty().flatMap { r -> r.clean.flatMap { s ->
            val a=maxOf(turn.start,s.start);val b=minOf(turn.end,s.end)
            if(b>a)listOf(Event(a,r.window,1),Event(b,r.window,-1)) else emptyList()
        } }.groupBy { it.time }.toSortedMap()
        val active=mutableMapOf<Int,Int>()
        var begin:Float?=null;var best:Turn?=null
        for((time,changes) in events) {
            val before=active.count { it.value>0 }>=2
            for(e in changes)active[e.window]=(active[e.window]?:0)+e.delta
            val after=active.count { it.value>0 }>=2
            if(!before && after)begin=time
            if(before && !after) {
                val a=begin!!;val length=time-a
                if(length>=.35f && (best==null || length>best!!.end-best!!.start)) {
                    val start=if(length>10f)a+(length-10f)/2 else a
                    best=Turn(start,minOf(time,start+10f),turn.speaker)
                }
                begin=null
            }
        }
        best?.let { index to it }
    }.sortedByDescending { it.second.end-it.second.start }
    // Coverage first, then remaining longest candidates; no new voice is forced.
    val order=(candidates.groupBy { it.second.speaker }.values.map { it.first() }+candidates).distinctBy { it.first }
    for((index,reference) in order) {
        val cost=reference.end-reference.start
        if(count<256 && seconds+cost<=600) {
            references[index]=reference;seconds+=cost;count++
        }
    }
    return AnchoredReferencePlan(references,prior.baselineIndices)
}
