package ru.neuroscribe

import org.junit.Test
import org.junit.Assert.*
import ru.neuroscribe.core.*
import java.io.File

/** Evaluate the SAME pure Kotlin reference selection/refinement used by Android. */
class CleanReferenceEvaluation {
    private val root = File(requireNotNull(System.getenv("NEUROSCRIBE_CLEANREFS_EVAL")))
    private val split = System.getenv("NEUROSCRIBE_CLEANREFS_SPLIT") ?: "development"
    private val phase = System.getenv("NEUROSCRIBE_CLEANREFS_PHASE") ?: "windows"
    private val confirmation = System.getenv("NEUROSCRIBE_CLEANREFS_CONFIRMATION") == "true"

    @Test fun evaluateFrozenCases() {
        val keys = File(root,"$split-keys.txt").readLines().filter { it.isNotBlank() }
        assertTrue(keys.isNotEmpty())
        val thresholds = if (split=="holdout") File(root,"selected-native.txt").readText().trim().toInt() else -1
        for (key in keys) {
            val rows=File(root,"$key.turns.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            val turns=rows.map { Turn(it[0].toFloat(),it[1].toFloat(),it[2].toInt()) }
            val native=key.substringAfterLast("native").toInt()
            require(split!="holdout" || native==50 || native==thresholds)
            val clean=cleanReferenceWindows(turns).map { w -> w.maxByOrNull { it.end-it.start } }
            val cleanCandidates=clean.mapIndexedNotNull { i,t -> t?.let { i to it } }
            val cleanSelected=selectRefinementSamples(cleanCandidates.map { it.second }).map { cleanCandidates[it].first }.toSet()
            val baselineSelected=selectRefinementSamples(turns).toSet()
            val anchoredPlan=if(confirmation)anchoredReferencePlan(turns) else null
            if (phase=="windows") {
                for ((variant, selected) in listOf("baseline" to baselineSelected,"clean" to cleanSelected)) {
                    File(root,"$key.$variant.windows.tsv").writeText(selected.sorted().joinToString("\n") { i ->
                        val t=if (variant=="clean") clean[i]!! else turns[i]
                        val range=speakerReferenceSamples(t,180*16000)
                        "$i\t${range.start}\t${range.end}"
                    }+"\n")
                }
                if(anchoredPlan!=null)File(root,"$key.anchored.windows.tsv").writeText(
                    anchoredPlan.references.mapIndexedNotNull { i,t -> t?.let {
                        val range=speakerReferenceSamples(it,180*16000)
                        "$i\t${range.start}\t${range.end}"
                    } }.joinToString("\n")+"\n")
                continue
            }
            fun load(name:String):Pair<List<FloatArray?>,List<Float>> {
                val vectors=MutableList<FloatArray?>(turns.size){null}
                val durations=MutableList(turns.size){0f}
                for (line in File(root,"$key.$name.vectors.tsv").readLines().filter { it.isNotBlank() }) {
                    val f=line.split('\t');val i=f[0].toInt();durations[i]=f[1].toFloat()
                    if(f.size>2) vectors[i]=usableSpeakerEmbedding(f.drop(2).map { it.toFloatOrNull()?:Float.NaN }.toFloatArray())
                }
                return vectors to durations
            }
            val variants=if(split=="holdout") {
                val selected=File(root,"selected-variant.txt").takeIf { it.exists() }?.readText()?.trim()?:"clean"
                if(native==50 && thresholds==50) listOf("baseline",selected)
                else if(native==50)listOf("baseline") else listOf(selected)
            } else if(confirmation)listOf("baseline","clean","clean_resnet60","clean_fusion60","clean_fusion50","anchored","anchored_consensus") else listOf("baseline","clean")
            for (variant in variants) {
                val (vectors,durations)=when {
                    variant=="baseline" -> load("baseline")
                    variant.startsWith("anchored") -> load("anchored")
                    variant=="clean_resnet60" -> load("cleanresnet")
                    variant.startsWith("clean_fusion") -> {
                        val (a,d)=load("clean");val (b,_)=load("cleanresnet")
                        a.indices.map { consensusSpeakerEmbedding(a[it],b[it]) } to d
                    }
                    else -> load("clean")
                }
                val threshold=if(variant=="clean_fusion50").5f else .6f
                val refined=if(variant.startsWith("anchored")) {
                    val (c,d)=load("anchored")
                    val base=c.mapIndexed { i,v->v.takeIf { i in anchoredPlan!!.baselineIndices } }
                    anchoredRefineSpeakers(turns,base,c,d,if(variant=="anchored_consensus")load("anchoredresnet").first else null)
                } else refineSpeakers(turns,vectors,threshold,referenceDurations=if(variant!="baseline")durations else null)
                File(root,"$key.$variant.refined.tsv").writeText(refined.joinToString("\n") {
                    "${it.start}\t${it.end}\t${it.speaker}"
                }+"\n")
                val gaps=if(variant=="baseline")listOf(1.2f)
                    else if(split=="holdout") listOf(File(root,"selected-gap.txt").readText().trim().toFloat())
                    else if(confirmation)listOf(1.2f) else listOf(1.2f,.4f,0f)
                for(gap in gaps) {
                    val chosen=groupSpeakerTurns(refined,180f,gap)
                    File(root,"$key.$variant.gap${(gap*100).toInt()}.output.tsv").writeText(chosen.joinToString("\n") { "${it.start}\t${it.end}\t${it.speaker}" }+"\n")
                }
            }
        }
    }
}
