package com.gpo.yoin.data.migration.spotoolfy

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotoolfyRecapCalculatorTest {

    /** Words are what spaces separate; enough to test the rules without ICU. */
    private val calculator = SpotoolfyRecapCalculator { text -> text.split(Regex("[\\s,.!?，。！？]+")) }

    private val trackA = "4uLU6hMCjMI75M1A2tKUQC"
    private val trackB = "7qiZfU4dY1lWllzX7mPBI3"
    private val trackC = "0VjIjW4GlUZAMYd2vXMi3b"

    private fun track(id: String, artist: String, cover: String?) =
        SpotoolfyTrack(id, "Song $id", artist, "Album", cover)

    private fun note(
        text: String,
        at: Long,
        trackId: String = trackA,
        lyric: String? = null,
    ) = ImportedNote(
        id = "$trackId-$at",
        trackId = trackId,
        content = text,
        text = text,
        lyricLines = listOfNotNull(lyric),
        recordedAt = at,
        positionMs = null,
        title = "Song $trackId",
        artist = "Artist",
    )

    private fun plan(
        notes: List<ImportedNote>,
        ratings: List<ImportedRating> = emptyList(),
        tracks: List<SpotoolfyTrack> = emptyList(),
    ) = SpotoolfyImportPlan(
        notes = notes,
        ratings = ratings,
        skipped = 0,
        firstRecordAt = (notes.map { it.recordedAt } + ratings.map { it.recordedAt }).minOrNull() ?: 0L,
        lastRecordAt = (notes.map { it.recordedAt } + ratings.map { it.recordedAt }).maxOrNull() ?: 0L,
        tracks = tracks.associateBy { it.trackId },
    )

    /** [count] notes that share enough words for a keyword burst. */
    private fun heavyNotes(count: Int = 24): List<ImportedNote> = (0 until count).map { i ->
        val words = listOf("耳机 循环 夏天", "海边 夏天 人声", "凌晨 循环 鼓点", "副歌 合成器 耳机", "地铁 海边 凌晨")
        note("${words[i % words.size]} 副歌 合成器 鼓点 地铁", at = 1_000L + i, trackId = if (i % 2 == 0) trackA else trackB)
    }

    @Test
    fun should_countNotesTracksArtistsAndRatings_when_planHasThem() {
        val recap = calculator.compute(
            plan(
                notes = listOf(note("a", 10L), note("b", 20L, trackId = trackB)),
                ratings = listOf(ImportedRating(trackA, 10f, 15L), ImportedRating(trackC, 1f, 5L)),
                tracks = listOf(
                    track(trackA, "Placeholder Artist, Guest Artist", null),
                    track(trackB, "placeholder artist", null),
                    track(trackC, "Third Artist", null),
                ),
            ),
        )

        assertEquals(2, recap.noteCount)
        assertEquals(3, recap.trackCount)
        // "Placeholder Artist" and "placeholder artist" are one artist.
        assertEquals(3, recap.artistCount)
        assertEquals(2, recap.ratedTrackCount)
        assertEquals(5L, recap.firstRecordAt)
        assertEquals(20L, recap.lastRecordAt)
    }

    @Test
    fun should_rankKeywordsByCount_when_notesShareWords() {
        val recap = calculator.compute(plan(heavyNotes()))

        assertTrue(recap.fallbackNotes.isEmpty())
        val words = recap.keywords.map { it.text }
        assertEquals(listOf("副歌", "合成器", "鼓点", "地铁"), words.take(4))
        assertTrue(recap.keywords.all { it.count >= 2 })
        assertEquals(recap.keywords.sortedByDescending { it.count }.map { it.count }, recap.keywords.map { it.count })
    }

    @Test
    fun should_listNewestNotesFirst_when_keywordAppearsInManyNotes() {
        val recap = calculator.compute(plan(heavyNotes()))

        val notes = recap.keywords.first { it.text == "副歌" }.notes
        assertEquals(3, notes.size)
        assertEquals(listOf(1_023L, 1_022L, 1_021L), notes.map { it.writtenAt })
    }

    @Test
    fun should_dropStopwordsShortWordsAndNumbers_when_countingKeywords() {
        val notes = (0 until 4).map { note("这首歌 今天 好 ok yes 2024 第3遍 night night", at = it.toLong()) }

        assertEquals(listOf("night"), calculator.keywords(notes).map { it.text })
    }

    @Test
    fun should_dropPossessiveFragments_when_countingKeywords() {
        val notes = (0 until 3).map { note("人的 夏天的 夏天", at = it.toLong()) }

        assertEquals(listOf("夏天"), calculator.keywords(notes).map { it.text })
    }

    @Test
    fun should_showNoteCards_when_thereAreTooFewNotes() {
        val notes = (0 until 9).map { note("note $it", at = it.toLong()) }

        val recap = calculator.compute(plan(notes))

        assertTrue(recap.keywords.isEmpty())
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 8L), recap.fallbackNotes.map { it.writtenAt })
    }

    @Test
    fun should_showNoteCards_when_notesShareTooFewWords() {
        val notes = (0 until 30).map { note("unique$it words$it", at = it.toLong()) }

        val recap = calculator.compute(plan(notes))

        assertTrue(recap.keywords.isEmpty())
        assertEquals(SpotoolfyRecapCalculator.MAX_FALLBACK_NOTES, recap.fallbackNotes.size)
    }

    @Test
    fun should_pickEarliestAndLatestSnippetFromTwoSongs_when_notesCarryLyrics() {
        val recap = calculator.compute(
            plan(
                listOf(
                    note("first", 1L, trackA, lyric = "把夏天折成纸飞机"),
                    note("dup", 2L, trackB, lyric = "把夏天折成纸飞机"),
                    note("long", 3L, trackB, lyric = "这一句歌词实在太长了".repeat(5)),
                    note("other song", 4L, trackB, lyric = "summer ends in a minor key"),
                    note("same song as first", 5L, trackA, lyric = "飞过你家的屋顶"),
                ),
            ),
        )

        assertEquals(listOf("把夏天折成纸飞机", "summer ends in a minor key"), recap.lyricSnippets.map { it.lyric })
        assertEquals("other song", recap.lyricSnippets[1].note)
        assertEquals(4L, recap.lyricSnippets[1].capturedAt)
    }

    @Test
    fun should_haveNoSnippets_when_noNoteCarriesLyrics() {
        assertTrue(calculator.compute(plan(heavyNotes())).lyricSnippets.isEmpty())
    }

    @Test
    fun should_countRepeatedEmoji_when_notesUseThem() {
        val notes = heavyNotes().mapIndexed { i, n ->
            n.copy(text = n.text + if (i % 3 == 0) " 🔥🔥" else if (i % 3 == 1) " 🌊" else " 👍🏽")
        }

        val recap = calculator.compute(plan(notes))

        assertEquals(listOf(RecapEmoji("🔥", 16), RecapEmoji("🌊", 8), RecapEmoji("👍", 8)), recap.emoji)
    }

    @Test
    fun should_orderCoversByFirstRecord_when_tracksShareAnAlbum() {
        val recap = calculator.compute(
            plan(
                notes = listOf(note("b", 20L, trackB), note("a", 30L, trackA)),
                ratings = listOf(ImportedRating(trackC, 5f, 10L)),
                tracks = listOf(
                    track(trackA, "x", "https://i.scdn.co/a"),
                    track(trackB, "x", "https://i.scdn.co/a"),
                    track(trackC, "x", "https://i.scdn.co/c"),
                ),
            ),
        )

        assertEquals(listOf("https://i.scdn.co/c", "https://i.scdn.co/a"), recap.coverUrls)
    }

    @Test
    fun should_cutLongNotes_when_makingAnExcerpt() {
        val excerpt = SpotoolfyRecapCalculator.excerpt("长".repeat(200) + "\n\nend")

        assertEquals(SpotoolfyRecapCalculator.MAX_EXCERPT_CHARS + 1, excerpt.length)
        assertTrue(excerpt.endsWith("…"))
        assertEquals("a b c", SpotoolfyRecapCalculator.excerpt(" a\n\nb   c "))
    }

    @Test
    fun should_roundTripThroughJson_when_storedInTheBatch() {
        val recap = calculator.compute(plan(heavyNotes()))

        val stored = Json.encodeToString(SpotoolfyRecap.serializer(), recap)

        assertEquals(recap, Json.decodeFromString(SpotoolfyRecap.serializer(), stored))
    }
}
