package ru.neuroscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun RecordingScreen(seconds: Long, paused: Boolean, levels: List<Float>, onPause: () -> Unit, onStop: () -> Unit, onDiscard: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topSpace = (maxHeight.value * .02f).dp.coerceIn(8.dp, 18.dp)
        val ringSize = (maxHeight.value * .33f).dp.coerceIn(200.dp, 290.dp)
        val visibleLevels = levels.takeLast(48).let { List(48-it.size) { 0f } + it }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                IconButton(onDiscard) { NeuroIcon(Glyph.Back, description = "Вернуться к записям") }
            }
            Spacer(Modifier.height(topSpace))
            Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(NeuroColors.Purple.copy(alpha = .035f), CircleShape))
                Box(Modifier.fillMaxSize(.76f).background(NeuroColors.Purple.copy(alpha = .07f), CircleShape))
                Box(Modifier.fillMaxSize(.53f).background(NeuroColors.Purple.copy(alpha = .09f), CircleShape))
                Box(Modifier.size(112.dp).background(NeuroColors.RoundGradient, CircleShape), contentAlignment = Alignment.Center) {
                    NeuroIcon(Glyph.Mic, Modifier.size(43.dp), Color.White)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(if (paused) "Запись на паузе" else "Идёт запись", style = MaterialTheme.typography.headlineMedium, fontSize = 23.sp)
            Text(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds/3600, seconds/60%60, seconds%60),
                color = NeuroColors.Muted, fontSize = 25.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            AudioWaveform(visibleLevels, Modifier.fillMaxWidth().height(44.dp), progress = if (paused) 0f else 1f)
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.height(180.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(NeuroColors.Background).padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundAction(if (paused) Glyph.Play else Glyph.Pause, if (paused) "Продолжить запись" else "Приостановить запись", onPause)
                RoundAction(Glyph.Stop, "Завершить и расшифровать", onStop, prominent = true)
                RoundAction(Glyph.Trash, "Удалить текущую запись", onDiscard)
            }
            Text("${if (paused) "Микрофон на паузе" else "Микрофон включён"} · до 20 минут", style = MaterialTheme.typography.bodySmall,
                color = NeuroColors.Muted, modifier = Modifier.padding(top = 18.dp, bottom = 20.dp))
        }
    }
}
