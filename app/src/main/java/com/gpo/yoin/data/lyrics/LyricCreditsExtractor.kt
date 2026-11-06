package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.model.LyricCredit
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.data.model.Lyrics

/**
 * Takes the production credits out of a song's lyrics (owner 2026-11-05: they
 * read badly in the lyrics view and belong in About).
 *
 * NetEase and QQ write them into the lyrics themselves, at the start and often
 * again at the end: "作词 : 唐恬", "Bass Guitar：David Stewart", "Recorded at …",
 * copyright notices, and on QQ a first line "Title - Artist". The rule:
 * - Only the head and the tail are scanned. Each scan stops at the first line
 *   that isn't a credit, so a sung line in the middle ("她说：别走") is never
 *   touched, and neither is dialogue right after the credits.
 * - A credit is "role : names" (also "role - names" in English) whose role is a
 *   known word in Chinese, Japanese, Korean or English; "Chorus:", "Rap:" and
 *   singer markers ("宿涵：") are not roles.
 * - A block counts only when it has a headline role (作词 / 作曲 / 编曲 / 制作人),
 *   three or more roles, or (for the tail) the head block counted.
 * - If fewer than two lines would be left, nothing is taken.
 *
 * Tuned on 132 NetEase and QQ lyrics of 70 songs in seven languages
 * (prototype: https://claude.ai/artifact/1UMCBUVEQbQi7MG2Q3z9qr).
 * Parsing calls this, and the cache keeps the raw text, so a rule change
 * applies to cached songs too.
 */
object LyricCreditsExtractor {

    /** [lyrics] without its credit lines, carrying them in [Lyrics.credits]. */
    fun extract(lyrics: Lyrics): Lyrics = when (lyrics) {
        is Lyrics.Synced -> {
            val found = scan(lyrics.lines.map { it.text })
            if (found == null) {
                lyrics
            } else {
                lyrics.copy(
                    lines = lyrics.lines.filterIndexed { index, _ -> index !in found.removed },
                    credits = found.credits,
                )
            }
        }
        is Lyrics.Unsynced -> {
            val all = lyrics.text.lines()
            val textIndices = all.indices.filter { all[it].isNotBlank() }
            val found = scan(textIndices.map { all[it].trim() })
            if (found == null) {
                lyrics
            } else {
                val removed = found.removed.map { textIndices[it] }.toSet()
                lyrics.copy(
                    text = all.filterIndexed { index, _ -> index !in removed }.joinToString("\n").trim(),
                    credits = found.credits,
                )
            }
        }
    }

    internal class Found(val removed: Set<Int>, val credits: LyricCredits?)

    /** Positions in [texts] to drop, and the credits they held; null when nothing is taken. */
    internal fun scan(texts: List<String>): Found? {
        if (texts.isEmpty()) return null
        val kinds = texts.map(::classify).toMutableList()
        // A parenthesised line only continues the credit right above it; anywhere else it is sung.
        kinds.indices.forEach { index ->
            if (kinds[index] is Kind.Continuation) {
                val above = kinds.getOrNull(index - 1)
                if (above !is Kind.Credit && above !is Kind.Continuation) kinds[index] = Kind.Lyric
            }
        }
        val removed = sortedSetOf<Int>()

        val head = block(kinds, texts.indices, head = true)
        val headOk = head.isNotEmpty() && head.counts(kinds, extra = false)
        if (headOk) removed += head
        val tail = block(kinds, texts.indices.reversed(), head = false).filter { it !in removed }
        if (tail.isNotEmpty() && tail.counts(kinds, extra = headOk)) removed += tail
        // Some files put part of the credits after the first sung lines (Vsinger: "作词" first, the rest after
        // an intro). Once the head counted, a run of MIN_ROLES or more credit lines in the middle goes too.
        if (headOk) removed += middleRuns(kinds, removed)

        if (removed.isEmpty() || texts.size - removed.size < MIN_LINES_LEFT) return null

        val roles = mutableListOf<LyricCredit>()
        val notices = mutableListOf<String>()
        removed.forEach { index ->
            when (val kind = kinds[index]) {
                is Kind.Credit -> if (kind.names.isNotEmpty()) {
                    roles += LyricCredit(kind.role, kind.names, kind.headline)
                }
                is Kind.Continuation -> roles.lastOrNull()?.let { last ->
                    roles[roles.lastIndex] = last.copy(names = "${last.names} ${kind.text}")
                }
                is Kind.Notice -> notices += kind.text
                Kind.Junk, Kind.TitleLine, Kind.Lyric -> Unit
            }
        }
        val credits = LyricCredits(roles, notices).takeUnless { it.isEmpty() }
        return Found(removed, credits)
    }

    /**
     * The run of credit lines from the start of [order]. A "Title - Artist"
     * line may sit in the head run (QQ puts it first, NetEase after its own
     * credits); at the tail it is a sung line.
     */
    private fun block(kinds: List<Kind>, order: IntProgression, head: Boolean): List<Int> {
        val hit = mutableListOf<Int>()
        for (index in order) {
            val kind = kinds[index]
            if (kind == Kind.Lyric || (kind == Kind.TitleLine && !head)) break
            hit += index
        }
        return hit
    }

