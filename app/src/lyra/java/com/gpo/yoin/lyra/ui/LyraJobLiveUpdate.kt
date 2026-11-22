package com.gpo.yoin.lyra.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gpo.yoin.R
import com.gpo.yoin.lyra.data.LyraJob

/**
 * Surfaces active Lyra library ingestion jobs (source downloads & Share Pass pulls) as an
 * Android 16 Promoted Live Update notification (matching `RestoreLiveUpdate`), falling back
 * to a standard silent progress notification on Android 8–15.
 */
internal class LyraJobLiveUpdate(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    fun showActive(activeJobs: List<LyraJob>) {
        if (activeJobs.isEmpty()) {
            clear()
            return
        }
        val lead = activeJobs.first()
        post(
            title = app.getString(R.string.lyra_live_title_running),
            text = lead.displayTitle(),
            chip = app.getString(R.string.lyra_live_chip_active, activeJobs.size),
            ongoing = true,
        )
    }

    fun showCompleted(job: LyraJob) {
        post(
            title = app.getString(
                if (job.isFailed) R.string.lyra_live_title_failed else R.string.lyra_live_title_done,
            ),
            text = job.displayTitle(),
            chip = null,
            ongoing = false,
        )
    }

    fun clear() {
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun post(
        title: String,
        text: String,
        chip: String?,
        ongoing: Boolean,
    ) {
        val manager = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(manager)
        val open = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            LyraHubActivity.intent(app),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressTrackerIcon(Icon.createWithResource(app, R.drawable.ic_live_yoin_mascot))
            if (ongoing) {
                style.setProgressIndeterminate(true)
            } else {
                style.setProgress(100)
            }
            Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_yoin)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setStyle(style)
                .apply {
                    if (ongoing) {
                        setTimeoutAfter(ONGOING_TIMEOUT_MS)
                        extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                    }
                    if (chip != null) setShortCriticalText(chip)
                }
                .build()
        } else {
            NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_yoin)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(100, if (ongoing) 0 else 100, ongoing)
                .apply { if (ongoing) setTimeoutAfter(ONGOING_TIMEOUT_MS) }
                .build()
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.lyra_live_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { setSound(null, null) },
        )
    }

    private companion object {
        const val CHANNEL_ID = "lyra_jobs"
        const val NOTIFICATION_ID = 0x6c_79_72
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        const val ONGOING_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
