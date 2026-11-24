package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.shared.portability.BackupContent
import com.gpo.yoin.shared.portability.BackupExport
import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.shared.portability.BackupSource
import com.gpo.yoin.shared.portability.ExportSummary
import com.gpo.yoin.shared.portability.ReadableLabels
import com.gpo.yoin.shared.portability.ReadableNames
import java.io.OutputStream
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [BackupContent] as the backup zip ([BackupFormat]) to a stream the caller owns (a SAF document):
 * the stream is finished but not closed. What goes in each entry is built by :shared ([BackupExport]); this
 * class only zips it.
 */
class BackupWriter(
    private val names: ReadableNames = ReadableNames.None,
    private val labels: ReadableLabels = ReadableLabels.English,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun write(content: BackupContent, source: BackupSource, out: OutputStream): ExportSummary {
        val files = BackupExport.files(content, source, names, labels, zone.id)
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_PATH))
        zip.write(files.manifestBytes)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(BackupFormat.SNAPSHOT_PATH))
        zip.write(files.snapshotBytes)
        zip.closeEntry()
        for (file in files.readable) {
            zip.putNextEntry(ZipEntry(file.path))
            zip.write(file.text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return files.summary
    }
}

/** `yoin-backup-YYYY-MM-DD.zip`, the date in [zone]. */
fun BackupFormat.fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String = fileName(now, zone.id)

/** The readable labels for [locale] (Chinese for any "zh" locale). */
fun ReadableLabels.Companion.forLocale(locale: Locale): ReadableLabels = forLanguage(locale.language)
