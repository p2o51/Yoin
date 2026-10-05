package com.gpo.yoin.data.lyrics

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.Normalizer
import java.util.Locale

/**
 * 自动匹配时，从歌词源的搜索结果里认出"就是这首歌"的那一条。认不出就返回 null，
 * 让下一个 provider 去试，而不是拿同名的别人的歌顶上。
 *
 * **标题。** 比较前先归一：NFKC（全角转半角）、[fold]（默认繁转简）、忽略大小写、
 * 标点和空白，拉丁字母去掉变音符。结尾注记——括号段（`(Live)`、`(Explicit)`、`(DJ 版)`）、
 * ` - 后缀`、`feat.` 子句——从外往里一层层剥。双方去掉"同一母带"注记后相同，算"原版"；
 * 双方去掉全部注记后才相同，算"注记版"。伴奏 / 纯音乐类注记取不到歌词，除非请求本身
 * 就是伴奏，否则不算匹配。
 *
 * **同一母带注记**（[SAME_MASTER_NOTE]）。录音没换，只是重做了母带或换了缩混，歌词和
 * 时间轴都和原版一样，所以跟不带注记的标题同一档（同档时排位靠前的胜）：
 * - `Remaster` / `Remastered` / `Remastering`，可带年份、`Digital(ly)`、`HD`、`40th Anniversary`、
 *   `Version` / `Ver.` / `Edition`：`2013 Remaster`、`Remastered 2009`、`2011 Remastered Version`；
 * - `Mono`、`Stereo`，可带 `Mix` / `Version`（`2009 Stereo Mix`）：同一次演唱的不同缩混；
 * - `Album Version`、`LP Version`、`Original Version`、`Original Mix`：字面上就是原版；
 * - `Explicit`：没消音的原版母带（QQ 给大量西文歌这样标）；
 * - 日文 `リマスター`、`リマスタリング`、`モノラル`、`ステレオ`、`アルバム・バージョン`，可带 `版` / `バージョン`。
 *
 * 这些词可以叠在同一段里（`Mono / Remastered 2009`），但只要混进别的词，整段就不算
 * （`Take 1 - Remastered` 是另一条录音）。`feat.` 子句永远不算。
 *
 * 刻意**不算**、按普通注记处理的：`Single Version`、`Single Edit`、`Radio Edit`、`Edit`（常是
 * 剪短的，剪口之后时间轴就错开）、`Clean`（消音版）、只带年份的 `2023 Mix`（新混音）、
 * 单独一个 `Original`（翻唱常这样标原唱）、`Taylor's Version`（重录）；Live、Remix、Acoustic、
 * Cover、Sped Up、Slowed 这些另一次演唱或加工更不用说。
 *
 * **专辑。** 候选带专辑名（[Candidate.album]）、并且请求的标题是原版（除了同一母带注记没有
 * 别的注记）时，再看一眼专辑：
 * - 现场 / Unplugged / 演唱会 / Remix 合辑 / Demo / 翻唱致敬 / 加速降速（[ALTERNATE_ALBUM_WORDS]、
 *   [ALTERNATE_ALBUM_MARKS]，`Live`、`Tour` 的位置规则见 [isLiveAlbum]）→ 最多算"注记版"。
 *   标题不带注记的现场录音（`Hotel California`，专辑 `Unplugged 1994`）就这样输给带 `Remaster`
 *   的录音室版；
 * - 访谈、卡拉 OK、伴奏合辑（[DOUBTFUL_ALBUM_WORDS]、[DOUBTFUL_ALBUM_MARKS]）→ 降到最后一档
 *   "存疑"：不排除，别无选择时还能用。
 *
 * 请求的就是 Live 之类时不看专辑——现场专辑正对路。请求方不带专辑（[pick] 只有标题和艺人），
 * 所以用户真在听现场专辑里不带注记的那一首时，拿到的会是录音室版的词。
 *
 * **艺人。** 请求的艺人串整体算一个名字，按 `&`、`,`、`、`、`/`、`;`、`feat.` 拆出的
 * 每一段也各算一个，逐一和候选的每位艺人比：
 * - 归一后相同 → 匹配；
 * - 书写系统（拉丁、汉字、假名、谚文……）完全一致却不同 → 否决。所以 `ロード` 不等于
 *   `ロードオブメジャー`，`IVE` 也不等于 `Five Seconds`，前缀和片段都不算；
 * - 书写系统不同、但双方都有拉丁词 → 一方的拉丁词全在另一方里才匹配，否则否决
 *   （`チャーリーxcx` 对 `Charli xcx`）；
 * - 其余情况（`テイラー・スウィフト` 对 `Taylor Swift`）无从判断。
 *
 * **挑选。** 先看艺人匹配的候选，取档位最高（原版 → 注记版 → 存疑）、排位靠前的一条。
 * 一条都没有时，只在"无从判断"的候选里看排位第一的那位艺人：他至少有 [MIN_DOMINANT_CUTS]
 * 条同名录音，并且比其余任何艺人都多，才认定是他，再从他的录音里同样按档位、排位取一条；
 * 否则返回 null。被否决的候选不参与这一步。
 */
