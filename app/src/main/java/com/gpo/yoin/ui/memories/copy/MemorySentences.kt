package com.gpo.yoin.ui.memories.copy

/**
 * Whole-sentence handling for every excerpt Memories shows. Ported from the
 * approved prototype (docs/handoff/memories-showcase/twostate4.html:
 * `sentences`, `joinS`, `wlen`). The card never cuts a sentence and never
 * shows an ellipsis, so everything that picks text works in whole sentences.
 */
object MemorySentences {
    private const val FULL_STOPS = "。！？!?"
    private const val CLOSERS = "。！？!?.”’」）)"

    /**
     * Splits on 。！？.!? A "." directly before a digit (8.5) is not a full
     * stop. Closing quotes and brackets after a stop stay with its sentence.
     */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            val next = text.getOrNull(i + 1)
            if (c in FULL_STOPS || (c == '.' && (next == null || next !in '0'..'9'))) {
                while (i + 1 < text.length && text[i + 1] in CLOSERS) {
                    i++
                    current.append(text[i])
                }
                current.toString().trim().takeIf(String::isNotEmpty)?.let(out::add)
                current.clear()
            }
            i++
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(out::add)
        return out
    }

    /**
     * Joins sentences back into running text: a space between two sentences,
     * none after CJK text or full-width punctuation.
     *
     * Deviation from the prototype: there a closing ” or 」 never takes a
     * space, so an English sentence ending in a quote ran into the next one
     * ("He said “stay.”Then…"). Here a closing quote takes the space unless the
     * character it closes on is CJK.
     */
    fun join(sentences: List<String>): String = sentences.fold("") { acc, sentence ->
        if (acc.isEmpty() || !acc.needsSpaceAfter()) acc + sentence else "$acc $sentence"
    }

    /**
     * Weighted length for the Medium teaser cap: a Han character (and CJK or
     * full-width punctuation) counts 1, anything else 0.5. Counted per code
     * point, so an emoji is one unit of 0.5.
     */
    fun weightedLength(text: String): Double {
        var total = 0.0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            total += if (codePoint in 0x2E80..0x9FFF || codePoint in 0xF900..0xFFEF) 1.0 else 0.5
            i += Character.charCount(codePoint)
        }
        return total
    }

    private fun String.needsSpaceAfter(): Boolean {
        val last = last()
        if (last.isCjkOrFullWidth()) return false
        if (last == '”') {
            val closed = dropLast(1).lastOrNull() ?: return true
            return !closed.isCjkOrFullWidth()
        }
        return true
    }

    private fun Char.isCjkOrFullWidth(): Boolean =
        this in '\u3000'..'\u303F' || this in '\uFF00'..'\uFFEF' || this in '\u3400'..'\u9FFF'
}
