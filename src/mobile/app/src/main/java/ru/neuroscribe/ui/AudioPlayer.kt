package ru.neuroscribe.ui

import android.media.MediaPlayer
import androidx.compose.runtime.*
import java.io.File

/** Owns the prepared player; callbacks from superseded instances are ignored. */
class AudioPlayer(private val file: (String) -> File) {
    var recordingId by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var preparing by mutableStateOf(false); private set
    var position by mutableFloatStateOf(0f); private set
    var duration by mutableFloatStateOf(0f); private set
    var activeReplica by mutableStateOf<Int?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var player: MediaPlayer? = null
    private var end: Float? = null

    fun toggle(id: String) {
        if (recordingId == id && playing) pause()
        else play(id, if (recordingId == id && position < duration - .1f) position else 0f)
    }

    fun play(id: String, start: Float = 0f, until: Float? = null, replica: Int? = null) {
        release()
        recordingId = id
        position = start
        end = until
        error = null
        val next = MediaPlayer()
        player = next
        preparing = true
        try {
            next.setDataSource(file(id).absolutePath)
            fun begin(prepared: MediaPlayer) {
                if (player !== prepared) return
                runCatching {
                    prepared.start()
                    preparing = false
                    playing = true
                    activeReplica = replica
                }.onFailure { fail() }
            }
            next.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    duration = prepared.duration / 1000f
                    if (start <= 0f) begin(prepared)
                    else runCatching { prepared.seekTo((start.coerceAtMost(duration) * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }.onFailure { fail() }
                }
            }
            next.setOnSeekCompleteListener { begin(it) }
            next.setOnCompletionListener {
                if (player === it) { playing = false; activeReplica = null; position = duration }
            }
            next.setOnErrorListener { failed, _, _ -> if (player === failed) fail(); true }
            next.prepareAsync()
        } catch (_: Exception) { fail() }
    }

    fun seek(id: String, fraction: Float, fallbackDuration: Float) {
        val length = if (recordingId == id && duration > 0) duration else fallbackDuration
        if (length > 0) play(id, (fraction.coerceIn(0f, 1f) * length).coerceAtMost((length-.05f).coerceAtLeast(0f)))
    }

    fun tick() {
        val current = player ?: return
        if (!playing) return
        runCatching {
            position = current.currentPosition / 1000f
            if (end != null && position >= end!!) pause()
        }.onFailure { fail() }
    }

    fun pause() {
        if (playing) runCatching { player?.pause() }
        playing = false
        activeReplica = null
    }

    fun clearError() { error = null }
    private fun fail() { release(); error = "Не удалось воспроизвести аудио. Попробуйте открыть запись ещё раз." }
    fun release() {
        playing = false
        preparing = false
        activeReplica = null
        val previous = player
        player = null
        previous?.release()
    }
}
