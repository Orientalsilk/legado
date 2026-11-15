package io.legado.app.service

/**
 * 多角色朗读标记（仅系统 TTS 使用）。
 *
 * 判断每句对话是男声还是女声，并在这句对话的开头插入一个表情符号标记，
 * 让支持“按标记选声音”的 TTS 引擎（如 MultiTTS）切换声音。
 * 旁白不加标记；判断不出来的对话也不加标记，保持引擎默认声音。
 */
internal enum class SpeakerGender { MALE, FEMALE }

/** 跨段落的判断状态：上一段对话的说话人，用来处理“一问一答”的交替。 */
internal class RoleTagState {
    var lastGender: SpeakerGender? = null

    /** 距离上一段有对话的段落已过几段；0 表示紧邻的上一段有对话。 */
    var age: Int = 99

    fun reset() {
        lastGender = null
        age = 99
    }
}

/**
 * 加标记后的文字，以及“加标记后的偏移 → 原文偏移”的换算，
 * 保证朗读高亮、进度不会因为多出的标记而错位。
 */
internal class TaggedSpeech(
    val text: String,
    private val insertAt: IntArray,
    private val insertLen: IntArray
) {
    val hasTags: Boolean get() = insertAt.isNotEmpty()

    fun toOriginalOffset(taggedOffset: Int): Int {
        var shift = 0
        for (k in insertAt.indices) {
            val start = insertAt[k] + shift
            if (taggedOffset < start) break
            if (taggedOffset < start + insertLen[k]) return insertAt[k]
            shift += insertLen[k]
        }
        return (taggedOffset - shift).coerceAtLeast(0)
    }
}

internal object SpeechRoleTagger {

    /** 🧑🏻 */
    const val MALE_TAG = "🧑🏻"

    /** 👩🏻 */
    const val FEMALE_TAG = "👩🏻"

    private val openToClose = mapOf('“' to '”', '「' to '」', '『' to '』', '"' to '"')

    private val femaleWords = listOf(
        "她", "女兵", "女子", "女人", "女孩", "少女", "姑娘", "妹妹", "姐姐", "母亲", "娘亲",
        "妈妈", "夫人", "太太", "小姐", "妻子", "老妇", "婆婆", "奶奶", "丫头", "公主",
        "皇后", "贵妃", "嫂子", "女儿", "女侠", "女将", "女官", "少妇", "美人"
    )

    private val maleWords = listOf(
        "他", "男子", "男人", "汉子", "少年", "老者", "老头", "父亲", "哥哥", "弟弟", "大叔",
        "公子", "先生", "青年", "将领", "将军", "丈夫", "儿子", "皇帝", "太子", "老爷",
        "大哥", "兄长", "少爷", "书生", "壮汉", "男孩"
    )

    private val femaleSet = femaleWords.toSet()

    private val wordRegex = Regex(
        (femaleWords + maleWords).sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
    )

    /** 出现在称呼前面，说明这个称呼是“被…的人”（宾语），不是说话的人。 */
    private const val objectMarkers =
        "对把将跟给朝向着冲看见到瞅望问被让叫请令使找喊唤骂瞪盯拉推扶抱拍抓碰摸拦挡追随陪替从与和同得道答说告诉教疼哄劝慰瞧"

    private const val dialoguePunct = "，。！？…；～—"

    private const val sentenceEnd = "。！？\n"

    private class Quote(val open: Int, val close: Int)

    private class Candidate(val gender: SpeakerGender)

    private fun SpeakerGender.flip(): SpeakerGender =
        if (this == SpeakerGender.MALE) SpeakerGender.FEMALE else SpeakerGender.MALE

