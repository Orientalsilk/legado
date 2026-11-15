package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudPageHighlightTest {

    @Test
    fun `paragraph continuation is highlighted from the visible page start`() {
        assertEquals(
            0,
            readAloudSpanStartOnPage(
                progressPosition = 10,
                paragraphStart = 0,
                paragraphEnd = 50,
                pageStart = 20,
                pageEnd = 40,
            )
        )
    }

    @Test
    fun `current speech offset is retained when it is already on the visible page`() {
        assertEquals(
            5,
            readAloudSpanStartOnPage(
                progressPosition = 25,
                paragraphStart = 0,
                paragraphEnd = 50,
                pageStart = 20,
                pageEnd = 40,
            )
        )
    }

    @Test
    fun `paragraphs outside the visible page are not highlighted`() {
        assertNull(readAloudSpanStartOnPage(5, 0, 10, 20, 40))
        assertNull(readAloudSpanStartOnPage(45, 0, 50, 20, 40))
    }
}
