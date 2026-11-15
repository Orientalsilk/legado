package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRoleTaggerTest {

    private val m = SpeechRoleTagger.MALE_TAG
    private val f = SpeechRoleTagger.FEMALE_TAG

    private fun run(vararg paragraphs: String): List<String> {
        val state = RoleTagState()
        return paragraphs.map { SpeechRoleTagger.tag(it, state).text }
    }

    @Test
    fun tagsHaveSameLengthAndAreInvisible() {
        assertEquals(m.length, f.length)
        assertEquals(3, m.length)
        assertTrue((m + f).none { it.isLetterOrDigit() })
    }

    @Test
    fun allSixTagsAreDistinctAndNoneContainsAnother() {
        val tags = listOf(
            SpeechRoleTagger.MALE_TAG, SpeechRoleTagger.FEMALE_TAG,
            SpeechRoleTagger.MALE_YOUTH_TAG, SpeechRoleTagger.FEMALE_YOUTH_TAG,
            SpeechRoleTagger.MALE_ELDER_TAG, SpeechRoleTagger.FEMALE_ELDER_TAG,
        )
        assertEquals(6, tags.toSet().size)
        for (a in tags) for (b in tags) {
            if (a != b) assertFalse("$a contains $b", a.contains(b))
        }
    }

    @Test
    fun elderAndYouthWordsPickTheirOwnVoice() {
        val out = run(
            "老者叹道：“你来了，坐吧。”",
            "少女低声说：“我不去，我害怕。”",
            "老妇笑着说：“好孩子，别怕。”",
            "少年怒道：“我偏要去！”"
        )
        assertEquals("老者叹道：${SpeechRoleTagger.MALE_ELDER_TAG}“你来了，坐吧。”", out[0])
        assertEquals("少女低声说：${SpeechRoleTagger.FEMALE_YOUTH_TAG}“我不去，我害怕。”", out[1])
        assertEquals("老妇笑着说：${SpeechRoleTagger.FEMALE_ELDER_TAG}“好孩子，别怕。”", out[2])
        assertEquals("少年怒道：${SpeechRoleTagger.MALE_YOUTH_TAG}“我偏要去！”", out[3])
    }

    @Test
    fun narrationWithoutDialogueIsUntouched() {
        val text = "他走进屋里，她抬头看了一眼。"
        val tagged = SpeechRoleTagger.tag(text, RoleTagState())
        assertFalse(tagged.hasTags)
        assertEquals(text, tagged.text)
    }

    @Test
    fun attributionBeforeColonDecidesSpeaker() {
        val out = run(
            "年轻将领笑：“如果只能打一个，那当然是要打建虏了。”",
            "女兵盯着他，不回答他问题，却忽然反问道：“哥，你见过吗？”"
        )
        assertEquals("年轻将领笑：${m}“如果只能打一个，那当然是要打建虏了。”", out[0])
        assertEquals("女兵盯着他，不回答他问题，却忽然反问道：${f}“哥，你见过吗？”", out[1])
    }

    @Test
    fun pronounUsedAsObjectIsIgnored() {
        val out = run("答完之后，见妹妹久久不说话，只是盯着他看，于是他笑道：“怎么样？”")
        assertEquals("答完之后，见妹妹久久不说话，只是盯着他看，于是他笑道：${m}“怎么样？”", out[0])
    }

    @Test
    fun attributionAfterQuoteDecidesSpeaker() {
        val out = run("“但我见过。”女兵咬咬唇。")
        assertEquals("${f}“但我见过。”女兵咬咬唇。", out[0])
    }

    @Test
    fun unnamedSpeakerAlternatesInBackAndForthDialogue() {
        val out = run(
            "“但我见过。”女兵咬咬唇。",
            "“哦，在哪？”李定国好奇。"
        )
        assertEquals("${m}“哦，在哪？”李定国好奇。", out[1])
    }

    @Test
    fun alternationDoesNotCrossNarrationParagraphs() {
        val out = run(
            "“但我见过。”女兵咬咬唇。",
            "李定国想了很久，没有说话。",
            "“哦，在哪？”李定国好奇。"
        )
        assertEquals("“哦，在哪？”李定国好奇。", out[2])
    }

    @Test
    fun quotedTermWithoutPunctuationIsNotDialogue() {
        val text = "所谓“正义”，不过是一句空话。"
        assertFalse(SpeechRoleTagger.tag(text, RoleTagState()).hasTags)
    }

    @Test
    fun audioCacheIdentityIncludesTheActualRoleTaggedText() {
        val plain = buildHttpTtsCacheFileName(
            "chapter", "https://tts.example/voice", 100, "variable", "header", "“你好。”"
        )
        val male = buildHttpTtsCacheFileName(
            "chapter", "https://tts.example/voice", 100, "variable", "header",
            SpeechRoleTagger.MALE_TAG + "“你好。”"
        )
        val female = buildHttpTtsCacheFileName(
            "chapter", "https://tts.example/voice", 100, "variable", "header",
            SpeechRoleTagger.FEMALE_TAG + "“你好。”"
        )

        assertNotEquals(plain, male)
        assertNotEquals(plain, female)
        assertNotEquals(male, female)
    }

    @Test
    fun offsetsAreMappedBackToOriginalText() {
        val text = "年轻将领笑：“如果只能打一个。”她说：“好。”"
        val tagged = SpeechRoleTagger.tag(text, RoleTagState())
        assertTrue(tagged.hasTags)
        // 每一个原文字符在加标记后的位置，都必须能换算回原来的位置。
        var taggedIndex = 0
        for (i in text.indices) {
            while (tagged.text.startsWith(m, taggedIndex) || tagged.text.startsWith(f, taggedIndex)) {
                // 标记内部的偏移落到后面那个引号上。
                for (k in 0 until m.length) assertEquals(i, tagged.toOriginalOffset(taggedIndex + k))
                taggedIndex += m.length
            }
            assertEquals(text[i], tagged.text[taggedIndex])
            assertEquals(i, tagged.toOriginalOffset(taggedIndex))
            taggedIndex++
        }
    }
}
