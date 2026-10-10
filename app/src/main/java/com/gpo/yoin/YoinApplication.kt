package com.gpo.yoin

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.contentcapture.ContentCaptureManager
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.gpo.yoin.player.applemusic.AppleMusicNativeMemoryPolicy
import com.gpo.yoin.ui.component.ArtworkNetworkRecovery
import com.gpo.yoin.ui.component.ArtworkRetrySignal
import com.gpo.yoin.widget.WidgetRefresher

class YoinApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /**
     * The ONE app-wide Coil loader. Every `AsyncImage` (which defaults to the
     * singleton) and the palette/theme extraction helpers share it, so a cover
     * decodes once into a single memory cache instead of once per Activity —
     * detail pages are separate Activities, so per-Activity loaders multiplied
     * every cache. Pixel-reading callers (palette/seed extraction) must keep
     * `allowHardware(false)` on their own [coil3.request.ImageRequest]s rather
     * than reach for a separately-configured loader. Built in YoinImageLoader.kt:
     * its own OkHttp client, a disk cache that never stores an error response,
     * and — debug builds only — the YoinPerf image listener
     * (docs/perf/yoinperf-logging.md); release gets no listener.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = buildYoinImageLoader(context)

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        // Compose's content-capture bridge re-walks the semantics tree on every frame whose semantics change —
        // measured ~3 ms of main thread per frame in Now Playing (lyrics scrolling, the playhead), more than its
        // layout and draw together. It only feeds the system's content-capture service (not accessibility,
        // not autofill), which a music player has nothing to give, so it is off app-wide.
        ContentCaptureManager.isEnabled = false
        // JavaCPP's process-memory budget is captured on first MusicKit/Pointer use.
        AppleMusicNativeMemoryPolicy.initialize(this)
        container = containerOverrideForTests ?: AppContainer(this)
        retireLegacyImageDiskCache(cacheDir)
        registerArtworkNetworkRetry()
        registerHostLifecycle()
        registerActivityEmbeddingRules()
        if (containerOverrideForTests == null) WidgetRefresher.start(this, container)
    }

    /**
     * Activity Embedding is Settings-only: the Settings list and its account /
     * service setup pages split list-detail in windows >= 840dp
     * (res/xml/main_split_config.xml, ratios in SplitLayout.kt). Detail pages
     * never embed — on Wide + tall windows they open as a column inside the
     * shell window (ui/navigation/pane/), everywhere else they push as their
     * own Activity (docs/adaptive-principles.md §3).
     */
    private fun registerActivityEmbeddingRules() {
        RuleController.getInstance(this)
            .setRules(RuleController.parseRules(this, R.xml.main_split_config))
        // Width-aware Settings ratio and the app background behind the
        // platform's split animations.
        installSplitAttributesCalculator(this, SplitController.getInstance(this))
    }

    /**
     * Failed artwork retries when the network recovers: a cover that failed
     * offline (Coil then asks only-if-cached and gets a 504) would otherwise
     * wait for the app to come back to the foreground. [ArtworkNetworkRecovery]
     * decides which callbacks are a recovery; the replay of the network current
     * at registration is not one.
     */
    private fun registerArtworkNetworkRetry() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val recovery = ArtworkNetworkRecovery(connectivity.activeNetwork)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                retryArtworkIf(recovery.onAvailable(network))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                retryArtworkIf(recovery.onCapabilitiesChanged(network, validated))
            }

            // API 29+; earlier releases never call it.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                retryArtworkIf(recovery.onBlockedStatusChanged(network, blocked))
            }

            override fun onLost(network: Network) {
                recovery.onLost(network)
            }

            private fun retryArtworkIf(recovered: Boolean) {
                if (recovered) ArtworkRetrySignal.bump()
            }
        }
        // The foreground retry still covers a device that refuses the callback.
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (error: RuntimeException) {
            Log.w(TAG, "No network callback for artwork retries", error)
        }
    }

    /**
     * Drive the playback host lifecycle (Spotify App Remote warm-up) from the
     * whole activity stack instead of a single Activity. With detail pages as
     * separate Activities, individual hosts can pause or stop during handoffs;
     * counting started activities keeps the remote connected as long as ANY
     * Yoin Activity is foregrounded, and only tears it down when the last one
     * stops (the app is actually backgrounded). Coming to the foreground also
     * retries failed artwork.
     */
    private fun registerHostLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private val started = StartedActivityCounter()

            override fun onActivityStarted(activity: Activity) {
                if (started.onStarted()) {
                    container.playbackManager.onHostStart(activity)
                    container.cloudSync.onAppForeground()
                    ArtworkRetrySignal.bump()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (started.onStopped()) {
                    container.playbackManager.onHostStop()
                    container.cloudSync.onAppBackground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        private const val TAG = "YoinApplication"

        @VisibleForTesting
        internal var containerOverrideForTests: AppContainer? = null
    }
}

/**
 * Counts Yoin's started Activities across the whole stack, for
 * [YoinApplication]'s host lifecycle: the first start brings the app to the
 * foreground, the last stop sends it to the background.
 */
internal class StartedActivityCounter {
    private var started = 0

    /** True when this start brings the app to the foreground. */
    fun onStarted(): Boolean = started++ == 0

    /** True when this stop sends the app to the background. */
    fun onStopped(): Boolean = --started == 0
}
