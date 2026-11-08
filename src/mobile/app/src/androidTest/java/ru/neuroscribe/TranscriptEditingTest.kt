package ru.neuroscribe

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.neuroscribe.core.*
import ru.neuroscribe.data.*
import ru.neuroscribe.ui.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class TranscriptEditingTest {
    @get:Rule val compose = createComposeRule()
    private val source = listOf(Replica(0f, 2f, 0, "Да."), Replica(3f, 5f, 1, "Продолжим."))

    @Test fun originalAndCorrectionSurviveReloadIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecordingStore(context)
        val recording = store.create("Правки").copy(replicas = editReplica(source, 0, "Нет.", 1), originalReplicas = source)
        try {
            store.save(recording)
            val loaded = RecordingStore(context).list().first { it.id == recording.id }
            assertEquals("Нет.", loaded.replicas[0].text)
            assertEquals("Да.", loaded.originalReplicas!![0].text)
            assertEquals(source, restoreReplica(loaded.replicas, loaded.originalReplicas, 0))
        } finally { File(context.filesDir, "recordings/${recording.id}.json").delete() }
    }

    @Test fun editDialogChangesTextAndSpeakerAndExposesFragmentRetry() {
        var result: Pair<String, Int>? = null
        val recording = Recording("editing-ui", "Встреча", duration = 5f, replicas = source)
        compose.setContent { NeuroTheme {
            TranscriptScreen(recording, false, false, 0f, null, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                onEditTurn = { _, _, text, speaker -> result = text to speaker })
        } }
        compose.onNodeWithContentDescription("Изменить реплику 1").performClick()
        compose.onNodeWithText("Текст реплики").performTextReplacement("Нет.")
        compose.onAllNodesWithText("Говорящий 2").filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        compose.onNodeWithText("Распознать этот фрагмент заново").assertExists()
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle { assertEquals("Нет." to 1, result) }
    }

    @Test fun removingFirstClusterDoesNotRenameRemainingSpeakerAndCanBeUndone() {
        val recording = Recording("stable-edit-ui", "Встреча", duration = 5f,
            replicas = editReplica(source, 0, "Да.", 1), originalReplicas = source)
        compose.setContent { NeuroTheme {
            TranscriptScreen(recording, false, false, 0f, null, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
        } }
        compose.onAllNodesWithText("Говорящий 2").assertCountEquals(2)
        compose.onNodeWithText("Говорящий 1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Изменить реплику 1").performClick()
        compose.onNodeWithText("Говорящий 1").assertExists()
        compose.onNodeWithText("Восстановить исходную реплику").assertExists()
    }
}
