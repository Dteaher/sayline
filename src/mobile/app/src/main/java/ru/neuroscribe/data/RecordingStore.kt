package ru.neuroscribe.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import ru.neuroscribe.core.Replica
import java.io.File
import java.util.UUID

data class Recording(
    val id: String,
    val title: String,
    val created: Long = System.currentTimeMillis(),
    val duration: Float = 0f,
    val processingMs: Long = 0,
    val replicas: List<Replica> = emptyList(),
    val names: Map<Int, String> = emptyMap(),
    val status: String = "Ожидает обработки",
    val waveform: List<Float> = emptyList(),
    val originalReplicas: List<Replica>? = null,
) {
    /** Initial anonymous cluster labels survive manual reassignment and emptied clusters. */
    val speakerOrder: List<Int> get() = ((originalReplicas ?: replicas) + replicas).map { it.speaker }.distinct()
    fun speakerName(id: Int): String = names[id]?.takeIf { it.isNotBlank() } ?: "Говорящий ${speakerOrder.indexOf(id) + 1}"
}

class RecordingStore(private val context: Context) {
    private companion object { val fileLock = Any() }
    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    fun audio(id: String) = File(root, "$id.audio")
    fun create(title: String) = Recording(UUID.randomUUID().toString(), title)

    fun import(uri: Uri): Recording {
        val title = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Разговор"
        val recording = create(title.take(120))
        val target = audio(recording.id)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= ru.neuroscribe.core.AudioLimits.MAX_FILE_BYTES) { "Файл больше 512 МБ. Выберите более короткую запись." }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Не удалось открыть аудиофайл.")
            require(target.length() > 0) { "Выбран пустой файл." }
            save(recording)
            return recording
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun save(recording: Recording) = synchronized(fileLock) {
        val json = JSONObject().put("id", recording.id).put("title", recording.title)
            .put("created", recording.created).put("duration", recording.duration.toDouble())
            .put("processingMs", recording.processingMs).put("status", recording.status)
        json.put("replicas", JSONArray().apply {
            recording.replicas.forEach { r -> put(JSONObject().put("start", r.start.toDouble())
                .put("end", r.end.toDouble()).put("speaker", r.speaker).put("text", r.text)) }
        })
        json.put("names", JSONObject().apply { recording.names.forEach { (id, name) -> put(id.toString(), name) } })
        recording.originalReplicas?.let { original ->
            json.put("originalReplicas", JSONArray().apply {
                original.forEach { r -> put(JSONObject().put("start", r.start.toDouble())
                    .put("end", r.end.toDouble()).put("speaker", r.speaker).put("text", r.text)) }
            })
        }
        json.put("waveform", JSONArray(recording.waveform.map { it.toDouble() }))
        val atomic = AtomicFile(File(root, "${recording.id}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun update(id: String, transform: (Recording) -> Recording): Recording? = synchronized(fileLock) {
        val current = list().firstOrNull { it.id == id } ?: return@synchronized null
        val updated = transform(current)
        require(updated.id == id)
        save(updated)
        updated
    }

    fun list(): List<Recording> = synchronized(fileLock) {
        root.listFiles()?.filter { it.extension == "json" || it.name.endsWith(".json.bak") }
            ?.map { File(root, it.name.removeSuffix(".bak")) }?.distinctBy { it.name }?.mapNotNull { file ->
        runCatching {
            val j = JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
            val a = j.getJSONArray("replicas")
            val names = j.optJSONObject("names") ?: JSONObject()
            Recording(j.getString("id"), j.getString("title"), j.getLong("created"),
                j.getDouble("duration").toFloat(), j.getLong("processingMs"),
                (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    Replica(it.getDouble("start").toFloat(), it.getDouble("end").toFloat(), it.getInt("speaker"), it.getString("text"))
                } }, names.keys().asSequence().associate { it.toInt() to names.getString(it) },
                j.getString("status"), j.optJSONArray("waveform")?.let { levels -> (0 until levels.length()).map { levels.getDouble(it).toFloat() } } ?: emptyList(),
                j.optJSONArray("originalReplicas")?.let { original -> (0 until original.length()).map { i ->
                    original.getJSONObject(i).let { Replica(it.getDouble("start").toFloat(), it.getDouble("end").toFloat(), it.getInt("speaker"), it.getString("text")) }
                } })
        }.getOrNull()
        }?.sortedByDescending { it.created } ?: emptyList()
    }
}
