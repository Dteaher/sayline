package ru.neuroscribe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.core.Replica
import ru.neuroscribe.data.Recording
import ru.neuroscribe.ui.*

@RunWith(AndroidJUnit4::class)
class TranscriptPolishTest {
    @get:Rule val compose = createComposeRule()
    private val ready = Recording("polish-ui-only", "Обсуждение проекта", duration = 10f, status = "Готово",
        replicas = listOf(Replica(1f, 5f, 0, "Проверка интерфейса.")))

    private fun show(recording: Recording = ready, onExport: () -> Unit = {}, onRetry: () -> Unit = {}) {
        compose.setContent { NeuroTheme { NeuroBackdrop { Box(Modifier.safeDrawingPadding()) {
            TranscriptScreen(recording, false, false, 0f, null, {}, {}, onExport, {}, {}, {}, {}, { _, _ -> }, onRetry)
        } } } }
    }

    @Test fun jsonExportIsVisibleWithoutOpeningAMenu() {
        var requested = false
        show(onExport = { requested = true })
        compose.onNodeWithContentDescription("Сохранить JSON").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test fun participantTabCommunicatesItsContentAndSelection() {
        show()
        compose.onNodeWithText("Участники").performClick().assertIsSelected()
        compose.onNodeWithText("Разговор в цифрах").assertIsDisplayed()
        compose.onNodeWithText("Стенограмма").performClick().assertIsSelected()
        compose.onNodeWithText("Проверка интерфейса.").assertIsDisplayed()
    }

    @Test fun noSpeechHasAnExplicitCompletedStateAndRetry() {
        var requested = false
        show(ready.copy(replicas = emptyList(), status = "Разборчивой речи не обнаружено"), onRetry = { requested = true })
        compose.onNodeWithText("Речь не обнаружена").assertIsDisplayed()
        compose.onNodeWithText("Повторить обработку").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test fun failedUndecodableAudioDoesNotOfferBrokenPlayback() {
        show(ready.copy(replicas = emptyList(), duration = 0f, status = "Ошибка обработки"))
        compose.onNodeWithText("Не удалось обработать запись").assertIsDisplayed()
        compose.onNodeWithContentDescription("Воспроизвести запись").assertDoesNotExist()
    }
}
