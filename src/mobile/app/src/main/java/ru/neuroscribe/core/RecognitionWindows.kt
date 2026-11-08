package ru.neuroscribe.core

import kotlin.math.roundToLong

/** Add silence around a model turn without extending into another interval.
 * Existing overlap inside the model turn remains intact; no source separation is implied.
 */
fun recognitionContext(turns: List<Turn>, index: Int, duration: Float): Turn {
    require(duration.isFinite() && duration >= 0f)
    val turn = turns[index]
    var start = maxOf(0f, turn.start - .3f)
    var end = minOf(duration, turn.end + .3f)
    // An earlier long turn can contain several later turns. Looking only at
    // adjacent list entries misses its activity on either side of this turn.
    turns.forEachIndexed { otherIndex, other ->
        if (otherIndex != index) {
            if (other.start < turn.start) start = maxOf(start, minOf(turn.start, other.end))
            if (other.end > turn.end) end = minOf(end, maxOf(turn.end, other.start))
        }
    }
    return turn.copy(start = minOf(turn.start, start), end = maxOf(turn.end, end))
}

data class RecognitionWindow(
    val startSample: Int,
    val endSample: Int,
    val keepStartSample: Int,
    val keepEndSample: Int,
) {
    init {
        require(startSample >= 0 && startSample <= keepStartSample &&
            keepStartSample < keepEndSample && keepEndSample <= endSample)
    }
}

/** Recognition is bounded; public replica timestamps remain the model's outer boundaries. */
fun recognitionWindows(turn: Turn, sampleCount: Int): List<RecognitionWindow> {
    require(sampleCount >= 0)
    if (!turn.start.isFinite() || !turn.end.isFinite()) return emptyList()
    val begin = (turn.start.toDouble() * 16000).toLong().coerceIn(0, sampleCount.toLong()).toInt()
    val end = (turn.end.toDouble() * 16000).toLong().coerceIn(0, sampleCount.toLong()).toInt()
    val windows = mutableListOf<RecognitionWindow>()
    var start = begin
    while (start < end) {
        val stop = minOf(end, start + 20 * 16000)
        windows += RecognitionWindow(maxOf(begin, start - 16000), minOf(end, stop + 16000), start, stop)
        start = stop
    }
    return windows
}

/** Assign whole words by their first lexical token; delayed punctuation follows that word. */
fun recognizedWindowText(tokens: Array<String>, timestamps: FloatArray, window: RecognitionWindow): String {
    require(tokens.size == timestamps.size)
    require(tokens.all { it.isNotEmpty() })
    require(timestamps.all { it.isFinite() && it >= 0f })
    require((1 until timestamps.size).all { timestamps[it] >= timestamps[it - 1] })
    data class Word(val text: StringBuilder, val sample: Long)
    val words = mutableListOf<Word>()
    var space = true
    tokens.forEachIndexed { index, token ->
        for (character in token) {
            if (character.isWhitespace() || Character.isSpaceChar(character)) {
                space = true
            } else if (character.isLetterOrDigit()) {
                if (space || words.isEmpty()) {
                    words += Word(StringBuilder(), window.startSample + (timestamps[index].toDouble() * 16000).roundToLong())
                }
                words.last().text.append(character)
                space = false
            } else if (words.isNotEmpty()) {
                words.last().text.append(character)
            }
        }
    }
    return words.filter { it.sample >= window.keepStartSample && it.sample < window.keepEndSample }
        .joinToString(" ") { it.text.toString() }
}