    private fun findQuotes(text: String): List<Quote> {
        val result = ArrayList<Quote>()
        var i = 0
        while (i < text.length) {
            val closeChar = openToClose[text[i]]
            if (closeChar != null) {
                val j = text.indexOf(closeChar, i + 1)
                if (j > i) {
                    result.add(Quote(i, j))
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun isDialogue(text: String, q: Quote): Boolean {
        if (q.close <= q.open + 1) return false
        for (k in q.open + 1 until q.close) {
            if (dialoguePunct.indexOf(text[k]) >= 0) return true
        }
        if (q.open == 0) return false
        val prev = text[q.open - 1]
        return prev == '：' || prev == ':'
    }

    private fun candidates(seg: String): List<Candidate> {
        val result = ArrayList<Candidate>()
        for (m in wordRegex.findAll(seg)) {
            val s = m.range.first
            val e = m.range.last + 1
            val word = m.value
            val before: Char? = if (s > 0) seg[s - 1] else null
            val after: Char? = if (e < seg.length) seg[e] else null
            if (after == '的') continue
            if (word == "他" || word == "她") {
                if (after != null && "们人乡处方".indexOf(after) >= 0) continue
                if (before != null && "其别排利吉".indexOf(before) >= 0) continue
            }
            if (before != null && objectMarkers.indexOf(before) >= 0) continue
            val gender = if (word in femaleSet) SpeakerGender.FEMALE else SpeakerGender.MALE
            result.add(Candidate(gender))
        }
        return result
    }

    private fun endsWithColon(seg: String): Boolean {
        val t = seg.trimEnd()
        return t.endsWith("：") || t.endsWith(":")
    }

    fun tag(text: String, state: RoleTagState): TaggedSpeech {
        val quotes = findQuotes(text).filter { isDialogue(text, it) }
        if (quotes.isEmpty()) {
            state.age = minOf(state.age + 1, 99)
            return TaggedSpeech(text, IntArray(0), IntArray(0))
        }
        val previousAge = state.age
        val genders = arrayOfNulls<SpeakerGender>(quotes.size)
        for (i in quotes.indices) {
            val q = quotes[i]
            val prevEnd = if (i == 0) -1 else quotes[i - 1].close
            val nextStart = if (i == quotes.size - 1) text.length else quotes[i + 1].open
            val before = text.substring(prevEnd + 1, q.open)
            val after = text.substring(q.close + 1, nextStart)

            var tailStart = 0
            for (k in before.length - 1 downTo 0) {
                if (sentenceEnd.indexOf(before[k]) >= 0) {
                    tailStart = k + 1
                    break
                }
            }
            tailStart = maxOf(tailStart, before.length - 40)
            val beforeTail = before.substring(tailStart)

            var afterHead = after.take(14)
            for (k in afterHead.indices) {
                if (sentenceEnd.indexOf(afterHead[k]) >= 0) {
                    afterHead = afterHead.substring(0, k)
                    break
                }
            }

            val fromBefore = candidates(beforeTail).lastOrNull()?.gender
            val fromAfter = candidates(afterHead).firstOrNull()?.gender
            val colonEnd = endsWithColon(before)

            var g: SpeakerGender? =
                if (colonEnd) fromBefore ?: fromAfter else fromAfter ?: fromBefore

            if (g == null) {
                if (i > 0) {
                    val p = genders[i - 1]
                    if (p != null) {
                        g = if (before.isBlank()) p.flip() else p
                    }
                } else if (before.isBlank() && previousAge == 0) {
                    g = state.lastGender?.flip()
                }
            }
            genders[i] = g
        }

        var lastKnown: SpeakerGender? = null
        for (g in genders) if (g != null) lastKnown = g
        if (lastKnown != null) {
            state.lastGender = lastKnown
            state.age = 0
        } else {
            state.lastGender = null
            state.age = 99
        }

        val sb = StringBuilder(text.length + 16)
        val insertAt = ArrayList<Int>()
        val insertLen = ArrayList<Int>()
        var last = 0
        for (i in quotes.indices) {
            val g = genders[i] ?: continue
            val tag = if (g == SpeakerGender.MALE) MALE_TAG else FEMALE_TAG
            sb.append(text, last, quotes[i].open)
            sb.append(tag)
            last = quotes[i].open
            insertAt.add(quotes[i].open)
            insertLen.add(tag.length)
        }
        sb.append(text, last, text.length)
        if (insertAt.isEmpty()) return TaggedSpeech(text, IntArray(0), IntArray(0))
        return TaggedSpeech(sb.toString(), insertAt.toIntArray(), insertLen.toIntArray())
    }
}
