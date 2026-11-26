package com.gpo.yoin.shared.albumabout

/*
 * The facade over the album About core (docs/design.md, album page › 专辑「关于」; Android 0.6.3, PR #326):
 * the prompts, the reply parser, the stored credits format, the credits line and its line-break model, Apple
 * Music's editorial notes, and the AI service's name in copy. Android, iOS (Swift, through YoinShared) and the
 * HarmonyOS Dart port all answer exactly as shared/src/commonTest/resources/golden/album-about.json says.
 *
 * Swift calls it as `YoinAlbumAbout.shared.…`. Plain types only: data classes of strings, lists and nullable
 * strings / ints, one enum ([AlbumCreditKind]); dates are ISO strings at the service's precision. The
 * platforms keep the AI call itself (HTTP, search, the appended search results), storage and sync
 * (`album_about_entries` / kind `album_about`), the UI and the strings.
 */
object YoinAlbumAbout {

    // ── stored rows ──────────────────────────────────────────────────

    /** Row kinds and canonical keys of `album_about_entries` (synced as is). */
    const val KIND_CANONICAL = "canonical"
    const val KIND_ASK = "ask"
    const val CANON_CREDITS = "credits"
    const val CANON_BLURB = "blurb"

    /** Tracks listed in the prompt. */
    const val maxPromptTracks = AlbumAboutPrompts.MAX_PROMPT_TRACKS

    /** The service's album notes quoted in the prompt, at most this many UTF-16 units. */
    const val maxNotesChars = AlbumAboutPrompts.MAX_NOTES_CHARS

    /** Names kept per credit item. */
    const val maxNames = AlbumAboutPrompts.MAX_NAMES

    // ── prompts ──────────────────────────────────────────────────────

    /**
     * The search queries for one album (the second only when the service allows two). Yoin runs them only
     * when it searches for the AI service itself; their pages are appended to the prompt by the platform.
     */
    fun searchQueries(grounding: AlbumAboutGrounding, count: Int): List<String> =
        AlbumAboutPrompts.searchQueries(grounding, count)

    /**
     * The search lead every searched prompt carries: [builtInSearch] when the AI service searches itself,
     * else whether Yoin's own search read any pages ([searchedPages]).
     */
    fun searchLead(builtInSearch: Boolean, searchedPages: Int): String =
        AlbumAboutPrompts.searchLead(builtInSearch, searchedPages)

    /**
     * The About prompt, before the platform appends its search results. With [AlbumAboutGrounding.serviceNotes]
     * (Apple Music) it is the credits-only prompt: no [ABOUT] block, the notes as one more source.
     */
    fun aboutPrompt(grounding: AlbumAboutGrounding, targetLanguage: String, searchLead: String): String =
        AlbumAboutPrompts.buildAboutPrompt(grounding, targetLanguage, searchLead)

    /** True when [aboutPrompt] asks for the credits only (the service wrote the paragraph). */
    fun isCreditsOnly(grounding: AlbumAboutGrounding): Boolean = grounding.serviceNotes != null

    /** The question prompt; the reply is the song Ask's `[TITLE]` / `[ANSWER]` format. */
    fun askPrompt(grounding: AlbumAboutGrounding, question: String, targetLanguage: String, searchLead: String): String =
        AlbumAboutPrompts.buildAskPrompt(grounding, question, targetLanguage, searchLead)

    /** The service's facts as the prompts list them, one per line. */
    fun groundingLines(grounding: AlbumAboutGrounding): String = AlbumAboutPrompts.groundingLines(grounding)

    /** How long the paragraph should be in [targetLanguage]. */
    fun blurbLength(targetLanguage: String): String = AlbumAboutPrompts.blurbLength(targetLanguage)

    // ── replies ──────────────────────────────────────────────────────

    /** One About reply as read: confident credits, the paragraph, and whether it was an answer at all. */
    fun parseReply(rawText: String): AlbumAboutReply = AlbumAboutPrompts.parseAboutResponse(rawText)

    /**
     * What to store from one About call: [parseReply], without the AI's paragraph when the service's own notes
     * are the paragraph ([hasServiceNotes]). Not [AlbumAboutReply.answered] = a failure: store nothing.
     */
    fun storedReply(rawText: String, hasServiceNotes: Boolean): AlbumAboutReply {
        val reply = parseReply(rawText)
        return if (hasServiceNotes) reply.copy(blurb = null) else reply
    }

    /** The `[CREDITS]` JSON alone (fences and prose around it tolerated). */
    fun parseCredits(text: String?): AlbumAiCredits = AlbumAboutPrompts.parseCredits(text)

    /** A value as shown, or null when it is blank or a placeholder ("N/A", "unknown", "未知"…). */
    fun cleanValue(value: String): String? = AlbumAboutPrompts.cleanValue(value)

    /** One paragraph: line breaks folded (no space between CJK sentences), markdown bold and "N/A" dropped. */
    fun cleanBlurb(text: String?): String? = AlbumAboutPrompts.cleanBlurb(text)

