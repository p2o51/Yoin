package com.gpo.yoin.data.migration.spotoolfy

import kotlinx.serialization.Serializable

/**
 * Everything the two-page recap shows (docs/spotoolfy-migration.md §6.3), worked out once at import and stored in
 * `import_batches.summaryJson`. Note excerpts live here rather than as note ids: while the landing shows the recap
 * the notes are still staged, not in `song_notes`.
 */
@Serializable
data class SpotoolfyRecap(
    val noteCount: Int,
    val trackCount: Int,
    val artistCount: Int,
    val ratedTrackCount: Int,
    val firstRecordAt: Long,
    val lastRecordAt: Long,
    /** Page 1's cover wall, oldest first. */
    val coverUrls: List<String>,
    /** Most frequent first. Empty when [fallbackNotes] stand in for them. */
    val keywords: List<RecapKeyword>,
    val emoji: List<RecapEmoji>,
    /** The earliest and the latest snippet, from two songs. */
    val lyricSnippets: List<RecapLyric>,
    /** Note cards for page 2 when there are too few notes or words for a keyword burst. */
    val fallbackNotes: List<RecapNote>,
)

@Serializable
data class RecapKeyword(
    val text: String,
    val count: Int,
    /** Notes using the word, newest first. */
    val notes: List<RecapNote>,
)

@Serializable
data class RecapEmoji(val emoji: String, val count: Int)

@Serializable
data class RecapLyric(
    val lyric: String,
    val trackTitle: String?,
    val artist: String?,
    val capturedAt: Long,
    /** What the user wrote with the snippet. */
    val note: String,
)

@Serializable
data class RecapNote(
    val text: String,
    val trackTitle: String?,
    val artist: String?,
    val writtenAt: Long,
)

/** Splits text into words. The app uses ICU's dictionary break iterator; tests may pass something simpler. */
fun interface WordSegmenter {
    fun words(text: String): List<String>
}

/** Computes [SpotoolfyRecap] from an import plan. Local and pure: no network, no Gemini (§6.3). */
class SpotoolfyRecapCalculator(private val segmenter: WordSegmenter) {

    fun compute(plan: SpotoolfyImportPlan): SpotoolfyRecap {
        val notes = plan.notes
        val trackIds = (notes.map { it.trackId } + plan.ratings.map { it.trackId }).toSet()
        val artists = trackIds
            .flatMap { id -> plan.tracks[id]?.artist.orEmpty().split(", ") }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val keywords = keywords(notes)
        val sparse = notes.size < MIN_NOTES_FOR_KEYWORDS || keywords.size < MIN_KEYWORDS
        return SpotoolfyRecap(
            noteCount = notes.size,
            trackCount = trackIds.size,
            artistCount = artists.size,
            ratedTrackCount = plan.ratings.size,
            firstRecordAt = plan.firstRecordAt,
            lastRecordAt = plan.lastRecordAt,
            coverUrls = coverUrls(plan),
            keywords = if (sparse) emptyList() else keywords,
            emoji = if (sparse) emptyList() else emoji(notes),
            lyricSnippets = lyricSnippets(notes),
            fallbackNotes = if (sparse) notes.takeLast(MAX_FALLBACK_NOTES).map { it.toRecapNote() } else emptyList(),
        )
    }

