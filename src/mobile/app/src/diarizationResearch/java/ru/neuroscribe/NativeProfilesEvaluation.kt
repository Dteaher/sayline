package ru.neuroscribe

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*

class NativeProfilesEvaluation {
    @Test fun evaluateActualAndroidEvidenceWithProductionFunctions() {
        val dir=File(requireNotNull(System.getenv("NEUROSCRIBE_NATIVE_PROFILE_DIR")))
        for(key in File(dir,"keys.txt").readLines().filter { it.isNotBlank() }) {
            val bytes=File(dir,"$key.trace.f32").readBytes();val buf=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val trace=decodeNativeDiarizationTrace(FloatArray(bytes.size/4) { buf.float },180f)
            val refined=File(dir,"$key.refined.tsv").readLines().filter { it.isNotBlank() }.map {
                val f=it.split('\t');Turn(f[0].toFloat(),f[1].toFloat(),f[2].toInt())
            }
            val proposals=nativeProfileMerges(trace.turns,refined,trace.references)
            val prior=anchoredReferencePlan(trace.turns)
            val recovered=nativeReferenceRecovery(trace.turns,trace.references)
            File(dir,"$key.reference-counts.tsv").writeText("${trace.references.size}\t${prior.references.count { it!=null }}\t${recovered.references.count { it!=null }}\n")
            val actual=proposals.map { "${it.source}\t${it.target}" }.sorted()
            val expected=File(dir,"$key.expected-proposals.tsv").readLines().filter { it.isNotBlank() }.sorted()
            assertEquals("Python/production policy parity: $key",expected,actual)
            val centered=nativeProfileMerges(trace.turns,refined,trace.references,centerProfiles=true)
            val centeredExpected=File(dir,"$key.expected-centered-proposals.tsv")
            if(centeredExpected.exists())assertEquals("Centered policy parity: $key",
                centeredExpected.readLines().filter { it.isNotBlank() }.sorted(),
                centered.map { "${it.source}\t${it.target}" }.sorted())
            val result=groupSpeakerTurns(applyFragmentMerges(refined,proposals),180f)
            File(dir,"$key.candidate.tsv").writeText(result.joinToString("\n") { "${it.start}\t${it.end}\t${it.speaker}" }+"\n")
        }
    }
}
