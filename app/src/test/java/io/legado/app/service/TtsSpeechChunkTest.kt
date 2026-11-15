package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSpeechChunkTest {

    @Test
    fun prefersSentenceBoundaryWithoutExceedingLimit() {
        val text = "第一句。第二句。第三句"
        val end = speechChunkEnd(text, 0, 8)

        assertEquals(8, end)
        assertTrue(end <= 8)
        assertEquals('。', text[end - 1])
    }

    @Test
    fun doesNotSplitSurrogatePairAtHardLimit() {
        val text = "abcdefgh😀ijkl"
        val end = speechChunkEnd(text, 0, 9)

        assertEquals(8, end)
        assertTrue(end == text.length || !Character.isHighSurrogate(text[end - 1]) ||
            !Character.isLowSurrogate(text[end]))
    }

    @Test
    fun alwaysMakesProgressForSmallLimits() {
        val text = "abcdefgh"
        val end = speechChunkEnd(text, 3, 1)

        assertEquals(4, end)
        assertTrue(end > 3)
    }

    @Test
    fun neverReturnsAnEndBeyondTheText() {
        val text = "短文本"
        assertEquals(text.length, speechChunkEnd(text, 0, 100))
    }

    @Test
    fun backwardPageMovementDoesNotRewindSpeech() {
        assertEquals(0, pendingSpeechPageMoves(5, 2))
        assertEquals(3, pendingSpeechPageMoves(2, 5))
    }
}
