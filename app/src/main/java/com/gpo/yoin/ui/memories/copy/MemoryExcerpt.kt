package com.gpo.yoin.ui.memories.copy

import java.time.LocalDate

/** Type size tier of a card excerpt, by UTF-16 length (the prototype's ts-aq-s / ts-aq-m). */
enum class MemoryExcerptSize { SHORT, MEDIUM, LONG }

/**
 * One candidate for the card's excerpt slot. The card shows the first
 * candidate that fits; [text] null is the last resort, the attribution line
 * alone ("Your review · Jul 26 · in Diary"). Never an ellipsis.
 */
data class MemoryExcerptCandidate(
    /** The user's words, whole sentences, without quotes; null for the attribution-only line. */
    val text: String?,
    val attribution: String,
) {
    val attributionOnly: Boolean get() = text == null

    val size: MemoryExcerptSize
        get() = when {
            text == null || text.length > 60 -> MemoryExcerptSize.LONG
            text.length <= 16 -> MemoryExcerptSize.SHORT
            else -> MemoryExcerptSize.MEDIUM
        }
}

/**
 * Card excerpt candidates, best first, ported from the approved prototype
 * (twostate4.html: `excerptCands`, `noteBy`, `sortedNotes`).
 *
 * Whole sentences from the start of the review's first paragraph; with no
 * review, the first song note in album order; then the shortest song note;
 * then the attribution line alone. On a Medium window the card is a teaser at
 * any slot height: at most two sentences and about 60 weighted characters
 * ([MemorySentences.weightedLength]), the first sentence alone always allowed.
 * Phones are capped by the slot instead, so they get every sentence count.
 */
object MemoryExcerpt {
    const val MEDIUM_TEASER_MAX_SENTENCES = 2
    const val MEDIUM_TEASER_MAX_WEIGHT = 60.0

    fun candidates(input: MemoryCopyInput, capped: Boolean, today: LocalDate): List<MemoryExcerptCandidate> {
        val songNotes = input.notes.filter { note -> note.track != null }
        val out = mutableListOf<MemoryExcerptCandidate>()
        val lastAttribution: String
        val review = input.review?.takeIf(String::isNotBlank)
        if (review != null) {
            lastAttribution = reviewAttribution(input.reviewWrittenOn, today)
            val sentences = MemorySentences.split(review.substringBefore('\n'))
            val most = if (capped) minOf(MEDIUM_TEASER_MAX_SENTENCES, sentences.size) else sentences.size
            for (k in most downTo 1) {
                val text = MemorySentences.join(sentences.take(k))
                if (!capped || k == 1 || MemorySentences.weightedLength(text) <= MEDIUM_TEASER_MAX_WEIGHT) {
                    out += MemoryExcerptCandidate(text, lastAttribution)
                }
            }
        } else if (songNotes.isNotEmpty()) {
            val first = albumOrder(input.notes).first { note -> note.track != null }
            lastAttribution = noteAttribution(first, today)
            out += MemoryExcerptCandidate(first.text, lastAttribution)
        } else {
            return emptyList()
        }
        songNotes.minByOrNull { note -> note.text.length }?.let { shortest ->
            out += MemoryExcerptCandidate(shortest.text, noteAttribution(shortest, today))
        }
        out += MemoryExcerptCandidate(text = null, attribution = "$lastAttribution · in Diary")
        return out
    }

    /**
     * Album notes first, then song notes by track number; within a song,
     * anchored notes by position and unanchored ones after them.
     */
    fun albumOrder(notes: List<MemoryCopyNote>): List<MemoryCopyNote> = notes.sortedWith(
        compareBy<MemoryCopyNote> { note -> note.track?.number ?: -1 }
            .thenBy { note -> note.positionMs ?: Long.MAX_VALUE },
    )

    fun reviewAttribution(writtenOn: LocalDate?, today: LocalDate): String =
        writtenOn?.let { date -> "Your review · ${MemoryDates.day(date, today)}" } ?: "Your review"

    /**
     * "Your note · Satellite Hearts 2:21" for a song note, "Your note · Sep 12"
     * for an album note. Deviation from the prototype: a song note without an
     * anchor read "… 0:00" there; here it carries no time.
     */
    fun noteAttribution(note: MemoryCopyNote, today: LocalDate): String {
        val track = note.track ?: return "Your note · ${MemoryDates.day(note.writtenOn, today)}"
        val anchor = note.positionMs?.let { position -> " ${anchorTime(position)}" }.orEmpty()
        return "Your note · ${track.title}$anchor"
    }

    /** m:ss of a note's anchor, whole seconds. */
    fun anchorTime(positionMs: Long): String {
        val seconds = (positionMs / 1000L).coerceAtLeast(0L)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
