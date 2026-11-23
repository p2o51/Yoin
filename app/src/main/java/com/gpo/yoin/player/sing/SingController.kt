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
 * The API the Sing UI binds to:
 * - [availability]: can the current song be sung (Spotify / Apple Music, or a device that failed the
 *   gate: no, with a reason).
 * - [enter] / [exit]; [mode]: Off / Preparing(progress) / Ready / Unavailable / Failed.
 * - [vocalLevel] + [setVocalLevel] (snaps to 12 % when near it, [SingGainCurve.snap]).
 *
 * When [waitForHandover] is true (live app), [mode] stays in [SingMode.Preparing] while
 * [SingPlaybackSwitcher] pre-buffers the quad stream ahead of the playhead, and flips to
 * [SingMode.Ready] (expanding the UI capsule) in exact sync with the ~950 ms ease-out vocal glide.
 * On [exit], vocals ease back to 1.0 over ~750 ms before the original stereo stream is restored.
 */
class SingController(
    private val scope: CoroutineScope,
    private val selector: SingProviderSelector,
    val mixControl: SingMixControl = SingMixControl(),
    private val waitForHandover: Boolean = false,
) {
    data class State(
        val enabled: Boolean = false,
        val vocalLevel: VocalLevel = VocalLevel.DEFAULT,
        /** The playing song (reported by the player), Sing on or off. */
        val current: SingRequest? = null,
        /** The song Sing is working on, while on. */
        val request: SingRequest? = null,
        val status: SingStatus? = null,
        /** True once [SingPlaybackSwitcher] has pre-buffered and swapped in the quad stream. */
        val handedOver: Boolean = false,
        /** Bumped whenever preparation fails so the UI can play a brief non-modal error cue. */
        val failureTick: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val mode: StateFlow<SingMode> = _state.map(::modeOf).stateIn(scope, SharingStarted.Eagerly, SingMode.Off)

    val vocalLevel: StateFlow<VocalLevel> = _state.map { it.vocalLevel }.stateIn(scope, SharingStarted.Eagerly, VocalLevel.DEFAULT)

    val failureTick: StateFlow<Int> = _state.map { it.failureTick }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val _availability = MutableStateFlow<SingAvailability>(SingAvailability.Unavailable(SingUnavailableReason.NoSource))
    val availability: StateFlow<SingAvailability> = _availability.asStateFlow()

    private var statusJob: Job? = null

    /** Whether Sing can be offered for a service at all (Spotify and Apple Music: no). */
    fun isSupported(providerId: String?): Boolean = SingProviderSelector.supportsSing(providerId)

    /** Sing on for the playing song. False when there is none or it can't be sung. */
    fun enter(): Boolean {
        val request = _state.value.current ?: return false
        if (availabilityOf(request) !is SingAvailability.Available) return false
        // The Sing stream starts at 1.0 (the mix), then eases out smoothly to the target vocal level.
        mixControl.setTarget(
            gain = _state.value.vocalLevel.gain,
            rampMs = SingMixControl.CUT_IN_GLIDE_MS,
            curve = SingRampCurve.EaseOutCubic,
        )
        mixControl.startAtUnity = true
        _state.update { it.copy(enabled = true, handedOver = false) }
        follow(request)
        return true
    }

    fun exit() {
        statusJob?.cancel()
        statusJob = null
        _state.value.request?.let { selector.providerFor(it.songId.provider).cancel(it) }
        mixControl.startAtUnity = false
        mixControl.setTarget(
            gain = 1f,
            rampMs = SingMixControl.CUT_OUT_GLIDE_MS,
            curve = SingRampCurve.EaseInOut,
        )
        _state.update {
            // Exiting from 0 % (e.g. via the two-step left-drag dismiss) resets the remembered level
            // back to the 12 % default so the next time Sing opens the capsule is never empty.
            val nextLevel = if (it.vocalLevel.position <= MIN_RESET_THRESHOLD) VocalLevel.DEFAULT else it.vocalLevel
            it.copy(enabled = false, vocalLevel = nextLevel, request = null, status = null, handedOver = false)
        }
        refreshAvailability()
    }

    /** Moves the vocal slider ([position] 0..1), snapping to the default 12 % when close to it. */
    fun setVocalLevel(position: Float) {
        val level = VocalLevel(SingGainCurve.snap(position))
        _state.update { it.copy(vocalLevel = level) }
        if (_state.value.enabled) {
            mixControl.setTarget(level.gain, SingMixControl.DEFAULT_RAMP_MS, SingRampCurve.Linear)
        }
    }

    /**
     * Where the listener is in the playing song, from [SingPlaybackSwitcher] (every half second and on
     * seeks while Sing is on): the on-device engine separates from there.
     */
    fun onPlayhead(positionMs: Long, playing: Boolean) {
        val request = _state.value.request ?: return
        if (!_state.value.enabled) return
        selector.providerFor(request.songId.provider).onPlayhead(request, positionMs, playing)
    }

    /**
     * Called by [SingPlaybackSwitcher] once Sing is audible: the quad stream swapped in, or (live) the
     * mixer first taking the vocal out of the original.
     */
    fun onStreamHandedOver(requestKey: String) {
        _state.update { s ->
            if (s.enabled && s.request?.key == requestKey) s.copy(handedOver = true) else s
        }
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
            _state.update { it.copy(request = null, status = null, handedOver = false) }
            return
        }
        // Carried over: the new song plays as it is until its stream is ready, then glides in from 1.0.
        mixControl.startAtUnity = !singing
        follow(request, alreadySinging = singing)
    }

    fun refreshAvailability() {
        _availability.value = _state.value.current?.let(::availabilityOf)
            ?: SingAvailability.Unavailable(SingUnavailableReason.NoSource)
    }

    private fun availabilityOf(request: SingRequest): SingAvailability =
        selector.providerFor(request.songId.provider).availability()

    private fun follow(request: SingRequest, alreadySinging: Boolean = false) {
        statusJob?.cancel()
        _state.update {
            it.copy(
                request = request,
                status = SingStatus.Preparing(),
                handedOver = alreadySinging,
            )
        }
        val provider = selector.providerFor(request.songId.provider)
        statusJob = scope.launch {
            provider.stream(request).collect { status ->
                if (status is SingStatus.Failed) {
                    // Turn Sing off cleanly on failure so track changes don't loop failures
                    // and a single tap retries; bump failureTick for a brief button-only cue.
                    mixControl.startAtUnity = false
                    _state.update {
                        if (it.request?.key == request.key) {
                            it.copy(
                                enabled = false,
                                request = null,
                                status = null,
                                handedOver = false,
                                failureTick = it.failureTick + 1,
                            )
                        } else {
                            it
                        }
                    }
                    refreshAvailability()
                    return@collect
                }
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
            is SingStatus.Ready -> if (waitForHandover && !state.handedOver) {
                SingMode.Preparing(null)
            } else {
                SingMode.Ready
            }
            is SingStatus.Unavailable -> SingMode.Unavailable(status.reason)
            is SingStatus.Failed -> SingMode.Failed(status.reason)
        }
    }

    companion object {
        private const val MIN_RESET_THRESHOLD = 0.005f
    }
}
