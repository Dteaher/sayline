package ru.neuroscribe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object NeuroColors {
    val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Purple = Color(0xFF7041ED)
    val Indigo: Color @Composable get() = MaterialTheme.colorScheme.primary
    val Pale: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val Background: Color @Composable get() = MaterialTheme.colorScheme.background
    val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val Gradient = Brush.linearGradient(listOf(Color(0xFF7544D3), Color(0xFF6640BB)))
    val RoundGradient = Gradient
    val SpeakerBackgrounds: List<Color> @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < .2f) listOf(Color(0xFF302841), Color(0xFF20343E), Color(0xFF233A31), Color(0xFF403124)) else listOf(Color(0xFFE8D8FF), Color(0xFFCFEFFF), Color(0xFFD8F2E8), Color(0xFFFFE5CD))
    val SpeakerText = listOf(Color(0xFF51328F), Color(0xFF1D617F), Color(0xFF226854), Color(0xFF8C5326))
}

val LocalThemeChoice = staticCompositionLocalOf { "Системная" }
val LocalSetTheme = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun NeuroTheme(content: @Composable () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("appearance", 0)
    var choice by rememberSaveable { mutableStateOf(prefs.getString("theme", "Системная") ?: "Системная") }
    val dark = choice == "Тёмная" || (choice == "Системная" && isSystemInDarkTheme())
    val scheme = if (dark) darkColorScheme(primary = Color(0xFFB99AFF), onPrimary = Color(0xFF211138),
        background = Color(0xFF111115), surface = Color(0xFF18181F), surfaceContainer = Color(0xFF24242E),
        onSurface = Color(0xFFF1EFF7), onBackground = Color(0xFFF1EFF7), onSurfaceVariant = Color(0xFFB9B5C7),
        outlineVariant = Color(0xFF393641)) else lightColorScheme(primary = Color(0xFF6736C5),
        background = Color(0xFFF7F6FA), surface = Color.White, surfaceContainer = Color(0xFFEEEBF4),
        onSurface = Color(0xFF19171F), onBackground = Color(0xFF19171F), onSurfaceVariant = Color(0xFF625C70), outlineVariant = Color(0xFFDCD7E5))
    CompositionLocalProvider(LocalThemeChoice provides choice, LocalSetTheme provides { value -> choice = value; prefs.edit().putString("theme", value).apply() }) {
    MaterialTheme(colorScheme = scheme,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 37.sp, letterSpacing = (-.9).sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 32.sp, letterSpacing = (-.6).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = (-.45).sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-.2).sp),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-.15).sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ), content = { CompositionLocalProvider(LocalContentColor provides scheme.onSurface) { content() } })
    }
}

@Composable
fun NeuroBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(NeuroColors.Background)) { content() }
}

enum class Glyph(val path: String) {
    Mic("M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1 -6 0z M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8"),
    Folder("M3 6h6l2 2h10v12H3z M3 6V4h6l2 2h8v2"),
    Pause("M8 5v14 M16 5v14"),
    Stop("M6 6h12v12H6z"),
    Play("M8 5l11 7 -11 7z"),
    Back("M14 5l-7 7 7 7 M7 12h14"),
    Arrow("M5 12h14 M14 7l5 5 -5 5"),
    Close("M6 6l12 12 M18 6L6 18"),
    Check("M5 12l4 4L19 6"),
    Share("M8 12l8 -6 M8 12l8 6 M7 12a2 2 0 1 0 -.01 .01 M18 5a2 2 0 1 0 -.01 .01 M18 19a2 2 0 1 0 -.01 .01"),
    Phone("M7 2h10v20H7z M10 18h4"),
    Trash("M4 6h16 M9 6V3h6v3 M6 6l1 15h10l1 -15 M10 10v7 M14 10v7"),
    More("M5 12h.01 M12 12h.01 M19 12h.01"),
    Settings("M12 8a4 4 0 1 0 .01 0 M10 2h4l1 3 3 1 3 -1 2 4 -2 2v3l2 2 -2 4 -3 -1 -3 1 -1 3h-4l-1 -3 -3 -1 -3 1 -2 -4 2 -2v-3l-2 -2 2 -4 3 1 3 -1z"),
    Sparkles("M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5 -6.5L3 12l6.5 -2.5z M20 2v4 M18 4h4"),
    Bookmark("M6 3h12v19l-6 -4 -6 4z"),
    Lock("M5 10h14v11H5z M8 10V6a4 4 0 0 1 8 0v4 M12 14v3"),
}

@Composable
fun NeuroIcon(icon: Glyph, modifier: Modifier = Modifier, tint: Color = NeuroColors.Ink, description: String? = null) {
    val path = androidx.compose.runtime.remember(icon) { PathParser().parsePathString(icon.path).toPath() }
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val solid = icon == Glyph.Play || icon == Glyph.Stop
            drawPath(path, tint, style = if (solid) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = if (icon == Glyph.More) 3.3f else 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun RoundAction(icon: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = false, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(140), label = "press")
    IconButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }.then(if (prominent) Modifier.size(68.dp).background(NeuroColors.RoundGradient, CircleShape) else Modifier.size(52.dp).background(NeuroColors.Pale.copy(alpha = .8f), CircleShape)), enabled = enabled, interactionSource = interaction) {
        Crossfade(icon, animationSpec = tween(180), label = "recording-action") { symbol ->
            NeuroIcon(symbol, Modifier.size(if (prominent) 26.dp else 22.dp), if (prominent) Color.White else NeuroColors.Ink, description)
        }
    }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: Glyph? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(140), label = "press")
    Button(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }.heightIn(min = 54.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
        contentPadding = PaddingValues(0.dp), interactionSource = interaction) {
        Row(Modifier.fillMaxWidth().background(NeuroColors.Gradient).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { NeuroIcon(icon, tint = Color.White); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun OfflineNote(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(NeuroColors.Pale.copy(alpha = .72f), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NeuroIcon(Glyph.Phone, tint = NeuroColors.Purple)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Выполняется офлайн", style = MaterialTheme.typography.labelLarge)
            Text("Данные не покидают устройство", style = MaterialTheme.typography.bodySmall, color = NeuroColors.Muted)
        }
    }
}
