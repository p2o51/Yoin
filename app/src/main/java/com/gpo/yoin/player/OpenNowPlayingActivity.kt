package com.gpo.yoin.player

import android.app.Activity
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.gpo.yoin.MainActivity
import com.gpo.yoin.YoinApplication

/**
 * Where the system's media controls lead (the media notification, the lock
 * screen, Quick Settings' player): back into Yoin as the user left it, with
 * Now Playing open. An invisible hop, like WidgetPlayActivity: it asks the
 * shell to open Now Playing, then brings Yoin's existing task forward as it
 * is — the page that was on top stays on top, and a second Home is never
 * stacked on the first. A cold start opens Home with Now
 * Playing over it.
 */
class OpenNowPlayingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen in front once Yoin is up takes it: the shell, or a detail page with its own player.
        (application as YoinApplication).container.experienceSessionStore.requestNowPlaying()
        // Yoin's own task, whatever started it (the launcher, a widget, a sign-in hand-back): brought
        // forward as it is. The launcher intent alone only resumes a task the launcher itself began.
        val task = getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull { task ->
            runCatching { task.taskInfo.baseActivity?.className == MainActivity::class.java.name }.getOrDefault(false)
        }
        if (task != null) task.moveToFront() else startActivity(launcherIntent(this))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /** The launcher icon's own intent: resumes Yoin's task where it was, or starts it. */
        internal fun launcherIntent(context: Context): Intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        /** The MediaSession's session activity (both playback services). */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, OpenNowPlayingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
