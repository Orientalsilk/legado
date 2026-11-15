package io.legado.app.ui.font

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontImportTest {

    @Test
    fun `font picker and importer accept individual fonts and collections`() {
        listOf("font.ttf", "font.otf", "font.ttc", "font.otc", "FONT.TTC", "folder\\font.otc")
            .forEach { assertTrue("$it should be supported", isSupportedFontFileName(it)) }
    }

    @Test
    fun `font picker and importer reject unrelated and partial extensions`() {
        listOf("font.woff", "font.woff2", "font.ttf.bak", ".ttc", "font")
            .forEach { assertFalse("$it should not be supported", isSupportedFontFileName(it)) }
    }
}
