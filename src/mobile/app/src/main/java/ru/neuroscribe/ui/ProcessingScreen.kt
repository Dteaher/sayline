package ru.neuroscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.neuroscribe.core.timeLabel

@Composable
fun ProcessingScreen(title: String, duration: Float, stage: String, onCancel: () -> Unit) {
    val current = when {
        stage.startsWith("Определяем") || stage.startsWith("Уточняем участников") -> 1
        stage.startsWith("Распознаём") || stage.startsWith("Загружаем модель") -> 2
        stage.startsWith("Сохраняем") -> 3
        stage.startsWith("Останавливаем") -> -1
        else -> 0
    }
    val steps = listOf("Подготовка аудио", "Определение говорящих", "Распознавание речи", "Формирование стенограммы")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onCancel, enabled = current >= 0) { NeuroIcon(Glyph.Back, description = "Отменить обработку") }
            Text("Обработка записи", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(4.dp))
        ProcessingArt(Modifier.size(120.dp))
        Spacer(Modifier.height(10.dp))
        Text(if (current < 0) "Останавливаем обработку…" else "Анализируем разговор…", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text("$title${if (duration > 0) " · ${timeLabel(duration)}" else ""}", color = NeuroColors.Muted, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
        Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp)) {
            steps.forEachIndexed { index, step ->
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            when {
                                current > index -> Box(Modifier.fillMaxSize().background(NeuroColors.RoundGradient, CircleShape), contentAlignment = Alignment.Center) { NeuroIcon(Glyph.Check, Modifier.size(16.dp), Color.White) }
                                current == index -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = NeuroColors.Purple, trackColor = NeuroColors.Line)
                                else -> Box(Modifier.size(20.dp).border(2.dp, NeuroColors.Line, CircleShape))
                            }
                        }
                        if (index < steps.lastIndex) Box(Modifier.width(2.dp).height(26.dp).background(if (current > index) Color(0xFFB9A3F7) else NeuroColors.Line))
                    }
                    Column(Modifier.weight(1f).padding(top = 2.dp, bottom = 10.dp)) {
                        Text(step, style = MaterialTheme.typography.titleMedium, color = if (current < index) NeuroColors.Muted else NeuroColors.Ink)
                        if (index == current && stage != step) Text(stage, style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
        OfflineNote()
        Text("Можно свернуть приложение или выключить экран", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, bottom = 24.dp))
    }
}
