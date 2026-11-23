package com.gpo.yoin.player.sing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the playing song can be sung, before anyone asks. */
sealed interface SingAvailability {
    data object Available : SingAvailability

    data class Unavailable(val reason: SingUnavailableReason) : SingAvailability
}

/** What a Sing UI shows: off, getting the stream ready, singing, or why not. */
sealed interface SingMode {
    data object Off : SingMode

    data class Preparing(val progress: Float?) : SingMode

    data object Ready : SingMode

    /** On, but this song can't be sung (it plays as it is); the next song may. */
    data class Unavailable(val reason: SingUnavailableReason) : SingMode

    data class Failed(val reason: SingFailure) : SingMode
}

/** The vocal slider: [position] 0..1 drives [gain] through [SingGainCurve]; [percent] is the gain. */
data class VocalLevel(val position: Float) {
    val gain: Float get() = SingGainCurve.gainFor(position)
    val percent: Int get() = Math.round(gain * 100f)

    /** At the default 12 % (the slider snapped there): the UI gives its light haptic tick on entering. */
    val atDefault: Boolean get() = position == SingGainCurve.DEFAULT_POSITION

    companion object {
        val DEFAULT = VocalLevel(SingGainCurve.DEFAULT_POSITION)
    }
}

/**
 * The API a Sing UI binds to (none yet; the owner's direction: long-press the Now Playing cover →
 * Sing; the rating bar becomes the vocal level, the heart the Sing button, lyrics expand).
 *
 * - [availability]: can the current song be sung (Spotify / Apple Music, or a device that failed the
 *   gate: no, with a reason).
 * - [enter] / [exit]; [mode]: Off / Preparing(progress) / Ready / Unavailable / Failed.
 * - [vocalLevel] + [setVocalLevel] (snaps to 12 % when near it, [SingGainCurve.snap]).
 *
 * Track changes carry Sing over: it stays on, and each new song gets its stream prepared (the chunk
 * under the play position first). Until it's ready the song plays as it is — vocal at full — and the
 * Sing stream then glides in from 1.0 as on [enter]. A song that can't be sung leaves Sing on with
 * [SingMode.Unavailable]; only [exit] (or the listener) turns it off.
 *
 * On [enter] the Sing stream is swapped in by [SingPlaybackSwitcher] at the same position, at gain 1.0
 * (the mix, so the swap is inaudible), and glides to the level over 250 ms; [exit] glides back to 1.0
 * and swaps the original back. Slider moves ramp over 20 ms.
 */
class SingController(
    private val scope: CoroutineScope,
    private val selector: SingProviderSelector,
    val mixControl: SingMixControl = SingMixControl(),
) {
    data class State(
        val enabled: Boolean = false,
        val vocalLevel: VocalLevel = VocalLevel.DEFAULT,
        /** The playing song (reported by the player), Sing on or off. */
        val current: SingRequest? = null,
        /** The song Sing is working on, while on. */
        val request: SingRequest? = null,
        val status: SingStatus? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val mode: StateFlow<SingMode> = _state.map(::modeOf).stateIn(scope, SharingStarted.Eagerly, SingMode.Off)

    val vocalLevel: StateFlow<VocalLevel> = _state.map { it.vocalLevel }.stateIn(scope, SharingStarted.Eagerly, VocalLevel.DEFAULT)

    private val _availability = MutableStateFlow<SingAvailability>(SingAvailability.Unavailable(SingUnavailableReason.NoSource))
    val availability: StateFlow<SingAvailability> = _availability.asStateFlow()

    private var statusJob: Job? = null

    /** Whether Sing can be offered for a service at all (Spotify and Apple Music: no). */
    fun isSupported(providerId: String?): Boolean = SingProviderSelector.supportsSing(providerId)

    /** Sing on for the playing song. False when there is none or it can't be sung. */
    fun enter(): Boolean {
        val request = _state.value.current ?: return false
        if (availabilityOf(request) !is SingAvailability.Available) return false
        // The Sing stream starts at 1.0 (the mix), then glides to the level.
        mixControl.setTarget(_state.value.vocalLevel.gain, SingMixControl.GLIDE_MS)
        mixControl.startAtUnity = true
        _state.update { it.copy(enabled = true) }
        follow(request)
        return true
    }

    fun exit() {
        statusJob?.cancel()
        statusJob = null
        _state.value.request?.let { selector.providerFor(it.songId.provider).cancel(it) }
        mixControl.startAtUnity = false
        mixControl.setTarget(1f, SingMixControl.GLIDE_MS)
        _state.update { it.copy(enabled = false, request = null, status = null) }
        refreshAvailability()
    }

    /** Moves the vocal slider ([position] 0..1), snapping to the default 12 % when close to it. */
    fun setVocalLevel(position: Float) {
        val level = VocalLevel(SingGainCurve.snap(position))
        _state.update { it.copy(vocalLevel = level) }
        if (_state.value.enabled) mixControl.setTarget(level.gain, SingMixControl.DEFAULT_RAMP_MS)
    }

    /**
     * The player is on [request]'s song now (null: nothing playing); called by [SingPlaybackSwitcher].
     * [singing]: the item already plays its Sing stream (gapless into a song done before), so there's
     * no glide to make.
     */
    fun onCurrentItem(request: SingRequest?, singing: Boolean = false) {
        val previous = _state.value
        _state.update { it.copy(current = request) }
        refreshAvailability()
        if (!previous.enabled || request?.key == previous.request?.key) return
        if (request == null) {
            statusJob?.cancel()
            _state.update { it.copy(request = null, status = null) }
            return
        }
        // Carried over: the new song plays as it is until its stream is ready, then glides in from 1.0.
        mixControl.startAtUnity = !singing
        follow(request)
    }

    fun refreshAvailability() {
        _availability.value = _state.value.current?.let(::availabilityOf)
            ?: SingAvailability.Unavailable(SingUnavailableReason.NoSource)
    }

    private fun availabilityOf(request: SingRequest): SingAvailability =
        selector.providerFor(request.songId.provider).availability()

    private fun follow(request: SingRequest) {
        statusJob?.cancel()
        _state.update { it.copy(request = request, status = SingStatus.Preparing()) }
        val provider = selector.providerFor(request.songId.provider)
        statusJob = scope.launch {
            provider.stream(request).collect { status ->
                _state.update { if (it.request?.key == request.key) it.copy(status = status) else it }
                if (status is SingStatus.Unavailable) refreshAvailability()
            }
        }
    }

    private fun modeOf(state: State): SingMode = if (!state.enabled) {
        SingMode.Off
    } else {
        when (val status = state.status) {
            null -> SingMode.Preparing(null)
            is SingStatus.Preparing -> SingMode.Preparing(status.progress)
            is SingStatus.Ready -> SingMode.Ready
            is SingStatus.Unavailable -> SingMode.Unavailable(status.reason)
            is SingStatus.Failed -> SingMode.Failed(status.reason)
        }
    }
}
