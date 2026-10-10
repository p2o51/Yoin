package com.gpo.yoin.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.player.normalizedSpotifyErrorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Debug-only, read-only App Remote probe (Q17): asks the Spotify app whether
 * each URI is in the user's library through UserApi.getLibraryState and logs
 * the answers under [TAG]. Connects App Remote when it isn't — it plays
 * nothing and leaves the queue alone — and leaves the connection as it found
 * it: one opened on a Subsonic or Apple Music account closes after the reads,
 * and nothing reconnects for it later. Logs no token or client id.
 *
 * ```
 * adb shell am broadcast -n com.gpo.yoin/.debug.LibraryStateProbeReceiver \
 *   -a com.gpo.yoin.debug.LIBRARY_STATE --esa uris spotify:track:<id>,spotify:album:<id>
 * ```
 *
 * Usage and the log format: docs/perf/yoinperf-logging.md. Only shell (which
 * holds DUMP) can send it; the debug manifest declares it.
 */
class LibraryStateProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || intent.action != ACTION) return
        val uris = (intent.getStringArrayExtra(EXTRA_URIS)?.toList() ?: intent.getStringExtra(EXTRA_URIS)?.split(','))
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (uris.isEmpty()) {
            Log.w(TAG, "probe: no uris (pass --esa uris spotify:track:<id>[,…])")
            return
        }
        val app = context.applicationContext as? YoinApplication ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            try {
                val probe = app.container.playbackManager.probeSpotifyLibraryStates(
                    uris.take(MAX_URIS),
                    CONNECT_TIMEOUT_MS
                )
                Log.i(
                    TAG,
                    "connect ${probe.connection} ms=${probe.connectMs} account=${probe.activeProviderId ?: "none"}"
                )
                probe.connectionNotes(CONNECT_TIMEOUT_MS).forEach { note -> Log.w(TAG, note) }
                if (uris.size > MAX_URIS) Log.w(TAG, "probe: read the first $MAX_URIS of ${uris.size} uris")
                probe.readings.forEach { reading ->
                    val error = reading.error
                    if (error == null) {
                        Log.i(
                            TAG,
                            "libraryState uri=${reading.uri} isAdded=${reading.isAdded} " +
                                "canAdd=${reading.canAdd} ms=${reading.elapsedMs}"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "libraryState uri=${reading.uri} ms=${reading.elapsedMs} " +
                                "error=${error.javaClass.simpleName}: " +
                                error.message.normalizedSpotifyErrorMessage()?.take(MAX_ERROR_CHARS).orEmpty()
                        )
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "probe failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val TAG = "YoinProbe"
        const val ACTION = "com.gpo.yoin.debug.LIBRARY_STATE"
        const val EXTRA_URIS = "uris"

        /**
         * A background broadcast (am broadcast's default) may run about a
         * minute after goAsync; this leaves room for the reads, each of which
         * gives up after 3 s.
         */
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /** App Remote's error text is a short sentence; this only bounds a surprise. */
        private const val MAX_ERROR_CHARS = 200

        /** At most this many reads (3 s each at worst) keep the broadcast inside its minute. */
        private const val MAX_URIS = 10
    }
}