internal class LyricCandidateMatcher(
    private val fold: (String) -> String = TraditionalToSimplified,
) {

    /**
     * 一条搜索结果：标题、全部演唱者、所在专辑（歌词源没给就是 null），顺序即歌词源给出的排位。
     */
    data class Candidate(val title: String, val artists: List<String>, val album: String? = null)

    /** 返回 [candidates] 里选中的下标；没有可信的匹配时返回 null。 */
    fun pick(candidates: List<Candidate>, title: String, artist: String): Int? {
        if (candidates.isEmpty()) return null
        val wantedTitle = titleOf(title) ?: return null
        val wantedArtists = artistNamesOf(artist)

        val cuts = candidates.mapIndexedNotNull { index, candidate ->
            val fit = fitOf(wantedTitle, candidate) ?: return@mapIndexedNotNull null
            val performers = candidate.artists.map(::nameOf).filter { it.key.isNotEmpty() }
            Cut(index, fit, performers, verdictOf(wantedArtists, performers))
        }
        if (cuts.isEmpty()) return null

        cuts.filter { it.verdict == Verdict.Match }.preferred()?.let { return it.index }
        return dominantPerformerCut(cuts.filter { it.verdict == Verdict.Unknown })?.index
    }

    // ── 标题 ─────────────────────────────────────────────────────────────────

    private fun fitOf(wanted: TitleShape, candidate: Candidate): Fit? {
        val offered = titleOf(candidate.title) ?: return null
        val byTitle = when {
            offered.key == wanted.key || offered.masterKey == wanted.masterKey -> Fit.Original
            offered.coreKey != wanted.coreKey -> return null
            offered.instrumental && !wanted.instrumental -> return null
            else -> Fit.Decorated
        }
        return if (wanted.isOriginal) maxOf(byTitle, albumFitOf(candidate.album)) else byTitle
    }

    private fun titleOf(raw: String): TitleShape? {
        val text = prepare(raw)
        val key = keyOf(text)
        if (key.isEmpty()) return null
        var core = text
        val notes = mutableListOf<Note>()
        while (true) {
            val (pattern, found) = TRAILING_NOTES.firstNotNullOfOrNull { candidate ->
                candidate.regex.find(core)?.let { candidate to it }
            } ?: break
            val rest = core.substring(0, found.range.first)
            if (keyOf(rest).isEmpty()) break
            val noteKey = keyOf(found.groupValues[1])
            notes += Note(
                key = noteKey,
                segmentKey = keyOf(found.value),
                sameMaster = pattern.versionNote && SAME_MASTER_NOTE.matches(noteKey),
            )
            core = rest
        }
        val coreKey = keyOf(core)
        // 剥的时候从外往里，拼回去要从里往外。
        val otherNotes = notes.asReversed().filterNot(Note::sameMaster).joinToString("") { it.segmentKey }
        return TitleShape(
            key = key,
            coreKey = coreKey,
            masterKey = coreKey + otherNotes,
            instrumental = notes.any { isInstrumentalNote(it.key) },
        )
    }

    private fun isInstrumentalNote(noteKey: String): Boolean =
        noteKey in INSTRUMENTAL_NOTES || INSTRUMENTAL_WORDS.any { noteKey.contains(it) }

    // ── 专辑 ─────────────────────────────────────────────────────────────────

    private fun albumFitOf(raw: String?): Fit {
        if (raw.isNullOrBlank()) return Fit.Original
        val text = prepare(raw)
        val key = keyOf(text)
        val words = latinWordListOf(text)
        return when {
            words.any(DOUBTFUL_ALBUM_WORDS::contains) || DOUBTFUL_ALBUM_MARKS.any(key::contains) -> Fit.Doubtful
            isLiveAlbum(words) ||
                words.any(ALTERNATE_ALBUM_WORDS::contains) ||
                ALTERNATE_ALBUM_MARKS.any(key::contains) -> Fit.Decorated
            else -> Fit.Original
        }
    }

    /**
     * 拉丁词 `live` 在最后（`Unplugged 1994 (live)`、`idina: live`），或者后面紧跟年份或
     * [LIVE_FOLLOWERS] 里的词（`Live in Tokyo 1966`、`After Hours (Live At SoFi Stadium)`、
     * `… 演唱会 Live CD`），才算现场专辑——`Live Through This`、`Live and Let Die` 不算。
     * `tour` 后面紧跟年份也算（`one-man tour 2021-2022`），`Magical Mystery Tour` 不算。
     */
    private fun isLiveAlbum(words: List<String>): Boolean = words.indices.any { i ->
        val next = words.getOrNull(i + 1)
        when (words[i]) {
            "live" -> next == null || next in LIVE_FOLLOWERS || isYear(next)
            "tour" -> next != null && isYear(next)
            else -> false
        }
    }

    // ── 艺人 ─────────────────────────────────────────────────────────────────

    private fun artistNamesOf(raw: String): List<Name> {
        val whole = raw.trim()
        if (whole.isEmpty()) return emptyList()
        return (listOf(whole) + whole.split(ARTIST_SEPARATOR))
            .map(::nameOf)
            .filter { it.key.isNotEmpty() }
            .distinctBy { it.key }
    }

    private fun nameOf(raw: String): Name {
        val text = prepare(raw)
        return Name(key = keyOf(text), scripts = scriptsOf(text), latinWords = latinWordsOf(text))
    }

    private fun verdictOf(wanted: List<Name>, performers: List<Name>): Verdict {
        if (wanted.isEmpty() || performers.isEmpty()) return Verdict.Unknown
        var undecided = false
        for (name in wanted) {
            for (performer in performers) {
                when (compare(name, performer)) {
                    Verdict.Match -> return Verdict.Match
                    Verdict.Unknown -> undecided = true
                    Verdict.Mismatch -> Unit
                }
            }
        }
        return if (undecided) Verdict.Unknown else Verdict.Mismatch
    }

    private fun compare(wanted: Name, offered: Name): Verdict = when {
        wanted.key == offered.key -> Verdict.Match
        wanted.scripts == offered.scripts -> Verdict.Mismatch
        wanted.latinWords.isEmpty() || offered.latinWords.isEmpty() -> Verdict.Unknown
        offered.latinWords.containsAll(wanted.latinWords) ||
            wanted.latinWords.containsAll(offered.latinWords) -> Verdict.Match
        else -> Verdict.Mismatch
    }

    // ── 挑选 ─────────────────────────────────────────────────────────────────

    private fun dominantPerformerCut(unverifiable: List<Cut>): Cut? {
        val lead = unverifiable.firstOrNull() ?: return null
        val cutsByPerformer = unverifiable
            .flatMap { cut -> cut.performerKeys.map { key -> key to cut } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val performer = lead.performerKeys.maxByOrNull { cutsByPerformer[it].orEmpty().size } ?: return null
        val own = cutsByPerformer.getValue(performer)
        if (own.size < MIN_DOMINANT_CUTS) return null
        val strongestRival = unverifiable
            .filterNot { performer in it.performerKeys }
            .flatMap { it.performerKeys }
            .groupingBy { it }
            .eachCount()
            .values
            .maxOrNull() ?: 0
        return if (own.size > strongestRival) own.preferred() else null
    }

    private fun List<Cut>.preferred(): Cut? = minWithOrNull(compareBy<Cut> { it.fit.ordinal }.thenBy { it.index })

    // ── 归一 ─────────────────────────────────────────────────────────────────

    /** NFKC → [fold] → 小写；保留标点，留给去注记用。 */
    private fun prepare(raw: String): String =
        fold(Normalizer.normalize(raw, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)

    /** 档位，越靠前越好。 */
    private enum class Fit { Original, Decorated, Doubtful }

    private enum class Verdict { Match, Mismatch, Unknown }

    /**
     * [key] 是整个标题；[coreKey] 去掉了全部结尾注记；[masterKey] 只去掉同一母带注记。
     * [isOriginal]：除了同一母带注记，没有别的注记。
     */
    private class TitleShape(
        val key: String,
        val coreKey: String,
        val masterKey: String,
        val instrumental: Boolean,
    ) {
        val isOriginal: Boolean get() = masterKey == coreKey
    }

    /** 剥下来的一段注记：[key] 是注记内容，[segmentKey] 连着 `feat` 之类的引导词。 */
    private class Note(val key: String, val segmentKey: String, val sameMaster: Boolean)

    /** [versionNote]：这种注记写的是版本，能算同一母带；`feat.` 子句写的是人，不能。 */
    private class NotePattern(val regex: Regex, val versionNote: Boolean)

    private class Name(
        val key: String,
        val scripts: Set<Character.UnicodeScript>,
        val latinWords: Set<String>,
    )

    private class Cut(
        val index: Int,
        val fit: Fit,
        performers: List<Name>,
        val verdict: Verdict,
    ) {
        val performerKeys: List<String> = performers.map { it.key }.distinct()
    }

    private companion object {
        /** 无从判断艺人时，排位第一的艺人至少要有这么多条同名录音才可信。 */
        const val MIN_DOMINANT_CUTS = 2

        /** 结尾注记，从外往里一层层剥；第 1 组是注记内容。 */
        val TRAILING_NOTES = listOf(
            NotePattern(Regex("""\s*[(\[【〔]([^()\[\]【】〔〕]*)[)\]】〕]\s*$"""), versionNote = true),
            NotePattern(Regex("""\s+[-–—]\s+(.+)$"""), versionNote = true),
            NotePattern(Regex("""\s+(?:feat\.?|ft\.|featuring)\s+(.+)$"""), versionNote = false),
        )

        /**
         * 同一母带注记：整段注记归一后（只剩小写字母和数字，没有空格）必须完全由这些词拼成，
         * 并且至少有一个"标记词"。标记词：`remaster(ed|ing)`、`mono`、`stereo`、`explicit`、
         * `album` / `lp` / `original` + `version` / `ver`、`original mix`，日文 `リマスター`、
         * `リマスタリング`、`モノラル`、`ステレオ`、`アルバム` + `バージョン` / `ヴァージョン`。
         * 搭配词：数字（年份、`40th`）、`anniversary`、`digital(ly)`、`hd`、`version`、`ver`、
         * `edition`、`mix`、`版`、`バージョン`、`ヴァージョン`。哪些刻意不算见类注释。
         */
        val SAME_MASTER_NOTE: Regex = run {
            val marker = "remaster(?:ed|ing)?|mono|stereo|explicit|(?:album|lp|original)(?:version|ver)|" +
                "originalmix|リマスター|リマスタリング|モノラル|ステレオ|アルバム(?:バージョン|ヴァージョン)"
            // `\d++` 占有：外层还有 `*`，`(\d+)*` 在不记忆回溯的引擎（Android 的 ICU）上，
            // 一长串数字配不上时会按切分方式指数回溯。
            val filler = "\\d++(?:st|nd|rd|th)?|anniversary|digital(?:ly)?|hd|version|ver|edition|mix|" +
                "版|バージョン|ヴァージョン"
            Regex("(?:$filler)*(?:$marker)(?:$filler|$marker)*")
        }

        /** 请求艺人串里的多人分隔符。整串本身也会参与比较，拆错不至于丢掉原名。 */
        val ARTIST_SEPARATOR = Regex(
            """\s*(?:[&＆,，、/／;；]|\s(?:feat\.?|ft\.|featuring)\s)\s*""",
            RegexOption.IGNORE_CASE,
        )

        /** 注记归一后整段等于这些，视为伴奏。 */
        val INSTRUMENTAL_NOTES = setOf("inst", "instver", "instversion")

        /** 注记归一后包含这些，视为伴奏。 */
        val INSTRUMENTAL_WORDS = listOf(
            "instrumental",
            "karaoke",
            "offvocal",
            "伴奏",
            "纯音乐",
            "カラオケ",
            "オフボーカル",
        )

        /** 专辑名里有这些拉丁词：不是这首歌本身（访谈、卡拉 OK、伴奏合辑），降到"存疑"。 */
        val DOUBTFUL_ALBUM_WORDS = setOf("interview", "interviews", "karaoke", "instrumental", "instrumentals", "inst")

        /** 专辑名归一后包含这些：同 [DOUBTFUL_ALBUM_WORDS]。 */
        val DOUBTFUL_ALBUM_MARKS = listOf(
            "offvocal", "访谈", "专访", "采访", "伴奏", "纯音乐", "カラオケ", "オフボーカル", "インタビュー", "인터뷰",
        )

        /** 专辑名里有这些拉丁词：另一次演唱或加工（现场、不插电、Remix、Demo、翻唱、加速降速），最多算"注记版"。 */
        val ALTERNATE_ALBUM_WORDS = setOf(
            "unplugged", "concert", "concerts", "acoustic", "remix", "remixes", "remixed", "demo", "demos",
            "outtakes", "rehearsal", "rehearsals", "bootleg", "covers", "tribute", "sped", "slowed", "nightcore",
        )

        /** 专辑名归一后包含这些：同 [ALTERNATE_ALBUM_WORDS]。 */
        val ALTERNATE_ALBUM_MARKS = listOf(
            "演唱会", "现场", "音乐会", "翻唱", "翻自", "致敬", "混音", "加速版", "慢速版", "降速版",
            "ライブ", "ライヴ", "コンサート", "リミックス", "라이브", "콘서트",
        )

        /** 专辑名里紧跟在 `live` 后面、说明是现场专辑的词（见 [isLiveAlbum]）。 */
        val LIVE_FOLLOWERS = setOf(
            "at", "in", "from", "on", "cd", "dvd", "album", "version", "ver", "edition", "recording",
            "recordings", "tour", "concert", "session", "sessions", "set", "show",
        )
    }
}

/** 比较键：只留字母和数字，拉丁字母去变音符。输入应已经过 prepare。 */
private fun keyOf(text: String): String {
    val out = StringBuilder(text.length)
    forEachCodePoint(text) { cp ->
        when {
            !Character.isLetterOrDigit(cp) -> Unit
            isLatin(cp) -> out.append(baseLatin(cp))
            else -> out.appendCodePoint(cp)
        }
    }
    return out.toString()
}

/** 名字里出现的书写系统；平假名并入片假名，通用 / 继承字符（`ー`、数字）不计。 */
private fun scriptsOf(text: String): Set<Character.UnicodeScript> {
    val scripts = mutableSetOf<Character.UnicodeScript>()
    forEachCodePoint(text) { cp ->
        if (!Character.isLetter(cp)) return@forEachCodePoint
        when (val script = Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED,
            Character.UnicodeScript.UNKNOWN,
            -> Unit
            Character.UnicodeScript.HIRAGANA -> scripts += Character.UnicodeScript.KATAKANA
            else -> scripts += script
        }
    }
    return scripts
}

/** 连续的拉丁字母 / ASCII 数字算一个词；至少两个字符且含字母才留下。 */
private fun latinWordsOf(text: String): Set<String> {
    val words = mutableSetOf<String>()
    val word = StringBuilder()
    fun flush() {
        if (word.length >= 2 && word.any(Char::isLetter)) words += word.toString()
        word.setLength(0)
    }
    forEachCodePoint(text) { cp ->
        when {
            Character.isLetter(cp) && isLatin(cp) -> word.append(baseLatin(cp))
            cp in '0'.code..'9'.code -> word.appendCodePoint(cp)
            else -> flush()
        }
    }
    flush()
    return words
}

/** 同 [latinWordsOf] 的切词，但按出现顺序全部保留（含单字符和纯数字），给专辑名看词序用。 */
private fun latinWordListOf(text: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) words += word.toString()
        word.setLength(0)
    }
    forEachCodePoint(text) { cp ->
        when {
            Character.isLetter(cp) && isLatin(cp) -> word.append(baseLatin(cp))
            cp in '0'.code..'9'.code -> word.appendCodePoint(cp)
            else -> flush()
        }
    }
    flush()
    return words
}

