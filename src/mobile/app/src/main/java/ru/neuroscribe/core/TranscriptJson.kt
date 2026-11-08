package ru.neuroscribe.core

import kotlin.math.roundToLong

/** The organizer's interchange format; names and display formatting stay outside it. */
fun exportJson(replicas: List<Replica>): String = buildString {
    append("{\"transcript\":[")
    replicas.forEachIndexed { index, replica ->
        require(replica.start.isFinite() && replica.end.isFinite() &&
            replica.start >= 0f && replica.end > replica.start && replica.speaker >= 0) {
            "Нельзя экспортировать реплику с некорректным временем или говорящим."
        }
        if (index > 0) append(',')
        append("{\"speaker_id\":\"speaker_")
        append(replica.speaker.toLong() + 1)
        append("\",\"start_ms\":")
        append((replica.start.toDouble() * 1000).roundToLong())
        append(",\"end_ms\":")
        append((replica.end.toDouble() * 1000).roundToLong())
        append(",\"text\":")
        appendJsonString(replica.text)
        append('}')
    }
    append("]}")
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        else -> if (character.code < 0x20 || character.isSurrogate()) {
            append("\\u").append(character.code.toString(16).padStart(4, '0'))
        } else append(character)
    }
    append('"')
}
