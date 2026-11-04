package com.gpo.yoin.player

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.gpo.yoin.data.remote.SubsonicResponse
import com.gpo.yoin.data.remote.SubsonicResponseBody
import com.gpo.yoin.data.remote.SubsonicStreamUrls
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a server can do with a transcode, from `getOpenSubsonicExtensions`. */
data class SubsonicServerTraits(
    /** The format Yoin asks for: mp3 ([from]). */
    val transcodeFormat: String,
    /** OpenSubsonic `transcodeOffset`: `stream` takes `timeOffset`, so a transcode can start mid-song. */
    val timeOffset: Boolean,
) {
    companion object {
        /** A server that didn't answer: mp3 is every Subsonic server's transcode. */
        val Plain = SubsonicServerTraits(transcodeFormat = FORMAT_MP3, timeOffset = false)

        /**
         * mp3 on every server, Navidrome's opus included (measured 2026-11-03
         * on the Pixel Tablet against a Navidrome-shaped server): a transcode
         * has no byte ranges, and an Ogg stream finds a time by reading pages
         * at byte offsets, so every opus seek made the server transcode from
         * the start up to there, several times, and its duration came out
         * short. A constant-bit-rate mp3 maps a time to a byte, so a seek is
         * one `timeOffset` request and the duration is right.
         */
        fun from(body: SubsonicResponseBody): SubsonicServerTraits {
            val extensions = body.openSubsonicExtensions.map { it.name }.toSet()
            return SubsonicServerTraits(
                transcodeFormat = FORMAT_MP3,
                timeOffset = "transcodeOffset" in extensions,
            )
        }

        const val FORMAT_MP3 = "mp3"
    }
}

/** What the player asks the server for, for one song: the file, or a transcode. */
data class StreamDelivery(val maxBitRateKbps: Int?, val format: String?) {
    val transcoded: Boolean get() = maxBitRateKbps != null

    companion object {
        val Original = StreamDelivery(maxBitRateKbps = null, format = null)

        /**
         * The file when [quality] is Original, or when the file is already at
         * or under the cap ([sourceKbps]): the server would send it as it is,
         * and the file seeks by byte. [traits] is asked only for a transcode.
         */
        fun plan(quality: StreamQuality, sourceKbps: Int?, traits: () -> SubsonicServerTraits): StreamDelivery {
            val cap = quality.maxBitRateKbps ?: return Original
            if (sourceKbps != null && sourceKbps <= cap) return Original
            return StreamDelivery(cap, traits().transcodeFormat)
        }
    }
}

/**
 * Picks a Subsonic song's stream when the player opens it, not when it is
 * queued (owner task 2026-11-03): the network decides which of the two
 * [StreamQualitySettings] applies. A song keeps the stream it started with —
 * reopening it (a seek, a retry) never switches format mid-song — so a
 * network change takes effect from the next song. Only the local player reads
 * through this; a DLNA speaker gets the queued url, the original file.
 *
 * A transcode has no length of its own, so it asks for an estimated one
 * (Navidrome's `estimateContentLength`), which gives it a duration and lets
 * the extractor seek. An mp3 transcode is constant bit rate, so a byte
 * position is a time: a reopen past the start asks the server to start the
 * transcode there (`timeOffset`, where the server has `transcodeOffset`)
 * instead of transcoding from the start and throwing the bytes away.
 */
