package com.gpo.yoin.ui.memories.showcase

import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.MemoryTrack
import com.gpo.yoin.ui.memories.MemoryWriting
import com.gpo.yoin.ui.memories.copy.MemoryExcerptCandidate
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card face and top bar's pure rules (twostate4 layoutFor phone tier, fitExcerpt, the dots, the bar). */
class MemoryCardFaceTest {

    @Test
    fun should_shrink_exhibit_when_container_is_shorter_than_760() {
        val phone = memoryCardMetrics(412.dp, 915.dp)
        assertEquals(256.dp, phone.cover)
        assertEquals(96.dp, phone.seal)
        assertEquals(59.dp, phone.air1)
        assertFalse(phone.short)
        assertFalse(phone.wide)

        val short = memoryCardMetrics(411.dp, 731.dp)
        assertEquals(168.dp, short.cover)
        assertEquals(72.dp, short.seal)
        assertEquals(24.dp, short.air1)
        assertTrue(short.short)

        // a tablet before P6: the phone card, centred in the 480 column, with the teaser cap
        assertTrue(memoryCardMetrics(800.dp, 1280.dp).wide)
        assertEquals(256.dp, memoryCardMetrics(1280.dp, 800.dp).cover)
    }

    @Test
    fun should_pin_emblem_to_cover_corner_by_its_own_size() {
        // right −0.3s, bottom −0.24s: the 96 hangs 28.8dp right of and 23.04dp below the 256 cover
        val (x, y) = sealOffset(256.dp, 96.dp)
        assertEquals(256f - 96f * 0.7f, x.value, 1e-3f)
        assertEquals(256f - 96f * 0.76f, y.value, 1e-3f)
    }

    @Test
    fun should_pick_whole_sentences_or_attribution_when_slot_is_short() {
        val candidates = listOf(
            MemoryExcerptCandidate("One. Two. Three.", "Your review · Jul 26"),
            MemoryExcerptCandidate("One. Two.", "Your review · Jul 26"),
            MemoryExcerptCandidate("One.", "Your review · Jul 26"),
            MemoryExcerptCandidate(null, "Your review · Jul 26 · in Diary"),
        )
        val heights = listOf(140, 100, 60, 16)
        assertEquals(0, pickExcerpt(candidates, slotPx = 150) { heights[it] })
        assertEquals(1, pickExcerpt(candidates, slotPx = 120) { heights[it] })
        assertEquals(2, pickExcerpt(candidates, slotPx = 60) { heights[it] })
        // not even one sentence fits: the signature alone, never a cut sentence
        assertEquals(3, pickExcerpt(candidates, slotPx = 20) { heights[it] })
        assertEquals(3, pickExcerpt(candidates, slotPx = -40) { heights[it] })
        assertNull(pickExcerpt(emptyList(), slotPx = 100) { 0 })

        // two candidates with the same words are measured as two slots (by index)
        val twins = listOf(
            MemoryExcerptCandidate("前奏的吉他像在水底。", "Your note · 序曲 0:12"),
            MemoryExcerptCandidate("前奏的吉他像在水底。", "Your note · 序曲 0:12"),
            MemoryExcerptCandidate(null, "Your note · 序曲 0:12 · in Diary"),
        )
        val measured = mutableListOf<Int>()
        val picked = pickExcerpt(twins, slotPx = 10) { index ->
            measured += index
            50
        }
        assertEquals(2, picked)
        assertEquals(listOf(0, 1), measured)
    }

    @Test
    fun should_count_notes_but_not_the_review_on_the_diary_button() {
        val memory = entry(
            writings = listOf(
                MemoryWriting(MemoryWriting.Kind.SONG_NOTE, "a", 1L),
                MemoryWriting(MemoryWriting.Kind.ALBUM_NOTE, "b", 2L),
                MemoryWriting(MemoryWriting.Kind.REVIEW, "c", 3L),
            ),
        )
        assertEquals(2, memory.diaryNoteCount())
        assertEquals(" · 2 notes", diaryNotesSuffix(2))
        assertEquals(" · 1 note", diaryNotesSuffix(1))
        assertNull(diaryNotesSuffix(0))
    }

