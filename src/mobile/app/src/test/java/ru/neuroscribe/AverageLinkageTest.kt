package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.averageLinkage
import java.util.Random

class AverageLinkageTest {
    @Test fun matchesGlobalMinimumOracleOnIndependentDistances() {
        val random = Random(20261108)
        repeat(80) {
            val n = 3 + random.nextInt(35)
            val matrix = Array(n) { FloatArray(n) }
            for (i in 0 until n) for (j in 0 until i) {
                matrix[i][j] = random.nextFloat(); matrix[j][i] = matrix[i][j]
            }
            for (threshold in listOf(.1f, .4f, .7f)) {
                val expected = oracle(matrix, threshold)
                val actual = averageLinkage(matrix, threshold)
                for (i in 0 until n) for (j in 0 until i)
                    assertEquals("partition $i,$j", expected[i] == expected[j], actual[i] == actual[j])
            }
        }
    }

    @Test fun highFirstReciprocalPairDoesNotHideLaterLowPair() {
        val m = arrayOf(floatArrayOf(0f, .8f, 1f, 1f), floatArrayOf(.8f, 0f, 1f, 1f),
            floatArrayOf(1f, 1f, 0f, .1f), floatArrayOf(1f, 1f, .1f, 0f))
        val labels = averageLinkage(m, .5f)
        assertNotEquals(labels[0], labels[1]); assertEquals(labels[2], labels[3])
    }

    @Test fun duplicatesTiesAndInclusiveBoundaryAreDeterministic() {
        val m = Array(8) { i -> FloatArray(8) { j -> if (i == j) 0f else .5f } }
        assertEquals(1, averageLinkage(m, .5f).distinct().size)
        assertEquals(8, averageLinkage(m, Math.nextDown(.5f)).distinct().size)
        assertArrayEquals(averageLinkage(m, .5f), averageLinkage(m, .5f))
        assertArrayEquals(intArrayOf(), averageLinkage(emptyArray(), .5f))
        assertArrayEquals(intArrayOf(0), averageLinkage(arrayOf(floatArrayOf(0f)), .5f))
    }

    @Test fun boundedLargeMatrixKeepsReturningVoices() {
        val m = Array(512) { i -> FloatArray(512) { j -> if (i % 4 == j % 4) .01f else .95f } }
        val labels = averageLinkage(m, .5f)
        assertEquals(4, labels.distinct().size)
        assertEquals(labels[0], labels[508])
    }

    @Test fun invalidDistancesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { averageLinkage(arrayOf(floatArrayOf(Float.NaN)), .5f) }
        assertThrows(IllegalArgumentException::class.java) { averageLinkage(arrayOf(floatArrayOf(0f, 1f)), .5f) }
    }

    private fun oracle(input: Array<FloatArray>, threshold: Float): IntArray {
        val groups = input.indices.map { mutableListOf(it) }.toMutableList()
        while (groups.size > 1) {
            var best = threshold; var left = -1; var right = -1
            for (i in groups.indices) for (j in 0 until i) {
                val d = groups[i].sumOf { a -> groups[j].sumOf { b -> input[a][b].toDouble() } } / (groups[i].size * groups[j].size)
                if (d <= best && (left < 0 || d < best)) { best = d.toFloat(); left = i; right = j }
            }
            if (left < 0) break
            groups[right].addAll(groups[left]); groups.removeAt(left)
        }
        val result = IntArray(input.size)
        groups.forEachIndexed { k, g -> g.forEach { result[it] = k } }
        return result
    }
}
