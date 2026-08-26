package com.gpo.yoin

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.window.embedding.RuleController
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

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
     * than reach for a separately-configured loader.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).build()

    override fun onCreate() {
        super.onCreate()
        container = containerOverrideForTests ?: AppContainer(this)
        registerHostLifecycle()
        registerActivityEmbeddingRules()
    }

    /**
     * Activity Embedding：>= 840dp 的窗口里 shell 与 detail 页左右分栏
     * （res/xml/main_split_config.xml）。detail 页本来就是独立 Activity
     * （native 跨 Activity back 的那次架构决定），这里只是声明规则 ——
     * 窄窗上规则不激活，推入/返回行为与手机完全一致。
     */
    private fun registerActivityEmbeddingRules() {
        RuleController.getInstance(this)
            .setRules(RuleController.parseRules(this, R.xml.main_split_config))
    }

    /**
     * Drive the playback host lifecycle (Spotify App Remote warm-up) from the
     * whole activity stack instead of a single Activity. With detail pages as
     * separate Activities, MainActivity.onStop fires during the shell→detail
     * handoff; counting started activities keeps the remote connected as long
     * as ANY Yoin Activity is foregrounded, and only tears it down when the
     * last one stops (the app is actually backgrounded).
     */
    private fun registerHostLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                if (startedCount == 0) {
                    container.playbackManager.onHostStart(activity)
                }
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount == 0) {
                    container.playbackManager.onHostStop()
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
