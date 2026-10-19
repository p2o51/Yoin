package com.gpo.yoin.ui.settings.backup

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
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.backup.RestoreTarget
import com.gpo.yoin.data.transfer.ReviewStatus

/**
 * A restore's progress as a Live Update (owner 2026-10-19: "灵动岛的感觉"):
 * one ongoing notification while the backup is matched on the target and
 * while the playlist is written — the Yoin mascot rides the progress bar and
 * each song's segment takes its outcome's colour as it comes back — asking to be promoted — Android 16's
 * status-bar chip shows "12/45" — with a progress bar (ProgressStyle on
 * Android 16+, a plain bar before). Matching done, it goes: the review waits
 * in the app. Written, it says what came of it and can be swiped away.
 * Nothing shows without the notification permission.
 */
/** One run of the Live Update's bar: [length] songs in [color] (null = not resolved yet). */
internal data class LiveSegment(val length: Int, val color: Int?)

internal class RestoreLiveUpdate(context: Context) : RestoreProgressSink {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    override fun matching(
        backup: PlaylistBackupRecord,
        target: RestoreTarget,
        done: Int,
        total: Int,
        outcomes: List<ReviewStatus>,
    ) {
        post(
            backup = backup,
            title = app.getString(R.string.playlist_restore_live_matching, target.displayName),
            text = backup.name,
            done = done,
            total = total,
            chip = app.getString(R.string.playlist_restore_progress, done, total),
            ongoing = true,
            outcomes = outcomes,
        )
    }

    override fun writing(backup: PlaylistBackupRecord, target: RestoreTarget, count: Int) {
        post(
            backup = backup,
            title = app.getString(R.string.playlist_restore_writing, target.displayName),
            text = backup.name,
            done = null,
            total = count,
            chip = count.toString(),
            ongoing = true,
        )
    }

    override fun finished(backup: PlaylistBackupRecord, target: RestoreTarget, record: PlaylistRestoreRecord) {
        val title = app.getString(
            if (record.failed) R.string.playlist_restore_not_created else R.string.playlist_restore_done,
            target.displayName,
        )
        post(
            backup = backup,
            title = title,
            text = app.resources.getQuantityString(
                R.plurals.playlist_backup_restore_added,
                record.added,
                record.added,
            ),
            done = if (record.failed) null else record.added,
            total = if (record.failed) 0 else record.added,
            chip = null,
            ongoing = false,
        )
    }

    override fun clear() {
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun post(
        backup: PlaylistBackupRecord,
        title: String,
        text: String,
        done: Int?,
        total: Int,
        chip: String?,
        ongoing: Boolean,
        outcomes: List<ReviewStatus> = emptyList(),
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
            backup.id.hashCode(),
            PlaylistBackupActivity.intent(app, backup.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // The Yoin mascot rides the bar; behind it, each song's segment wears what it matched to.
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressTrackerIcon(Icon.createWithResource(app, R.drawable.ic_live_yoin_mascot))
            if (total > 0) {
                style.setProgressSegments(liveSegments(outcomes, total).map { run ->
                    Notification.ProgressStyle.Segment(run.length).apply { run.color?.let(::setColor) }
                })
                style.setProgress((done ?: 0).coerceIn(0, total))
            }
            if (done == null && ongoing) style.setProgressIndeterminate(true)
            Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_yoin)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(backup.name.takeIf { it != text })
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setStyle(style)
                .apply {
                    // An ongoing update that stops coming (the process died mid-restore) clears itself.
                    if (ongoing) setTimeoutAfter(ONGOING_TIMEOUT_MS)
                    if (chip != null) setShortCriticalText(chip)
                    // Android 16 QPR2's Live Updates read this extra (setRequestPromotedOngoing).
                    if (ongoing) extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
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
                .setProgress(total, done ?: 0, done == null && ongoing)
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
                app.getString(R.string.playlist_restore_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { setSound(null, null) },
        )
    }

    internal companion object {
        /** Yoin's own colours, one per outcome (the mascot's arms): matched violet, to check pink, not found grey. */
        const val COLOR_MATCHED = 0xFF9113FF.toInt()
        const val COLOR_TO_CHECK = 0xFFFF59CD.toInt()
        const val COLOR_NOT_FOUND = 0xFF8E8A99.toInt()

        /**
         * The bar's segments: the songs resolved so far, runs of one outcome
         * merged (a long playlist stays a handful of segments), then the rest
         * of the playlist in the bar's own colour.
         */
        fun liveSegments(outcomes: List<ReviewStatus>, total: Int): List<LiveSegment> {
            val runs = ArrayList<LiveSegment>()
            outcomes.take(total).forEach { status ->
                val color = when (status) {
                    ReviewStatus.Matched, ReviewStatus.Picked -> COLOR_MATCHED
                    ReviewStatus.Uncertain -> COLOR_TO_CHECK
                    ReviewStatus.NotFound -> COLOR_NOT_FOUND
                }
                val last = runs.lastOrNull()
                if (last?.color == color) runs[runs.lastIndex] = last.copy(length = last.length + 1) else runs += LiveSegment(1, color)
            }
            val rest = total - outcomes.size.coerceAtMost(total)
            if (rest > 0) runs += LiveSegment(rest, null)
            return runs
        }

        const val CHANNEL_ID = "playlist_restore"
        const val NOTIFICATION_ID = 0x70_6c_72
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        const val ONGOING_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
