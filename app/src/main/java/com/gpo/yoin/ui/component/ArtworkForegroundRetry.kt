package com.gpo.yoin.ui.component

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Retry failed artwork when the process resumes, even if Android has not yet
 * stopped its background Activity. The process lifecycle already coalesces
 * short Activity handoffs; playback keeps its separate started-Activity count.
 * The first resume is not a recovery, including an observer added while resumed.
 */
internal class ArtworkForegroundRetry(
    private val retry: () -> Unit = ArtworkRetrySignal::bump
) : DefaultLifecycleObserver {
    private var paused = false

    override fun onPause(owner: LifecycleOwner) {
        paused = true
    }

    override fun onResume(owner: LifecycleOwner) {
        if (paused) {
            paused = false
            retry()
        }
    }
}
