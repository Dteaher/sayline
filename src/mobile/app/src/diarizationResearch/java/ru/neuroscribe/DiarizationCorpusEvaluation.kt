package ru.neuroscribe

import org.junit.Test
import org.junit.Assert.*
import ru.neuroscribe.core.*
import java.io.File

/** Opt-in JVM research source set; evaluates actual production Kotlin on cached neural vectors. */
class DiarizationCorpusEvaluation {
    @Test fun compareFrozenBaselineAndCandidateOnCachedNativeTurns() {
        val root = File(requireNotNull(System.getenv("NEUROSCRIBE_DIARIZATION_EVAL")))
        val split = System.getenv("NEUROSCRIBE_DIARIZATION_SPLIT") ?: "development"
        val allowed = File(root, "$split-keys.txt").readLines().filter { it.isNotBlank() }.toSet()
        assertTrue(allowed.isNotEmpty())
        for (key in allowed) {
            require(split != "holdout" || key.endsWith("on030") || key.endsWith("on015")) { "Exploratory holdout key rejected" }
            val rows = File(root, "$key.input.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            val turns = rows.map { Turn(it[0].toFloat(), it[1].toFloat(), it[2].toInt()) }
            val vectors = rows.map { row -> if (row.size == 4) null else {
                val parsed = row.drop(4).map { it.toFloatOrNull() ?: Float.NaN }.toFloatArray()
                usableSpeakerEmbedding(parsed)
            } }
            val duration = 120f
            val baseline = if (turns.size in 2..128) legacyRefineSpeakers(turns, vectors) else turns
            val fast = if (turns.size in 2..128) refineSpeakers(turns, vectors) else turns
            val selected = selectRefinementSamples(turns).toSet()
            val boundedVectors = vectors.mapIndexed { i, v -> v.takeIf { i in selected } }
            val bounded = refineSpeakers(turns, boundedVectors)
            val guard = refineSpeakers(turns, boundedVectors, preserveOverlap = true)
            val variants = mutableListOf("baseline" to baseline, "fast" to fast, "bounded" to bounded, "guard" to guard,
                "global" to refineSpeakers(turns, boundedVectors, globalShortAssignment = true))
            for (threshold in listOf(.45f, .55f, .6f, .65f))
                variants += "threshold${(threshold*100).toInt()}" to refineSpeakers(turns, boundedVectors, threshold)
            val evaluated = if (split == "holdout") {
                if (key.endsWith("on030")) listOf("baseline" to baseline)
                else listOf("selected" to refineSpeakers(turns, boundedVectors, .6f))
            } else variants
            for ((name, chosen) in evaluated) {
                for ((stage, result) in listOf("refined" to chosen, "grouped" to groupSpeakerTurns(chosen, duration))) {
                    File(root, "$key.$name.$stage.tsv").writeText(result.joinToString("\n") { "${it.start}\t${it.end}\t${it.speaker}" } + "\n")
                }
            }
        }
    }

    @Test fun measureClusteringOnFixedVectorsWithoutCallingItAndroidPerformance() {
        val root = File(requireNotNull(System.getenv("NEUROSCRIBE_DIARIZATION_EVAL")))
        val random = java.util.Random(20261108)
        val times = mutableListOf<String>()
        for (n in listOf(128, 256, 512)) {
            val turns = (0 until n).map { Turn(it*3f,it*3f+2f,it%4) }
            val vectors = turns.map { FloatArray(64) { random.nextFloat() } }
            for (algorithm in if (n == 128) listOf("legacy","chain") else listOf("chain")) {
                fun run() = if (algorithm == "legacy") legacyRefineSpeakers(turns,vectors) else refineSpeakers(turns,vectors)
                repeat(3) { run() }
                val elapsed = (0 until 7).map { val began=System.nanoTime();run();(System.nanoTime()-began)/1_000_000.0 }.sorted()
                times += "{\"algorithm\":\"$algorithm\",\"anchors\":$n,\"median_ms\":${elapsed[3]}}"
            }
        }
        File(root,"clustering-host-benchmark.json").writeText("{\"scope\":\"Host JVM synthetic vectors, not Android inference\",\"results\":[${times.joinToString(",")}]}\n")
    }
}
