package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.sync.CanonicalJson
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Account fingerprints (fp:v1): the provider-level identity of a music
 * account, computed locally from credentials. Fingerprints only MATCH
 * accounts; a binding's sync id is minted from one once ([deterministicId])
 * and then frozen. Never change the v1 normalization: every user's existing
 * bindings and other devices' auto-binds depend on it byte for byte.
 */
object AccountFingerprints {
    const val SUBSONIC_V1_PREFIX = "subsonic:v1:"
    const val SPOTIFY_V1_PREFIX = "spotify:v1:"
    private const val SEPARATOR = '\u001F'
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /**
     * Subsonic: host (lowercased/punycode by HttpUrl) + non-default port +
     * path without trailing "/", then the trimmed lowercased username.
     * Scheme, userinfo, query and fragment are dropped, so http/https or a
     * pasted "user:pass@" never split one server. Null when the address
     * can't be parsed or the username is blank.
     */
    fun subsonic(serverUrl: String, username: String): String? {
        val url = parseServerUrl(serverUrl) ?: return null
        val user = username.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty) ?: return null
        return SUBSONIC_V1_PREFIX + hostPort(url) + url.encodedPath.trimEnd('/') + SEPARATOR + user
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
        return "$user @ ${hostPort(url)}"
    }

    private fun parseServerUrl(serverUrl: String): HttpUrl? {
        val trimmed = serverUrl.trim().takeIf(String::isNotEmpty) ?: return null
        val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return withScheme.toHttpUrlOrNull()
    }

    private fun hostPort(url: HttpUrl): String {
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
    }
}
