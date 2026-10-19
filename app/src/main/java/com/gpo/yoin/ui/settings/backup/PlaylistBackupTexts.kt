package com.gpo.yoin.ui.settings.backup

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.gpo.yoin.R
import com.gpo.yoin.data.backup.UnexportedReason
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.settings.ServiceIdentity
import com.gpo.yoin.ui.settings.serviceIdentity
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/** A backup or restore time: "Oct 19, 14:05" this year, "Oct 19, 2025, 14:05" before, in the device's locale. */
internal fun formatBackupTime(
    thenMs: Long,
    nowMs: Long,
    resources: Resources,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val sameYear = Instant.ofEpochMilli(thenMs).atZone(zone).year == Instant.ofEpochMilli(nowMs).atZone(zone).year
    val locale = resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, if (sameYear) "MMMdjm" else "yMMMdjm")
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone), locale).apply { timeInMillis = thenMs }
    return DateFormat.format(pattern, calendar).toString()
}

@StringRes
internal fun UnexportedReason.labelRes(): Int = when (this) {
    UnexportedReason.LocalFile -> R.string.playlist_backup_reason_local_file
    UnexportedReason.Episode -> R.string.playlist_backup_reason_episode
    UnexportedReason.Video -> R.string.playlist_backup_reason_video
    UnexportedReason.Unavailable -> R.string.playlist_backup_reason_unavailable
}

/** The service a backup or restore target belongs to: its name, Yoin Symbol and hue, as Settings shows accounts. */
internal fun serviceOf(provider: String): ServiceIdentity = ProviderKind.fromKeyOrSubsonic(provider).serviceIdentity
