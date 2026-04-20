package com.gpo.yoin.data.lyrics

/**
 * QQ 音乐歌词偶尔会以「Latin-1 当 UTF-8 字节流误解码」的 mojibake 形式返回（例如
 * `Ã©` 实际是 UTF-8 的 `é`）。Spotoolfy 在 `qq_encoding.dart` 里用 codepoint 统计
 * 识别可疑字符，能复原就复原。这里直接 port。
 */
internal object QQEncoding {

    private val MOJIBAKE_INDICATORS = setOf(
        0x00B0, 0x00B1, 0x00B2, 0x00B3, 0x00B4, 0x00B5, 0x00B6, 0x00B7,
        0x00B8, 0x00B9, 0x00BA, 0x00BB, 0x00BC, 0x00BD, 0x00BE, 0x00BF,
        0x00C2, 0x00C3, 0x00D0, 0x00D1,
        0x00E2, 0x00E3, 0x00E4, 0x00E5, 0x00E6, 0x00E7, 0x00E8, 0x00E9,
        0x00EA, 0x00EB, 0x00EC, 0x00ED, 0x00EE, 0x00EF, 0x00F0, 0x00F1,
        0x00F2, 0x00F3, 0x00F4, 0x00F5, 0x00F6, 0x00F7, 0x00F8, 0x00F9,
        0x00FA, 0x00FB, 0x00FC, 0x00FD, 0x00FE, 0x00FF,
    )

    fun normalize(value: String): String {
        val originalScore = mojibakeScore(value)
        if (originalScore == 0) return value

        val decoded = tryDecodeLatin1AsUtf8(value) ?: return value
        val decodedScore = mojibakeScore(decoded)
        return if (decodedScore < originalScore) decoded else value
    }

    fun normalizeNullable(value: String?): String? {
        if (value.isNullOrBlank()) return value
        return normalize(value)
    }

    /**
     * 逐个 codepoint 扫描：凡 <= 0xFF 的进缓冲（代表「可能是被误解码的字节」），
     * 到遇到真·高位 codepoint（Emoji 或真中文）就先把缓冲当 Latin-1 字节再用
     * UTF-8 解回来。若整段确实产生了变化就返回新串，否则回退到原串。
     */
    private fun tryDecodeLatin1AsUtf8(value: String): String? {
        val out = StringBuilder()
        val chunk = StringBuilder()
        var changed = false

        fun flushChunk() {
            if (chunk.isEmpty()) return
            val source = chunk.toString()
            try {
                val bytes = source.toByteArray(Charsets.ISO_8859_1)
                val decoded = String(bytes, Charsets.UTF_8)
                out.append(decoded)
                if (decoded != source) changed = true
            } catch (_: Exception) {
                out.append(source)
            }
            chunk.setLength(0)
        }

        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (cp <= 0xFF) {
                chunk.appendCodePoint(cp)
            } else {
                flushChunk()
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        flushChunk()

        if (!changed) return if (out.isEmpty()) value else out.toString()
        return out.toString()
    }

    private fun mojibakeScore(value: String): Int {
        var score = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            when {
                MOJIBAKE_INDICATORS.contains(cp) -> score += 1
                cp in 0x80..0x9F -> score += 2
                cp == 0xFFFD -> score += 3
            }
            i += Character.charCount(cp)
        }
        return score
    }
}