    /** Words used at least twice, by count; CJK words of two or more characters, Latin words of three letters. */
    internal fun keywords(notes: List<ImportedNote>): List<RecapKeyword> {
        val counts = HashMap<String, Int>()
        val usedIn = HashMap<String, MutableList<ImportedNote>>()
        for (note in notes) {
            for (raw in segmenter.words(note.text)) {
                val word = raw.trim().lowercase()
                if (!isKeyword(word)) continue
                counts[word] = (counts[word] ?: 0) + 1
                val list = usedIn.getOrPut(word) { ArrayList() }
                if (list.lastOrNull() !== note) list += note
            }
        }
        return counts.entries
            .filter { it.value >= MIN_KEYWORD_COUNT }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_KEYWORDS)
            .map { (word, count) ->
                RecapKeyword(
                    text = word,
                    count = count,
                    notes = usedIn.getValue(word).takeLast(MAX_NOTES_PER_KEYWORD).asReversed().map { it.toRecapNote() },
                )
            }
    }

    private fun isKeyword(word: String): Boolean {
        if (word in SpotoolfyStopwords.all || word.any { it.isDigit() }) return false
        val length = word.codePointCount(0, word.length)
        return when {
            // "一个人的晚上" can split as 一个 / 人的 / 晚上: a word ending in the possessive 的 is a fragment.
            word.all { isCjk(it) } -> length >= 2 && !word.endsWith('的')
            word.all { it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } ->
                length >= 3
            else -> false
        }
    }

    private fun emoji(notes: List<ImportedNote>): List<RecapEmoji> {
        val counts = LinkedHashMap<String, Int>()
        for (note in notes) {
            var i = 0
            val text = note.text
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (isPictograph(cp)) {
                    val key = String(Character.toChars(cp))
                    counts[key] = (counts[key] ?: 0) + 1
                }
                i += Character.charCount(cp)
            }
        }
        return counts.entries
            .filter { it.value >= MIN_KEYWORD_COUNT }
            .sortedByDescending { it.value }
            .take(MAX_EMOJI)
            .map { RecapEmoji(it.key, it.value) }
    }

    /** The first line of each snippet, short enough for a card; the earliest one and the latest from another song. */
    private fun lyricSnippets(notes: List<ImportedNote>): List<RecapLyric> {
        val seen = HashSet<String>()
        val candidates = notes.mapNotNull { note ->
            val line = note.lyricLines.firstOrNull() ?: return@mapNotNull null
            if (line.codePointCount(0, line.length) > MAX_LYRIC_CHARS || !seen.add(line)) return@mapNotNull null
            note to line
        }
        val first = candidates.firstOrNull() ?: return emptyList()
        val latest = candidates.drop(1).lastOrNull { (note, _) -> note.trackId != first.first.trackId }
        return listOfNotNull(first, latest).map { (note, line) ->
            RecapLyric(
                lyric = line,
                trackTitle = note.title,
                artist = note.artist,
                capturedAt = note.recordedAt,
                note = excerpt(note.text),
            )
        }
    }

    private fun coverUrls(plan: SpotoolfyImportPlan): List<String> {
        val firstSeen = LinkedHashMap<String, Long>()
        (plan.notes.map { it.trackId to it.recordedAt } + plan.ratings.map { it.trackId to it.recordedAt })
            .sortedBy { it.second }
            .forEach { (trackId, at) ->
                val url = plan.tracks[trackId]?.coverUrl ?: return@forEach
                firstSeen.putIfAbsent(url, at)
            }
        return firstSeen.keys.take(MAX_COVERS)
    }

    private fun ImportedNote.toRecapNote() = RecapNote(excerpt(text), title, artist, recordedAt)

    companion object {
        const val MIN_NOTES_FOR_KEYWORDS = 20
        const val MIN_KEYWORDS = 8
        const val MIN_KEYWORD_COUNT = 2
        const val MAX_KEYWORDS = 30
        const val MAX_NOTES_PER_KEYWORD = 3
        const val MAX_EMOJI = 3
        const val MAX_LYRIC_CHARS = 40
        const val MAX_FALLBACK_NOTES = 6
        const val MAX_COVERS = 24
        const val MAX_EXCERPT_CHARS = 120

        /** At most [MAX_EXCERPT_CHARS] characters, cut on a code point and marked with an ellipsis. */
        fun excerpt(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            if (flat.codePointCount(0, flat.length) <= MAX_EXCERPT_CHARS) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, MAX_EXCERPT_CHARS)).trimEnd() + "…"
        }

        private fun isCjk(c: Char): Boolean = when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
            else -> c == 'ー'
        }

        /** Emoji pictographs; skin tones, flags' regional letters and keycap parts are not counted on their own. */
        private fun isPictograph(cp: Int): Boolean = when (cp) {
            in 0x1F1E6..0x1F1FF, in 0x1F3FB..0x1F3FF -> false
            in 0x1F300..0x1FAFF, in 0x2600..0x27BF, in 0x1F000..0x1F2FF -> true
            0x2B50, 0x2B55, 0x231A, 0x231B, 0x23F0, 0x23F3 -> true
            else -> false
        }
    }
}
