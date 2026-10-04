package com.gpo.yoin.ui.memories.copy

import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor

/** The language Yoin's own prose is written in: the motif title, the narration and its question. */
enum class MemoryProseLanguage { EN, ZH }

/** Where the card's title came from. The serif belongs to [AI] only. */
enum class MemoryTitleKind { AI, MOTIF, ALBUM }

/** A fact Yoin has already said on this card; the narration never says one twice. */
enum class MemoryFact { PLAYS, RANGE, COVERAGE, NOTES, TOP, SCORE }

/** One song of the album, in album order. [rating] is null when unrated. */
data class MemoryCopyTrack(
    val number: Int,
    val title: String,
    val rating: Float?,
)

/** A note the user wrote: on a song ([track] set) or on the whole album ([track] null). */
data class MemoryCopyNote(
    val text: String,
    val writtenOn: LocalDate,
    val track: MemoryCopyTrack? = null,
    val positionMs: Long? = null,
)

/** Play history in Yoin only (never visits). Absent when the album was never played in Yoin. */
data class MemoryListening(
    val plays: Int,
    val firstHeard: LocalDate,
    val lastHeard: LocalDate,
)

/**
 * Everything Yoin's copy may look at for one album memory. Built by the
 * coordinator; the copy functions never touch the repository or a clock.
 */
data class MemoryCopyInput(
    val albumName: String,
    val aiTitle: String?,
    val review: String?,
    val reviewWrittenOn: LocalDate?,
    /** Oldest first. Ties within a day keep this order. */
    val notes: List<MemoryCopyNote>,
    /** Album order. */
    val tracks: List<MemoryCopyTrack>,
    val ratedTracks: Int,
    val totalTracks: Int,
    /** The user's album rating; null unless the card shows an album score. */
    val albumScore: Float?,
    val listening: MemoryListening?,
)

/** Yoin's words on one card. [narration] and [question] only exist while there is no review. */
data class MemoryVoiceCopy(
    val language: MemoryProseLanguage,
    val titleKind: MemoryTitleKind,
    val title: String,
    val narration: String?,
    val question: String?,
    val said: Set<MemoryFact>,
)

/**
 * The listening facts handed to Gemini for the narration, already phrased in
 * [language] where they are words (months, "two days ago"). Never carries
 * the review or the notes' text.
 */
data class MemoryNarrationBrief(
    val language: MemoryProseLanguage,
    val facts: List<String>,
    /** The motif title on the card, when there is one: its facts must not be repeated. */
    val alreadySaid: String?,
) {
    val languageName: String
        get() = when (language) {
            MemoryProseLanguage.ZH -> "Simplified Chinese"
            MemoryProseLanguage.EN -> "English"
        }

    /** Stable text of everything that shapes the prompt; the cache key hashes it. */
    val signal: String
        get() = buildString {
            append(language.name).append('\n')
            append(alreadySaid.orEmpty()).append('\n')
            facts.forEach { fact -> append(fact).append('\n') }
        }
}

/**
 * Yoin's voice, ported from the approved prototype (twostate4.html:
 * `writesIn`, `signals`, `motif`, `narrative` ①–④, `voice`, `num`).
 *
 * The rule for every line Yoin writes on a memory: second person, past tense,
 * one or two sentences, built only from local signals (play count, date span,
 * season, track names, the top-rated track). It never judges the music, never
 * quotes or paraphrases the review or the notes, and never names the mechanism
 * ("memory"). The question follows from the fact before it. A fact the motif
 * title already said is not repeated by the narration.
 *
 * Beyond the prototype (its samples were all played): an album never played
 * in Yoin has no [MemoryListening]. Then the plays motif is skipped (the title
 * falls back to the album name when no other motif fits), and every template
 * drops the clause that needs plays or dates instead of saying "0 plays".
 */
object MemoryVoice {
    private val ZH_NUM = listOf("零", "一", "两", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
    private val EN_NUM = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    )

    /**
     * Which script the user writes in, from the review and the notes. One Han
     * character carries about as much as two latin letters. Only Han counts as
     * Chinese: any kana or hangul means Japanese or Korean, which have no
     * templates, so Yoin falls back to the app language. Nothing written yet:
     * the app language.
     */
    fun writesIn(
        review: String?,
        noteTexts: List<String>,
        appLanguage: MemoryProseLanguage = MemoryProseLanguage.EN,
    ): MemoryProseLanguage {
        var han = 0
        var latin = 0
        (sequenceOf(review.orEmpty()) + noteTexts.asSequence()).forEach { text ->
            text.forEach { c ->
                when (c) {
                    in '\u3040'..'\u30FF', in '\uAC00'..'\uD7AF' -> return appLanguage
                    in '\u3400'..'\u9FFF' -> han++
                    in 'A'..'Z', in 'a'..'z' -> latin++
                }
            }
        }
        if (han == 0 && latin == 0) return appLanguage
        return if (han * 2 >= latin) MemoryProseLanguage.ZH else MemoryProseLanguage.EN
    }

