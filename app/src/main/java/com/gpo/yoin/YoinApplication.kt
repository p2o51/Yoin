package com.gpo.yoin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.contentcapture.ContentCaptureManager
import androidx.window.embedding.RuleController
import androidx.window.embedding.SplitController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.gpo.yoin.perf.YoinPerfImages
import com.gpo.yoin.player.applemusic.AppleMusicNativeMemoryPolicy
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
     * than reach for a separately-configured loader. Debug builds add only the
     * YoinPerf image listener (docs/perf/yoinperf-logging.md); release gets none.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .apply { YoinPerfImages.listenerFactory()?.let(::eventListenerFactory) }
            .build()

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
     * Drive the playback host lifecycle (Spotify App Remote warm-up) from the
     * whole activity stack instead of a single Activity. With detail pages as
     * separate Activities, individual hosts can pause or stop during handoffs;
     * counting started activities keeps the remote connected as long as ANY
     * Yoin Activity is foregrounded, and only tears it down when the last one
     * stops (the app is actually backgrounded).
     */
    private fun registerHostLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                if (startedCount == 0) {
                    container.playbackManager.onHostStart(activity)
                    container.cloudSync.onAppForeground()
                }
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount == 0) {
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
        @VisibleForTesting
        internal var containerOverrideForTests: AppContainer? = null
    }
}
