package com.gpo.yoin.shared.data.lyrics

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Small multiplatform replacements for the java.* text / URL / cache bits the
 * Android lyrics code leans on (Character.UnicodeScript, codePointAt,
 * URLEncoder / URLDecoder, LinkedHashMap LRU, HttpUrl).
 */

// ── code points ──────────────────────────────────────────────────────────────

internal inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            action(0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00))
            i += 2
        } else {
            action(c.code)
            i += 1
        }
    }
}

internal fun codePointString(cp: Int): String = if (cp < 0x10000) {
    cp.toChar().toString()
} else {
    val v = cp - 0x10000
    charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
}

internal fun codePointCount(text: String): Int {
    var n = 0
    forEachCodePoint(text) { n += 1 }
    return n
}

/** The first [max] code points of [text]. */
internal fun takeCodePoints(text: String, max: Int): String {
    var n = 0
    var i = 0
    while (i < text.length && n < max) {
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        n += 1
    }
    return text.substring(0, i)
}

private fun isSupplementaryHan(cp: Int): Boolean =
    cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2EBEF || cp in 0x2F800..0x2FA1F || cp in 0x30000..0x323AF

internal fun isLetterCp(cp: Int): Boolean =
    if (cp < 0x10000) cp.toChar().isLetter() else isSupplementaryHan(cp)

internal fun isLetterOrDigitCp(cp: Int): Boolean =
    if (cp < 0x10000) cp.toChar().isLetterOrDigit() else isSupplementaryHan(cp)

internal fun containsHan(text: String): Boolean {
    var found = false
    forEachCodePoint(text) { cp -> if (!found && scriptOf(cp) == Script.HAN) found = true }
    return found
}

// ── scripts ──────────────────────────────────────────────────────────────────

/**
 * The writing systems the matcher tells apart. Covers what Java's
 * `Character.UnicodeScript` distinguishes for the names we see in practice;
 * [OTHER] lumps the rest, [COMMON] is digits, punctuation, `ー`, `・`, …
 */
internal enum class Script { COMMON, LATIN, HAN, HIRAGANA, KATAKANA, HANGUL, CYRILLIC, GREEK, ARABIC, HEBREW, THAI, DEVANAGARI, OTHER }

internal fun scriptOf(cp: Int): Script = when {
    cp < 0x41 -> Script.COMMON
    cp in 0x41..0x5A || cp in 0x61..0x7A -> Script.LATIN
    cp == 0xAA || cp == 0xBA -> Script.LATIN
    cp in 0xC0..0x24F && cp != 0xD7 && cp != 0xF7 -> Script.LATIN
    cp in 0x250..0x2AF -> Script.LATIN
    cp in 0x1E00..0x1EFF || cp in 0x2C60..0x2C7F || cp in 0xA720..0xA7FF -> Script.LATIN
    cp in 0xFF21..0xFF3A || cp in 0xFF41..0xFF5A -> Script.LATIN
    cp in 0x370..0x3FF && cp != 0x37E && cp != 0x385 && cp != 0x387 || cp in 0x1F00..0x1FFF -> Script.GREEK
    cp in 0x400..0x52F || cp in 0x1C80..0x1C8F || cp in 0x2DE0..0x2DFF || cp in 0xA640..0xA69F -> Script.CYRILLIC
    cp in 0x590..0x5FF || cp in 0xFB1D..0xFB4F -> Script.HEBREW
    cp in 0x600..0x6FF && cp != 0x60C && cp != 0x61B && cp != 0x61F && cp != 0x640 || cp in 0x750..0x77F ||
        cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF -> Script.ARABIC
    cp in 0x900..0x97F -> Script.DEVANAGARI
    cp in 0xE01..0xE5B && cp != 0xE3F -> Script.THAI
    cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xA960..0xA97F || cp in 0xAC00..0xD7FF -> Script.HANGUL
    cp in 0x3041..0x3096 || cp in 0x309D..0x309F -> Script.HIRAGANA
    cp in 0x30A1..0x30FA || cp in 0x30FD..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9D -> Script.KATAKANA
    cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0x2E80..0x2FDF ||
        cp == 0x3005 || cp == 0x3007 || cp in 0x3021..0x3029 || cp in 0x3038..0x303B || isSupplementaryHan(cp) -> Script.HAN
    cp < 0x2B0 -> Script.COMMON
    // Punctuation, symbols, CJK punctuation (`・` `ー` `、`), fullwidth punctuation, emoji: Unicode "Common".
    cp in 0x2000..0x2BFF || cp in 0x3000..0x30FF || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF65 ||
        cp in 0x1F000..0x1FAFF -> Script.COMMON
    else -> Script.OTHER
}