@OptIn(UnstableApi::class)
internal class SubsonicStreamResolver(
    private val settings: () -> StreamQualitySettings,
    private val isUnmetered: () -> Boolean,
    private val traitsFor: (streamUrl: String) -> SubsonicServerTraits,
    /** The file's own bit rate as the server lists it, by Subsonic song id; null when unknown. */
    private val sourceKbpsFor: (songRawId: String) -> Int? = { null },
    /** What the sound goes out through, for [StreamQuality.Auto]. */
    private val outputKind: () -> AudioOutputKind = { AudioOutputKind.Speaker },
) : ResolvingDataSource.Resolver {

    private val pinned = ConcurrentHashMap<String, StreamDelivery>()

    /** The stream each song now plays from, by Subsonic song id, for the format tag. */
    fun deliveryFor(songRawId: String): StreamDelivery? = pinned[songRawId]

    /** Forget every song's stream but [keepSongIds]': they choose again next time they open. */
    fun retainOnly(keepSongIds: Set<String>) {
        pinned.keys.retainAll(keepSongIds)
    }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val url = dataSpec.uri.toString()
        val songId = SubsonicStreamUrls.songIdOf(url) ?: return dataSpec
        val sourceKbps = sourceKbpsFor(songId)
        val delivery = pinned.getOrPut(songId) {
            StreamDelivery.plan(settings().forNetwork(isUnmetered()).forOutput(outputKind()), sourceKbps) {
                traitsFor(url)
            }
        }
        if (!delivery.transcoded) return dataSpec
        // A byte is a time only when the server is known to encode at the cap:
        // a file listed above it. Otherwise the reopen keeps its byte position.
        val constantAtCap = sourceKbps != null && sourceKbps > delivery.maxBitRateKbps!!
        val seeksByTime = constantAtCap && delivery.format == SubsonicServerTraits.FORMAT_MP3
        val offset = if (seeksByTime && traitsFor(url).timeOffset) {
            timeOffsetSeconds(dataSpec.position, delivery.maxBitRateKbps!!)
        } else {
            0
        }
        val rewritten = SubsonicStreamUrls.withTranscode(
            url = url,
            maxBitRateKbps = delivery.maxBitRateKbps,
            format = delivery.format,
            timeOffsetSeconds = offset,
            estimateContentLength = true,
        )
        return if (offset > 0) {
            // The server starts there; the bytes stand in for the ones at
            // dataSpec.position (constant bit rate), so read the new stream
            // from its own start.
            dataSpec.buildUpon().setUri(Uri.parse(rewritten)).setPosition(0).build()
        } else {
            dataSpec.withUri(Uri.parse(rewritten))
        }
    }

    companion object {
        /** The whole second a constant-bit-rate byte [position] falls in. */
        internal fun timeOffsetSeconds(position: Long, kbps: Int): Int {
            if (position <= 0L || kbps <= 0) return 0
            return (position / (kbps * 1000L / 8L)).toInt()
        }
    }
}

/**
 * [SubsonicServerTraits] per server, asked once with a stream url's own
 * signature. A server that doesn't answer is [SubsonicServerTraits.Plain] for
 * a while, then asked again. Blocking: called on the player's loader thread.
 */
internal class SubsonicServerTraitsCache(private val client: OkHttpClient) {
    private data class Entry(val traits: SubsonicServerTraits, val until: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun traitsFor(streamUrl: String): SubsonicServerTraits {
        val key = SubsonicStreamUrls.serverKey(streamUrl) ?: return SubsonicServerTraits.Plain
        val now = System.currentTimeMillis()
        entries[key]?.takeIf { it.until > now }?.let { return it.traits }
        val asked = runCatching { ask(streamUrl) }.getOrNull()
        val entry = Entry(
            traits = asked ?: SubsonicServerTraits.Plain,
            until = now + if (asked != null) KNOWN_FOR_MS else RETRY_AFTER_MS,
        )
        entries[key] = entry
        return entry.traits
    }

    private fun ask(streamUrl: String): SubsonicServerTraits? {
        val url = SubsonicStreamUrls.extensionsUrl(streamUrl) ?: return null
        val call = client.newBuilder()
            .callTimeout(ASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url(url).build())
        call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = json.decodeFromString(SubsonicResponse.serializer(), response.body.string()).subsonicResponse
            // A plain Subsonic server answers with an error: no extensions.
            if (body.status != "ok") return SubsonicServerTraits.Plain
            return SubsonicServerTraits.from(body)
        }
    }

    private companion object {
        const val ASK_TIMEOUT_SECONDS = 4L
        const val KNOWN_FOR_MS = 6 * 60 * 60 * 1000L
        const val RETRY_AFTER_MS = 5 * 60 * 1000L
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/**
 * The bit rate each queued Subsonic file has, by song id, as the server lists
 * it: written when a song is queued, read by [SubsonicStreamResolver] on the
 * loader thread.
 */
internal object SubsonicSourceBitrates {
    private val kbps = ConcurrentHashMap<String, Int>()

    fun record(songRawId: String, bitRateKbps: Int?) {
        if (bitRateKbps != null && bitRateKbps > 0) kbps[songRawId] = bitRateKbps
    }

    fun of(songRawId: String): Int? = kbps[songRawId]
}

/** NET_CAPABILITY_NOT_METERED on the active network; no network counts as metered. */
internal fun ConnectivityManager.activeNetworkUnmetered(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
