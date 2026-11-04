package com.gpo.yoin.data.offline

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication

/**
 * Keeps [OfflineDownloads]' manager running while songs download, with a
 * progress notification, and brings it back through JobScheduler when Wi‑Fi
 * (or a network at all, when mobile data is allowed) returns.
 */
@OptIn(UnstableApi::class)
class YoinDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.offline_notification_channel,
    R.string.offline_notification_channel_description,
) {
    private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager =
        (application as YoinApplication).container.offlineDownloads.manager

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notifications.buildProgressNotification(
            this,
            R.drawable.ic_stat_yoin,
            null,
            getString(R.string.offline_notification_downloading),
            downloads,
            notMetRequirements,
        )

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 0x59_4F_44
        const val CHANNEL_ID = "yoin_downloads"
        const val JOB_ID = 0x59_4F_44
    }
}