    /** Runs between the head and the tail made only of credit lines, holding [MIN_ROLES] or more roles. */
    private fun middleRuns(kinds: List<Kind>, removed: Set<Int>): List<Int> {
        val taken = mutableListOf<Int>()
        var run = mutableListOf<Int>()
        fun close() {
            val roles = run.count { (kinds[it] as? Kind.Credit)?.names?.isNotEmpty() == true }
            if (roles >= MIN_ROLES) taken += run
            run = mutableListOf()
        }
        kinds.indices.forEach { index ->
            val kind = kinds[index]
            val creditLine = kind is Kind.Credit || kind is Kind.Notice || kind is Kind.Continuation ||
                (kind == Kind.Junk && run.isNotEmpty())
            if (index !in removed && creditLine) run += index else close()
        }
        close()
        return taken
    }

    /**
     * Whether a run is really credits: a headline role or [MIN_ROLES] roles. A
     * tail run after a head run that was counts as it is (it holds no lyric by
     * construction: notices, junk such as a lone "-", credits).
     */
    private fun List<Int>.counts(kinds: List<Kind>, extra: Boolean): Boolean {
        val credits = map { kinds[it] }.filterIsInstance<Kind.Credit>().filter { it.names.isNotEmpty() }
        return extra || credits.any { it.headline != null } || credits.size >= MIN_ROLES
    }

    internal sealed interface Kind {
        data object Lyric : Kind
        data object Junk : Kind

        /** "Title - Artist": dropped inside the head run, a lyric anywhere else. */
        data object TitleLine : Kind
        data class Credit(
            val role: String,
            val names: String,
            val headline: LyricCreditRole? = null,
        ) : Kind
        data class Notice(val text: String) : Kind
        data class Continuation(val text: String) : Kind
    }

    internal fun classify(raw: String): Kind {
        val kind = classifyLine(raw.trim())
        // "孤勇者 (《英雄联盟：双城之战》…) - 陈奕迅": a title line can hold a colon.
        return if (kind == Kind.Lyric && raw.length <= MAX_TITLE_LINE && TITLE_LINE.matches(raw.trim())) {
            Kind.TitleLine
        } else {
            kind
        }
    }

    private fun classifyLine(text: String): Kind {
        if (text.isEmpty() || JUNK.matches(text)) return Kind.Junk
        if (CODE.containsMatchIn(text)) return Kind.Credit(role = "", names = text)
        if (CONTINUATION.matches(text) && (text.length > 20 || '/' in text)) return Kind.Continuation(text)

        val match = CREDIT.matchEntire(text)
        if (match == null) {
            DASH_CREDIT.matchEntire(text)?.let { dash ->
                val key = dash.groupValues[1].trim()
                if (!HAS_CJK.containsMatchIn(key) && isLatinRole(key)) {
                    return Kind.Credit(key, dash.groupValues[2].trim(), headline = headlineOf(key))
                }
            }
            if (PROSE.containsMatchIn(text)) return Kind.Credit(role = "", names = text)
            if (NOTICE.containsMatchIn(text)) return Kind.Notice(text)
            return Kind.Lyric
        }
        val written = match.groupValues[1].trim()
        val names = match.groupValues[2].trim()
        if (names.isNotEmpty() && SENTENCE_END.containsMatchIn(names)) return Kind.Lyric

        // Bilingual roles ("作词 Lyricist", "调声 Tuning", "录音版权Sub Publishing", Vsinger's albums): the
        // Chinese part names the role, and is what About shows; the English part can vouch for it too.
        val bilingual = BILINGUAL_KEY.matchEntire(written)
        val key = bilingual?.groupValues?.get(1)?.trim() ?: written
        val english = bilingual?.groupValues?.get(2)?.trim()

        (headlineOf(key) ?: english?.let(::headlineOf))?.let { return Kind.Credit(key, names, headline = it) }
        if (NOTICE_ROLE.containsMatchIn(key)) return Kind.Notice("$written：$names")
        val isRole = if (HAS_CJK.containsMatchIn(key)) {
            (key.length <= MAX_CJK_ROLE && CJK_ROLE.containsMatchIn(key)) || english?.let(::isLatinRole) == true
        } else {
            isLatinRole(key)
        }
        return if (isRole) Kind.Credit(key, names) else Kind.Lyric
    }

    private fun headlineOf(key: String): LyricCreditRole? =
        HEADLINES.firstOrNull { (_, pattern) -> pattern.matches(key) }?.first

    private fun isLatinRole(key: String): Boolean {
        val words = key.lowercase().split(NON_WORD).filter(String::isNotEmpty)
        return words.isNotEmpty() && words.size <= MAX_LATIN_WORDS && words.any(LATIN_ROLE::contains)
    }

