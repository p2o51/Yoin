package com.gpo.yoin.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState as GmsCastState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Chromecast connection states exposed to the UI layer. */
sealed interface CastState {
    data object NotAvailable : CastState
    data object Available : CastState
    data class Connected(val deviceName: String) : CastState
}

/**
 * Manages Chromecast integration via Media3 [CastPlayer].
 *
 * Call [initialize] once after construction. When a Cast session becomes
 * available the manager automatically transfers playback from the local
 * player (provided via [setLocalPlayerProvider]) to the [CastPlayer] and
 * back when the session ends.
 */
class CastManager(private val context: Context) {

    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var localPlayerProvider: (() -> Player?)? = null

    private val _castState = MutableStateFlow<CastState>(CastState.NotAvailable)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    /**
     * Provide a lambda that returns the current local [Player] (typically a
     * [androidx.media3.session.MediaController]). Called when transferring
     * playback to/from the Cast device.
     */
    fun setLocalPlayerProvider(provider: () -> Player?) {
        localPlayerProvider = provider
    }

    /** Initialise Cast SDK. Safe to call on devices without Google Play Services. */
    fun initialize() {
        try {
            CastContext.getSharedInstance(context, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    setupCastPlayer(ctx)
                    setupCastStateListener(ctx)
                    updateInitialState(ctx)
                }
                .addOnFailureListener {
                    _castState.value = CastState.NotAvailable
                }
        } catch (_: Exception) {
            _castState.value = CastState.NotAvailable
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun setupCastPlayer(ctx: CastContext) {
        val player = CastPlayer(ctx)
        castPlayer = player

        player.setSessionAvailabilityListener(
            object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    val session = ctx.sessionManager.currentCastSession
                    val deviceName =
                        session?.castDevice?.friendlyName ?: "Cast Device"
                    _castState.value = CastState.Connected(deviceName)
                    transferToCast()
                }

                override fun onCastSessionUnavailable() {
                    transferFromCast()
                    _castState.value =
                        if (ctx.castState != GmsCastState.NO_DEVICES_AVAILABLE) {
                            CastState.Available
                        } else {
                            CastState.NotAvailable
                        }
                }
            },
        )
    }

    private fun setupCastStateListener(ctx: CastContext) {
        ctx.addCastStateListener { state ->
            when (state) {
                GmsCastState.NO_DEVICES_AVAILABLE -> {
                    if (_castState.value !is CastState.Connected) {
                        _castState.value = CastState.NotAvailable
                    }
                }
                GmsCastState.NOT_CONNECTED -> {
                    if (_castState.value !is CastState.Connected) {
                        _castState.value = CastState.Available
                    }
                }
                // CONNECTING / CONNECTED handled by SessionAvailabilityListener
                else -> {}
            }
        }
    }

    private fun updateInitialState(ctx: CastContext) {
        when (ctx.castState) {
            GmsCastState.NO_DEVICES_AVAILABLE ->
                _castState.value = CastState.NotAvailable
            GmsCastState.NOT_CONNECTED ->
                _castState.value = CastState.Available
            GmsCastState.CONNECTED -> {
                val session = ctx.sessionManager.currentCastSession
                val deviceName =
                    session?.castDevice?.friendlyName ?: "Cast Device"
                _castState.value = CastState.Connected(deviceName)
            }
            else -> {} // CONNECTING — keep current state
        }
    }

    // ── Playback transfer ───────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun transferToCast() {
        val local = localPlayerProvider?.invoke() ?: return
        val cast = castPlayer ?: return

        val items = buildList {
            for (i in 0 until local.mediaItemCount) {
                add(local.getMediaItemAt(i))
            }
        }
        if (items.isEmpty()) return

        val index = local.currentMediaItemIndex
        val position = local.currentPosition
        val wasPlaying = local.isPlaying

        local.pause()
        cast.setMediaItems(items, index, position)
        cast.prepare()
        if (wasPlaying) cast.play()
    }

    @OptIn(UnstableApi::class)
    private fun transferFromCast() {
        val local = localPlayerProvider?.invoke() ?: return
        val cast = castPlayer ?: return

        val items = buildList {
            for (i in 0 until cast.mediaItemCount) {
                add(cast.getMediaItemAt(i))
            }
        }
        if (items.isEmpty()) return

        val index = cast.currentMediaItemIndex
        val position = cast.currentPosition
        val wasPlaying = cast.isPlaying

        cast.stop()
        local.setMediaItems(items, index, position)
        local.prepare()
        if (wasPlaying) local.play()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    fun release() {
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        castContext = null
        localPlayerProvider = null
    }
}
