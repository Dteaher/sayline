package ru.neuroscribe

import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.neuroscribe.audio.AudioDecoder
import ru.neuroscribe.data.Recording
import ru.neuroscribe.data.RecordingStore
import ru.neuroscribe.ml.SpeechEngine
import ru.neuroscribe.core.waveformEnvelope

data class ScreenState(
    val history: List<Recording> = emptyList(),
    val selected: Recording? = null,
    val stage: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val recording: Boolean = false,
    val recordingSeconds: Long = 0,
    val recordingPaused: Boolean = false,
    val recordingLevels: List<Float> = emptyList(),
)

class NeuroViewModel(app: Application) : AndroidViewModel(app) {
    val store = RecordingStore(app)
    private val mutable = sharedState
    val state = mutable.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var pending: Recording? = null
    private var recordingClock: Job? = null
    private var previewJob: Job? = null
    private var activeStarted = 0L
    private var activeMillis = 0L
    private val microphoneLevels = mutableListOf<Float>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val history = store.list().map {
                if (it.status == "Обработка" && job?.isActive != true) it.copy(status = "Обработка прервана").also(store::save) else it
            }
            mutable.update { it.copy(history = history) }
        }
    }

    fun select(recording: Recording?) {
        previewJob?.cancel()
        previewJob = null
        mutable.update { it.copy(selected = recording, error = null) }
        if (recording != null && recording.waveform.isEmpty()) previewJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val pcm = AudioDecoder().decode(store.audio(recording.id))
                recording.copy(duration = pcm.size / 16000f, waveform = waveformEnvelope(pcm))
            }.onSuccess { prepared ->
                if (mutable.value.selected?.id == recording.id && mutable.value.stage == null) {
                    runCatching { store.update(recording.id) { latest -> latest.copy(duration = prepared.duration, waveform = prepared.waveform) } }
                    val history = store.list()
                    mutable.update { latest -> latest.copy(
                        selected = if (latest.selected?.id == recording.id && latest.stage == null)
                            latest.selected.copy(duration = prepared.duration, waveform = prepared.waveform) else latest.selected,
                        history = history) }
                }
            }
        }
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun clearNotice() { mutable.update { it.copy(notice = null) } }
    fun permissionDenied() { mutable.update { it.copy(error = "Для записи нужен доступ к микрофону. Разрешите его в настройках приложения или импортируйте аудио.") } }

    fun import(uri: Uri) = operate {
        mutable.update { it.copy(stage = "Копируем аудио на устройство") }
        val recording = store.import(uri)
        mutable.update { it.copy(selected = recording) }
        process(recording)
    }

    fun retry() { mutable.value.selected?.let { recording -> operate { process(recording) } } }

    fun editTurn(index: Int, expected: ru.neuroscribe.core.Replica, text: String, speaker: Int) {
        updateTranscript { current ->
            require(current.replicas.getOrNull(index) == expected) { "Реплика изменилась. Откройте её заново." }
            current.copy(originalReplicas = current.originalReplicas ?: current.replicas,
                replicas = ru.neuroscribe.core.editReplica(current.replicas, index, text, speaker, current.speakerOrder.toSet()))
        }
    }

    fun restoreTurn(index: Int) = updateTranscript { current ->
        current.copy(replicas = ru.neuroscribe.core.restoreReplica(current.replicas, current.originalReplicas ?: current.replicas, index))
    }

    private fun updateTranscript(transform: (Recording) -> Recording) {
        val selected = mutable.value.selected ?: return
        if (mutable.value.stage != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = store.update(selected.id, transform) ?: return@launch
                mutable.update { state -> state.copy(selected = if (state.selected?.id == updated.id) updated else state.selected,
                    history = store.list(), notice = "Изменения сохранены. Исходный результат доступен.") }
            } catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Не удалось сохранить исправления.") } }
        }
    }

    fun recognizeTurn(index: Int, expected: ru.neuroscribe.core.Replica, suppressNoise: Boolean = false) {
        val selected = mutable.value.selected ?: return
        operate {
            val current = store.list().first { it.id == selected.id }
            require(current.replicas.getOrNull(index) == expected) { "Реплика изменилась. Откройте её заново." }
            val pcm = AudioDecoder().decode(store.audio(current.id))
            val text = SpeechEngine(getApplication()).recognizeFragment(pcm, expected, { stage -> mutable.update { it.copy(stage = stage) } }, suppressNoise)
            require(text.isNotBlank()) { "В этом фрагменте не удалось распознать речь. Исходный текст сохранён." }
            currentCoroutineContext().ensureActive()
            val updated = store.update(current.id) { latest ->
                require(latest.replicas.getOrNull(index) == expected)
                latest.copy(originalReplicas = latest.originalReplicas ?: latest.replicas,
                    replicas = latest.replicas.mapIndexed { i, r -> if (i == index) r.copy(text = text) else r })
            } ?: error("Запись недоступна")
            mutable.update { it.copy(selected = updated, notice = "Фрагмент распознан заново. Исходный результат сохранён.") }
        }
    }

    private fun operate(block: suspend () -> Unit) {
        if (job?.isCompleted == false || mutable.value.recording) return
        mutable.update { it.copy(error = null, stage = "Подготавливаем аудио") }
        job = ProcessingService.execute(getApplication()) { awaitService ->
            try {
                awaitService()
                // A legacy/pending recording may still be decoding its waveform preview.
                // Join cancellation before allocating a second full 16 kHz model input.
                previewJob?.cancelAndJoin()
                previewJob = null
                block()
            }
            catch (_: CancellationException) { markInterrupted("Обработка отменена") }
            catch (_: OutOfMemoryError) {
                markInterrupted("Недостаточно памяти")
                mutable.update { it.copy(error = "Недостаточно памяти. Закройте другие приложения и выберите более короткую запись.") }
            }
            catch (e: Exception) {
                markInterrupted("Ошибка обработки")
                mutable.update { it.copy(error = when (e) {
                    is java.io.IOException -> "Не удалось прочитать или сохранить запись. Проверьте файл и свободное место."
                    is IllegalArgumentException, is IllegalStateException -> e.message ?: "Не удалось обработать аудио. Попробуйте WAV, M4A или MP3."
                    else -> "Не удалось обработать запись. Попробуйте другой аудиофайл."
                }) }
            } finally {
                val history = withContext(NonCancellable + Dispatchers.IO) { store.list() }
                mutable.update { it.copy(stage = null, history = history) }
            }
        }
    }

    private suspend fun markInterrupted(status: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            mutable.value.selected?.let { selected ->
                val updated = selected.copy(status = if (selected.replicas.isNotEmpty() && selected.status != "Обработка") selected.status else status)
                runCatching { store.save(updated) }
                mutable.update { it.copy(selected = updated) }
            }
        }
    }

    private suspend fun process(recording: Recording) {
        val started = SystemClock.elapsedRealtime()
        store.save(recording.copy(status = "Обработка"))
        mutable.update { it.copy(selected = recording.copy(status = "Обработка"), stage = "Подготавливаем аудио") }
        val samples = AudioDecoder().decode(store.audio(recording.id))
        val duration = samples.size / 16000f
        val prepared = recording.copy(duration = duration, waveform = waveformEnvelope(samples), status = "Обработка")
        store.save(prepared)
        mutable.update { it.copy(selected = prepared) }
        val replicas = SpeechEngine(getApplication()).transcribe(samples) { stage -> mutable.update { it.copy(stage = stage) } }
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(stage = "Сохраняем стенограмму") }
        val result = prepared.copy(duration = duration, replicas = replicas,
            processingMs = SystemClock.elapsedRealtime() - started,
            status = if (replicas.isEmpty()) "Разборчивой речи не обнаружено" else "Готово")
        store.save(result)
        mutable.update { it.copy(selected = result) }
    }

    fun cancel() {
        mutable.update { it.copy(stage = "Останавливаем после текущего этапа…") }
        job?.cancel()
    }

    @Suppress("DEPRECATION")
    fun startRecording() {
        if (mutable.value.stage != null || mutable.value.recording || recorder != null) return
        val entry = store.create("Разговор · " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.forLanguageTag("ru")).format(java.util.Date()))
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(getApplication()) else MediaRecorder()
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(48000)
            rec.setAudioEncodingBitRate(96000)
            rec.setAudioChannels(1)
            rec.setOutputFile(store.audio(entry.id).absolutePath)
            rec.setMaxDuration(ru.neuroscribe.core.AudioLimits.MAX_SECONDS * 1000)
            rec.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording() }
            rec.prepare()
            rec.start()
            recorder = rec
            pending = entry
            activeStarted = SystemClock.elapsedRealtime()
            activeMillis = 0L
            microphoneLevels.clear()
            mutable.update { it.copy(recording = true, recordingSeconds = 0, recordingPaused = false, recordingLevels = emptyList(), error = null) }
            recordingClock = viewModelScope.launch {
                while (isActive) {
                    if (!mutable.value.recordingPaused) {
                        val level = runCatching { rec.maxAmplitude / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)
                        microphoneLevels += kotlin.math.sqrt(level)
                        mutable.update { it.copy(recordingSeconds = (activeMillis + SystemClock.elapsedRealtime() - activeStarted) / 1000,
                            recordingLevels = microphoneLevels.takeLast(48)) }
                    }
                    delay(80)
                }
            }
        } catch (_: Exception) {
            rec.release()
            store.audio(entry.id).delete()
            mutable.update { it.copy(error = "Не удалось включить микрофон. Проверьте разрешение и закройте другие приложения записи.") }
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        val entry = pending ?: return
        recorder = null
        pending = null
        recordingClock?.cancel()
        val stopped = runCatching { rec.stop() }
        rec.release()
        val captured = entry.copy(duration = (activeMillis + if (mutable.value.recordingPaused) 0L else SystemClock.elapsedRealtime()-activeStarted)/1000f,
            waveform = waveformEnvelope(microphoneLevels.toFloatArray()))
        mutable.update { it.copy(recording = false, recordingPaused = false) }
        if (stopped.isFailure) {
            store.audio(entry.id).delete()
            mutable.update { it.copy(error = "Запись слишком короткая. Запишите хотя бы несколько секунд.") }
            return
        }
        operate { store.save(captured); mutable.update { it.copy(selected = captured) }; process(captured) }
    }

    fun toggleRecordingPause() {
        val rec = recorder ?: return
        try {
            if (mutable.value.recordingPaused) {
                rec.resume()
                activeStarted = SystemClock.elapsedRealtime()
                mutable.update { it.copy(recordingPaused = false) }
            } else {
                rec.pause()
                activeMillis += SystemClock.elapsedRealtime() - activeStarted
                mutable.update { it.copy(recordingPaused = true, recordingSeconds = activeMillis / 1000) }
            }
        } catch (_: Exception) { mutable.update { it.copy(error = "Не удалось переключить паузу. Попробуйте завершить запись.") } }
    }

    fun discardRecording() {
        val rec = recorder ?: return
        recordingClock?.cancel()
        recorder = null
        runCatching { rec.stop() }
        rec.release()
        pending?.let { store.audio(it.id).delete() }
        pending = null
        microphoneLevels.clear()
        mutable.update { it.copy(recording = false, recordingPaused = false, recordingSeconds = 0, recordingLevels = emptyList()) }
    }

    fun renameTitle(recording: Recording, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = store.update(recording.id) { it.copy(title = title.trim().take(120)) } ?: return@launch
                mutable.update { it.copy(selected = if (it.selected?.id == recording.id) it.selected.copy(title = updated.title) else it.selected, history = store.list()) }
            } catch (_: Exception) { mutable.update { it.copy(error = "Не удалось сохранить название записи.") } }
        }
    }

    fun rename(speaker: Int, name: String) {
        val current = mutable.value.selected ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = store.update(current.id) { it.copy(names = it.names + (speaker to name.trim().take(40))) } ?: return@launch
                mutable.update { it.copy(selected = if (it.selected?.id == current.id) it.selected.copy(names = updated.names) else it.selected, history = store.list()) }
            } catch (_: Exception) { mutable.update { it.copy(error = "Не удалось сохранить имя.") } }
        }
    }

    fun export(uri: Uri, text: String) {
        clearNotice()
        mutable.update { it.copy(error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("No output stream")
                mutable.update { it.copy(notice = "Файл JSON сохранён") }
            } catch (_: Exception) { mutable.update { it.copy(error = "Не удалось сохранить файл экспорта.") } }
        }
    }

    override fun onCleared() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        pending?.let { store.audio(it.id).delete() }
        if (recorder != null) mutable.update { it.copy(recording = false, recordingPaused = false,
            recordingSeconds = 0, recordingLevels = emptyList()) }
        recorder = null
        pending = null
    }

    companion object {
        private val sharedState = MutableStateFlow(ScreenState())
        private var job: Job? = null
    }
}
