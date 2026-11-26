package com.gpo.yoin.shared.share

import com.gpo.yoin.shared.albumabout.YoinAlbumAbout
import com.gpo.yoin.shared.notes.YoinNoteLine
import com.gpo.yoin.shared.notes.YoinNoteReader
import hct.Hct
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The facade over the share-card rules (Android PR #335, owner 2026-11-25): which tabs a song has, which layouts
 * each tab offers and which it opens on, what each image says (the lyric lines, About's credits and paragraph,
 * the note), the tones' colour roles, the Story zoom, the export sizes and the file name. Android, iOS (Swift,
 * through YoinShared) and the HarmonyOS Dart port all answer exactly as
 * shared/src/commonTest/resources/golden/share-cards.json says.
 *
 * Swift calls it as `YoinShareCards.shared.…`. Plain types only: data classes of strings, numbers, lists and
 * nullables, and a few enums. Every function takes all its arguments (Swift sees no Kotlin defaults). The
 * platforms keep the drawing (the ten layouts, at their logical sizes), the sheet, the export (render, PNG,
 * save, share), the per-device layout memory, the dates and the strings.
 */
object YoinShareCards {

    // ── stored About rows (Android `song_about_entries`, synced as `song_about`) ─────────────────────────────

    const val KIND_CANONICAL = "canonical"
    const val KIND_ASK = "ask"
    const val KIND_OWN = "own"
    const val CANON_LYRICIST = "lyricist"
    const val CANON_COMPOSER = "composer"
    const val CANON_PRODUCER = "producer"
    const val CANON_CREATION_TIME = "creation_time"
    const val CANON_CREATION_LOCATION = "creation_location"
    const val CANON_REVIEW = "review"

    // ── sizes ───────────────────────────────────────────────────────────────────────────────────────────

    /** Every image lays out at its logical size (dp = pt, font scale 1): a Card is this wide, as tall as it needs. */
    const val cardDesignWidthDp = 360

    /** A Story is 360 × 640. */
    const val storyDesignWidthDp = 360
    const val storyDesignHeightDp = 640

    /** A Card exports this wide (720 logical px at 2×), its height following the content ([exportSize]). */
    const val cardExportWidthPx = 1440

    /** A Story exports at the common 9:16 canvas. */
    const val storyExportWidthPx = 1080
    const val storyExportHeightPx = 1920

    /** A Story's content keeps clear of the chrome stories draw over the image: the frame it zooms into. */
    const val storyMarginHorizontalDp = 28
    const val storyMarginVerticalDp = 72

    const val storyMinZoom = ShareZoomSearch.MIN_ZOOM
    const val storyMaxZoom = ShareZoomSearch.MAX_ZOOM

    // ── tabs and layouts ────────────────────────────────────────────────────────────────────────────────

    /** Whether the song has something for [tab]. */
    fun has(content: ShareCardContent, tab: ShareTab): Boolean = when (tab) {
        ShareTab.Lyrics -> content.lyrics != null && content.lyrics.lines.isNotEmpty()
        // Credits or AI words; the year alone is not an About.
        ShareTab.About -> content.about != null && (content.about.paragraph != null || content.about.credits.any { !it.fact })
        ShareTab.Note -> content.note != null && content.note.content.isNotBlank()
    }

    /** The tabs this song has something for, in order (none: the sheet doesn't open). */
    fun tabs(content: ShareCardContent): List<ShareTab> = ShareTab.entries.filter { has(content, it) }

    /** Every layout of [tab], in 版式 order. */
    fun allLayouts(tab: ShareTab): List<ShareLayout> = ShareLayout.entries.filter { it.tab == tab }

