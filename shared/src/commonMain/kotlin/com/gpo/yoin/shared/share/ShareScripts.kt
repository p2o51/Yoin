package com.gpo.yoin.shared.share

/**
 * The 竖排 gate's script test, table-driven so every platform answers alike ([ShareScriptTables], generated from
 * the JVM's `Character.isLetter` / `Character.UnicodeScript`, which Android used before the move — the
 * androidHostTest `ShareScriptsTableTest` checks every code point against it).
 */
internal object ShareScripts {

    /**
     * Whether [text] is CJK all through: at least one Han / Hiragana / Katakana / Hangul / Bopomofo letter and no
     * letter of any other script. Digits, punctuation, symbols and spaces don't count either way. Script, not
     * Script_Extensions: the prolonged sound mark ー (U+30FC) and the halfwidth ｰ ﾞ ﾟ are Common-script letters,
     * so a line with one is not CJK (pinned as Android had it).
     */
    fun isCjk(text: String): Boolean {
        var cjk = false
        var i = 0
        while (i < text.length) {
            val cp = codePointAt(text, i)
            i += if (cp > 0xFFFF) 2 else 1
            if (!isLetter(cp)) continue
            if (isCjkLetter(cp)) cjk = true else return false
        }
        return cjk
    }

    /** `Character.isLetter(cp)`. */
    fun isLetter(cp: Int): Boolean = inRanges(ShareScriptTables.letters, cp)

    /** A letter of the Han, Hiragana, Katakana, Hangul or Bopomofo script. */
    fun isCjkLetter(cp: Int): Boolean = inRanges(ShareScriptTables.cjkLetters, cp)

    /** Whether any UTF-16 unit of [text] is a letter on its own (a surrogate half never is). */
    fun hasLetterUnit(text: String): Boolean = text.any { isLetter(it.code) }

    /** The code point at [index], a well-formed surrogate pair read as one (as `String.codePointAt`). */
    private fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
        return high.code
    }

    private fun inRanges(ranges: IntArray, cp: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                cp < ranges[mid * 2] -> high = mid - 1
                cp > ranges[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}
