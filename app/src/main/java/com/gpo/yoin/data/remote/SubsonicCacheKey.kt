package com.gpo.yoin.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The cache key of a signed Subsonic REST url, such as a cover from
 * [SubsonicApiFactory.buildCoverArtUrl]: the url without the parameters that
 * sign it ([SubsonicAuth]'s token and salt) or name the protocol (version,
 * client, format). The salt is drawn once per process, so the same cover's url
 * changes on every start, and a cache keyed on the url never finds what the
 * last process stored. The key keeps the server, the path, the user and the
 * resource's own parameters (id, size), so two accounts or two sizes never
 * share an entry, and it holds no credential.
 */
object SubsonicCacheKey {
    /** [url]'s key, or null when [url] is not a signed Subsonic REST url. */
    fun of(url: String): String? {
        if (REST_PATH_MARKER !in url) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (REST_SEGMENT !in parsed.pathSegments) return null
        if (parsed.queryParameter(TOKEN) == null || parsed.queryParameter(SALT) == null) return null
        return parsed.newBuilder()
            .apply { SIGNATURE_AND_PROTOCOL.forEach(::removeAllQueryParameters) }
            .build()
            .toString()
    }

    private const val REST_SEGMENT = "rest"
    private const val REST_PATH_MARKER = "/$REST_SEGMENT/"
    private const val TOKEN = "t"
    private const val SALT = "s"

    // What SubsonicApiFactory adds after the user: the signature, then the protocol.
    private val SIGNATURE_AND_PROTOCOL = listOf(TOKEN, SALT, "v", "c", "f")
}