    private const val MIN_LINES_LEFT = 2
    private const val MIN_ROLES = 3
    private const val MAX_CJK_ROLE = 12
    private const val MAX_LATIN_WORDS = 7
    private const val MAX_TITLE_LINE = 160

    private val CREDIT = Regex("""^([^:：]{1,40}?)\s*[:：]\s*(.*)$""")
    private val DASH_CREDIT = Regex("""^([^-–]{1,40}?)\s+[-–]\s+(.+)$""")
    private val TITLE_LINE = Regex("""^.+\s[-–]\s.+$""")
    private val CONTINUATION = Regex("""^[(（].*[)）]$""")
    private val JUNK = Regex("""^[\s\-–—_~·•*.。,，♪]+$""")
    private val CODE = Regex("""^(isrc|upc|ean)\b""", RegexOption.IGNORE_CASE)
    private val PROSE = Regex(
        "^(" +
            "recorded|mixed|mastered|published|produced|engineered|written|performed|edited|" +
            "courtesy|licensed|distributed|administered|arranged|programmed" +
            """)\b.*\b(at|by|of)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NOTICE = Regex(
        "(版权|版權|著作权|著作權|未经|未經|许可|許可|℗|©|copyright|all rights reserved)",
        RegexOption.IGNORE_CASE,
    )
    private val NOTICE_ROLE = Regex("""(版权|版權|出品|发行|發行|监制|監製|著作)""")
    private val SENTENCE_END = Regex("""[。！？]$""")
    private val HAS_CJK = Regex("""[぀-ヿ㐀-鿿가-힯]""")
    private val BILINGUAL_KEY = Regex("""^(.*[぀-ヿ㐀-鿿가-힯])\s*([A-Za-z][A-Za-z0-9 &.'/()-]*)$""")
    private val NON_WORD = Regex("""[^a-z&]+""")

    private val HEADLINES = listOf(
        LyricCreditRole.Lyricist to Regex(
            """^(作词|作詞|词|詞|填词|填詞|lyricist|lyricists|lyrics|lyrics by|written by|words by|작사)$""",
            RegexOption.IGNORE_CASE,
        ),
        LyricCreditRole.Composer to Regex(
            """^(作曲|曲|composer|composers|composed by|music by|작곡)$""",
            RegexOption.IGNORE_CASE,
        ),
        LyricCreditRole.Arranger to Regex(
            """^(编曲|編曲|arranger|arrangers|arranged by|arrangement|편곡)$""",
            RegexOption.IGNORE_CASE,
        ),
        LyricCreditRole.Producer to Regex(
            """^(制作人|製作人|监制|監製|producer|producers|produced by|프로듀서|プロデューサー)$""",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val CJK_ROLE = Regex(
        "(词|詞|曲|制作|製作|琴|筝|箏|琵琶|阮|笙|埙|吉他|贝斯|貝斯|鼓|箫|笛|胡|唢呐|键盘|鍵盤|弦乐|弦樂|和声|和聲|和音|合声|合唱|人声|人聲|配唱|" +
            "采样|录音|錄音|混音|缩混|母带|母帶|工程|助理|编写|編寫|编程|演奏|乐队|樂隊|乐手|乐团|樂團|总监|監製|监制|" +
            "统筹|統籌|企划|企劃|策划|协力|協力|出品|发行|發行|版权|版權|宣传|推广|视觉|封面|摄影|设计|後期|后期|谱|譜|贡献者|" +
            "调声|調聲|调教|調教|调校|翻译|翻譯|经纪|經紀|编辑|編輯|剪辑|剪輯|绘|繪|美术|美術|动画|動畫|" +
            "ギター|ベース|ドラム|ピアノ|コーラス|レコーディング|ミックス|マスタリング|プログラミング|" +
            "믹싱|마스터링|녹음|기타|베이스|드럼|피아노|코러스|디렉팅|프로그래밍)",
    )

    private val LATIN_ROLE: Set<String> = (
        "by guitar guitars bass drums drum percussion keyboard keyboards keys synth synths synthesizer piano organ " +
            "strings string violin viola cello horn horns trumpet sax saxophone flute choir harmony hormony vocal " +
            "vocals background programming programing programmer engineer engineers engineering mixer mix mixing " +
            "mixed master mastering mastered recording recorded studio studios personnel producer producers " +
            "production produced arrange arranger arranged arrangement composer composed lyrics lyricist written " +
            "directed director assistant coordinator coordination a&r timpani gong sample samples additional " +
            "executive op sp publisher publishers publishing label loop loops pads pad programmed lead backing " +
            "electric acoustic talkbox tenor alto baritone editing editor edited digital assisted engineered " +
            "original writer writers sub musicians credits arragement arrangements orchestra orchestration " +
            "conductor conducted brass trombone woodwinds clarinet harp banjo mandolin ukulele turntables " +
            "scratches beats beat tuning tuner translator translation manager management supervisor illustration " +
            "illustrator artwork animation animator video pv guzheng pipa erhu dizi xiao guqin suona"
        ).split(' ').toSet()
}
