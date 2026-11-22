package com.gpo.yoin.data.highlight

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * Typed structured-output schemas for ML Kit GenAI Prompt API 1.0.0-beta5+ (constrained decoding via AICore).
 *
 * Devices whose AICore reports `isStructuredOutputFeatureAvailable() == true` use these directly to avoid
 * malformed JSON or markdown-fence wrappers; older AICore builds fall back cleanly to raw text parsing.
 */
@Generable(description = "Two to three short follow-up questions grounded in the song About notes")
data class AboutFollowUpSchema(
    @Guide(
        description = "2 to 3 concise follow-up questions in the requested language, ending with a question mark",
        minItems = 2,
        maxItems = 3,
    )
    val questions: List<String>,
)

@Generable(description = "Three short daily greetings for morning, afternoon, and evening on the Home header")
data class HomeGreetingSchema(
    @Guide(description = "Short morning greeting (single line, no emojis)")
    val morning: String,
    @Guide(description = "Short afternoon greeting (single line, no emojis)")
    val afternoon: String,
    @Guide(description = "Short evening greeting (single line, no emojis)")
    val evening: String,
)

@Generable(description = "A 1-to-3 line lyric highlight range from a single stanza")
data class LyricHighlightSchema(
    @Guide(description = "1-based starting line number", minimum = 1.0, maximum = 90.0)
    val start: Int,
    @Guide(description = "1-based ending line number (start, start+1, or start+2)", minimum = 1.0, maximum = 90.0)
    val end: Int,
)