    @Test
    fun should_build_emblem_model_from_card_score_and_rated_tracks() {
        val rated = entry(
            scoreText = "10.0",
            scoreKind = MemoryScoreKind.ALBUM_RATING,
            tracks = listOf(track(9f), track(null), track(8.5f)),
        )
        val model = rated.grooveModel(MemoryPaletteSamples.M1)
        assertEquals(GrooveKind.Album, model.kind)
        assertEquals(listOf(true, false, true), model.trackRated)
        assertEquals("10.0", model.scoreText)
        assertEquals(4, model.tier)

        // no track list (album lookup failed): the counts stand in, rated first
        val counts = entry(scoreText = "7.8", scoreKind = MemoryScoreKind.AVERAGE_TRACK_RATING, rated = 2, total = 4)
        assertEquals(listOf(true, true, false, false), counts.grooveModel(MemoryPaletteSamples.M3).trackRated)

        // "N/A" never draws a score
        val notAvailable = entry(scoreText = "N/A", scoreKind = MemoryScoreKind.ALBUM_RATING)
        assertEquals(GrooveKind.Unrated, notAvailable.grooveModel(MemoryPaletteSamples.M2).kind)
    }

    @Test
    fun should_drop_year_from_artist_line_only_when_it_is_a_year() {
        assertEquals("HYUKOH", "HYUKOH · 2024".artistOnly())
        assertEquals("Single · by A", "Single · by A".artistOnly())
        assertEquals("Album", "Album".artistOnly())
    }

    @Test
    fun should_resolve_dot_tap_to_nearest_centre() {
        // 18dp pitch, first centre 16dp into the 32dp box
        assertEquals(0, nearestDot(x = 0f, count = 5, pitchPx = 18f, firstCentrePx = 16f))
        assertEquals(0, nearestDot(x = 24.9f, count = 5, pitchPx = 18f, firstCentrePx = 16f))
        assertEquals(1, nearestDot(x = 25.1f, count = 5, pitchPx = 18f, firstCentrePx = 16f))
        assertEquals(4, nearestDot(x = 200f, count = 5, pitchPx = 18f, firstCentrePx = 16f))
        // each dot's area is symmetric about its centre
        assertEquals(2, nearestDot(x = 16f + 36f - 8.9f, count = 5, pitchPx = 18f, firstCentrePx = 16f))
        assertEquals(2, nearestDot(x = 16f + 36f + 8.9f, count = 5, pitchPx = 18f, firstCentrePx = 16f))
        // slot A keeps 12dp clear of the first dot box
        assertEquals(12f + 90f + 7f + 12f, slotEndInset(5).value, 1e-3f)
    }

    @Test
    fun should_fade_and_parallax_neighbour_bar_slots() {
        assertEquals(1f, barVisibility(0f), 1e-6f)
        assertEquals(0f, barVisibility(0.5f), 1e-6f)
        assertEquals(0.56f, barVisibility(-0.2f), 1e-4f)
        assertEquals(0f, barVisibility(2f), 1e-6f)
        // the slots travel at 40% of the page: page −rel·W plus 0.6·W·rel
        assertEquals(0.6f * 400f * 0.5f, parallaxPx(0.5f, 400f), 1e-4f)
        assertEquals(0.5f, smoothstep(0f, 1f, 0.5f), 1e-6f)
    }

    private fun track(rating: Float?) = MemoryTrack(
        stableId = "t$rating",
        title = "t",
        artist = "a",
        durationSeconds = null,
        rating = rating,
    )

    private fun entry(
        scoreText: String = "N/A",
        scoreKind: MemoryScoreKind = MemoryScoreKind.NONE,
        tracks: List<MemoryTrack> = emptyList(),
        writings: List<MemoryWriting> = emptyList(),
        rated: Int = 0,
        total: Int = 0,
    ) = MemoryEntry(
        stableId = "m",
        sourceActivityId = 0L,
        entityType = MemoryEntityType.ALBUM,
        entityId = "m",
        entityProvider = "subsonic",
        title = "Album",
        supportingText = "Artist · 2024",
        metaText = null,
        coverArtUrl = null,
        timestamp = 0L,
        scoreText = scoreText,
        scoreKind = scoreKind,
        scoreSupportingText = null,
        footerText = null,
        ratedTrackCount = rated,
        totalTrackCount = total,
        writings = writings,
        playbackSongs = emptyList(),
        tracks = tracks,
    )
}
