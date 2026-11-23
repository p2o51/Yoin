package com.gpo.yoin.player.sing

import android.net.Uri
import androidx.annotation.StringRes
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The one seam between Sing playback and whoever makes the 4-channel stream. The on-device engine
 * ([OnDeviceSingStreamProvider]) is one implementation; the Lyra server client (Rebecca) is another
 * ([LyraSingStreamProvider], a stub for now). [SingProviderSelector] picks one per song; the playback
 * side ([SingPlaybackSwitcher] → [SingMixerAudioProcessor]) takes either kind of [SingSource] through
 * the same pipeline.
 *
 * The stream format both sides share: AAC-LC, 4 channels, in an MP4 (.m4a), channel order
 * CH0 = I_L, CH1 = I_R, CH2 = V_L, CH3 = V_R (instrumental, vocal), same sample rate and timeline as
 * the original song.
 */
interface SingStreamProvider {
    /**
     * The song's Sing stream: [SingStatus.Preparing] while it is made, then [SingStatus.Ready], or
     * [SingStatus.Unavailable] / [SingStatus.Failed]. Collecting starts the work (or joins it); the
     * work outlives the collector so a song once started is finished and cached (race to idle).
     */
    fun stream(request: SingRequest): Flow<SingStatus>

    /** Stops work on [request] if any is running (Sing turned off, or another song asked for). */
    fun cancel(request: SingRequest) {}

    /** Whether this provider can sing at all right now (e.g. the device gate's last answer). */
    fun availability(): SingAvailability = SingAvailability.Available

    /**
     * Where the listener is in [request]'s song ([playing] or paused), reported every half second and
     * on every seek while Sing is on — the on-device engine separates from there.
     */
    fun onPlayhead(request: SingRequest, positionMs: Long, playing: Boolean) {}
}

/**
 * A song Sing is asked for. [streamUri] is the url the player streams the original from (the
 * on-device engine reads the same bytes, from the downloads or the stream cache when it can);
 * [positionMs] is where playback is, so the engine starts there.
 */
data class SingRequest(
    val songId: MediaId,
    val profileId: String,
    val streamUri: String? = null,
    val positionMs: Long = 0L,
) {
    /** Identity for caches and job de-duplication; [positionMs] is a hint, not part of it. */
    val key: String get() = "$profileId|${songId.provider}|${songId.rawId}"
}

sealed interface SingStatus {
    data class Unavailable(val reason: SingUnavailableReason) : SingStatus

    /** [progress] in 0..1 when known. */
    data class Preparing(val progress: Float? = null, val stage: Stage = Stage.Working) : SingStatus {
        enum class Stage { Model, Reading, Working, Saving }
    }

    data class Ready(val source: SingSource) : SingStatus

    data class Failed(val reason: SingFailure, val detail: String? = null) : SingStatus
}

enum class SingUnavailableReason(@param:StringRes @get:StringRes val messageRes: Int) {
    /** Spotify and Apple Music play in their own players; their audio never reaches Yoin's sink. */
    ProviderUnsupported(R.string.sing_unavailable_provider),

    /** The device gate failed (too slow, too hot, or no 4-channel decode); the UI can suggest Lyra. */
    DeviceInsufficient(R.string.sing_device_insufficient),

    /** Nothing to read the song from (no stream url, no download). */
    NoSource(R.string.sing_unavailable_no_source),

    /** The Lyra server path is not built yet. */
    LyraPending(R.string.sing_unavailable_lyra_pending),
}

enum class SingFailure(@param:StringRes @get:StringRes val messageRes: Int) {
    ModelDownload(R.string.sing_failed_model),
    Decode(R.string.sing_failed_generic),
    Inference(R.string.sing_failed_generic),
    Encode(R.string.sing_failed_generic),
    Remote(R.string.sing_failed_generic),
}

/**
 * Where the Sing audio is: a 4-channel stream ([LocalFile], [Remote]) the switcher swaps in, or a
 * vocal track the live mixer subtracts from the original as it plays ([Live]).
 */
sealed interface SingSource {
    /** See [SingMixControl.channelMap]; identity unless a decoder was found to reorder channels. */
    val channelMap: IntArray

    /** A cached on-device result (an .m4a in the Sing cache). */
    data class LocalFile(val file: File, override val channelMap: IntArray = SingMixControl.IDENTITY) : SingSource {
        override fun equals(other: Any?): Boolean = other is LocalFile && other.file == file && other.channelMap.contentEquals(channelMap)

        override fun hashCode(): Int = file.hashCode() * 31 + channelMap.contentHashCode()
    }

    /**
     * A Lyra server stream: `/api/v1/sing/{id}/quad.m4a` (audio/mp4, served with HTTP 206 Range), its
     * [uri] already carrying the Subsonic `u`/`t`/`s` auth query, as Lyra's other /api/v1 routes do.
     */
    data class Remote(
        val uri: Uri,
        val headers: Map<String, String> = emptyMap(),
        override val channelMap: IntArray = SingMixControl.IDENTITY,
    ) : SingSource {
        override fun equals(other: Any?): Boolean =
            other is Remote && other.uri == uri && other.headers == headers && other.channelMap.contentEquals(channelMap)

        override fun hashCode(): Int = (uri.hashCode() * 31 + headers.hashCode()) * 31 + channelMap.contentHashCode()
    }

    /**
     * The on-device engine's vocal stem, filling while the song plays (complete on a cache hit). Ready
     * means V covers the next few seconds from the playhead; no stream is swapped — the mixer takes V
     * out of the original ([SingMixerAudioProcessor], live mode).
     */
    data class Live(val track: SingVocalTrack) : SingSource {
        override val channelMap: IntArray get() = SingMixControl.IDENTITY
    }

    companion object {
        const val MIME_TYPE = "audio/mp4"
    }
}

/** Spotify, Apple Music, anything without [com.gpo.yoin.data.source.Capability.SING]. */
object UnavailableSingStreamProvider : SingStreamProvider {
    override fun stream(request: SingRequest): Flow<SingStatus> =
        flowOf(SingStatus.Unavailable(SingUnavailableReason.ProviderUnsupported))

    override fun availability(): SingAvailability = SingAvailability.Unavailable(SingUnavailableReason.ProviderUnsupported)
}

/**
 * The Lyra server path (Rebecca builds it on this seam): GET `/api/v1/sing/{id}/quad.m4a` with the
 * profile's Subsonic auth → [SingSource.Remote]. Until then it reports [SingUnavailableReason.LyraPending]
 * and the selector only picks it when Lyra is online, which nothing reports yet.
 */
class LyraSingStreamProvider : SingStreamProvider {
    override fun stream(request: SingRequest): Flow<SingStatus> =
        flowOf(SingStatus.Unavailable(SingUnavailableReason.LyraPending))

    override fun availability(): SingAvailability = SingAvailability.Unavailable(SingUnavailableReason.LyraPending)
}