internal fun isLatinCp(cp: Int): Boolean = scriptOf(cp) == Script.LATIN

/** [cp] as a plain letter: for ASCII itself, else its NFD base letter with combining marks dropped. */
internal fun baseLatin(cp: Int): String {
    if (cp < 0x80) return codePointString(cp)
    val decomposed = normalizeNfd(codePointString(cp))
    return decomposed.filter { it.category != CharCategory.NON_SPACING_MARK }
}

// ── percent-encoding (java.net.URLEncoder / URLDecoder, UTF-8) ───────────────

private const val HEX = "0123456789ABCDEF"

internal fun urlEncodeForm(text: String): String {
    val out = StringBuilder()
    for (b in text.encodeToByteArray()) {
        val v = b.toInt() and 0xFF
        val c = v.toChar()
        when {
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '*' || c == '_' -> out.append(c)
            c == ' ' -> out.append('+')
            else -> out.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
    }
    return out.toString()
}

/** Throws [IllegalArgumentException] on a truncated or non-hex `%` escape, like `URLDecoder`. */
internal fun urlDecodeForm(text: String): String {
    val out = StringBuilder()
    val pending = ArrayList<Byte>()
    fun flush() {
        if (pending.isNotEmpty()) {
            out.append(pending.toByteArray().decodeToString())
            pending.clear()
        }
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when (c) {
            '%' -> {
                require(i + 2 < text.length) {
                    "Incomplete trailing escape (%) pattern"
                }
                val hi = hexValue(text[i + 1])
                val lo = hexValue(text[i + 2])
                require(hi >= 0 && lo >= 0) { "Illegal hex characters in escape (%) pattern" }
                pending += ((hi shl 4) or lo).toByte()
                i += 3
            }
            else -> {
                flush()
                out.append(if (c == '+') ' ' else c)
                i += 1
            }
        }
    }
    flush()
    return out.toString()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

// ── urls ─────────────────────────────────────────────────────────────────────

/** An absolute http(s) URL with a host, or null (OkHttp's `toHttpUrlOrNull`). */
internal fun parseHttpUrl(text: String): Url? = try {
    // Ktor's parser is lenient ("not a url" parses as a relative path); OkHttp's insists on a scheme.
    require(text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true))
    val url = URLBuilder(text).build()
    val protocol = url.protocol
    if ((protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS) && url.host.isNotEmpty()) url else null
} catch (e: Exception) {
    null
}

// ── bounded LRU ──────────────────────────────────────────────────────────────

/** Access-ordered bounded map, safe for concurrent coroutines. State belongs to its owner instance. */
internal class LruMemory<K : Any, V : Any>(private val capacity: Int) {
    private val mutex = Mutex()
    private val map = LinkedHashMap<K, V>()

    suspend fun remember(key: K, value: V) = mutex.withLock {
        map.remove(key)
        map[key] = value
        while (map.size > capacity) map.remove(map.keys.first())
    }

    suspend fun recall(key: K): V? = mutex.withLock {
        val value = map.remove(key) ?: return@withLock null
        map[key] = value
        value
    }

    suspend fun forget(key: K) {
        mutex.withLock { map.remove(key) }
    }
}
