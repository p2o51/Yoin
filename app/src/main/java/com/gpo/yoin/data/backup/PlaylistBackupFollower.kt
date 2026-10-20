package com.gpo.yoin.data.backup

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gpo.yoin.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps playlist cloud backups following their playlists
 * (docs/playlist-transfer.md §7): each time the app comes to the foreground,
 * once the profiles are up, the backups due a check
 * ([PlaylistBackupService.FOLLOW_INTERVAL_MS]) are read again. Never in the
 * background: no job, no wake-ups, nothing while the app is closed.
 */
internal object PlaylistBackupFollower {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Playlist backup follow-up stopped", error)
        },
    )
    private var job: Job? = null

    fun start(container: AppContainer) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (job?.isActive == true) return
                    job = scope.launch {
                        container.profileStartupDone.first { it }
                        // Off the return-to-app path: the first frames and the feed come first.
                        delay(START_DELAY_MS)
                        val summary = container.playlistBackupService.followDue()
                        if (summary.checked > 0) Log.i(TAG, "Followed $summary")
                    }
                }
            },
        )
    }

    private const val TAG = "PlaylistBackupFollower"
    private const val START_DELAY_MS = 8_000L
}
