package io.legado.app.service

/**
 * 多角色朗读标记（仅系统 TTS 使用）。
 *
 * 判断每句对话是男声还是女声，并在这句对话的开头插入一个不可见的标记，
 * 让支持“按标记选声音”的 TTS 引擎（如 MultiTTS）切换声音。
 * 旁白不加标记；判断不出来的对话也不加标记，保持引擎默认声音。
 */
internal enum class SpeakerGender { MALE, FEMALE }

/** 对应 MultiTTS 里的“青年 / 少年 / 老年”三档声音。 */
internal enum class SpeakerAge { ADULT, YOUTH, ELDER, LEAD }

internal data class Speaker(val gender: SpeakerGender, val age: SpeakerAge = SpeakerAge.ADULT)

/** 跨段落的判断状态：上一段对话的说话人，用来处理“一问一答”的交替。 */
internal class RoleTagState {
    var lastGender: Speaker? = null

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

    /**
     * 不可见标记，只用 U+2060 / U+200C / U+200D 组合，互相不会成为对方的子串：
     * 青年  男 2060 200C 2060      女 2060 200D 2060
     * 少年  男 2060 200C 200C 2060 女 2060 200D 200D 2060
     * 老年  男 2060 200C 200D 2060 女 2060 200D 200C 2060
     * 主角  男 2060 200C 200D 200D 2060 女 2060 200D 200C 200C 2060
     */
    const val MALE_TAG = "\u2060\u200C\u2060"
    const val FEMALE_TAG = "\u2060\u200D\u2060"
    const val MALE_YOUTH_TAG = "\u2060\u200C\u200C\u2060"
    const val FEMALE_YOUTH_TAG = "\u2060\u200D\u200D\u2060"
    const val MALE_ELDER_TAG = "\u2060\u200C\u200D\u2060"
    const val FEMALE_ELDER_TAG = "\u2060\u200D\u200C\u2060"
    const val MALE_LEAD_TAG = "\u2060\u200C\u200D\u200D\u2060"
    const val FEMALE_LEAD_TAG = "\u2060\u200D\u200C\u200C\u2060"

    /** 返回“男主、女主”名字的原始设置：第一行男主，第二行女主，名字用顿号、逗号或竖线隔开。 */
    @Volatile
    var leadNamesProvider: () -> String = { "" }

    private fun tagOf(speaker: Speaker): String = when (speaker.gender) {
        SpeakerGender.MALE -> when (speaker.age) {
            SpeakerAge.ADULT -> MALE_TAG
            SpeakerAge.YOUTH -> MALE_YOUTH_TAG
            SpeakerAge.ELDER -> MALE_ELDER_TAG
            SpeakerAge.LEAD -> MALE_LEAD_TAG
        }
        SpeakerGender.FEMALE -> when (speaker.age) {
            SpeakerAge.ADULT -> FEMALE_TAG
            SpeakerAge.YOUTH -> FEMALE_YOUTH_TAG
            SpeakerAge.ELDER -> FEMALE_ELDER_TAG
            SpeakerAge.LEAD -> FEMALE_LEAD_TAG
        }
    }

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
    private val maleSet = maleWords.toSet()

    /** 这些称呼落在“少年”或“老年”声音上，和 MultiTTS 的角色规则保持一致。 */
    private val youthWords = setOf("少年", "男孩", "儿子", "少女", "女孩", "丫头", "女儿", "公主")
    private val elderWords = setOf(
        "老者", "老头", "父亲", "老爷", "将军", "皇帝",
        "老妇", "婆婆", "奶奶", "母亲", "娘亲", "妈妈", "夫人", "皇后"
    )

    private class Matcher(
        val regex: Regex,
        val maleLeads: Set<String>,
        val femaleLeads: Set<String>,
        val learnedMale: Set<String>,
        val learnedFemale: Set<String>
    )

    private val baseMatcher = buildMatcher(emptyList(), emptyList(), emptySet(), emptySet())

    private var cachedRaw: String = ""

    // ---- 自动学习人名性别：整章先扫一遍，“李定国……他”这类写法记为男，“女兵……她”记为女 ----
    @Volatile
    private var learnedMale: Set<String> = emptySet()

    @Volatile
    private var learnedFemale: Set<String> = emptySet()

    @Volatile
    private var learnedVersion: Int = 0
    private var learnedBookKey: String = ""
    private var learnedFromList: List<String>? = null

    private val nameContext = Regex(
        "(?<![一-龥])([一-龥]{2,3})(?:又|也|却|便|就|还|都|更|不|好像|仿佛|似乎|重重|轻轻|缓缓|淡淡|冷冷|低声)?" +
            "(?:觉得|感觉|说道|说|问道|问|答道|答|笑道|叹道|怒道|喝道|喊道|道|摇头|点头|皱眉|苦笑|冷笑|微笑|笑|惊讶|好奇|想|" +
            "笑了笑|转身|站起|低下头|抬起头|看着|望着|盯着|沉默|愣住|叹了口气)"
    )
    private const val badNameStart = "的了是在也都就还又却但只于而且并这那有被把将对向跟给和与及或如若虽既因所其他她它我你您咱"
    private val pronounRegex = Regex("[他她]")

    @Synchronized
    fun resetLearnedNames() {
        learnedMale = emptySet()
        learnedFemale = emptySet()
        learnedBookKey = ""
        learnedFromList = null
        learnedVersion++
    }

