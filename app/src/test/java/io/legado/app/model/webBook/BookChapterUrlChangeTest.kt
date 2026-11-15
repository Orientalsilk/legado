package io.legado.app.model.webBook

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class BookChapterUrlChangeTest {

    @Test
    fun `same chapter with a changed URL is counted`() {
        val old = listOf(
            BookChapter(url = "a", title = "Chapter 1", bookUrl = "book", index = 0),
            BookChapter(url = "b", title = "Chapter 2", bookUrl = "book", index = 1),
            BookChapter(url = "c", title = "Chapter 3", bookUrl = "book", index = 2),
        )
        val updated = listOf(
            BookChapter(url = "a", title = "Chapter 1", bookUrl = "book", index = 0),
            BookChapter(url = "d", title = "Chapter 2", bookUrl = "book", index = 1),
            BookChapter(url = "c", title = "Chapter 3", bookUrl = "book", index = 2),
        )

        assertEquals(1, countChangedChapterUrls(old, updated))
    }

    @Test
    fun `new titles and volume entries are not treated as changed chapter URLs`() {
        val old = listOf(
            BookChapter(url = "a", title = "Chapter 1", bookUrl = "book", index = 0),
            BookChapter(url = "b", title = "Chapter 2", bookUrl = "book", index = 1),
        )
        val updated = listOf(
            BookChapter(url = "new", title = "Chapter 1 revised", bookUrl = "book", index = 0),
            BookChapter(url = "volume", title = "Volume 1", bookUrl = "book", index = 1, isVolume = true),
        )

        assertEquals(0, countChangedChapterUrls(old, updated))
    }

    @Test
    fun `equivalent absolute URLs are not counted as changes`() {
        val old = listOf(
            BookChapter(
                url = "chapter/2",
                baseUrl = "https://example.com/",
                title = "Chapter 2",
                bookUrl = "book",
                index = 1,
            )
        )
        val updated = listOf(
            BookChapter(
                url = "https://example.com/chapter/2",
                baseUrl = "https://example.com/",
                title = "Chapter 2",
                bookUrl = "book",
                index = 1,
            )
        )

        assertEquals(0, countChangedChapterUrls(old, updated))
    }
}
