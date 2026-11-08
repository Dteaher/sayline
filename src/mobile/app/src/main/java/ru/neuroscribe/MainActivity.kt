package ru.neuroscribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import ru.neuroscribe.core.exportText
import ru.neuroscribe.core.exportJson
import ru.neuroscribe.data.Recording
import ru.neuroscribe.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent { NeuroTheme { NeuroScribe() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeuroScribe(vm: NeuroViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
    SideEffect {
        (context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    val selected = state.selected
    val busy = state.stage != null
    val player = remember { AudioPlayer(vm.store::audio) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var renameSpeaker by remember { mutableStateOf<Int?>(null) }
    var renameRecording by remember { mutableStateOf<Recording?>(null) }
    var editName by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var pendingExport by rememberSaveable { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.export(it, pendingExport) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startRecording() else vm.permissionDenied() }

    fun startRecording() {
        player.release()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun back() { player.release(); vm.select(null) }
    fun renameTitle(recording: Recording) { renameRecording = recording; editName = recording.title }
    DisposableEffect(busy, state.recording) {
        val window = (context as? ComponentActivity)?.window
        if (busy || state.recording) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(lifecycle, vm, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && (context as? ComponentActivity)?.isChangingConfigurations != true) {
                if (vm.state.value.recording && !vm.state.value.recordingPaused) vm.toggleRecordingPause()
                player.release()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player.playing) { while (player.playing) { delay(80); player.tick() } }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            vm.clearNotice()
        }
    }
    BackHandler(state.recording || busy || selected != null) {
        when { state.recording -> discard = true; busy -> vm.cancel(); else -> back() }
    }
    NeuroBackdrop {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                AnimatedContent(targetState = state, modifier = Modifier.fillMaxSize(),
                    contentKey = { when { it.recording -> "recording"; it.stage != null -> "processing"; it.selected != null -> "transcript:${it.selected.id}"; else -> "home" } },
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) }, label = "screen") { frame ->
                    val selected = frame.selected
                    val busy = frame.stage != null
                when {
                    frame.recording -> RecordingScreen(frame.recordingSeconds, frame.recordingPaused, frame.recordingLevels,
                        vm::toggleRecordingPause, vm::stopRecording, { discard = true })
                    busy -> ProcessingScreen(selected?.title ?: "Новая запись", selected?.duration ?: 0f, frame.stage.orEmpty(), vm::cancel)
                    selected != null -> TranscriptScreen(selected, player.recordingId == selected.id && player.playing,
                        player.recordingId == selected.id && player.preparing, if (player.recordingId == selected.id) player.position else 0f,
                        if (player.recordingId == selected.id) player.activeReplica else null,
                        ::back,
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, selected.title).putExtra(Intent.EXTRA_TEXT, exportText(selected.replicas, selected.speakerOrder.associateWith(selected::speakerName)))
                            context.startActivity(Intent.createChooser(intent, "Поделиться стенограммой"))
                        },
                        onExport = { pendingExport = exportJson(selected.replicas); export.launch("Sayline-${selected.id.take(8)}.json") },
                        onRenameTitle = { renameTitle(selected) },
                        onRenameSpeaker = { renameSpeaker = it; editName = selected.names[it].orEmpty() },
                        onPlay = { player.toggle(selected.id) },
                        onSeek = { player.seek(selected.id, it, selected.duration) },
                        onPlayReplica = { index, replica ->
                            if (player.recordingId == selected.id && player.activeReplica == index && player.playing) player.pause()
                            else player.play(selected.id, replica.start, replica.end, index)
                        }, onRetry = { player.release(); vm.retry() },
                        onEditTurn = vm::editTurn, onRecognizeTurn = { index, replica, suppressNoise -> player.release(); vm.recognizeTurn(index, replica, suppressNoise) },
                        onRestoreTurn = vm::restoreTurn,
                        onExportOriginal = { pendingExport = exportJson(selected.originalReplicas ?: selected.replicas); export.launch("Sayline-${selected.id.take(8)}-original.json") })
                    else -> HomeScreen(frame.history, if (player.playing) player.recordingId else null,
                        ::startRecording, { player.release(); import.launch(arrayOf("audio/*")) },
                        { if (player.recordingId != it.id) player.release(); vm.select(it) },
                        { player.toggle(it.id) }, ::renameTitle, { about = true })
                }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = if (selected != null && selected.duration > 0) 88.dp else 16.dp))
            }
        }
    }
    if (state.error != null || player.error != null) AlertDialog(
        onDismissRequest = { vm.clearError(); player.clearError() },
        title = { Text("Не удалось выполнить действие") }, text = { Text(state.error ?: player.error.orEmpty()) },
        confirmButton = { TextButton({ vm.clearError(); player.clearError() }) { Text("Понятно") } })

    if (renameSpeaker != null || renameRecording != null) AlertDialog(
        onDismissRequest = { renameSpeaker = null; renameRecording = null },
        title = { Text(if (renameSpeaker != null) "Имя говорящего" else "Название записи") },
        text = { OutlinedTextField(editName, { editName = it.take(if (renameSpeaker != null) 40 else 120) }, singleLine = true, label = { Text(if (renameSpeaker != null) "Например, Анна" else "Например, Встреча по проекту") }) },
        confirmButton = { TextButton({
            renameSpeaker?.let { vm.rename(it, editName) }
            renameRecording?.let { vm.renameTitle(it, editName) }
            renameSpeaker = null; renameRecording = null
        }, enabled = renameSpeaker != null || editName.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton({ renameSpeaker = null; renameRecording = null }) { Text("Отмена") } })

    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Удалить текущую запись?") },
        text = { Text("Незавершённая запись не будет сохранена.") },
        confirmButton = { TextButton({ discard = false; vm.discardRecording() }) { Text("Удалить запись") } },
        dismissButton = { TextButton({ discard = false }) { Text("Отмена") } })

    if (about) ModalBottomSheet(onDismissRequest = { about = false }, containerColor = NeuroColors.Background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 26.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sayline", style = MaterialTheme.typography.headlineMedium)
            Text("Ваши разговоры. Ваше устройство. Ваша приватность.", color = NeuroColors.Muted)
            OfflineNote()
            Text("Аудио и стенограммы хранятся на устройстве. Нет регистрации и облачной обработки.", style = MaterialTheme.typography.bodyMedium)
            Text("Форматы: WAV, M4A, MP3. Записи до 20 минут, до 512 МБ.", style = MaterialTheme.typography.bodyMedium)
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            val setTheme = LocalSetTheme.current
            val currentTheme = LocalThemeChoice.current
            listOf("Системная", "Светлая", "Тёмная").forEach { mode ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { setTheme(mode) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = currentTheme == mode, onClick = { setTheme(mode) })
                    Text(mode)
                }
            }
            Text("Модели и лицензии", style = MaterialTheme.typography.titleMedium)
            Text("sherpa-onnx и 3D-Speaker — Apache 2.0. GigaAM, pyannote, GTCRN и ONNX Runtime — MIT. WeSpeaker VoxCeleb — CC BY 4.0.", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
            Text("Версия 0.7.0", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
        }
    }
}