/** `19xx` / `20xx`。 */
private fun isYear(word: String): Boolean =
    word.length == 4 && word.all { it in '0'..'9' } && (word.startsWith("19") || word.startsWith("20"))

private fun isLatin(cp: Int): Boolean = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN

private fun baseLatin(cp: Int): String {
    if (cp < 0x80) return String(Character.toChars(cp))
    return Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
}

private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        action(cp)
        i += Character.charCount(cp)
    }
}

/**
 * 默认的 [LyricCandidateMatcher] 折叠：用 ICU 的 `Traditional-Simplified` 变换把繁体转成简体
 * （`android.icu.text.Transliterator`，API 29 起公开）。更早的系统、或变换不可用时原样返回，
 * 繁简不同的名字届时落到"无从判断"或否决，宁可交给下一个 provider。
 * 不含汉字的文本直接放行，不进 ICU。
 */
internal object TraditionalToSimplified : (String) -> String {
    override fun invoke(value: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !containsHan(value)) return value
        return IcuTraditionalToSimplified.apply(value)
    }

    private fun containsHan(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) return true
            i += Character.charCount(cp)
        }
        return false
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private object IcuTraditionalToSimplified {

    private const val TRANSFORM_ID = "Traditional-Simplified"

    /** null = 系统 ICU 里没有这个变换（或拿不到），之后一律原样返回。 */
    private val transliterator: Transliterator? by lazy {
        try {
            Transliterator.getInstance(TRANSFORM_ID)
        } catch (e: RuntimeException) {
            null
        }
    }

    fun apply(value: String): String {
        val transform = transliterator ?: return value
        // ICU 的 Transliterator 不保证线程安全，搜索可能在多个 IO 线程上并发。
        return synchronized(transform) { transform.transliterate(value) } ?: value
    }
}
