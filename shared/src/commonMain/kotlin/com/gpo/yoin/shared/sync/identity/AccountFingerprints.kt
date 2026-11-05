package com.gpo.yoin.shared.sync.identity

import com.gpo.yoin.shared.data.lyrics.normalizeNfkc
import com.gpo.yoin.shared.sync.CanonicalJson

/**
 * Account fingerprints (fp:v1): the provider-level identity of a music
 * account, computed locally from credentials. Fingerprints only MATCH
 * accounts; a binding's sync id is minted from one once ([deterministicId])
 * and then frozen. Never change the v1 normalization: every user's existing
 * bindings and other devices' auto-binds depend on it byte for byte.
 *
 * Port of Android's `data/sync/identity/AccountFingerprints.kt`, which parses
 * the address with OkHttp's HttpUrl. [ServerAddress] reproduces the parts of
 * HttpUrl the fingerprint reads (scheme gate, host canonicalisation, default
 * ports, path dot-segments and percent-encoding). Hosts follow OkHttp 5's
 * `toCanonicalHost`: IPv6 literals in their compressed form (an IPv4-mapped
 * one as dotted IPv4), names mapped and punycode-encoded per label, a
 * trailing dot kept. The name mapping is NFKC + lowercase + the ideographic
 * full stop, which covers what real server names use; OkHttp's full UTS #46
 * table also drops "ignored" code points and refuses disallowed ones.
 */
object AccountFingerprints {
    const val SUBSONIC_V1_PREFIX = "subsonic:v1:"
    const val SPOTIFY_V1_PREFIX = "spotify:v1:"
    private const val SEPARATOR = '\u001F'
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * Subsonic: host (lowercased) + non-default port + path without trailing
     * "/", then the trimmed lowercased username. Scheme, userinfo, query and
     * fragment are dropped, so http/https or a pasted "user:pass@" never split
     * one server. Null when the address can't be parsed or the username is blank.
     */
    fun subsonic(serverUrl: String, username: String): String? {
        val url = parseServerUrl(serverUrl) ?: return null
        val user = username.trim().lowercase().takeIf(String::isNotEmpty) ?: return null
        return SUBSONIC_V1_PREFIX + url.hostPort() + url.encodedPath.trimEnd('/') + SEPARATOR + user
    }

    fun spotify(userId: String): String = SPOTIFY_V1_PREFIX + userId

    /** Sync id minted at first bind: "s-" + the first 24 hex chars of sha256(fingerprint). */
    fun deterministicId(fingerprint: String): String = "s-" + CanonicalJson.sha256Hex(fingerprint).take(24)

    /** What a binding stores (and compares) instead of the fingerprint itself. */
    fun fingerprintHash(fingerprint: String): String = CanonicalJson.sha256Hex(fingerprint)

    /**
     * Display hint for the account descriptor: "<username> @ <host[:port]>".
     * No scheme, path, userinfo or query, so nothing that could carry a secret.
     */
    fun subsonicHint(serverUrl: String, username: String): String? {
        val url = parseServerUrl(serverUrl) ?: return null
        val user = username.trim().takeIf(String::isNotEmpty) ?: return null
        return "$user @ ${url.hostPort()}"
    }

    private fun parseServerUrl(serverUrl: String): ServerAddress? {
        val trimmed = serverUrl.trim().takeIf(String::isNotEmpty) ?: return null
        val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return ServerAddress.parse(withScheme)
    }
}

