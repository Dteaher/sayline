package ru.neuroscribe.core

fun editReplica(replicas: List<Replica>, index: Int, text: String, speaker: Int,
                allowedSpeakers: Set<Int> = replicas.map { it.speaker }.toSet()): List<Replica> {
    require(index in replicas.indices) { "Реплика больше не существует." }
    require(text.isNotBlank() && text.length <= 20000) { "Введите текст реплики (до 20 000 символов)." }
    require(speaker in allowedSpeakers) { "Выберите участника этой записи." }
    return replicas.mapIndexed { i, replica -> if (i == index) replica.copy(text = text.trim(), speaker = speaker) else replica }
}

fun restoreReplica(replicas: List<Replica>, original: List<Replica>, index: Int): List<Replica> {
    require(replicas.size == original.size && index in replicas.indices) { "Не удалось сопоставить исходную реплику." }
    require(replicas[index].start == original[index].start && replicas[index].end == original[index].end)
    return replicas.mapIndexed { i, replica -> if (i == index) original[i] else replica }
}
