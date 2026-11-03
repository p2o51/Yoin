package com.gpo.yoin.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Rewrites a signed Subsonic `stream.view` url from
 * [SubsonicApiFactory.buildStreamUrl] at the moment the player opens it: the
 * transcode a network asks for, and where a transcoded stream starts. The
 * token signs the password, not the query, so the signature stays valid.
 */
object SubsonicStreamUrls {
    /** The song a stream [url] plays, or null when [url] is not a signed Subsonic stream. */
    fun songIdOf(url: String): String? {
        val parsed = streamUrl(url) ?: return null
        return parsed.queryParameter(ID)
    }

    /**
     * [url] asking the server for [format] at no more than [maxBitRateKbps],
     * starting [timeOffsetSeconds] in when it is above zero. Null
     * [maxBitRateKbps] is the original file: every transcode parameter goes.
     * [estimateContentLength] asks Navidrome for a Content-Length (duration ×
     * bit rate), without which a transcode has no length, so no duration and
     * no seeking; other servers ignore it. Returns [url] unchanged when it is
     * not a signed Subsonic stream.
     */
    fun withTranscode(
        url: String,
        maxBitRateKbps: Int?,
        format: String?,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = false,
    ): String {
        val parsed = streamUrl(url) ?: return url
        return parsed.newBuilder()
            .apply { TranscodeParameters.forEach(::removeAllQueryParameters) }
            .apply {
                if (maxBitRateKbps != null) {
                    addQueryParameter(MAX_BIT_RATE, maxBitRateKbps.toString())
                    format?.let { addQueryParameter(FORMAT, it) }
                    if (timeOffsetSeconds > 0) addQueryParameter(TIME_OFFSET, timeOffsetSeconds.toString())
                    if (estimateContentLength) addQueryParameter(ESTIMATE_CONTENT_LENGTH, "true")
                }
            }
            .build()
            .toString()
    }

    /**
     * `getOpenSubsonicExtensions` on the server and with the signature of the
     * stream [url]; null when [url] is not a signed Subsonic stream.
     */
    fun extensionsUrl(url: String): String? {
        val parsed = streamUrl(url) ?: return null
        val segments = parsed.pathSegments
        return parsed.newBuilder()
            .removePathSegment(segments.lastIndex)
            .addPathSegment(EXTENSIONS_ENDPOINT)
            .apply { (TranscodeParameters + ID).forEach(::removeAllQueryParameters) }
            .build()
            .toString()
    }

    /** The server a stream [url] is on (scheme, host, port, path before `/rest/`): what traits are kept by. */
    fun serverKey(url: String): String? {
        val parsed = streamUrl(url) ?: return null
        val restIndex = parsed.pathSegments.lastIndexOf(REST)
        return parsed.newBuilder()
            .query(null)
            .apply { repeat(parsed.pathSegments.size - restIndex) { removePathSegment(restIndex) } }
            .build()
            .toString()
    }

    private fun streamUrl(url: String): HttpUrl? {
        if (STREAM_MARKER !in url) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        val segments = parsed.pathSegments
        if (segments.size < 2 || segments[segments.lastIndex - 1] != REST) return null
        if (segments.last() != STREAM_ENDPOINT && segments.last() != STREAM_ENDPOINT_BARE) return null
        if (parsed.queryParameter("t") == null || parsed.queryParameter(ID) == null) return null
        return parsed
    }

    private const val REST = "rest"
    private const val STREAM_ENDPOINT = "stream.view"
    private const val STREAM_ENDPOINT_BARE = "stream"
    private const val STREAM_MARKER = "/rest/stream"
    private const val EXTENSIONS_ENDPOINT = "getOpenSubsonicExtensions.view"
    private const val ID = "id"
    private const val MAX_BIT_RATE = "maxBitRate"
    private const val FORMAT = "format"
    private const val TIME_OFFSET = "timeOffset"
    private const val ESTIMATE_CONTENT_LENGTH = "estimateContentLength"
    private val TranscodeParameters = listOf(MAX_BIT_RATE, FORMAT, TIME_OFFSET, ESTIMATE_CONTENT_LENGTH)
}
