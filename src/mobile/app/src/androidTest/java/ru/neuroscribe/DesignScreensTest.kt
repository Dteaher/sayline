package ru.neuroscribe

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.core.Replica
import ru.neuroscribe.data.Recording
import ru.neuroscribe.ui.*
import java.io.File

/** Isolated visual fixtures, never inserted into production recording history or model results. */
@RunWith(AndroidJUnit4::class)
class DesignScreensTest {
    @get:Rule val compose = createComposeRule()
    private val meeting = Recording("visual-test-only", "Встреча по проекту", duration = 86f, status = "Готово",
        waveform = listOf(.12f,.3f,.5f,.2f,.4f,.8f,1f,.65f,.2f,.5f,.3f,.7f,.2f,.4f,.15f,.6f,.4f,.1f,.3f,.15f,.5f,.3f,.1f,.4f),
        replicas = listOf(
            Replica(3f, 8f, 0, "Добрый день. Сегодня обсудим разработку нашего приложения."),
            Replica(9f, 14f, 1, "Хорошо, я подготовила результаты тестирования."),
            Replica(16f, 21f, 0, "Отлично. Тогда начнём с распознавания речи."),
            Replica(24f, 31f, 1, "Да, есть несколько важных наблюдений, хочу их показать.")))

    @Test fun homeShowsPrimaryActionsAndRecordButtonWorks() {
        var requested = false
        compose.setContent { NeuroTheme { NeuroBackdrop { Box(Modifier.safeDrawingPadding()) {
            HomeScreen(listOf(meeting, meeting.copy(id = "visual-2", title = "Интервью"), meeting.copy(id = "visual-3", title = "Обсуждение идей")),
                null, { requested = true }, {}, {}, {}, {}, {})
        } } } }
        compose.onNodeWithText("Импортировать аудио").assertIsDisplayed()
        save("home-fixture")
        compose.onNodeWithText("Записать разговор").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test fun recordingHasPauseStopAndDiscardActions() {
        var paused = false
        compose.setContent { NeuroTheme { NeuroBackdrop { Box(Modifier.safeDrawingPadding()) {
            RecordingScreen(204, false, meeting.waveform, { paused = true }, {}, {})
        } } } }
        compose.onNodeWithContentDescription("Завершить и расшифровать").assertIsDisplayed()
        save("recording-fixture")
        compose.onNodeWithContentDescription("Приостановить запись").performClick()
        compose.runOnIdle { assertTrue(paused) }
    }

    @Test fun processingUsesRealPhaseOrderAndCanCancel() {
        var cancelled = false
        compose.setContent { NeuroTheme { NeuroBackdrop { Box(Modifier.safeDrawingPadding()) {
            ProcessingScreen(meeting.title, meeting.duration, "Распознаём речь · 2 из 4", { cancelled = true })
        } } } }
        compose.onNodeWithText("Определение говорящих").assertIsDisplayed()
        save("processing-fixture")
        compose.onNodeWithContentDescription("Отменить обработку").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun transcriptShowsRepliesAndFactualSummary() {
        compose.setContent { NeuroTheme { NeuroBackdrop { Box(Modifier.safeDrawingPadding()) {
            TranscriptScreen(meeting, false, false, 0f, null, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
        } } } }
        compose.onNodeWithText("Добрый день. Сегодня обсудим разработку нашего приложения.").assertIsDisplayed()
        save("transcript-fixture")
        compose.onNodeWithText("Участники").performClick()
        compose.onNodeWithText("Разговор в цифрах").assertIsDisplayed()
        compose.onNodeWithText("2 реплики · 00:10 речи").assertExists()
        save("summary-fixture")
    }

    @Test fun narrowHomeWithDoubleFontKeepsBothActionsVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NeuroTheme { NeuroBackdrop { Box(Modifier.requiredWidth(320.dp).safeDrawingPadding()) {
                    HomeScreen(emptyList(), null, {}, {}, {}, {}, {}, {})
                } } }
            }
        }
        compose.onNodeWithText("Импортировать аудио").assertIsDisplayed()
        compose.onNodeWithText("Записать разговор").assertIsDisplayed()
        save("home-320-font200")
    }

    @Test fun longTranscriptWithDoubleFontKeepsExportAndEditorAvailable() {
        val long = meeting.copy(title = "Обсуждение результатов исследования и планов дальнейшей разработки",
            replicas = List(30) { i -> Replica(i*12f, i*12f+10, i%3,
                "Это длинная реплика участника: обсуждаем результаты исследования, качество распознавания и следующие шаги. ".repeat(6)) })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NeuroTheme { NeuroBackdrop { Box(Modifier.requiredWidth(320.dp).safeDrawingPadding()) {
                    TranscriptScreen(long, false, false, 0f, null, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
                } } }
            }
        }
        compose.onNodeWithText("JSON").assertIsDisplayed()
        compose.onNodeWithContentDescription("Изменить реплику 1").assertIsDisplayed()
        save("transcript-320-font200")
    }

    @Test fun wideHomeKeepsImportAndRecordVisibleAt430dp() {
        compose.setContent { NeuroTheme { NeuroBackdrop { Box(Modifier.requiredWidth(430.dp).safeDrawingPadding()) {
            HomeScreen(emptyList(), null, {}, {}, {}, {}, {}, {})
        } } } }
        compose.onNodeWithText("Импортировать аудио").assertIsDisplayed()
        compose.onNodeWithText("Записать разговор").assertIsDisplayed()
        save("home-430")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "design-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