    /** The layouts [tab] offers for this content, in 版式 order (empty when the tab has nothing). */
    fun layouts(content: ShareCardContent, tab: ShareTab): List<ShareLayout> {
        if (!has(content, tab)) return emptyList()
        return allLayouts(tab).filter { layout ->
            when (layout) {
                // 竖排 only when every line is CJK: Latin set on its side reads as a mistake.
                ShareLayout.LyricsVertical -> content.lyrics!!.lines.all { isCjk(it.text) }
                // The quote needs a paragraph to quote.
                ShareLayout.AboutQuote -> content.about!!.paragraph != null
                // A timeline needs the note's moment and the song's length.
                ShareLayout.NoteTimeline -> content.note!!.positionMs != null && content.song.durationMs > 0L
                else -> true
            }
        }
    }

    /** The layout [tab] opens on: [remembered] when this content offers it, else the tab's first; null when none. */
    fun resolveLayout(content: ShareCardContent, tab: ShareTab, remembered: ShareLayout?): ShareLayout? {
        val offered = layouts(content, tab)
        return remembered?.takeIf { it in offered } ?: offered.firstOrNull()
    }

    /**
     * The layout remembered for [tab] as stored ([ShareLayout.storeValue] under [ShareTab.storeKey]): a layout a
     * later build dropped, or one filed under another tab, reads as none chosen.
     */
    fun layoutForStoreValue(tab: ShareTab, value: String?): ShareLayout? =
        value?.let { stored -> ShareLayout.entries.firstOrNull { it.name == stored && it.tab == tab } }

    /** The tone in swatch [slot] (0–3) for [role]; the sheet keeps the slot across tabs. */
    fun tone(role: ShareRole, slot: Int): ShareTone = role.tones()[slot]

