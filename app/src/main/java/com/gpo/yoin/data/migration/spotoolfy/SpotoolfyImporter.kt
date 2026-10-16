package com.gpo.yoin.data.migration.spotoolfy

import androidx.room.withTransaction
import com.gpo.yoin.data.local.ImportBatch
import com.gpo.yoin.data.local.ImportItem
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Brings a Spotoolfy export into a new user's account (docs/spotoolfy-migration.md §4.7, §5). Two steps:
 * - [stage]: read the file, keep it, record a staged batch with its recap. Nothing reaches an account yet; the
 *   landing shows the recap before Spotify is even connected.
 * - [commit]: once Spotify is connected, write the notes and ratings into that account, in one transaction, only
 *   if the account holds nothing of the user's yet (D1).
 *
 * Files live in [directory] (noBackupFilesDir): [pendingFile] while one waits for the landing, then
 * `<batchId>.json` until the batch is committed or dropped. Staging, committing and dropping take turns ([writes]):
 * "use another file" tapped while a commit runs waits for it, then takes back what it wrote.
 */
class SpotoolfyImporter(
    private val database: YoinDatabase,
    private val directory: File,
    private val segmenter: WordSegmenter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface ReceiveResult {
        data object Received : ReceiveResult

        data object TooLarge : ReceiveResult

        data object Unreadable : ReceiveResult
    }

    sealed interface StageResult {
        data class Staged(val batchId: String, val recap: SpotoolfyRecap) : StageResult

        /** This very file is already in an account. */
        data object AlreadyImported : StageResult

        data object NotSpotoolfy : StageResult

        data object Corrupt : StageResult

        data object TooLarge : StageResult

        /** A Spotoolfy file with no note or rating of the user's own (a fresh install has only sample rows). */
        data object Empty : StageResult
    }

    sealed interface CommitResult {
        data class Committed(val noteCount: Int, val ratingCount: Int) : CommitResult

        /** The account already holds notes, ratings or an import: imports only go into a new user's account. */
        data object NotEligible : CommitResult

        /**
         * Everything in the file is in Yoin already, under another account (an earlier export of the same notes):
         * note ids are global, so those notes stay where they are and nothing was written.
         */
        data object AlreadyInYoin : CommitResult

        /** The batch or its file is gone (dropped meanwhile, or storage was cleared). */
        data object Missing : CommitResult
    }

    private val batches get() = database.importBatchDao()
    private val writes = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _arrivals = MutableStateFlow(0)

    /** Moves each time a file is received, so an open landing picks it up. */
    val arrivals: StateFlow<Int> = _arrivals.asStateFlow()

    /** The file the share sheet or the file picker handed over, waiting for the landing's Spotoolfy step. */
    val pendingFile: File get() = File(directory, PENDING_FILE)

    fun hasPending(): Boolean = pendingFile.isFile

    /**
     * Copies [input] to [pendingFile], replacing one already waiting. Blocking: call it off the main thread, but
     * from the component that was handed the file — its read permission ends with it.
     */
    fun receive(input: InputStream): ReceiveResult {
        directory.mkdirs()
        val partial = File(directory, "$PENDING_FILE.part")
        val result = try {
            val fits = input.use { source -> partial.outputStream().use { target -> copyWithin(source, target) } }
            if (fits) ReceiveResult.Received else ReceiveResult.TooLarge
        } catch (_: IOException) {
            ReceiveResult.Unreadable
        } catch (_: SecurityException) {
            ReceiveResult.Unreadable
        }
        if (result != ReceiveResult.Received) {
            partial.delete()
            return result
        }
        pendingFile.delete()
        if (!partial.renameTo(pendingFile)) {
            partial.delete()
            return ReceiveResult.Unreadable
        }
        _arrivals.value += 1
        return result
    }

    /** Copies until [SpotoolfyExportParser.MAX_BYTES]; false when the source holds more. */
    private fun copyWithin(source: InputStream, target: java.io.OutputStream): Boolean {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = source.read(buffer)
            if (n < 0) return true
            total += n
            if (total > SpotoolfyExportParser.MAX_BYTES) return false
            target.write(buffer, 0, n)
        }
    }

    fun clearPending() {
        pendingFile.delete()
    }

    /** Stages [pendingFile] ([stage]). */
    suspend fun stagePending(): StageResult = stage(pendingFile)

    /**
     * Reads [file] and records it as the staged batch, replacing an earlier staged one. The file is moved into
     * the batch's keeping (or deleted when it can't be used). The same file again returns the batch it already is.
     */
    suspend fun stage(file: File): StageResult = withContext(io) { writes.withLock { stageLocked(file) } }

    private suspend fun stageLocked(file: File): StageResult {
        if (!file.isFile) return StageResult.Corrupt
        val sha = sha256(file)
        batches.getStaged().firstOrNull { it.fileSha256 == sha }?.let { staged ->
            val recap = decode(staged)
            if (recap != null && batchFile(staged.batchId).isFile) {
                if (file != batchFile(staged.batchId)) file.delete()
                return StageResult.Staged(staged.batchId, recap)
            }
        }
        if (batches.findCommittedByFile(sha) != null) {
            file.delete()
            return StageResult.AlreadyImported
        }
        val export = when (val parsed = file.inputStream().buffered().use(SpotoolfyExportParser::parse)) {
            is SpotoolfyParseResult.Parsed -> parsed.export
            SpotoolfyParseResult.NotSpotoolfy -> return StageResult.NotSpotoolfy.also { file.delete() }
            SpotoolfyParseResult.Corrupt -> return StageResult.Corrupt.also { file.delete() }
            SpotoolfyParseResult.TooLarge -> return StageResult.TooLarge.also { file.delete() }
        }
        val plan = SpotoolfyImportMapper.map(export)
        if (plan.isEmpty) {
            file.delete()
            return StageResult.Empty
        }
        val recap = SpotoolfyRecapCalculator(segmenter).compute(plan)
        discardStagedLocked()
        val batchId = UUID.randomUUID().toString()
        val kept = batchFile(batchId)
        if (!file.renameTo(kept)) {
            file.copyTo(kept, overwrite = true)
            file.delete()
        }
        batches.upsert(
            ImportBatch(
                batchId = batchId,
                source = ImportBatch.SOURCE_SPOTOOLFY,
                status = ImportBatch.STATUS_STAGED,
                profileId = null,
                fileSha256 = sha,
                importedAt = clock(),
                noteCount = plan.notes.size,
                ratingCount = plan.ratings.size,
                summaryJson = json.encodeToString(SpotoolfyRecap.serializer(), recap),
            ),
        )
        return StageResult.Staged(batchId, recap)
    }

    /** The batch waiting for an account, if any. */
    suspend fun staged(): ImportBatch? = withContext(io) { batches.getStaged().firstOrNull() }

    suspend fun batch(batchId: String): ImportBatch? = withContext(io) { batches.get(batchId) }

    suspend fun recap(batchId: String): SpotoolfyRecap? = withContext(io) { batches.get(batchId)?.let(::decode) }

    /** The newest batch written to [profileId] (Settings › About › Spotoolfy recap). */
    fun observeLatestCommitted(profileId: String): Flow<ImportBatch?> = batches.observeLatestCommitted(profileId)

    /** Whether [profileId] can still take an import: nothing of the user's in it yet (§4.1). */
    suspend fun isEligible(profileId: String): Boolean = withContext(io) { batches.countUserData(profileId) == 0 }

    /**
     * Writes [batchId] into [profileId]: every note (titled [unknownTitle] when Spotoolfy had no title) and every
     * track's latest rating, each recorded as the batch's, then the batch is committed and its file deleted. A note
     * whose id another account already holds stays there and is neither written nor recorded, so taking this batch
     * back out never touches it. Committing a batch already in [profileId] again is a no-op that reports what it
     * wrote.
     */
    suspend fun commit(batchId: String, profileId: String, unknownTitle: String): CommitResult = withContext(io) {
        writes.withLock { commitLocked(batchId, profileId, unknownTitle) }
    }

    private suspend fun commitLocked(batchId: String, profileId: String, unknownTitle: String): CommitResult {
        val batch = batches.get(batchId) ?: return CommitResult.Missing
        if (batch.status == ImportBatch.STATUS_COMMITTED) {
            return if (batch.profileId == profileId) {
                CommitResult.Committed(batch.noteCount, batch.ratingCount)
            } else {
                CommitResult.NotEligible
            }
        }
        val file = batchFile(batchId)
        if (!file.isFile) return CommitResult.Missing
        val parsed = file.inputStream().buffered().use(SpotoolfyExportParser::parse)
        val export = (parsed as? SpotoolfyParseResult.Parsed)?.export ?: return CommitResult.Missing
        val plan = SpotoolfyImportMapper.map(export)
        val provider = MediaId.PROVIDER_SPOTIFY
        val notes = plan.notes.map { note ->
            SongNote(
                id = note.id,
                profileId = profileId,
                trackId = note.trackId,
                provider = provider,
                content = note.content,
                createdAt = note.recordedAt,
                updatedAt = note.recordedAt,
                title = note.title ?: unknownTitle,
                artist = note.artist.orEmpty(),
                positionMs = note.positionMs,
            )
        }
        val ratings = plan.ratings.map { rating ->
            LocalRating(
                profileId = profileId,
                songId = rating.trackId,
                provider = provider,
                rating = rating.rating,
                serverRating = (rating.rating / 2f).roundToInt(),
                needsSync = false,
                updatedAt = rating.recordedAt,
            )
        }
        val result: CommitResult = try {
            writeBatch(batchId, profileId, notes, ratings)
        } catch (_: BatchGone) {
            CommitResult.Missing
        }
        if (result is CommitResult.Committed) file.delete()
        return result
    }

    /** [commitLocked]'s transaction. */
    private suspend fun writeBatch(
        batchId: String,
        profileId: String,
        notes: List<SongNote>,
        ratings: List<LocalRating>,
    ): CommitResult {
        val provider = MediaId.PROVIDER_SPOTIFY
        return database.withTransaction {
            // Checked again inside the transaction: cloud sync may have brought this account's data down meanwhile.
            if (batches.get(batchId)?.status != ImportBatch.STATUS_STAGED) return@withTransaction CommitResult.Missing
            if (batches.countUserData(profileId) > 0) return@withTransaction CommitResult.NotEligible
            val writtenNotes = notes.chunked(WRITE_CHUNK).flatMap { chunk ->
                val rowIds = database.songNoteDao().insertAllIfAbsent(chunk)
                chunk.filterIndexed { index, _ -> rowIds[index] != -1L }
            }
            if (writtenNotes.isEmpty() && ratings.isEmpty()) return@withTransaction CommitResult.AlreadyInYoin
            ratings.chunked(WRITE_CHUNK).forEach { database.localRatingDao().upsertAll(it) }
            val items = writtenNotes.map { ImportItem(batchId, ImportItem.KIND_SONG_NOTE, it.id) } +
                ratings.map { rating ->
                    val key = ImportItem.ratingKey(profileId, provider, rating.songId)
                    ImportItem(batchId, ImportItem.KIND_LOCAL_RATING, key)
                }
            items.chunked(WRITE_CHUNK).forEach { batches.insertItems(it) }
            // Checked above in this same transaction; should the row still be gone, the writes roll back.
            if (batches.markCommitted(batchId, profileId, writtenNotes.size, ratings.size) != 1) throw BatchGone()
            CommitResult.Committed(writtenNotes.size, ratings.size)
        }
    }

    private class BatchGone : RuntimeException()

    /**
     * Drops [batchId] whatever its state: a staged batch and its file go; a committed one first takes back every
     * row it wrote ("use another file", §4.7: in a new user's account those rows are all there is).
     */
    suspend fun discard(batchId: String): Unit = withContext(io) { writes.withLock { discardLocked(batchId) } }

    private suspend fun discardLocked(batchId: String) {
        val batch = batches.get(batchId)
        if (batch?.status == ImportBatch.STATUS_COMMITTED) {
            database.withTransaction {
                batches.getItemKeys(batchId, ImportItem.KIND_SONG_NOTE)
                    .chunked(WRITE_CHUNK)
                    .forEach { database.songNoteDao().deleteByIds(it, batch.profileId.orEmpty()) }
                batches.getItemKeys(batchId, ImportItem.KIND_LOCAL_RATING)
                    .mapNotNull { key -> key.split('|').takeIf { it.size == 3 } }
                    .groupBy({ (profileId, provider, _) -> profileId to provider }, { (_, _, songId) -> songId })
                    .forEach { (scope, songIds) ->
                        val (profileId, provider) = scope
                        songIds.chunked(WRITE_CHUNK).forEach { chunk ->
                            database.localRatingDao().deleteForSongs(chunk, provider, profileId)
                        }
                    }
                batches.deleteItems(batchId)
                batches.delete(batchId)
            }
        } else if (batch != null) {
            batches.delete(batchId)
        }
        batchFile(batchId).delete()
    }

    /** Every staged batch and its file: the landing ended without an account to put them in. */
    suspend fun discardStaged(): Unit = withContext(io) { writes.withLock { discardStagedLocked() } }

    /**
     * A first run that ended between Spotify connecting and its file going in (the process went, and with an
     * account in place the landing never comes back): the staged batch goes into [spotifyProfileId], the account
     * that run connected, as the landing would have put it. Whatever can't go in is dropped, as at a landing's end.
     * Null when nothing was staged or that run connected no Spotify account.
     */
    suspend fun resumeInterrupted(spotifyProfileId: String?, unknownTitle: String): CommitResult? = withContext(io) {
        writes.withLock {
            val staged = batches.getStaged().firstOrNull()
            val result = if (staged != null && spotifyProfileId != null) {
                commitLocked(staged.batchId, spotifyProfileId, unknownTitle)
            } else {
                null
            }
            discardStagedLocked()
            clearPending()
            result
        }
    }

    private suspend fun discardStagedLocked() {
        batches.getStaged().forEach { staged ->
            batches.delete(staged.batchId)
            batchFile(staged.batchId).delete()
        }
    }

    private fun batchFile(batchId: String) = File(directory, "$batchId.json")

    private fun decode(batch: ImportBatch): SpotoolfyRecap? =
        runCatching { json.decodeFromString(SpotoolfyRecap.serializer(), batch.summaryJson) }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PENDING_FILE = "pending.json"
        const val BUFFER_BYTES = 64 * 1024

        /** Rows per statement: under SQLite's bound-parameter limit for the IN (...) deletes. */
        const val WRITE_CHUNK = 500
    }
}
