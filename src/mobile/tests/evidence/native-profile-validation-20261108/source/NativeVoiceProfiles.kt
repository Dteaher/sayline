package ru.neuroscribe.core

import kotlin.math.sqrt

/** Native embedding space stays separate from ERes2Net. Profiles are assigned
 * through clean model intervals, not speaker-ID equality across passes. */
fun nativeProfileMerges(original: List<Turn>, refined: List<Turn>, trace: List<NativeVoiceReference>): List<FragmentMerge> {
    require(original.size==refined.size)
    require(original.indices.all { original[it].start==refined[it].start && original[it].end==refined[it].end })
    fun union(spans:List<SpeechSpan>):Double {
        var end=Double.NEGATIVE_INFINITY;var total=0.0
        for(s in spans.sortedBy { it.start }) { total+=maxOf(0.0,s.end-maxOf(s.start.toDouble(),end));end=maxOf(end,s.end.toDouble()) }
        return total
    }
    fun overlaps(a:List<SpeechSpan>,b:List<SpeechSpan>):List<SpeechSpan> = a.flatMap { s -> b.mapNotNull { t ->
        val start=maxOf(s.start,t.start);val end=minOf(s.end,t.end)
        if(end>start)SpeechSpan(start,end) else null
    } }
    val groups=refined.groupBy { it.speaker }.mapValues { (_,ts)->ts.map { SpeechSpan(it.start,it.end) } }
    data class Reference(val source:NativeVoiceReference,val vector:DoubleArray,val mass:Double)
    val profiles=groups.keys.associateWith { mutableListOf<Reference>() }
    val compatible=original.indices.groupBy { original[it].speaker }.mapValues { (_,indices) ->
        indices.groupBy { refined[it].speaker }.mapValues { (_,ids)->ids.map { SpeechSpan(original[it].start,original[it].end) } }
    }
    for(r in trace) {
        val vector=usableSpeakerEmbedding(r.embedding)?:continue
        val mass=union(r.clean)
        if(mass<=0)continue
        val ranked=compatible[r.nativeSpeaker].orEmpty().map { (g,ss)->g to union(overlaps(r.clean,ss)) }
            .sortedWith(compareByDescending<Pair<Int,Double>> { it.second }.thenByDescending { it.first })
        if(ranked.isEmpty() || ranked[0].second/mass<.7)continue
        if(ranked.size>1 && (ranked[0].second-ranked[1].second)/mass<.3)continue
        val norm=sqrt(vector.sumOf { it.toDouble()*it })
        profiles.getValue(ranked[0].first)+=Reference(r,DoubleArray(vector.size) { vector[it]/norm },ranked[0].second)
    }
    require(profiles.values.flatten().map { it.vector.size }.distinct().size<=1)
    val cores=groups.keys.filter { union(groups.getValue(it))>=5 && profiles.getValue(it).size>=2 }
    if(cores.size<2)return emptyList()
    val proposed=groups.keys.filter { it !in cores && profiles.getValue(it).size>=2 }.mapNotNull { source ->
        val ranked=cores.map { target ->
            var sum=0.0;var weight=0.0
            for(r in profiles.getValue(source)) {
                val similarities=profiles.getValue(target).map { q -> r.vector.indices.sumOf { d->r.vector[d]*q.vector[d] } }
                    .sortedDescending().take(2)
                val w=minOf(2.0,r.mass);sum+=similarities.average()*w;weight+=w
            }
            target to sum/weight
        }.sortedWith(compareByDescending<Pair<Int,Double>> { it.second }.thenByDescending { it.first })
        val (target,similarity)=ranked[0];val margin=similarity-ranked[1].second
        if(similarity<.55 || margin<.1 || union(overlaps(groups.getValue(source),groups.getValue(target)))>.2)return@mapNotNull null
        val localConflicts=profiles.getValue(source).flatMap { r -> profiles.getValue(target).flatMap { q ->
            if(r.source.window==q.source.window && r.source.localSpeaker!=q.source.localSpeaker)
                overlaps(r.source.active,q.source.active) else emptyList()
        } }
        if(union(localConflicts)>.2)null else FragmentMerge(source,target,similarity,margin)
    }
    val rejected=mutableSetOf<Int>()
    for(i in proposed.indices)for(j in 0 until i)if(proposed[i].target==proposed[j].target &&
        union(overlaps(groups.getValue(proposed[i].source),groups.getValue(proposed[j].source)))>.2) {
        rejected+=proposed[i].source;rejected+=proposed[j].source
    }
    return proposed.filter { it.source !in rejected }
}
