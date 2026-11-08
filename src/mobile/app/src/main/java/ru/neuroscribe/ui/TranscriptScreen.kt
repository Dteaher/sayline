package ru.neuroscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.neuroscribe.core.Replica
import ru.neuroscribe.core.timeLabel
import ru.neuroscribe.data.Recording
import java.util.Locale

@Composable
fun TranscriptScreen(recording: Recording, playing: Boolean, preparing: Boolean, position: Float, activeReplica: Int?,
                     onBack: () -> Unit, onShare: () -> Unit, onExport: () -> Unit, onRenameTitle: () -> Unit,
                     onRenameSpeaker: (Int) -> Unit, onPlay: () -> Unit, onSeek: (Float) -> Unit,
                     onPlayReplica: (Int, Replica) -> Unit, onRetry: () -> Unit,
                     onEditTurn: (Int, Replica, String, Int) -> Unit = { _, _, _, _ -> },
                     onRecognizeTurn: (Int, Replica, Boolean) -> Unit = { _, _, _ -> },
                     onRestoreTurn: (Int) -> Unit = {}, onExportOriginal: () -> Unit = {}) {
    var summary by rememberSaveable(recording.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var editing by remember(recording.id) { mutableStateOf<Pair<Int, Replica>?>(null) }
    val speakers = recording.speakerOrder
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 9.dp, top = 9.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { NeuroIcon(Glyph.Back, description = "К записям") }
            Column(Modifier.weight(1f).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recording.title, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${timeLabel(recording.duration)} · ${speakerCount(recording.replicas.map { it.speaker }.distinct().size)}", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (recording.replicas.isNotEmpty()) TextButton(onExport,
                Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Сохранить JSON" },
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("JSON", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Box {
                IconButton({ menu = true }) { NeuroIcon(Glyph.More, Modifier.size(20.dp), description = "Действия со стенограммой") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Переименовать запись") }, { menu = false; onRenameTitle() })
                    if (recording.replicas.isNotEmpty()) DropdownMenuItem({ Text("Поделиться текстом") }, { menu = false; onShare() })
                    if (recording.originalReplicas != null) DropdownMenuItem({ Text("Сохранить исходный JSON") }, { menu = false; onExportOriginal() })
                }
            }
        }
        if (LocalDensity.current.fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                TextButton({ summary = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Стенограмма", fontWeight = if (!summary) FontWeight.Bold else FontWeight.Normal) }
                TextButton({ summary = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Участники", fontWeight = if (summary) FontWeight.Bold else FontWeight.Normal) }
            }
        } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 48.dp).height(IntrinsicSize.Min).background(NeuroColors.Pale.copy(alpha = .65f), RoundedCornerShape(22.dp))) {
            listOf("Стенограмма", "Участники").forEachIndexed { index, title ->
                val selected = summary == (index == 1)
                val tabColor by animateColorAsState(if (selected) Color(0xFF7544D3) else Color.Transparent, tween(180), label = "tab")
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(22.dp))
                    .background(tabColor)
                    .selectable(selected = selected, role = Role.Tab) { summary = index == 1 }, contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else NeuroColors.Ink, fontSize = 13.sp)
                    }
                }
            }
        }
        }
        if (summary) {
            SummaryContent(recording, speakers.filter { id -> recording.replicas.any { it.speaker == id } }, onRenameSpeaker, Modifier.weight(1f))
        } else if (recording.replicas.isEmpty()) {
            val noSpeech = recording.status == "Разборчивой речи не обнаружено"
            val failed = recording.status == "Ошибка обработки" || recording.status == "Недостаточно памяти"
            val interrupted = recording.status.contains("прервана") || recording.status.contains("отменена")
            val title = when {
                noSpeech -> "Речь не обнаружена"
                failed -> "Не удалось обработать запись"
                interrupted -> "Обработка прервана"
                else -> "Запись готова к обработке"
            }
            val hint = when {
                noSpeech -> "Не удалось выделить разборчивую речь. Прослушайте аудио или попробуйте другую запись."
                recording.status == "Недостаточно памяти" -> "Закройте другие приложения или выберите более короткую запись, затем повторите обработку."
                failed -> "Проверьте аудиофайл и свободное место. Можно повторить обработку или импортировать другую запись."
                interrupted -> "Аудио сохранено на устройстве. Запустите обработку ещё раз, чтобы получить стенограмму."
                else -> "Распознаем речь и разделим участников прямо на устройстве. Интернет не потребуется."
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(70.dp).background(NeuroColors.Pale, CircleShape), contentAlignment = Alignment.Center) { SoundMark(Modifier.size(34.dp), NeuroColors.Purple) }
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
                Text(hint, color = NeuroColors.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                GradientButton(if (noSpeech || failed || interrupted) "Повторить обработку" else "Обработать запись", onRetry)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 23.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(recording.replicas) { index, replica ->
                    val speaker = speakers.indexOf(replica.speaker).coerceAtLeast(0)
                    val color = NeuroColors.SpeakerBackgrounds[speaker % NeuroColors.SpeakerBackgrounds.size]
                    val name = recording.speakerName(replica.speaker)
                    if (LocalDensity.current.fontScale > 1.3f) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, modifier = Modifier.weight(1f).clickable { onRenameSpeaker(replica.speaker) }, style = MaterialTheme.typography.titleMedium)
                                IconButton({ editing = index to replica }) { NeuroIcon(Glyph.More, description = "Изменить реплику ${index+1}") }
                            }
                            Text("${timeLabel(replica.start)} — ${timeLabel(replica.end)}", color = NeuroColors.Muted, style = MaterialTheme.typography.bodySmall)
                            Text(replica.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPlayReplica(index, replica) })
                        }
                    } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.width((52f * LocalDensity.current.fontScale).coerceAtMost(96f).dp).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(timeLabel(replica.start), style = MaterialTheme.typography.labelMedium, color = NeuroColors.Ink,
                                modifier = Modifier.background(NeuroColors.Pale.copy(alpha = .65f), RoundedCornerShape(8.dp)).padding(horizontal = 5.dp, vertical = 3.dp))
                            Text(timeLabel(replica.end), style = MaterialTheme.typography.bodySmall, fontSize = 10.sp, color = NeuroColors.Muted, modifier = Modifier.padding(start = 5.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(13.dp)).background(color).clickable { onRenameSpeaker(replica.speaker) }
                                    .padding(horizontal = 11.dp, vertical = 6.dp), color = NeuroColors.Ink)
                            Spacer(Modifier.weight(1f))
                            IconButton({ editing = index to replica }, Modifier.size(48.dp)) {
                                NeuroIcon(Glyph.More, Modifier.size(18.dp), description = "Изменить реплику ${index + 1}")
                            }
                            }
                            Text(replica.text, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 3.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                                    .background(if (activeReplica == index) color.copy(alpha = .65f) else Color.Transparent)
                                    .clickable { onPlayReplica(index, replica) }.padding(horizontal = 11.dp, vertical = 10.dp))
                        }
                    }
                    }
                }
            }
        }
        if (recording.duration > 0) PlaybackBar(recording, playing, preparing, position, onPlay, onSeek)
    }
    editing?.let { (index, replica) ->
        EditReplicaDialog(recording, index, replica, { editing = null },
            onSave = { text, speaker -> onEditTurn(index, replica, text, speaker); editing = null },
            onRecognize = { suppressNoise -> onRecognizeTurn(index, replica, suppressNoise); editing = null },
            onRestore = { onRestoreTurn(index); editing = null })
    }
}