    /** Yoin's title, narration and question, in the language the user writes in. */
    fun compose(
        input: MemoryCopyInput,
        today: LocalDate,
        appLanguage: MemoryProseLanguage = MemoryProseLanguage.EN,
    ): MemoryVoiceCopy = composeIn(
        input = input,
        today = today,
        language = writesIn(input.review, input.notes.map(MemoryCopyNote::text), appLanguage),
    )

    /** [compose] with the prose language fixed. */
    fun composeIn(input: MemoryCopyInput, today: LocalDate, language: MemoryProseLanguage): MemoryVoiceCopy {
        val signals = signals(input, today)
        val said = mutableSetOf<MemoryFact>()
        val aiTitle = input.aiTitle?.takeIf(String::isNotBlank)
        val motif = if (aiTitle == null) motif(input, signals, language, today) else null
        val titleKind: MemoryTitleKind
        val title: String
        when {
            aiTitle != null -> {
                titleKind = MemoryTitleKind.AI
                title = aiTitle
            }
            motif != null -> {
                titleKind = MemoryTitleKind.MOTIF
                title = motif.text
                said += motif.uses
            }
            else -> {
                titleKind = MemoryTitleKind.ALBUM
                title = input.albumName
            }
        }
        var narration: String? = null
        var question: String? = null
        if (input.review.isNullOrBlank()) {
            val told = narrative(input, signals, language, said, today)
            narration = told.narration
            question = told.question
            said += told.uses
        }
        return MemoryVoiceCopy(language, titleKind, title, narration, question, said)
    }

    /**
     * The facts Gemini may narrate from, or null when there is nothing to
     * narrate (a review is on the card, or there is no listening signal at all).
     */
    fun narrationBrief(input: MemoryCopyInput, voice: MemoryVoiceCopy, today: LocalDate): MemoryNarrationBrief? {
        if (!input.review.isNullOrBlank()) return null
        val language = voice.language
        val zh = language == MemoryProseLanguage.ZH
        val signals = signals(input, today)
        val facts = mutableListOf<String>()
        input.listening?.let { listening ->
            facts += "Plays in Yoin: ${listening.plays}"
            facts += "Listening since: ${since(listening, today, language)}"
            facts += "Last played: ${ago(listening, signals, today, language)}"
        }
        if (signals.seasons >= 2) facts += "Seasons between the first and the latest play: ${signals.seasons}"
        if (input.ratedTracks > 0 && input.totalTracks > 0) {
            facts += "Tracks rated: ${input.ratedTracks} of ${input.totalTracks}"
        }
        signals.top?.let { top ->
            facts += "Highest-rated track: ${trackName(top.title, zh)}, ${score(top.rating ?: 0f)}"
        }
        input.albumScore?.let { albumScore -> facts += "Album score the listener gave: ${score(albumScore)}" }
        if (signals.notes > 0) {
            val span = signals.noteDays?.takeIf { signals.notes >= 2 }?.let { days -> ", over $days days" }.orEmpty()
            val latest = signals.latest?.track?.title
                ?.let { title -> "on the track ${trackName(title, zh)}" }
                ?: "about the whole album"
            facts += "Notes written: ${signals.notes}$span; the latest is $latest"
        }
        if (facts.isEmpty()) return null
        return MemoryNarrationBrief(
            language = language,
            facts = facts,
            alreadySaid = voice.title.takeIf { voice.titleKind == MemoryTitleKind.MOTIF },
        )
    }

