package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.engine.SyncJson
import java.io.OutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/** What an export wrote. */
data class ExportSummary(
    val manifest: BackupManifest,
    /** Readable Markdown files written. */
    val readableFiles: Int,
    /** Kinds whose domain table couldn't be read (their last synced copy went in instead). */
    val readFailures: List<String>,
)

/**
 * Writes a [BackupContent] as the backup zip ([BackupFormat]) to a stream the caller owns (a SAF document):
 * the stream is finished but not closed.
 */
class BackupWriter(
    private val names: ReadableNames = ReadableNames.None,
    private val labels: ReadableLabels = ReadableLabels.English,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun write(content: BackupContent, source: BackupSource, out: OutputStream): ExportSummary {
        val snapshot = content.snapshot
        val snapshotBytes = SyncJson.encodeToString(SyncEnvelope.serializer(), snapshot).toByteArray(Charsets.UTF_8)
        val (readable, accounts) = ReadableExport.render(
            accounts = content.accounts,
            localProfiles = content.localProfiles,
            records = snapshot.records,
            names = names,
            labels = labels,
            zone = zone,
        )
        val live = snapshot.records.filter { !it.deleted }
        val counts = live.filter { it.scope != SyncFormat.GLOBAL_SCOPE }
            .groupBy { it.scope }
            .mapValues { (_, records) -> records.groupingBy { it.kind }.eachCount().toSortedMap() }
        val manifest = BackupManifest(
            exportedAt = snapshot.writtenAt,
            source = source,
            accounts = accounts.map { it.copy(counts = counts[it.scope].orEmpty()) },
            globalCounts = live.filter { it.scope == SyncFormat.GLOBAL_SCOPE }.groupingBy { it.kind }.eachCount().toSortedMap(),
            snapshot = BackupSnapshotInfo(
                sha256 = BackupFormat.sha256Hex(snapshotBytes),
                bytes = snapshotBytes.size.toLong(),
                records = snapshot.records.size,
                tombstones = snapshot.records.count { it.deleted },
            ),
        )
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_PATH))
        zip.write(MANIFEST_JSON.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(BackupFormat.SNAPSHOT_PATH))
        zip.write(snapshotBytes)
        zip.closeEntry()
        for (file in readable) {
            zip.putNextEntry(ZipEntry(file.path))
            zip.write(file.text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return ExportSummary(manifest, readable.size, content.readFailures)
    }

    internal companion object {
        val MANIFEST_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
