package ru.neuroscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.neuroscribe.core.timeLabel
import ru.neuroscribe.data.Recording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(history: List<Recording>, playingId: String?, onRecord: () -> Unit, onImport: () -> Unit,
               onOpen: (Recording) -> Unit, onPlay: (Recording) -> Unit, onRename: (Recording) -> Unit, onAbout: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sayline", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onAbout) { NeuroIcon(Glyph.Settings, Modifier.size(22.dp), description = "О приложении") }
            }
            Text("Разговоры становятся текстом.", style = MaterialTheme.typography.bodyLarge,
                color = NeuroColors.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        }

        item {
            GradientButton("Импортировать аудио", onImport, Modifier.fillMaxWidth(), Glyph.Folder)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onRecord, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
                NeuroIcon(Glyph.Mic); Spacer(Modifier.width(12.dp)); Text("Записать разговор", textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            Text("WAV, M4A, MP3 · до\u00a020\u00a0минут · до\u00a0512\u00a0МБ", style = MaterialTheme.typography.bodySmall,
                color = NeuroColors.Muted, modifier = Modifier.padding(top = 10.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showAll) "Все записи" else "Последние записи", style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (history.size > 3) TextButton({ showAll = !showAll }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (showAll) "Свернуть" else "Все", color = NeuroColors.Muted)
                    Spacer(Modifier.width(6.dp)); NeuroIcon(Glyph.Arrow, Modifier.size(16.dp), NeuroColors.Muted)
                }
            }
        }
        if (history.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(38.dp).background(NeuroColors.Pale, CircleShape), contentAlignment = Alignment.Center) { SoundMark(Modifier.size(22.dp), NeuroColors.Purple) }
                Text("Первый разговор — начало истории", style = MaterialTheme.typography.titleMedium)
                Text("Запишите или импортируйте аудио.\nВаши записи будут храниться здесь.", style = MaterialTheme.typography.bodyMedium, color = NeuroColors.Muted)
            }
        }
        items(if (showAll) history else history.take(3), key = { it.id }) { recording ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().shadow(3.dp, RoundedCornerShape(18.dp), ambientColor = NeuroColors.Indigo.copy(alpha = .05f), spotColor = NeuroColors.Indigo.copy(alpha = .06f))
                .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable { onOpen(recording) }.padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onPlay(recording) }, enabled = recording.duration > 0) {
                    Box(Modifier.size(33.dp).background(if (recording.duration > 0) NeuroColors.Pale else NeuroColors.Pale.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                        NeuroIcon(if (playingId == recording.id) Glyph.Pause else Glyph.Play, Modifier.size(16.dp),
                            if (recording.duration > 0) NeuroColors.Indigo else NeuroColors.Muted.copy(alpha = .45f),
                            if (playingId == recording.id) "Приостановить аудио" else "Слушать запись")
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(recording.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontSize = 14.sp)
                    Text("${relativeDate(recording.created)} · ${if (recording.duration > 0) timeLabel(recording.duration) else "Аудиозапись"}", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
                    Text(when { recording.status == "Готово" -> "✓ Готово"; recording.status.contains("Ошибка") -> "! ${recording.status}"; recording.status.contains("Обработка") -> "↻ ${recording.status}"; else -> "— ${recording.status}" },
                        color = if (recording.status == "Ошибка обработки" || recording.status == "Недостаточно памяти") MaterialTheme.colorScheme.error else NeuroColors.Muted,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton({ menu = true }) { NeuroIcon(Glyph.More, Modifier.size(19.dp), NeuroColors.Muted, "Действия с записью") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Открыть запись") }, { menu = false; onOpen(recording) })
                        DropdownMenuItem({ Text("Переименовать") }, { menu = false; onRename(recording) })
                    }
                }
            }
        }
    }
}

private fun relativeDate(timestamp: Long): String {
    val format = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    val today = format.format(Date())
    val date = format.format(Date(timestamp))
    return when {
        date == today -> "Сегодня"
        date == format.format(Date(System.currentTimeMillis()-86_400_000)) -> "Вчера"
        else -> SimpleDateFormat("d MMM", Locale.forLanguageTag("ru")).format(Date(timestamp))
    }
}