    /** The credits as stored in the `canonical` / `credits` row ([AlbumAiCredits.encode]). */
    fun encodeCredits(credits: AlbumAiCredits): String = credits.encode()

    fun decodeCredits(text: String?): AlbumAiCredits = AlbumAiCredits.decode(text)

    /**
     * The key of an ask row: the question trimmed, lower-cased, "(feat. …)" dropped, spaces folded. The same
     * key as the song About's (Android `SongAboutEntry.normalize`), so asking again replaces the row.
     */
    fun questionKey(question: String): String =
        question.trim()
            .lowercase()
            .replace(FeatSuffix, "")
            .replace(Whitespace, " ")
            .trim()

    private val FeatSuffix = Regex("\\s*\\(feat\\.[^)]*\\)", RegexOption.IGNORE_CASE)
    private val Whitespace = Regex("\\s+")

    /** One album's stored rows as the page reads them. */
    fun content(rows: List<AlbumAboutRow>): AlbumAboutContent {
        val canonical = rows.filter { it.kind == KIND_CANONICAL }.associateBy { it.entryKey }
        return AlbumAboutContent(
            credits = AlbumAiCredits.decode(canonical[CANON_CREDITS]?.answerText),
            blurb = canonical[CANON_BLURB]?.answerText?.trim()?.takeIf(String::isNotEmpty),
            asks = rows
                .filter { it.kind == KIND_ASK && it.answerText.isNotBlank() }
                .sortedByDescending { it.createdAt }
                .map { row ->
                    AlbumAboutAsk(
                        question = row.promptText?.takeIf(String::isNotBlank) ?: row.entryKey,
                        title = row.titleText?.takeIf(String::isNotBlank),
                        answer = row.answerText.trim(),
                    )
                },
            generated = canonical.isNotEmpty(),
        )
    }

    // ── the credits line ─────────────────────────────────────────────

    /** Between items: a no-break space, the dot, a breakable space. */
    const val creditItemSeparator = AlbumCredits.ITEM_SEPARATOR

    /**
     * The line's items for one album, in order: the service's labels ([sourceLabels]) before the AI's; the
     * service's first-release date before the AI's, either only for a reissue. Dates are ISO ("2014-05-20",
     * "2014-05", "2014").
     */
    fun creditItems(
        sourceLabels: List<String>,
        releaseDate: String?,
        originalReleaseDate: String?,
        ai: AlbumAiCredits,
    ): List<AlbumCreditItem> = AlbumCredits.items(sourceLabels, releaseDate, originalReleaseDate, ai)

    /**
     * The line, or null when nothing is known. [dateText] is the service-dated first release
     * ([AlbumCreditItem.date]) in the platform's date style; null shows the ISO date.
     */
    fun creditsLine(items: List<AlbumCreditItem>, labels: AlbumCreditLabels, dateText: String?): AlbumCreditsLine? =
        AlbumCredits.line(items, labels, dateText)

    /** One item's value (no label); null when it holds nothing to show. */
    fun creditValue(item: AlbumCreditItem, labels: AlbumCreditLabels, dateText: String?): String? =
        AlbumCredits.value(item, labels, dateText)

    /** One item as the line writes it: the label glued to its value, the value's own break rules. */
    fun creditItemText(label: String, value: String): String = AlbumCredits.itemText(label, value)

    /** A label that never breaks: no-break spaces, word joiners between CJK characters. */
    fun unbreakable(label: String): String = AlbumCredits.unbreakable(label)

    /** A line's text cut where it may break ([AlbumCreditsLine.segments]). */
    fun creditSegments(text: String): List<String> = AlbumCredits.segments(text)

    // ── Apple Music's notes ──────────────────────────────────────────

    /** Apple Music's `editorialNotes`: `standard`, else `short`, as plain text; null when neither has words. */
    fun editorialNotes(standard: String?, short: String?): String? = EditorialNotes.pick(standard, short)

    /** Light HTML as plain text (breaks and paragraphs kept, tags dropped, entities decoded); null when empty. */
    fun plainTextFromHtml(html: String): String? = EditorialNotes.plainTextFromHtml(html)

    // ── the AI service's name in copy ────────────────────────────────

    /**
     * Copy with the AI service's name filled in (`%1$s`), the space before or after a Chinese name dropped:
     * "问 %1$s" + 千问 → "问千问", + Gemini → "问 Gemini". The names themselves are each platform's strings:
     * Gemini, DeepSeek, Qwen (千问 in Chinese), and "AI" for a custom endpoint.
     */
    fun aiCopy(template: String, name: String): String = tightenCjkSpaces(template.replace("%1\$s", name))

    /** A single space between two CJK characters dropped (where a filled-in name is itself Chinese). */
    fun tightenCjkSpaces(text: String): String = CjkSpace.replace(text, "$1$2")

    private val CjkSpace = Regex("([\\u3000-\\u9FFF\\uFF00-\\uFFEF]) ([\\u3000-\\u9FFF\\uFF00-\\uFFEF])")
}
