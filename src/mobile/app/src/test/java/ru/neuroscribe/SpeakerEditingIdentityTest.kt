package ru.neuroscribe

import org.junit.Assert.*
import org.junit.Test
import ru.neuroscribe.core.*
import ru.neuroscribe.data.Recording

class SpeakerEditingIdentityTest {
    @Test fun reassigningFirstTurnKeepsOriginalNamesAndEmptiedSpeakerAvailable() {
        val original = listOf(Replica(0f, 1f, 4, "Да."), Replica(2f, 3f, 8, "Нет."))
        val changed = Recording("stable", "Разговор", replicas = editReplica(original, 0, "Да.", 8), originalReplicas = original)
        assertEquals(listOf(4, 8), changed.speakerOrder)
        assertEquals("Говорящий 2", changed.speakerName(8))
        assertEquals("Говорящий 1", changed.speakerName(4))
        val restored = editReplica(changed.replicas, 0, "Да.", 4, changed.speakerOrder.toSet())
        assertEquals(original, restored)
    }
}