@Composable
private fun EditReplicaDialog(recording: Recording, index: Int, replica: Replica, onDismiss: () -> Unit,
                              onSave: (String, Int) -> Unit, onRecognize: (Boolean) -> Unit, onRestore: () -> Unit) {
    var text by remember { mutableStateOf(replica.text) }
    var speaker by remember { mutableIntStateOf(replica.speaker) }
    var suppressNoise by remember { mutableStateOf(false) }
    val speakers = recording.speakerOrder
    val original = recording.originalReplicas?.getOrNull(index) ?: replica
    val compact = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density < 700f || LocalDensity.current.fontScale > 1.2f
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Исправить реплику") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${timeLabel(replica.start)}–${timeLabel(replica.end)} · исходный результат сохраняется", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it.take(20000) }, label = { Text("Текст реплики") }, minLines = 3, maxLines = if (compact) 4 else 7, modifier = Modifier.fillMaxWidth())
                Text("Кто говорит", style = MaterialTheme.typography.labelLarge)
                speakers.forEach { id ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(speaker == id, role = Role.RadioButton) { speaker = id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(speaker == id, null)
                        Text(recording.speakerName(id), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (original != replica) {
                    Text("Исходный текст: ${original.text}", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
                    TextButton(onRestore) { Text("Восстановить исходную реплику") }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(suppressNoise, role = Role.Checkbox) { suppressNoise = !suppressNoise }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(suppressNoise, null)
                    Text("Подавить фоновый шум", modifier = Modifier.padding(start = 8.dp))
                }
                TextButton({ onRecognize(suppressNoise) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Распознать этот фрагмент заново") }
                Text("Время и участник сохранятся.", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
            }
        },
        confirmButton = { TextButton({ onSave(text, speaker) }, enabled = text.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

@Composable
private fun SummaryContent(recording: Recording, speakers: List<Int>, onRename: (Int) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Разговор в цифрах", style = MaterialTheme.typography.titleLarge)
            Text("Участники, реплики и хронометраж записи", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            val stats = listOf("Длительность" to timeLabel(recording.duration), "Реплик" to recording.replicas.size.toString(), "Говорящих" to speakers.size.toString())
            BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(18.dp)) {
                if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.2f) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        stats.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = NeuroColors.Muted,
                                    modifier = Modifier.weight(1f).padding(end = 12.dp))
                                Text(value, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        stats.forEach { (label, value) -> Column {
                            Text(value, style = MaterialTheme.typography.titleLarge)
                            Text(label, style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
                        } }
                    }
                }
            }
        }
        itemsIndexed(speakers) { index, speaker ->
            val turns = recording.replicas.filter { it.speaker == speaker }
            val length = turns.sumOf { (it.end-it.start).toDouble() }.toFloat()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(NeuroColors.SpeakerBackgrounds[index % 4].copy(alpha = .5f)).clickable { onRename(speaker) }.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(recording.speakerName(speaker), style = MaterialTheme.typography.titleMedium)
                    Text("${replicaCount(turns.size)} · ${timeLabel(length)} речи", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
                }
                NeuroIcon(Glyph.Arrow, Modifier.size(18.dp), NeuroColors.Muted)
            }
        }
        if (recording.processingMs > 0) item {
            Text("Обработка: %.1f с".format(Locale.forLanguageTag("ru"), recording.processingMs/1000.0), style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
            if (recording.duration > 0) Text("RTF: %.2f".format(Locale.forLanguageTag("ru"), recording.processingMs/1000.0/recording.duration), style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
        }
    }
}

@Composable
private fun PlaybackBar(recording: Recording, playing: Boolean, preparing: Boolean, position: Float, onPlay: () -> Unit, onSeek: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 22.dp, top = 8.dp)
        .shadow(9.dp, RoundedCornerShape(21.dp), ambientColor = NeuroColors.Indigo.copy(alpha = .05f), spotColor = NeuroColors.Indigo.copy(alpha = .06f))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(21.dp)).padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onPlay, enabled = !preparing) {
            Box(Modifier.size(36.dp).background(NeuroColors.RoundGradient, CircleShape), contentAlignment = Alignment.Center) {
                if (preparing) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else NeuroIcon(if (playing) Glyph.Pause else Glyph.Play, Modifier.size(17.dp), Color.White, if (playing) "Пауза" else "Воспроизвести запись")
            }
        }
        Text(timeLabel(position), style = MaterialTheme.typography.labelMedium, color = NeuroColors.Muted, fontSize = 10.sp)
        AudioWaveform(recording.waveform, Modifier.weight(1f).height(44.dp), if (recording.duration > 0) position/recording.duration else 0f, onSeek)
        Text(timeLabel(recording.duration), style = MaterialTheme.typography.labelMedium, color = NeuroColors.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
    }
}

private fun speakerCount(count: Int): String = if (count == 0) "без стенограммы" else "$count ${if (count%10 == 1 && count%100 != 11) "говорящий" else "говорящих"}"
private fun replicaCount(count: Int): String = "$count " + when {
    count%100 in 11..14 -> "реплик"
    count%10 == 1 -> "реплика"
    count%10 in 2..4 -> "реплики"
    else -> "реплик"
}
