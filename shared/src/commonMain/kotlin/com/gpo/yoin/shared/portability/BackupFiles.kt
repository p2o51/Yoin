package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.sync.SyncEnvelope
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.engine.MalformedSyncFileException
import com.gpo.yoin.shared.sync.engine.SnapshotCodec
import com.gpo.yoin.shared.sync.engine.SyncJson
import com.gpo.yoin.shared.sync.engine.SyncSchemaTooNewException
import kotlinx.serialization.SerializationException

/** Everything one backup holds, before it is written out. */
data class BackupContent(
    /** All records, sorted by (kind, scope, key); [SyncEnvelope.fileClass] = [BackupFormat.SNAPSHOT_FILE_CLASS]. */
    val snapshot: SyncEnvelope,
    /** One per scope: this device's profiles first (oldest first), then accounts only known from Drive. */
    val accounts: List<BackupAccount>,
    /** Local profile id behind each on-device account's scope. */
    val localProfiles: Map<String, String>,
    /** Kinds whose domain table couldn't be read (their replica copy, if any, was used instead). */
    val readFailures: List<String>,
)

/** What an export wrote. */
data class ExportSummary(
    val manifest: BackupManifest,
    /** Readable Markdown files written. */
    val readableFiles: Int,
    /** Kinds whose domain table couldn't be read (their last synced copy went in instead). */
    val readFailures: List<String>,
)

/**
 * The files of one backup, in zip order: [BackupFormat.MANIFEST_PATH], [BackupFormat.SNAPSHOT_PATH], then
 * [readable]. The platform writes them into a zip as they are (UTF-8, no other entries).
 */
class BackupExportFiles(
    val summary: ExportSummary,
    val manifestBytes: ByteArray,
    val snapshotBytes: ByteArray,
    val readable: List<BackupTextFile>,
) {
    val manifest: BackupManifest get() = summary.manifest
}

/** A backup that was read and checked: its manifest and its snapshot's records. */
data class ParsedBackup(
    val manifest: BackupManifest,
    val snapshot: SyncEnvelope,
    /** Readable Markdown files present (informational; never read). */
    val readableFiles: Int,
)

/** Why a file couldn't be imported. */
sealed class BackupReadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Not a zip, or no Yoin manifest in it. */
    class NotABackup(message: String) : BackupReadException(message)

    /** Written by a newer Yoin (backup format or sync schema): update to import it. */
    class TooNew(val formatVersion: Int) : BackupReadException("Backup format $formatVersion is newer than this Yoin")

    /** The snapshot doesn't match the manifest's SHA-256: damaged or edited. */
    class ChecksumMismatch : BackupReadException("Backup data doesn't match its checksum")

    class Corrupt(message: String, cause: Throwable? = null) : BackupReadException(message, cause)
}

/** Builds the files of a backup from its [BackupContent] (the zip is the platform's). */
object BackupExport {
    suspend fun files(
        content: BackupContent,
        source: BackupSource,
        names: ReadableNames,
        labels: ReadableLabels,
        zoneId: String,
    ): BackupExportFiles {
        val snapshot = content.snapshot
        val snapshotBytes = SyncJson.encodeToString(SyncEnvelope.serializer(), snapshot).encodeToByteArray()
        val (readable, accounts) = ReadableExport.render(
            accounts = content.accounts,
            localProfiles = content.localProfiles,
            records = snapshot.records,
            names = names,
            labels = labels,
            zoneId = zoneId,
        )
        val live = snapshot.records.filter { !it.deleted }
        val counts = live.filter { it.scope != SyncFormat.GLOBAL_SCOPE }
            .groupBy { it.scope }
            .mapValues { (_, records) -> sortedCounts(records.map { it.kind }) }
        val manifest = BackupManifest(
            exportedAt = snapshot.writtenAt,
            source = source,
            accounts = accounts.map { it.copy(counts = counts[it.scope].orEmpty()) },
            globalCounts = sortedCounts(live.filter { it.scope == SyncFormat.GLOBAL_SCOPE }.map { it.kind }),
            snapshot = BackupSnapshotInfo(
                sha256 = BackupFormat.sha256Hex(snapshotBytes),
                bytes = snapshotBytes.size.toLong(),
                records = snapshot.records.size,
                tombstones = snapshot.records.count { it.deleted },
            ),
        )
        val manifestBytes = BackupManifestJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()
        return BackupExportFiles(ExportSummary(manifest, readable.size, content.readFailures), manifestBytes, snapshotBytes, readable)
    }

    /** kind -> count, keys in sorted order (the manifest lists them sorted). */
    private fun sortedCounts(kinds: List<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        kinds.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.keys.sorted().associateWith { counts.getValue(it) }
    }
}

/** Checks what a platform read out of a backup zip ([BackupFormat]) and decodes it. */
object BackupParser {
    /**
     * [manifestBytes] / [snapshotBytes]: the two entries' bytes (null when the zip has no such entry);
     * [readableFiles]: how many entries sit under [BackupFormat.READABLE_DIR].
     */
    @Throws(BackupReadException::class)
    fun parse(manifestBytes: ByteArray?, snapshotBytes: ByteArray?, readableFiles: Int): ParsedBackup {
        val manifestText = manifestBytes?.decodeToString()
            ?: throw BackupReadException.NotABackup("No ${BackupFormat.MANIFEST_PATH}")
        val manifest = try {
            BackupManifestJson.decodeFromString(BackupManifest.serializer(), manifestText)
        } catch (e: SerializationException) {
            throw BackupReadException.NotABackup("Unreadable manifest")
        } catch (e: IllegalArgumentException) {
            throw BackupReadException.NotABackup("Unreadable manifest")
        }
        if (manifest.format != BackupFormat.FORMAT) throw BackupReadException.NotABackup("Not a Yoin backup")
        if (manifest.formatVersion > BackupFormat.FORMAT_VERSION) throw BackupReadException.TooNew(manifest.formatVersion)
        val bytes = snapshotBytes ?: throw BackupReadException.Corrupt("No ${BackupFormat.SNAPSHOT_PATH}")
        if (!BackupFormat.sha256Hex(bytes).equals(manifest.snapshot.sha256, ignoreCase = true)) {
            throw BackupReadException.ChecksumMismatch()
        }
        val snapshot = try {
            SnapshotCodec.decode(bytes)
        } catch (e: SyncSchemaTooNewException) {
            throw BackupReadException.TooNew(e.schema)
        } catch (e: MalformedSyncFileException) {
            throw BackupReadException.Corrupt("Unreadable snapshot", e)
        }
        if (snapshot.fileClass != BackupFormat.SNAPSHOT_FILE_CLASS) throw BackupReadException.Corrupt("Not a backup snapshot")
        return ParsedBackup(manifest, snapshot, readableFiles)
    }
}
