package com.gpo.yoin.shared.sync.identity

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
 * HttpUrl the fingerprint reads (scheme gate, host case, default ports, path
 * dot-segments and percent-encoding). Known gap: internationalised (non-ASCII)
 * host names are lowercased but not punycode-encoded as HttpUrl does.
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

        private fun canonicalHost(raw: String): String? {
            val decoded = percentDecode(raw) ?: return null
            if (decoded.startsWith("[") && decoded.endsWith("]")) {
                val inner = decoded.substring(1, decoded.length - 1).lowercase()
                if (inner.isEmpty() || inner.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return null
                if (':' !in inner) return null
                return inner
            }
            val host = decoded.lowercase().removeSuffix(".")
            if (host.isEmpty()) return null
            if (host.any { it.code <= 0x20 || it.code == 0x7f || it in FORBIDDEN_HOST_CHARS }) return null
            if (host.length > 253) return null
            return host
        }

        private val FORBIDDEN_HOST_CHARS = "#%/:?@[\\]".toSet()

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
