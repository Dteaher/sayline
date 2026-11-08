package ru.neuroscribe

import java.io.File
import org.junit.Test
import ru.neuroscribe.core.*

class FragmentValidation {
    @Test fun frozenPolicy() {
        val root = File(requireNotNull(System.getenv("NEUROSCRIBE_FRAGMENT_CACHE")))
        val windows = System.getenv("NEUROSCRIBE_FRAGMENT_PHASE") == "windows"
        for (key in File(root,"holdout-keys.txt").readLines().filter { it.isNotBlank() }) {
            val turns = File(root,"$key.turns.tsv").readLines().filter { it.isNotBlank() }.map {
                val f=it.split('\t'); Turn(f[0].toFloat(),f[1].toFloat(),f[2].toInt())
            }
            val plan=anchoredReferencePlan(turns)
            if (windows) {
                File(root,"$key.anchored.windows.tsv").writeText(plan.references.mapIndexedNotNull { i,t -> t?.let {
                    val s=speakerReferenceSamples(it,2880000); "$i\t${s.start}\t${s.end}"
                } }.joinToString("\n")+"\n")
                continue
            }
            val vectors=MutableList<FloatArray?>(turns.size){null}; val durations=MutableList(turns.size){0f}
            for(line in File(root,"$key.anchored.vectors.tsv").readLines().filter { it.isNotBlank() }) {
                val f=line.split('\t');val i=f[0].toInt();durations[i]=f[1].toFloat()
                if(f.size>2)vectors[i]=usableSpeakerEmbedding(f.drop(2).map(String::toFloat).toFloatArray())
            }
            val current=anchoredRefineSpeakers(turns,vectors.mapIndexed { i,v -> v.takeIf { i in plan.baselineIndices } },vectors,durations)
            val proposals=fragmentMergeProposals(current,vectors,durations)
            for((variant,raw) in listOf("current" to current,"candidate" to applyFragmentMerges(current,proposals))) {
                File(root,"$key.$variant.tsv").writeText(groupSpeakerTurns(raw,180f).joinToString("\n") { "${it.start}\t${it.end}\t${it.speaker}" }+"\n")
            }
            File(root,"$key.proposals.txt").writeText(proposals.joinToString("\n"))
        }
    }
}
