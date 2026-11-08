package ru.neuroscribe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
fun SoundMark(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(36.dp)) {
        val heights = listOf(.24f, .55f, .9f, .55f, .24f)
        heights.forEachIndexed { i, h ->
            val x = size.width * (.12f + i * .19f)
            drawLine(color, Offset(x, size.height * (1-h)/2), Offset(x, size.height*(1+h)/2), size.width*.09f, StrokeCap.Round)
        }
    }
}

@Composable
fun HomeWaves(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        repeat(3) { layer ->
            val path = Path()
            path.moveTo(0f, size.height*.82f)
            for (i in 0..100) {
                val x = i / 100f
                val envelope = sin(PI*x).toFloat().pow(1.3f)
                val y = .62f - sin((x * 2.15f + layer*.18f)*PI*2).toFloat() * .29f * envelope
                path.lineTo(size.width*x, size.height*y)
            }
            path.lineTo(size.width, size.height*.9f); path.lineTo(0f, size.height*.9f); path.close()
            drawPath(path, Brush.verticalGradient(listOf(Color(0xFF8F6AFF).copy(alpha = .24f-layer*.035f), Color(0xFF9A9CFF).copy(alpha = .015f))))
        }
    }
}

/** Values come from actual microphone samples or decoded audio; empty data is flat. */
@Composable
fun AudioWaveform(levels: List<Float>, modifier: Modifier = Modifier, progress: Float = 1f, onSeek: ((Float) -> Unit)? = null) {
    val bars = if (levels.isEmpty()) List(48) { 0f } else levels
    val interaction = if (onSeek == null) Modifier else Modifier
        .pointerInput(onSeek) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
        .semantics {
            contentDescription = "Позиция воспроизведения"
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            setProgress { onSeek(it.coerceIn(0f, 1f)); true }
        }
    Canvas(modifier.then(interaction)) {
        val gap = size.width / bars.size
        bars.forEachIndexed { index, amplitude ->
            val h = maxOf(3.dp.toPx(), amplitude.coerceIn(0f, 1f) * size.height * .9f)
            val x = gap * (index + .5f)
            drawLine(if ((index+.5f)/bars.size <= progress) NeuroColors.Purple else Color(0xFFD3CCEC),
                Offset(x, (size.height-h)/2), Offset(x, (size.height+h)/2), minOf(3.dp.toPx(), gap*.46f), StrokeCap.Round)
        }
    }
}

@Composable
fun ProcessingArt(modifier: Modifier = Modifier) {
    Canvas(modifier.size(160.dp)) {
        val w = size.width
        val h = size.height
        for (i in 2 downTo 0) {
            val centerY = h * (.34f + i*.14f)
            val path = Path().apply {
                moveTo(w*.5f, centerY-h*.19f)
                cubicTo(w*.55f, centerY-h*.2f, w*.78f, centerY-h*.04f, w*.86f, centerY)
                quadraticBezierTo(w*.94f, centerY+h*.055f, w*.85f, centerY+h*.1f)
                lineTo(w*.55f, centerY+h*.27f)
                quadraticBezierTo(w*.5f, centerY+h*.3f, w*.44f, centerY+h*.27f)
                lineTo(w*.14f, centerY+h*.1f)
                quadraticBezierTo(w*.06f, centerY+h*.055f, w*.14f, centerY)
                lineTo(w*.44f, centerY-h*.17f)
                quadraticBezierTo(w*.48f, centerY-h*.2f, w*.5f, centerY-h*.19f)
                close()
            }
            drawPath(path, Brush.linearGradient(
                if (i == 0) listOf(Color(0xFFF5F0FF), Color(0xFFB2A2FF)) else listOf(Color.White, Color(0xFF8667F8)),
                Offset(0f, centerY-h*.15f), Offset(w*.65f, centerY+h*.3f)))
        }
        listOf(.18f,.45f,.72f,.45f,.18f).forEachIndexed { i, length ->
            val x = w*(.36f+i*.07f)
            drawLine(NeuroColors.Purple, Offset(x,h*(.365f-length*.12f)), Offset(x,h*(.365f+length*.12f)), w*.025f, StrokeCap.Round)
        }
    }
}