    /**
     * A small count in words: 两 for two in Chinese (counting only: 两天,
     * 两条), "two" in English; numerals past twelve.
     */
    fun num(n: Int, language: MemoryProseLanguage, capitalize: Boolean = false): String = when (language) {
        MemoryProseLanguage.ZH -> ZH_NUM.getOrNull(n) ?: n.toString()
        MemoryProseLanguage.EN -> {
            val word = EN_NUM.getOrNull(n) ?: n.toString()
            if (capitalize) word.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } else word
        }
    }

    internal data class Signals(
        val notes: Int,
        val coverage: Double,
        /** First to latest note, both days counted; null without notes. */
        val noteDays: Int?,
        val latest: MemoryCopyNote?,
        /** Whole seasons (91 days) between the first and the latest play; 0 when never played. */
        val seasons: Int,
        val top: MemoryCopyTrack?,
        /** Days since the latest play; null when never played. */
        val agoDays: Int?,
    )

    internal data class Motif(val text: String, val uses: Set<MemoryFact>)

    internal data class Told(val narration: String?, val question: String, val uses: Set<MemoryFact>)

    internal fun signals(input: MemoryCopyInput, today: LocalDate): Signals {
        val dated = input.notes.sortedBy(MemoryCopyNote::writtenOn)
        val listening = input.listening
        return Signals(
            notes = input.notes.size,
            coverage = if (input.totalTracks > 0) input.ratedTracks.toDouble() / input.totalTracks else 0.0,
            noteDays = if (dated.isEmpty()) {
                null
            } else {
                MemoryDates.daysBetween(dated.first().writtenOn, dated.last().writtenOn) + 1
            },
            latest = dated.lastOrNull(),
            seasons = listening?.let {
                floor(MemoryDates.daysBetween(it.firstHeard, it.lastHeard) / 91.0 + 0.5).toInt()
            } ?: 0,
            // the first track with the highest score, in album order
            top = input.tracks
                .filter { track -> track.rating != null }
                .reduceOrNull { best, track -> if (track.rating!! > best.rating!!) track else best },
            agoDays = listening?.let { MemoryDates.daysBetween(it.lastHeard, today) },
        )
    }

    /** "since February" / "二月以来"; a first play in another year carries it: "February 2025" / "2025年二月". */
    internal fun since(listening: MemoryListening, today: LocalDate, language: MemoryProseLanguage): String {
        val first = listening.firstHeard
        val otherYear = first.year != today.year
        val month = MemoryDates.monthName(first.monthValue, language)
        return when (language) {
            MemoryProseLanguage.ZH -> (if (otherYear) "${first.year}年" else "") + month
            MemoryProseLanguage.EN -> month + if (otherYear) " ${first.year}" else ""
        }
    }

    /** "two days ago" / "前天"; a week or more back, the date itself: "on Sep 20" / "9月20日". */
    internal fun ago(
        listening: MemoryListening,
        signals: Signals,
        today: LocalDate,
        language: MemoryProseLanguage,
    ): String {
        val n = signals.agoDays ?: MemoryDates.daysBetween(listening.lastHeard, today)
        return when (language) {
            MemoryProseLanguage.ZH -> when {
                n <= 0 -> "今天"
                n == 1 -> "昨天"
                n == 2 -> "前天"
                n < 7 -> "${ZH_NUM[n]}天前"
                else -> MemoryDates.dayZh(listening.lastHeard, today)
            }
            MemoryProseLanguage.EN -> when {
                n <= 0 -> "today"
                n == 1 -> "yesterday"
                n < 7 -> "${EN_NUM[n]} days ago"
                else -> "on ${MemoryDates.day(listening.lastHeard, today)}"
            }
        }
    }

    /** The fallback title: a short deterministic line in the title slot (never serif). */
    internal fun motif(
        input: MemoryCopyInput,
        signals: Signals,
        language: MemoryProseLanguage,
        today: LocalDate,
    ): Motif? {
        val zh = language == MemoryProseLanguage.ZH
        if (signals.coverage >= 0.6 && signals.seasons >= 2) {
            return Motif(
                text = if (zh) {
                    "${num(signals.seasons, language)}个季节，一首一首"
                } else {
                    "${num(signals.seasons, language, capitalize = true)} seasons, track by track"
                },
                uses = setOf(MemoryFact.RANGE, MemoryFact.COVERAGE),
            )
        }
        val noteDays = signals.noteDays
        if (signals.notes >= 2 && noteDays != null) {
            return Motif(
                text = if (zh) {
                    "${num(noteDays, language)}天，${num(signals.notes, language)}条笔记"
                } else {
                    "${num(noteDays, language, capitalize = true)} day${if (noteDays > 1) "s" else ""}, " +
                        "${num(signals.notes, language)} notes"
                },
                uses = setOf(MemoryFact.NOTES),
            )
        }
        val listening = input.listening ?: return null
        return Motif(
            text = if (zh) {
                "${since(listening, today, language)}以来，${listening.plays} 遍"
            } else {
                "${playsEn(listening.plays)} since ${since(listening, today, language)}"
            },
            uses = setOf(MemoryFact.PLAYS, MemoryFact.RANGE),
        )
    }

    /** The narration + question shown when there is no review yet. */
    internal fun narrative(
        input: MemoryCopyInput,
        signals: Signals,
        language: MemoryProseLanguage,
        said: Set<MemoryFact>,
        today: LocalDate,
    ): Told {
        val zh = language == MemoryProseLanguage.ZH
        val listening = input.listening
        val noteDays = signals.noteDays
        // ① notes, nothing rated
        if (signals.notes >= 2 && noteDays != null && input.ratedTracks == 0 && input.albumScore == null) {
            val track = signals.latest?.track?.title
            val narration = if (zh) {
                "${num(noteDays, language)}天里记了${num(signals.notes, language)}条笔记，" +
                    (track?.let { "最近一条写在《$it》。" } ?: "最近一条写给整张专辑。")
            } else {
                "${num(signals.notes, language, capitalize = true)} notes in ${num(noteDays, language)} " +
                    "day${if (noteDays > 1) "s" else ""}; " +
                    (track?.let { "the latest was on $it." } ?: "the latest was about the whole album.")
            }
            return Told(
                narration = narration,
                question = if (zh) "合起来看，这张专辑你会怎么说？" else "Put together, what would you say about the album?",
                uses = setOf(MemoryFact.NOTES),
            )
        }
        // ② rated track by track
        val top = signals.top
        if (signals.coverage >= 0.6 && top != null) {
            val topScore = score(top.rating ?: 0f)
            val topLine = if (zh) {
                "最高分是《${top.title}》的 $topScore。"
            } else {
                "${top.title} scored highest, ${article(topScore)} $topScore."
            }
            val question = if (zh) "那整张专辑呢？" else "And the album as a whole?"
            if (signals.seasons >= 2 && MemoryFact.RANGE !in said) {
                return Told(
                    narration = if (zh) {
                        "你跨了${num(signals.seasons, language)}个季节回来听，$topLine"
                    } else {
                        "You came back across ${num(signals.seasons, language)} seasons. $topLine"
                    },
                    question = question,
                    uses = setOf(MemoryFact.RANGE, MemoryFact.TOP),
                )
            }
            // the count and the top score are two facts, said as two clauses
            if (listening != null) {
                return Told(
                    narration = if (zh) {
                        "听了 ${listening.plays} 遍，$topLine"
                    } else {
                        "${playsEn(listening.plays)} in, ${top.title} is still your top track at $topScore."
                    },
                    question = question,
                    uses = setOf(MemoryFact.PLAYS, MemoryFact.TOP),
                )
            }
            return Told(narration = topLine, question = question, uses = setOf(MemoryFact.TOP))
        }
        // ③ an album score, no words yet
        val albumScore = input.albumScore
        if (albumScore != null) {
            val scoreText = score(albumScore)
            val question = if (zh) {
                "你给了 $scoreText 分，是哪里打动了你？"
            } else {
                "You gave it ${article(scoreText)} $scoreText. What earned it?"
            }
            if (listening == null) return Told(narration = null, question = question, uses = setOf(MemoryFact.SCORE))
            val ago = ago(listening, signals, today, language)
            // the plays motif ("5 plays since February") already said the count and the span: only the last listen
            if (MemoryFact.PLAYS in said || MemoryFact.RANGE in said) {
                return Told(
                    narration = if (zh) "最近一次听是$ago。" else "You last played it $ago.",
                    question = question,
                    uses = setOf(MemoryFact.SCORE),
                )
            }
            val since = since(listening, today, language)
            return Told(
                narration = if (zh) {
                    "${since}以来听了 ${listening.plays} 遍，最近一次是$ago。"
                } else {
                    "${playsEn(listening.plays)} since $since, the last one $ago."
                },
                question = question,
                uses = setOf(MemoryFact.PLAYS, MemoryFact.RANGE, MemoryFact.SCORE),
            )
        }
        // ④ fallback
        return Told(
            narration = null,
            question = if (zh) "这张专辑，留下了什么？" else "What stays with you from this one?",
            uses = emptySet(),
        )
    }

    /** Narration then question as one paragraph: Chinese joins without a space. */
    fun paragraph(narration: String?, question: String?, language: MemoryProseLanguage): String? {
        val parts = listOfNotNull(narration?.takeIf(String::isNotBlank), question?.takeIf(String::isNotBlank))
        if (parts.isEmpty()) return null
        return parts.joinToString(if (language == MemoryProseLanguage.ZH) "" else " ")
    }

    /** One decimal, like the prototype's toFixed(1): 9.0, 8.5. */
    internal fun score(value: Float): String = String.format(Locale.US, "%.1f", value)

    // Deviation from the prototype, which always wrote "a 8.5": English takes "an" before eight.
    private fun article(scoreText: String): String = if (scoreText.startsWith("8")) "an" else "a"

    // Deviation from the prototype, which wrote "1 plays": the singular for one play.
    private fun playsEn(plays: Int): String = if (plays == 1) "1 play" else "$plays plays"

    private fun trackName(title: String, zh: Boolean): String = if (zh) "《$title》" else title
}
