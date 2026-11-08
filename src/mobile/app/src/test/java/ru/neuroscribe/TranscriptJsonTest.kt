package ru.neuroscribe

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.neuroscribe.core.Replica
import ru.neuroscribe.core.exportJson

class TranscriptJsonTest {
    @Test fun emptySpeechExportsAnEmptyTranscript() {
        assertEquals("{\"transcript\":[]}", exportJson(emptyList()))
    }

    @Test fun exportUsesOrganizerSchemaAndStableSpeakerIdentity() {
        val turns = listOf(
            Replica(1.25f, 4.38f, 0, "Добрый день!"),
            Replica(5f, 6.5f, 2, "Здравствуйте."),
            Replica(7f, 8f, 0, "Продолжим?"),
        )
        assertEquals("{\"transcript\":[{\"speaker_id\":\"speaker_1\",\"start_ms\":1250,\"end_ms\":4380,\"text\":\"Добрый день!\"},{\"speaker_id\":\"speaker_3\",\"start_ms\":5000,\"end_ms\":6500,\"text\":\"Здравствуйте.\"},{\"speaker_id\":\"speaker_1\",\"start_ms\":7000,\"end_ms\":8000,\"text\":\"Продолжим?\"}]}", exportJson(turns))
    }

    @Test fun quotesBackslashesAndControlCharactersRemainValidJson() {
        assertEquals("{\"transcript\":[{\"speaker_id\":\"speaker_1\",\"start_ms\":0,\"end_ms\":1000,\"text\":\"Он: \\\"да\\\".\\nПуть C:\\\\записи\\t\\u0000\\r\\b\\f\"}]}",
            exportJson(listOf(Replica(0f, 1f, 0, "Он: \"да\".\nПуть C:\\записи\t\u0000\r\b\u000c"))))
    }

    @Test fun timestampsRoundToIntegerMilliseconds() {
        assertEquals("{\"transcript\":[{\"speaker_id\":\"speaker_2\",\"start_ms\":1250,\"end_ms\":4381,\"text\":\"тест\"}]}",
            exportJson(listOf(Replica(1.2504f, 4.3806f, 1, "тест"))))
    }

    @Test(expected = IllegalArgumentException::class) fun nonFiniteTimestampsAreRejected() {
        exportJson(listOf(Replica(Float.NaN, 1f, 0, "тест")))
    }

    @Test(expected = IllegalArgumentException::class) fun reversedIntervalsAreRejected() {
        exportJson(listOf(Replica(2f, 1f, 0, "тест")))
    }

    @Test(expected = IllegalArgumentException::class) fun negativeSpeakerIdsAreRejected() {
        exportJson(listOf(Replica(0f, 1f, -1, "тест")))
    }
}
