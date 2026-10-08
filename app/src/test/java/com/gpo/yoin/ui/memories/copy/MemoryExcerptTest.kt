package com.gpo.yoin.ui.memories.copy

import com.gpo.yoin.ui.memories.copy.CopyGolden.TODAY
import java.time.LocalDate
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExcerptTest {

    @Test
    fun should_match_prototype_excerpt_candidates() {
        // The prototype printed "0:00" for a song note without an anchor; this port prints no time.
        // Safe to normalise: no sample note is anchored at exactly 0:00.
        CopyGolden.rawInputs.values.forEach { raw ->
            raw.list("notes").forEach { note -> assertNotEquals(0, note.jsonObject.optInt("at")) }
        }
        val cases = CopyGolden.load("copy-excerpt.json").list("cases")
        assertTrue(cases.size >= 40)
        cases.forEach { element ->
            val case = element.jsonObject
            val label = "${case.text("key")} medium=${case.flag("medium")}"
            val expected = case.list("candidates").map { candidate ->
                val o = candidate.jsonObject
                MemoryExcerptCandidate(
                    text = if (o.flag("only")) null else o.optText("text"),
                    attribution = goldenAttribution(o.text("by").replace(" 0:00", "")),
                )
            }
            val actual = MemoryExcerpt.candidates(
                input = CopyGolden.inputs.getValue(case.text("key")),
                capped = case.flag("medium"),
                today = TODAY,
            )
            assertEquals(label, expected, actual)
        }
    }

    @Test
    fun should_use_whole_sentences_only_when_slot_is_short() {
        val review = CopyGolden.inputs.getValue("m1/as-is").review!!
        val sentences = MemorySentences.split(review.substringBefore('\n'))
        val wholePrefixes = (1..sentences.size).map { k -> MemorySentences.join(sentences.take(k)) }.toSet()

        val reviewBy = MemoryExcerptAttribution(MemoryExcerptKind.REVIEW, "Jul 26")
        listOf(false, true).forEach { capped ->
            val candidates = MemoryExcerpt.candidates(CopyGolden.inputs.getValue("m1/as-is"), capped, TODAY)
            val fromReview = candidates.filter { it.attribution == reviewBy && it.text != null }
            assertTrue(fromReview.isNotEmpty())
            fromReview.forEach { candidate ->
                assertTrue(candidate.text, candidate.text in wholePrefixes)
                assertFalse(candidate.text!!.contains("…"))
            }
            // longest first, so the card can walk down to whatever fits the slot
            assertEquals(fromReview.sortedByDescending { it.text!!.length }, fromReview)
            // the last resort is the attribution alone, never a cut sentence, and never "in Diary"
            assertTrue(candidates.last().attributionOnly)
            assertEquals(reviewBy, candidates.last().attribution)
        }
        // a phone may take every whole-sentence prefix
        val phone = MemoryExcerpt.candidates(CopyGolden.inputs.getValue("m1/as-is"), capped = false, today = TODAY)
        assertEquals(sentences.size, phone.count { it.attribution == reviewBy && it.text != null })
    }

    @Test
    fun should_cap_medium_teaser_at_two_sentences_or_60_weighted_chars() {
        // m1's first two sentences weigh more than 60: Medium keeps only the first
        val m1 = MemoryExcerpt.candidates(CopyGolden.inputs.getValue("m1/as-is"), capped = true, today = TODAY)
        assertEquals(
            listOf("第一次听这张是在去机场的夜班巴士上，窗外的高速路灯一盏一盏往后退，耳机里的弦乐刚好起来。"),
            m1.filter { it.attribution == MemoryExcerptAttribution(MemoryExcerptKind.REVIEW, "Jul 26") && it.text != null }
                .map { it.text },
        )

        // three short sentences: at most two, longest first
        val short = CopyGolden.inputs.getValue("m4/as-is").copy(
            review = "Quiet start. The bridge lifts. Then it ends.",
            reviewWrittenOn = LocalDate.of(2026, 9, 29),
        )
        val medium = MemoryExcerpt.candidates(short, capped = true, today = TODAY).filterNot { it.attributionOnly }
        assertEquals(listOf("Quiet start. The bridge lifts.", "Quiet start."), medium.map { it.text })
        medium.forEach { candidate ->
            assertTrue(
                MemorySentences.split(candidate.text!!).size <= MemoryExcerpt.MEDIUM_TEASER_MAX_SENTENCES,
            )
            assertTrue(MemorySentences.weightedLength(candidate.text!!) <= MemoryExcerpt.MEDIUM_TEASER_MAX_WEIGHT)
        }
        // the phone is capped by the slot instead: all three are offered
        val phone = MemoryExcerpt.candidates(short, capped = false, today = TODAY).filterNot { it.attributionOnly }
        assertEquals("Quiet start. The bridge lifts. Then it ends.", phone.first().text)
    }

    @Test
    fun should_attribute_song_notes_with_anchor_and_album_notes_with_date() {
        val track = MemoryCopyTrack(number = 3, title = "未寄出的信", rating = 10f)
        val anchored = MemoryCopyNote("x", LocalDate.of(2026, 8, 2), track, positionMs = 72_400L)
        val unanchored = anchored.copy(positionMs = null)
        val album = MemoryCopyNote("y", LocalDate.of(2025, 9, 12))

        assertEquals(
            MemoryExcerptAttribution(MemoryExcerptKind.NOTE, "未寄出的信 1:12"),
            MemoryExcerpt.noteAttribution(anchored, TODAY),
        )
        assertEquals(
            MemoryExcerptAttribution(MemoryExcerptKind.NOTE, "未寄出的信"),
            MemoryExcerpt.noteAttribution(unanchored, TODAY),
        )
        assertEquals(
            MemoryExcerptAttribution(MemoryExcerptKind.NOTE, "Sep 12, 2025"),
            MemoryExcerpt.noteAttribution(album, TODAY),
        )
    }

    @Test
    fun should_size_excerpt_by_length() {
        val by = MemoryExcerptAttribution(MemoryExcerptKind.REVIEW, "Jul 26")
        assertEquals(MemoryExcerptSize.SHORT, MemoryExcerptCandidate("好听。", by).size)
        assertEquals(MemoryExcerptSize.MEDIUM, MemoryExcerptCandidate("a".repeat(17), by).size)
        assertEquals(MemoryExcerptSize.LONG, MemoryExcerptCandidate("a".repeat(61), by).size)
    }
}

/** Prototype "by" lines, without the old " · in Diary" tail. */
private fun goldenAttribution(by: String): MemoryExcerptAttribution {
    val cleaned = by.removeSuffix(" · in Diary")
    val (kind, rest) = when {
        cleaned.startsWith("Your note") -> MemoryExcerptKind.NOTE to cleaned.removePrefix("Your note")
        cleaned.startsWith("Your review") -> MemoryExcerptKind.REVIEW to cleaned.removePrefix("Your review")
        else -> error("Unexpected attribution: $cleaned")
    }
    return MemoryExcerptAttribution(kind, rest.removePrefix(" · ").ifBlank { null })
}