    // ── lyrics ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Whether [text] is CJK all through: at least one Han / kana / Hangul / Bopomofo letter and no letter of any
     * other script (digits, punctuation, symbols and spaces don't count either way). By Unicode Script, plus the
     * Common-script letters only Japanese writes (ー, halfwidth ｰ ﾞ ﾟ, 〆), so "ラーメン" is CJK.
     */
    fun isCjk(text: String): Boolean = ShareScripts.isCjk(text)

    /**
     * The line the playhead is in, for a share opened without a pick ("lyrics tab with no selection = current
     * line"): the last timed line started by [positionMs], else the first line. Blank lines never; null when none.
     */
    fun currentLine(lyrics: List<ShareLyricLine>, positionMs: Long, showTranslation: Boolean): ShareLyrics? {
        val lines = lyrics.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return null
        val current = lines.lastOrNull { it.startMs != null && it.startMs <= positionMs } ?: lines.first()
        return ShareLyrics(lines = listOf(current), passageStarts = emptyList(), showTranslation = showTranslation)
    }

    /**
     * Where a pick of the lyrics' [selected] indices (any order; outside 0 until [lineCount] ignored) skips part of
     * the song: positions in the picked lines, in order, that start a new passage.
     */
    fun passageStarts(selected: List<Int>, lineCount: Int): List<Int> {
        val picked = selected.filter { it in 0 until lineCount }.distinct().sorted()
        return picked.indices.filter { i -> i > 0 && picked[i] != picked[i - 1] + 1 }
    }

    /**
     * The share text sent with the image, the same for every tab: 「歌名 · 歌手」 on one line, the title alone
     * without an artist; null without a title.
     */
    fun caption(title: String?, artist: String?): String? {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return null
        val by = artist?.trim().orEmpty()
        return if (by.isEmpty()) t else "$t$captionSeparator$by"
    }

    /** Between the title and the artist in [caption]. */
    const val captionSeparator = " · "

    /** Classic's line size on the 360 card (sp): short picks 22, medium 19, long 17. */
    fun cardLineSizeSp(lines: List<String>): Float {
        val chars = lines.sumOf { it.length }
        return when {
            chars <= 90 && lines.size <= 4 -> 22f
            chars <= 200 -> 19f
            else -> 17f
        }
    }

    /** The most lines a Poster shows: the first big, up to three more small. */
    const val posterMaxLines = 4

    /** A Story poster draws the lead this much larger than [SharePoster.leadSizeSp]. */
    const val posterStoryLeadBoostSp = 6f

    /** Between the translations on a Poster's one translation line. */
    const val posterTranslationSeparator = "  /  "

    /** What a Poster draws: the first non-blank lines (at most [posterMaxLines]); null when there are none. */
    fun poster(pick: ShareLyrics): SharePoster? {
        val shown = pick.lines.filter { it.text.isNotBlank() }.take(posterMaxLines)
        val lead = shown.firstOrNull() ?: return null
        val translation = if (pick.showTranslation) {
            shown.mapNotNull { it.translation?.trim()?.takeIf(String::isNotEmpty) }
                .joinToString(posterTranslationSeparator)
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return SharePoster(
            lead = lead,
            rest = shown.drop(1),
            translation = translation,
            leadSizeSp = posterLeadSizeSp(lead.text),
            leadTime = lead.startMs?.let(::time),
        )
    }

    /** The lead line's size on a Poster Card (sp), by its UTF-16 length: the shorter, the larger. */
    fun posterLeadSizeSp(text: String): Float = when {
        text.length <= 12 -> 40f
        text.length <= 24 -> 34f
        text.length <= 40 -> 28f
        else -> 24f
    }

    /** The excerpt is drawn at least this much of the Player's bar, so a short pick still shows. */
    const val playerExcerptMinFraction = 0.06f

    /** A line's assumed length after its start: where the excerpt ends after the last line's start. */
    const val playerExcerptLineMs = 4_000L

    /**
     * The span of [pick] on a [durationMs]-long song: its first line's start to its last line's start plus a
     * line's length, drawn at least [playerExcerptMinFraction] of the bar. Null when the song's length or every
     * line's time is unknown (the Player then draws the lines without a bar).
     */
    fun playerExcerpt(pick: ShareLyrics, durationMs: Long): ShareExcerpt? {
        if (durationMs <= 0L) return null
        val times = pick.lines.mapNotNull { it.startMs }
        if (times.isEmpty()) return null
        val startMs = times.min().coerceIn(0L, durationMs)
        val endMs = (times.max() + playerExcerptLineMs).coerceIn(startMs, durationMs)
        var start = startMs.toFloat() / durationMs
        var end = endMs.toFloat() / durationMs
        if (end - start < playerExcerptMinFraction) {
            end = (start + playerExcerptMinFraction).coerceAtMost(1f)
            start = (end - playerExcerptMinFraction).coerceAtLeast(0f)
        }
        return ShareExcerpt(start, end, startMs, endMs)
    }

    /** Candidate 竖排 sizes (sp), largest first. */
    val verticalSizesSp: List<Float> = listOf(28f, 26f, 24f, 22f, 20f, 17f, 15f, 13f)

    /** One character's height in a column, × the type size. */
    const val verticalCharAdvance = 1.14f

    /** One column's width with the gap after it, × the type size (the gap is the 0.5). */
    const val verticalColumnAdvance = 1.5f

    /** The Vertical layout's column height and the width its lyric columns share (dp). */
    const val verticalColumnHeightDp = 380f
    const val verticalAvailableWidthDp = 250f

    /** The width the 「歌词大意」 footnote column takes from the lyric columns when there is one (dp). */
    const val verticalFootnoteWidthDp = 22f

    /** Between the translations in the footnote ("歌词大意：A；B"). */
    const val verticalGistSeparator = "；"

    /**
     * Sets [lines] in columns [columnHeightDp] tall within [widthDp]: one line per column, a line longer than a
     * column carries on in the next (spaces set as ideographic spaces). The largest size from [verticalSizesSp] at
     * which every line fits one column and the columns fit the width; else the largest at which the columns fit;
     * else the smallest (the Story zoom takes it from there). Columns are in reading order: the first is the
     * rightmost.
     */
    fun verticalPlan(lines: List<String>, columnHeightDp: Float, widthDp: Float): ShareVerticalPlan {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
        fun plan(size: Float): ShareVerticalPlan {
            val perColumn = max(1, floor(columnHeightDp / (size * verticalCharAdvance)).toInt())
            val columns = clean.flatMap { line -> line.replace(" ", "　").chunked(perColumn) }
            return ShareVerticalPlan(columns, size, perColumn)
        }
        val fitting = verticalSizesSp.map(::plan).filter { it.columns.size * it.fontSizeSp * verticalColumnAdvance <= widthDp }
        // A line to a column reads as a poem; a line broken over two only when nothing larger fits whole.
        return fitting.firstOrNull { it.columns.size == clean.size }
            ?: fitting.firstOrNull()
            ?: plan(verticalSizesSp.last())
    }

    /**
     * The 「歌词大意」 footnote's translations, when the pick shows them: each line's translation trimmed, empty
     * ones dropped. The platform writes them as its "歌词大意：%1$s" string, joined by [verticalGistSeparator].
     */
    fun verticalGist(pick: ShareLyrics): List<String> =
        if (pick.showTranslation) pick.lines.mapNotNull { it.translation?.trim()?.takeIf(String::isNotEmpty) } else emptyList()

    /** The Vertical layout's plan for [pick]: its columns in the layout's frame, less the footnote's width. */
    fun verticalLyricsPlan(pick: ShareLyrics): ShareVerticalPlan {
        val footnote = if (verticalGist(pick).isEmpty()) 0f else verticalFootnoteWidthDp
        return verticalPlan(pick.lines.map { it.text }, verticalColumnHeightDp, verticalAvailableWidthDp - footnote)
    }

    /**
     * Short text beside the Vertical lines (the title, the artist, the footnote) at [sizeSp] in columns
     * [columnHeightDp] tall: CJK (or letterless) text stacked character by character, in columns in reading order;
     * null when it is to be turned on its side instead.
     */
    fun verticalTextColumns(text: String, sizeSp: Float, columnHeightDp: Float): List<String>? {
        if (!isCjk(text) && ShareScripts.hasLetterUnit(text)) return null
        val perColumn = (columnHeightDp / (sizeSp * verticalCharAdvance)).toInt().coerceAtLeast(1)
        return text.chunked(perColumn)
    }

    /** [text] as vertical text sets it, character by character ([verticalForm]). */
    fun verticalText(text: String): String = text.map(::verticalForm).joinToString("")

    /**
     * A character as vertical text sets it: CJK punctuation turns to its vertical presentation form (。， become
     * ︒︐, 「」 become ﹁﹂, dashes and ellipses stand up); everything else as it is.
     */
    fun verticalForm(c: Char): Char = when (c) {
        '，' -> '︐'
        '、' -> '︑'
        '。' -> '︒'
        '：' -> '︓'
        '；' -> '︔'
        '！' -> '︕'
        '？' -> '︖'
        '「' -> '﹁'
        '」' -> '﹂'
        '『' -> '﹃'
        '』' -> '﹄'
        '（', '(' -> '︵'
        '）', ')' -> '︶'
        '《' -> '︽'
        '》' -> '︾'
        '〈' -> '︿'
        '〉' -> '﹀'
        '…' -> '︙'
        '—', '－', '-', 'ー' -> '︱'
        '～', '~' -> '≀'
        else -> c
    }

    // ── About ───────────────────────────────────────────────────────────────────────────────────────────

    /** Credits a Quote shows as chips, and a Liner in its 2 × 2 grid: the first of [ShareAbout.credits]. */
    const val quoteCredits = 3
    const val linerCredits = 4

    /**
     * About's share content: the credits — the lyrics' credits first, then the server's composer, then the AI's
     * lyricist / composer / producer, creation time and place, then the year (a [ShareCredit.fact]) — and one
     * paragraph: the AI answer or review asked for ([focusKind] + [focusKey], the row long-pressed), else the
     * review. Never the server's file facts (format, bitrate, plays, path…), never the listener's own entries.
     * Null when there is nothing to share (the year alone is nothing). Values fold runs of whitespace to one space.
     */
    fun aboutPick(
        entries: List<ShareAboutEntry>,
        sources: ShareAboutSources,
        labels: ShareAboutLabels,
        focusKind: String?,
        focusKey: String?,
    ): ShareAbout? {
        val canonical = entries.filter { it.kind == KIND_CANONICAL }.associateBy { it.entryKey }
        val rows = mutableListOf<ShareCredit>()
        fun ai(key: String, label: String) {
            val row = canonical[key] ?: return
            val value = oneLine(row.answerText) ?: return
            rows += ShareCredit(label, value, ai = true)
        }
        fun add(label: String, value: String?) {
            value?.let(::oneLine)?.let { rows += ShareCredit(label, it) }
        }
        // A blank name is no name: the AI's row shows instead.
        fun names(value: String?): String? = value?.takeIf { oneLine(it) != null }
        fun credited(key: String, label: String, names: String?) {
            if (names != null) add(label, names) else ai(key, label)
        }
        credited(CANON_LYRICIST, labels.lyricist, names(sources.lyricist))
        credited(CANON_COMPOSER, labels.composer, names(sources.composer) ?: names(sources.serverComposer))
        add(labels.arranger, sources.arranger)
        credited(CANON_PRODUCER, labels.producer, names(sources.producer))
        ai(CANON_CREATION_TIME, labels.creationTime)
        ai(CANON_CREATION_LOCATION, labels.creationLocation)
        sources.year?.let { rows += ShareCredit(labels.year, it.toString(), fact = true) }

        val review = entries.firstOrNull { it.kind == KIND_CANONICAL && it.entryKey == CANON_REVIEW && it.answerText.isNotBlank() }
        // The AI's paragraphs: the review, then its answers; the listener's own entries are theirs, not shared.
        val paragraphs = buildList {
            review?.let { add(Triple(it, labels.review, it.answerText.trim())) }
            entries.filter { it.kind == KIND_ASK && it.answerText.isNotBlank() }.forEach { row ->
                val heading = row.titleText?.takeIf { it.isNotBlank() } ?: row.promptText.orEmpty()
                add(Triple(row, heading, row.answerText.trim()))
            }
        }
        val chosen = paragraphs.firstOrNull { (row, _, _) -> focusKind != null && row.kind == focusKind && row.entryKey == focusKey }
            ?: paragraphs.firstOrNull { (row, _, _) -> row.kind == KIND_CANONICAL }
        if (rows.none { !it.fact } && chosen == null) return null
        return ShareAbout(credits = rows, heading = chosen?.second ?: labels.review, paragraph = chosen?.third)
    }

    /**
     * About's sign on the image: the platform's "由 %1$s 整理" / "By %1$s" with the AI service's name filled in,
     * the space before or after a Chinese name dropped (由千问整理, 由 Gemini 整理) — album About's rule
     * ([com.gpo.yoin.shared.albumabout.YoinAlbumAbout.aiCopy]).
     */
    fun aiSign(template: String, name: String): String = YoinAlbumAbout.aiCopy(template, name)

    private val Whitespace = Regex("\\s+")

    private fun oneLine(text: String): String? = text.replace(Whitespace, " ").trim().takeIf { it.isNotEmpty() }

    // ── notes ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The note an image shares: [noteId] when given (and not blank), else the note the playhead is in (the latest
     * anchored at or before [positionMs]), else the newest. Blank notes never; null when none.
     */
    fun notePick(notes: List<ShareNote>, noteId: String?, positionMs: Long): ShareNote? {
        val live = notes.filter { it.content.isNotBlank() }
        return noteId?.let { id -> live.firstOrNull { it.noteId == id } }
            ?: anchoredAt(live, positionMs)?.let { id -> live.firstOrNull { it.noteId == id } }
            ?: live.maxByOrNull { it.createdAt }
    }

    /** The latest anchored note at or before [positionMs] (a later one in [notes] wins a tie); null when none. */
    private fun anchoredAt(notes: List<ShareNote>, positionMs: Long): String? {
        var best: ShareNote? = null
        for (note in notes) {
            val anchor = note.positionMs ?: continue
            if (anchor <= positionMs && (best?.positionMs ?: -1L) <= anchor) best = note
        }
        return best?.noteId
    }

    /** Where a Timeline's marker sits: the note's moment as a fraction of the song (0–1); null without either. */
    fun timelineFraction(positionMs: Long?, durationMs: Long): Float? {
        val at = positionMs ?: return null
        if (durationMs <= 0L) return null
        return (at.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** The note's text as drawn: its lines and runs, mentions as chips (title, cover), no touch. */
    fun noteLines(note: ShareNote): List<YoinNoteLine> = YoinNoteReader.read(note.content)

    // ── time ────────────────────────────────────────────────────────────────────────────────────────────

    /** "1:05" for a moment in the song: whole minutes (no hours, "65:00"), seconds in two digits; negative = 0. */
    fun time(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return "${total / 60L}:${(total % 60L).toString().padStart(2, '0')}"
    }

    // ── colours ─────────────────────────────────────────────────────────────────────────────────────────

    /** Secondary text (the artist, the signs) is the tone's content colour at this alpha. */
    const val secondaryAlpha = 0.72f

    /** Paper's ink: the role colour at its light-theme tone (T40), in both themes. */
    const val paperInkTone = 40

    /** Paper's Story backdrop: the role at its light container tone (T90). */
    const val paperBackdropTone = 90

    /**
     * [tone]'s colours for [role], as roles of the playing palette's scheme (Now Playing's theme is the cover's).
     * The lyrics keep their pairings (Vivid and Mist tertiary); About draws from tertiary, a note from primary, with
     * Paper in the fourth slot: a white card, the role re-toned as ink. The fourth slot follows the role: a lyrics
     * image asked for Paper draws Mist and the others the other way round, so a slot kept across tabs stays valid.
     */
    fun toneColors(tone: ShareTone, role: ShareRole): ShareToneColors {
        fun ink(source: ShareInkSource, tone: Int? = null) = ShareInk(source, tone)
        if (role == ShareRole.Lyrics) {
            return when (tone) {
                ShareTone.Soft -> ShareToneColors(
                    ink(ShareInkSource.PrimaryContainer),
                    ink(ShareInkSource.OnPrimaryContainer),
                    ink(ShareInkSource.Primary),
                )
                ShareTone.Deep -> ShareToneColors(
                    ink(ShareInkSource.OnPrimaryContainer),
                    ink(ShareInkSource.PrimaryContainer),
                    ink(ShareInkSource.PrimaryContainer),
                )
                ShareTone.Vivid -> ShareToneColors(
                    ink(ShareInkSource.Tertiary),
                    ink(ShareInkSource.OnTertiary),
                    ink(ShareInkSource.TertiaryContainer),
                )
                ShareTone.Mist, ShareTone.Paper -> ShareToneColors(
                    ink(ShareInkSource.TertiaryContainer),
                    ink(ShareInkSource.OnTertiaryContainer),
                    ink(ShareInkSource.Tertiary),
                )
            }
        }
        val aiRole = role == ShareRole.Ai
        val main = if (aiRole) ShareInkSource.Tertiary else ShareInkSource.Primary
        val onMain = if (aiRole) ShareInkSource.OnTertiary else ShareInkSource.OnPrimary
        val container = if (aiRole) ShareInkSource.TertiaryContainer else ShareInkSource.PrimaryContainer
        val onContainer = if (aiRole) ShareInkSource.OnTertiaryContainer else ShareInkSource.OnPrimaryContainer
        return when (tone) {
            ShareTone.Soft -> ShareToneColors(ink(container), ink(onContainer), ink(main))
            ShareTone.Deep -> ShareToneColors(ink(onContainer), ink(container), ink(container))
            ShareTone.Vivid -> ShareToneColors(ink(main), ink(onMain), ink(container))
            ShareTone.Mist, ShareTone.Paper -> ShareToneColors(
                ink(ShareInkSource.White),
                ink(main, paperInkTone),
                ink(main, paperBackdropTone),
            )
        }
    }

    /** [argb] at HCT tone [tone], its hue and chroma kept (as far as the gamut allows): Paper's ink and backdrop. */
    fun retone(argb: Int, tone: Int): Int {
        val hct = Hct.fromInt(argb)
        return Hct.from(hct.hue, hct.chroma, tone.toDouble()).toInt()
    }

    // ── Story zoom ──────────────────────────────────────────────────────────────────────────────────────

    /** How far [layout]'s Story may zoom in: a poster never grows (it fills the frame's height), a card to 1.4. */
    fun storyMaxZoom(layout: ShareLayout): Float = if (layout.storyIsPoster) 1f else storyMaxZoom

    /** Whether content [heightPx] tall laid out at [zoom] fits a [frameHeightPx] frame (half a pixel's slack). */
    fun storyFits(heightPx: Int, zoom: Float, frameHeightPx: Int): Boolean =
        heightPx * zoom <= frameHeightPx + ShareZoomSearch.FIT_SLACK_PX

    /**
     * The zoom [ShareZoomSearch] settles on when [fits] answers at once: [maxZoom] when even that fits;
     * [storyMinZoom] when nothing does. [fits] lays the content out at the frame's width / zoom and says whether
     * its height × zoom fits the frame ([storyFits]).
     */
    fun storyZoom(maxZoom: Float, fits: (zoom: Float) -> Boolean): Float {
        val search = ShareZoomSearch(maxZoom)
        while (!search.settled) search.report(fits(search.zoom))
        return search.zoom
    }

    // ── export ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The PNG's pixel size for an image laid out [laidOutWidth] × [laidOutHeight] (any unit): a fixed width per
     * format, so the image doesn't depend on the screen; a Card's height follows its content.
     */
    fun exportSize(format: ShareFormat, laidOutWidth: Float, laidOutHeight: Float): ShareExportSize = when (format) {
        ShareFormat.Story -> ShareExportSize(storyExportWidthPx, storyExportHeightPx)
        ShareFormat.Card -> ShareExportSize(
            cardExportWidthPx,
            (laidOutHeight * cardExportWidthPx / laidOutWidth).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * The uniform scale that fits a [childWidth] × [childHeight] image into [maxWidth] × [maxHeight] (a preview),
     * never above [maxScale] (1 = never enlarge; a wide sheet's preview may go to 1.5). 1 for an empty child.
     */
    fun fitScale(childWidth: Int, childHeight: Int, maxWidth: Int, maxHeight: Int, maxScale: Float): Float {
        if (childWidth <= 0 || childHeight <= 0) return 1f
        return min(maxScale, min(maxWidth.toFloat() / childWidth, maxHeight.toFloat() / childHeight))
    }

    /** The song title as the file name carries it, at most this many UTF-16 units. */
    const val fileTitleMaxChars = 48

    /** Saved images go to Pictures/Yoin (Android) — the Photos album name on iOS, if one is used. */
    const val galleryFolder = "Yoin"

    /**
     * The title for the file name: the characters file systems refuse (\ / : * ? " < > | and control characters)
     * as spaces, runs of spaces folded, trimmed, capped at [fileTitleMaxChars]; empty when nothing is left.
     */
    fun fileTitle(songTitle: String?): String {
        val replaced = buildString {
            songTitle.orEmpty().forEach { c ->
                append(if (c in ForbiddenInFileNames || c.code < 0x20 || c.code == 0x7F) ' ' else c)
            }
        }
        val folded = buildString {
            replaced.forEach { c -> if (c != ' ' || lastOrNull() != ' ') append(c) }
        }
        return folded.trim().take(fileTitleMaxChars).trim()
    }

    private const val ForbiddenInFileNames = "\\/:*?\"<>|"

    /**
     * The saved image's name, the same for every layout: "Yoin - <title> - <ms>.png", or "Yoin <ms>.png" without a
     * title (the Android strings `np_share_file_named` / `np_share_file_untitled`, the same in every language).
     */
    fun fileName(songTitle: String?, nowMs: Long): String {
        val title = fileTitle(songTitle)
        return if (title.isEmpty()) "Yoin $nowMs.png" else "Yoin - $title - $nowMs.png"
    }
}
