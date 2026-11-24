package com.gpo.yoin.player.sing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gpo.yoin.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Test builds only (registered in the debug and releaseDebugSigned manifests, behind android.permission.DUMP;
 * R8 drops it from the Play release): resolves Sing's models the way a Sing request does — the Play AI Pack
 * (fetched if need be) or the bundled builds — and, with `--ez run true`, opens a LiteRT session and runs one
 * silent chunk. Everything goes to logcat under YoinSing. Needs no account, so AI Pack delivery
 * (bundletool --local-testing, or a Play test track) can be checked on its own:
 *
 *     adb shell am broadcast -a com.gpo.yoin.debug.SING_MODEL -p <package> [--ez run true]
 */
class SingModelProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val run = intent.getBooleanExtra(EXTRA_RUN, false)
        scope.launch {
            try {
                Log.i(TAG, "probe: bundled=${BuildConfig.SING_BUNDLED_MODELS}, SoC ${LiteRtSingBackend.socManufacturer()}/${LiteRtSingBackend.socModel()}")
                val backend = LiteRtSingBackend(
                    app,
                    LiteRtSingBackend.resolver(app, BuildConfig.SING_BUNDLED_MODELS) { Log.i(TAG, "probe: $it") },
                    OnDeviceSingStreamProvider.defaultThreads(),
                )
                Log.i(TAG, "probe: modelId ${backend.modelId}, on the device already: ${backend.readyModel}")
                var shown = -1
                val model = backend.ensure { p ->
                    val pct = (p * 100).toInt()
                    if (pct / 10 != shown / 10) {
                        shown = pct
                        Log.i(TAG, "probe: model progress $pct %")
                    }
                }
                Log.i(TAG, "probe: model ready ${model.path} (${model.length()} B), plan ${backend.plan.joinToString(" → ")}")
                if (run) {
                    backend.open(model).use { session ->
                        Log.i(TAG, "probe: one chunk ${SingDeviceGate.benchmarkChunkMs(session)} ms")
                    }
                }
                Log.i(TAG, "probe: OK")
            } catch (e: Exception) {
                Log.w(TAG, "probe: FAILED (${e.javaClass.simpleName}: ${e.message})", e)
            }
        }
    }

    private companion object {
        const val TAG = "YoinSing"
        const val EXTRA_RUN = "run"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
