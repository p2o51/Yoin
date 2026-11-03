package com.gpo.yoin.shared.detail

/**
 * The album scrapbook's (page 2) irregular-but-stable tilts: every piece leans
 * by an angle picked from its set by a hash of "albumId/key", so the same album
 * always lays out the same way, on both apps.
 */
object ScrapbookTilt {
    // Tilt sets in degrees; no 0 (a 0° piece reads as an alignment slip).
    val PAPER = floatArrayOf(-1.5f, -1f, -0.5f, 0.5f, 1f, 1.5f)
    val CARD = floatArrayOf(-1f, -0.6f, 0.6f, 1f)
    val TICKET = floatArrayOf(-2f, -1.25f, 1.25f, 2f)
    val STICKER = floatArrayOf(-12f, -8f, -5f, 5f, 8f, 12f)
    val COVER = floatArrayOf(-3f, 3f)
    val TAG = floatArrayOf(-3f, -2f, 2f, 3f)

    /** 32-bit FNV-1a over the UTF-16 code units of [text], as an unsigned value. */
    fun hash(text: String): Long {
        var h = 0x811C9DC5.toInt()
        for (c in text) {
            h = h xor c.code
            h *= 16777619
        }
        return h.toLong() and 0xFFFFFFFFL
    }

    /** The tilt of piece [key] on album [seed]: an angle from [set]. */
    fun tilt(seed: String, key: String, set: FloatArray): Float = set[(hash("$seed/$key") % set.size).toInt()]

    /** Whether album [seed]'s clusters start mirrored (sides alternate from there). */
    fun startsMirrored(seed: String): Boolean = (hash(seed) and 1L) == 1L
}
