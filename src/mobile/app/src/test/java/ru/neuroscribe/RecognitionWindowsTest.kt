package ru.neuroscribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.neuroscribe.core.RecognitionWindow
import ru.neuroscribe.core.Turn
import ru.neuroscribe.core.recognitionWindows
import ru.neuroscribe.core.recognizedWindowText

class RecognitionWindowsTest {
    @Test fun longTurnHasAdjacentOwnedRangesAndOnlyOneSecondOfContext() {
        val windows = recognitionWindows(Turn(2f, 47f, 0), 50 * 16000)
        assertEquals(listOf(
            RecognitionWindow(32000, 368000, 32000, 352000),
            RecognitionWindow(336000, 688000, 352000, 672000),
            RecognitionWindow(656000, 752000, 672000, 752000),
        ), windows)
        assertTrue(windows.all { it.endSample - it.startSample <= 22 * 16000 })
        assertEquals(windows.dropLast(1).map { it.keepEndSample }, windows.drop(1).map { it.keepStartSample })
    }

    @Test fun turnAndContextAreClippedToActualAudio() {
        assertEquals(listOf(RecognitionWindow(0, 32000, 0, 32000)),
            recognitionWindows(Turn(-2f, 40f, 0), 32000))
        assertTrue(recognitionWindows(Turn(5f, 8f, 0), 32000).isEmpty())
        assertTrue(recognitionWindows(Turn(3f, 2f, 0), 10 * 16000).isEmpty())
        assertTrue(recognitionWindows(Turn(Float.NaN, 2f, 0), 10 * 16000).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { recognitionWindows(Turn(0f, 1f, 0), -1) }
    }

    @Test fun wordStartingExactlyAtTheBoundaryIsOwnedByTheFollowingWindow() {
        val windows = recognitionWindows(Turn(0f, 40f, 0), 40 * 16000)
        assertEquals("первое", recognizedWindowText(arrayOf(" пер", "вое", " сло", "во"),
            floatArrayOf(19f, 19.1f, 20f, 20.1f), windows[0]))
        assertEquals("слово", recognizedWindowText(arrayOf(" пер", "вое", " сло", "во"),
            floatArrayOf(0f, .1f, 1f, 1.1f), windows[1]))
    }

    @Test fun aWordCrossingTheBoundaryIsKeptWholeExactlyOnce() {
        val windows = recognitionWindows(Turn(0f, 40f, 0), 40 * 16000)
        assertEquals("слово", recognizedWindowText(arrayOf(" сло", "во"), floatArrayOf(19.8f, 20.2f), windows[0]))
        assertEquals("", recognizedWindowText(arrayOf(" сло", "во"), floatArrayOf(.8f, 1.2f), windows[1]))
    }

    @Test fun delayedPunctuationFollowsItsWordAcrossTheOwnershipBoundary() {
        val windows = recognitionWindows(Turn(0f, 40f, 0), 40 * 16000)
        assertEquals("слово.", recognizedWindowText(arrayOf(" слово", " ", "."),
            floatArrayOf(19.8f, 20f, 20.8f), windows[0]))
        assertEquals("дальше", recognizedWindowText(arrayOf(" слово", " ", ".", " дальше"),
            floatArrayOf(.8f, 1f, 1.8f, 2f), windows[1]))
    }

    @Test fun repeatedGenuineWordsArePreserved() {
        val window = RecognitionWindow(0, 160000, 0, 160000)
        assertEquals("да да", recognizedWindowText(arrayOf(" да", " ", "д", "а"),
            floatArrayOf(1f, 1.2f, 1.3f, 1.4f), window))
    }

    @Test fun unicodeSpacesAndBpePiecesReconstructWordsAndPunctuation() {
        val window = RecognitionWindow(0, 160000, 0, 160000)
        assertEquals("Добрый день, мир!", recognizedWindowText(
            arrayOf("\u2003Д", "о", "б", "ры", "й", "\u00a0", "день", ",", " ", "ми", "р", "!"),
            floatArrayOf(1f, 1.01f, 1.02f, 1.03f, 1.04f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f), window))
    }

    @Test fun missingOrInvalidTokenTimingIsRejected() {
        val window = RecognitionWindow(0, 160000, 0, 160000)
        assertEquals("", recognizedWindowText(emptyArray(), floatArrayOf(), window))
        val invalid = listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(-1f))
        for (times in invalid) assertThrows(IllegalArgumentException::class.java) {
            recognizedWindowText(arrayOf(" слово"), times, window)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recognizedWindowText(arrayOf(" один", " два"), floatArrayOf(2f, 1f), window)
        }
        assertThrows(IllegalArgumentException::class.java) {
            recognizedWindowText(arrayOf(""), floatArrayOf(1f), window)
        }
    }
}