    /**
     * 扫描整章文字，学习“人名 → 性别”。只在人名后面的同一句里出现“他/她”时才记一票，
     * 票数不少于 2 且同一性别占 80% 以上才采用，避免把错的词当成人名。
     */
    @Synchronized
    fun learnFromChapter(bookKey: String, paragraphs: List<String>) {
        if (paragraphs === learnedFromList) return
        learnedFromList = paragraphs
        if (bookKey != learnedBookKey) {
            learnedBookKey = bookKey
            learnedMale = emptySet()
            learnedFemale = emptySet()
        }
        val votes = HashMap<String, IntArray>()
        for (paragraph in paragraphs) {
            for (m in nameContext.findAll(paragraph)) {
                val name = m.groupValues[1]
                if (badNameStart.indexOf(name[0]) >= 0) continue
                if (name.contains('他') || name.contains('她')) continue
                if (name in femaleSet || name in maleSet) continue
                val windowStart = m.range.last + 1
                var windowEnd = minOf(paragraph.length, windowStart + 30)
                for (k in windowStart until windowEnd) {
                    if ("。！？“”\n".indexOf(paragraph[k]) >= 0) {
                        windowEnd = k
                        break
                    }
                }
                val window = paragraph.substring(windowStart, windowEnd)
                for (p in pronounRegex.findAll(window)) {
                    val s = p.range.first
                    val before: Char? = if (s > 0) window[s - 1] else null
                    val after: Char? = if (s + 1 < window.length) window[s + 1] else null
                    if (after == '的' || (after != null && "们人乡处方".indexOf(after) >= 0)) continue
                    if (before != null && (objectMarkers.indexOf(before) >= 0 ||
                            "其别排利吉".indexOf(before) >= 0)
                    ) continue
                    val v = votes.getOrPut(name) { IntArray(2) }
                    if (p.value == "他") v[0]++ else v[1]++
                    break
                }
            }
        }
        val male = HashSet(learnedMale)
        val female = HashSet(learnedFemale)
        for ((name, v) in votes) {
            val total = v[0] + v[1]
            if (total < 2) continue
            if (v[0] * 5 >= total * 4) {
                if (name !in female) male.add(name)
            } else if (v[1] * 5 >= total * 4) {
                if (name !in male) female.add(name)
            }
        }
        if (male != learnedMale || female != learnedFemale) {
            learnedMale = male
            learnedFemale = female
            learnedVersion++
        }
    }
    private var cachedMatcher: Matcher = baseMatcher

    private fun buildMatcher(
        maleLeads: List<String>,
        femaleLeads: List<String>,
        learnedMale: Set<String>,
        learnedFemale: Set<String>
    ): Matcher {
        val words = (femaleWords + maleWords + maleLeads + femaleLeads +
            learnedMale + learnedFemale).distinct()
        val regex = Regex(
            words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        )
        return Matcher(regex, maleLeads.toSet(), femaleLeads.toSet(), learnedMale, learnedFemale)
    }

    private fun parseNames(line: String): List<String> =
        line.split(Regex("[|,，、;；\\s]+")).map { it.trim() }.filter { it.length >= 2 || it == "我" }

    @Synchronized
    private fun currentMatcher(): Matcher {
        val raw = runCatching { leadNamesProvider() }.getOrDefault("")
        val key = raw + "#" + learnedVersion
        if (key == cachedRaw) return cachedMatcher
        val lines = raw.lines()
        val male = parseNames(lines.getOrElse(0) { "" })
        val female = parseNames(lines.getOrElse(1) { "" })
        cachedMatcher = if (male.isEmpty() && female.isEmpty() &&
            learnedMale.isEmpty() && learnedFemale.isEmpty()
        ) {
            baseMatcher
        } else {
            buildMatcher(male, female, learnedMale, learnedFemale)
        }
        cachedRaw = key
        return cachedMatcher
    }

    /** 出现在称呼前面，说明这个称呼是“被…的人”（宾语），不是说话的人。 */
    private const val objectMarkers =
        "对把将跟给朝向着冲看见到瞅望问被让叫请令使找喊唤骂瞪盯拉推扶抱拍抓碰摸拦挡追随陪替从与和同得道答说告诉教疼哄劝慰瞧"

    private const val dialoguePunct = "，。！？…；～—"

    private const val sentenceEnd = "。！？\n"

    private class Quote(val open: Int, val close: Int)

    private class Candidate(val speaker: Speaker)

    private fun Speaker.flip(): Speaker =
        Speaker(if (gender == SpeakerGender.MALE) SpeakerGender.FEMALE else SpeakerGender.MALE)

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

    private fun candidates(seg: String, matcher: Matcher): List<Candidate> {
        val result = ArrayList<Candidate>()
        for (m in matcher.regex.findAll(seg)) {
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
            val speaker = when (word) {
                in matcher.maleLeads -> Speaker(SpeakerGender.MALE, SpeakerAge.LEAD)
                in matcher.femaleLeads -> Speaker(SpeakerGender.FEMALE, SpeakerAge.LEAD)
                in matcher.learnedMale -> Speaker(SpeakerGender.MALE)
                in matcher.learnedFemale -> Speaker(SpeakerGender.FEMALE)
                else -> {
                    val gender = if (word in femaleSet) SpeakerGender.FEMALE else SpeakerGender.MALE
                    val age = when (word) {
                        in youthWords -> SpeakerAge.YOUTH
                        in elderWords -> SpeakerAge.ELDER
                        else -> SpeakerAge.ADULT
                    }
                    Speaker(gender, age)
                }
            }
            result.add(Candidate(speaker))
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
        val matcher = currentMatcher()
        val genders = arrayOfNulls<Speaker>(quotes.size)
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

            val fromBefore = candidates(beforeTail, matcher).lastOrNull()?.speaker
            val fromAfter = candidates(afterHead, matcher).firstOrNull()?.speaker
            val colonEnd = endsWithColon(before)

            var g: Speaker? =
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

        var lastKnown: Speaker? = null
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
            val tag = tagOf(g)
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