/** The HttpUrl subset fingerprints need: http(s) scheme, canonical host, port, encoded path. */
internal class ServerAddress private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    val encodedPath: String,
) {
    fun hostPort(): String {
        val h = if (':' in host) "[$host]" else host
        return if (port == defaultPort(scheme)) h else "$h:$port"
    }

    companion object {
        fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

        fun parse(input: String): ServerAddress? {
            val colon = input.indexOf(':')
            if (colon <= 0) return null
            val scheme = input.substring(0, colon).lowercase()
            if (scheme != "http" && scheme != "https") return null
            var pos = colon + 1
            // HttpUrl skips any run of slashes / backslashes after the scheme.
            while (pos < input.length && (input[pos] == '/' || input[pos] == '\\')) pos++
            val authorityEnd = input.indexOfAny(charArrayOf('/', '\\', '?', '#'), pos).let { if (it < 0) input.length else it }
            val authority = input.substring(pos, authorityEnd)
            val hostPort = authority.substringAfterLast('@')
            val (rawHost, port) = splitHostPort(hostPort, scheme) ?: return null
            val host = canonicalHost(rawHost) ?: return null
            val rest = input.substring(authorityEnd)
            val pathEnd = rest.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) rest.length else it }
            val path = encodedPath(rest.substring(0, pathEnd))
            return ServerAddress(scheme, host, port, path)
        }

        private fun splitHostPort(text: String, scheme: String): Pair<String, Int>? {
            val portColon = if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                if (close + 1 < text.length && text[close + 1] != ':') return null
                if (close + 1 < text.length) close + 1 else -1
            } else {
                text.indexOf(':')
            }
            if (portColon < 0) return text to defaultPort(scheme)
            val portText = text.substring(portColon + 1)
            val port = portText.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 5 }?.toInt()
                ?.takeIf { it in 1..65535 } ?: return null
            return text.substring(0, portColon) to port
        }

        /** OkHttp `toCanonicalHost`: an IPv6 literal (with or without brackets), else an IDN name. */
        private fun canonicalHost(raw: String): String? {
            val decoded = percentDecode(raw) ?: return null
            if (':' in decoded) {
                val bracketed = decoded.startsWith("[") && decoded.endsWith("]")
                val address = (if (bracketed) Ipv6.decode(decoded, 1, decoded.length - 1) else Ipv6.decode(decoded, 0, decoded.length))
                    ?: return null
                return Ipv6.toAscii(address)
            }
            val host = Idna.toAscii(decoded) ?: return null
            if (host.isEmpty()) return null
            if (host.any { it.code <= 0x20 || it.code >= 0x7f || it in FORBIDDEN_HOST_CHARS }) return null
            if (invalidLabelLengths(host)) return null
            return host
        }

        private val FORBIDDEN_HOST_CHARS = "#%/:?@[\\]".toSet()

        /** Each label 1..63, the whole 1..253; one trailing '.' allowed (OkHttp). */
        private fun invalidLabelLengths(host: String): Boolean {
            if (host.length !in 1..253) return true
            var start = 0
            while (true) {
                val dot = host.indexOf('.', start)
                val length = if (dot == -1) host.length - start else dot - start
                if (length !in 1..63) return true
                if (dot == -1 || dot == host.length - 1) return false
                start = dot + 1
            }
        }

        private fun percentDecode(text: String): String? {
            if ('%' !in text) return text
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (value != null) {
                        bytes += value.toByte()
                        i += 3
                        continue
                    }
                }
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }

        /** HttpUrl's path canonicalisation: "\" = "/", dot segments resolved, PATH_SEGMENT_ENCODE_SET escaped. */
        private fun encodedPath(raw: String): String {
            if (raw.isEmpty()) return "/"
            val segments = ArrayList<String>()
            val parts = raw.replace('\\', '/').split('/').drop(1)
            parts.forEachIndexed { index, part ->
                val last = index == parts.lastIndex
                when (part.lowercase()) {
                    ".", "%2e" -> if (last) segments += ""
                    "..", "%2e.", ".%2e", "%2e%2e" -> {
                        if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                        if (last) segments += ""
                    }
                    else -> segments += encodeSegment(part)
                }
            }
            return "/" + segments.joinToString("/")
        }

        private const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"

        private fun encodeSegment(segment: String): String = buildString {
            var i = 0
            val hex = "0123456789ABCDEF"
            while (i < segment.length) {
                val c = segment[i]
                val alreadyEscaped = c == '%' && i + 2 < segment.length &&
                    segment.substring(i + 1, i + 3).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                if (alreadyEscaped) {
                    append(segment, i, i + 3)
                    i += 3
                    continue
                }
                if (c == '%' || c.code < 0x20 || c.code >= 0x7f || c in PATH_SEGMENT_ENCODE_SET) {
                    val end = if (c.isHighSurrogate() && i + 1 < segment.length) i + 2 else i + 1
                    for (b in segment.substring(i, end).encodeToByteArray()) {
                        val v = b.toInt() and 0xff
                        append('%').append(hex[v ushr 4]).append(hex[v and 0x0f])
                    }
                    i = end
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

/** OkHttp's IPv6 literal parsing and canonical (RFC 5952) text. */
internal object Ipv6 {
    /** 16 bytes, or null when [input] in [pos, limit) isn't an IPv6 address. */
    fun decode(input: String, pos: Int, limit: Int): ByteArray? {
        val address = ByteArray(16)
        var b = 0
        var compress = -1
        var groupOffset = -1
        var i = pos
        while (i < limit) {
            if (b == address.size) return null
            if (i + 2 <= limit && input.startsWith("::", i)) {
                if (compress != -1) return null
                i += 2
                b += 2
                compress = b
                if (i == limit) break
            } else if (b != 0) {
                if (input.startsWith(":", i)) {
                    i++
                } else if (input.startsWith(".", i)) {
                    // A dotted IPv4 tail: parse the previous group again as its first octet.
                    if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) return null
                    b += 2
                    break
                } else {
                    return null
                }
            }
            var value = 0
            groupOffset = i
            while (i < limit) {
                val digit = input[i].digitToIntOrNull(16) ?: break
                value = (value shl 4) + digit
                i++
            }
            val groupLength = i - groupOffset
            if (groupLength == 0 || groupLength > 4) return null
            address[b++] = (value ushr 8 and 0xff).toByte()
            address[b++] = (value and 0xff).toByte()
        }
        if (b != address.size) {
            if (compress == -1) return null
            address.copyInto(address, address.size - (b - compress), compress, b)
            address.fill(0, compress, compress + (address.size - b))
        }
        return address
    }

    private fun decodeIpv4Suffix(input: String, pos: Int, limit: Int, address: ByteArray, addressOffset: Int): Boolean {
        var b = addressOffset
        var i = pos
        while (i < limit) {
            if (b == address.size) return false
            if (b != addressOffset) {
                if (input[i] != '.') return false
                i++
            }
            var value = 0
            val groupOffset = i
            while (i < limit) {
                val c = input[i]
                if (c < '0' || c > '9') break
                if (value == 0 && groupOffset != i) return false // no leading zeros
                value = value * 10 + (c - '0')
                if (value > 255) return false
                i++
            }
            if (i == groupOffset) return false
            address[b++] = value.toByte()
        }
        return b == addressOffset + 4
    }

    /** Lowercase hex groups, the longest run (2+ groups, first wins) of zeros as "::"; an IPv4-mapped one as IPv4. */
    fun toAscii(address: ByteArray): String {
        val mapped = (0 until 10).all { address[it] == 0.toByte() } &&
            address[10] == 0xff.toByte() && address[11] == 0xff.toByte()
        if (mapped) return (12 until 16).joinToString(".") { (address[it].toInt() and 0xff).toString() }
        var longestRunOffset = -1
        var longestRunLength = 0
        var i = 0
        while (i < address.size) {
            val runOffset = i
            while (i < 16 && address[i] == 0.toByte() && address[i + 1] == 0.toByte()) i += 2
            val runLength = i - runOffset
            if (runLength > longestRunLength && runLength >= 4) {
                longestRunOffset = runOffset
                longestRunLength = runLength
            }
            i += 2
        }
        val out = StringBuilder()
        i = 0
        while (i < address.size) {
            if (i == longestRunOffset) {
                out.append(':')
                i += longestRunLength
                if (i == 16) out.append(':')
            } else {
                if (i > 0) out.append(':')
                val group = (address[i].toInt() and 0xff shl 8) or (address[i + 1].toInt() and 0xff)
                out.append(group.toString(16))
                i += 2
            }
        }
        return out.toString()
    }
}

/** Host names to ASCII as OkHttp's `idnToAscii` does for real names: mapped, then punycode per label. */
internal object Idna {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 0x80
    private const val PREFIX = "xn--"

    fun toAscii(host: String): String? {
        val labels = map(host).split('.')
        val out = ArrayList<String>(labels.size)
        for (label in labels) {
            if (label.all { it.code < 0x80 }) {
                if (label.startsWith(PREFIX) && decode(label.substring(PREFIX.length)) == null) return null
                out += label
            } else {
                out += PREFIX + encode(codePoints(label))
            }
        }
        return out.joinToString(".")
    }

    /** UTS #46 mapping, as far as names use it: compatibility forms, lowercase, ideographic full stops. */
    private fun map(host: String): String {
        val nfkc = normalizeNfkc(host)
        return buildString {
            for (c in nfkc) {
                when (c) {
                    '。', '．', '｡' -> append('.')
                    // UTS #46 "ignored": soft hyphen, zero-width space, word joiner, BOM.
                    '­', '​', '⁠', '﻿' -> Unit
                    // Per character, so a final Σ maps to σ as UTS #46 does (not to ς).
                    else -> append(c.lowercase())
                }
            }
        }
    }

    private fun codePoints(s: String): List<Int> {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out += ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun adapt(delta: Int, numPoints: Int, first: Boolean): Int {
        var d = if (first) delta / DAMP else delta / 2
        d += d / numPoints
        var k = 0
        while (d > ((BASE - TMIN) * TMAX) / 2) {
            d /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * d / (d + SKEW)
    }

    private fun threshold(k: Int, bias: Int): Int = when {
        k <= bias -> TMIN
        k >= bias + TMAX -> TMAX
        else -> k - bias
    }

    /** RFC 3492 encode (the label holds a non-ASCII code point). */
    fun encode(input: List<Int>): String {
        val out = StringBuilder()
        for (c in input) if (c < 0x80) out.append(c.toChar())
        val basic = out.length
        var handled = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        while (handled < input.size) {
            val m = input.filter { it >= n }.min()
            delta += (m - n) * (handled + 1)
            n = m
            for (c in input) {
                if (c < n) delta++
                if (c == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = threshold(k, bias)
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta, handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    /** RFC 3492 decode of an "xn--" label's tail; null when it isn't valid punycode (OkHttp refuses the host). */
    fun decode(text: String): List<Int>? {
        val lastDash = text.lastIndexOf('-')
        val out = ArrayList<Int>()
        if (lastDash > 0) {
            for (c in text.substring(0, lastDash)) {
                if (c.code >= 0x80) return null
                out += c.code
            }
        }
        var pos = if (lastDash > 0) lastDash + 1 else 0
        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS
        while (pos < text.length) {
            val oldI = i
            var w = 1
            var k = BASE
            while (true) {
                if (pos >= text.length) return null
                val c = text[pos++]
                val d = when (c) {
                    in 'a'..'z' -> c - 'a'
                    in 'A'..'Z' -> c - 'A'
                    in '0'..'9' -> c - '0' + 26
                    else -> return null
                }
                if (d > (Int.MAX_VALUE - i) / w) return null
                i += d * w
                val t = threshold(k, bias)
                if (d < t) break
                if (w > Int.MAX_VALUE / (BASE - t)) return null
                w *= BASE - t
                k += BASE
            }
            bias = adapt(i - oldI, out.size + 1, oldI == 0)
            if (i / (out.size + 1) > 0x10FFFF - n) return null
            n += i / (out.size + 1)
            i %= out.size + 1
            if (n > 0x10FFFF) return null
            out.add(i, n)
            i++
        }
        return out
    }
}
