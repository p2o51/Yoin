package com.gpo.yoin.data.home

import com.gpo.yoin.perf.YoinPerf
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A feed read back from [HomeSnapshotStore]: whose it is ([profileId], [provider]) and when it was written. */
class HomeSnapshot internal constructor(
    val profileId: String,
    val provider: String,
    val savedAt: Long,
    internal val feed: HomeFeedDto
)

/**
 * Home's feed per account (P2 PR3, owner Q14a): the last feed Home showed,
 * kept so a cold start paints it at once instead of a spinner, the fresh load
 * then replacing it block by block.
 *
 * One versioned JSON file per profile under `noBackupFilesDir/home_snapshot/`:
 * outside cloud backup and device transfer (data_extraction_rules.xml only
 * includes the database and the active-id prefs; Android never backs up
 * noBackupFilesDir), and never cleared by the system the way cacheDir is. It
 * holds only what the feed shows, covers as CoverRef storage keys
 * ([HomeSnapshotDtos]), and records the provider so a reader can drop a feed
 * that isn't of the account's source.
 *
 * Nothing is read until Home asks ([read], off the caller's thread), so
 * constructing the store costs nothing. Writes are conflated per profile and
 * capped ([save]); a write lands whole or not at all (temp file + move). An
 * unreadable file — corrupt, another format version, another profile's — is
 * deleted and read as none, never thrown.
 */
class HomeSnapshotStore(
    // Resolved on the IO dispatcher: getNoBackupFilesDir() may create the directory.
    private val directory: () -> File,
    // Where conflated writes run: outlives a ViewModel, so a queued write isn't lost with it.
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    // A burst of publishes (a load's tiers landing) settles into one write...
    private val settleMs: Long = SETTLE_MS,
    // ...and after a write the next waits at least this long.
    private val minWriteIntervalMs: Long = MIN_WRITE_INTERVAL_MS
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // Reads, writes and deletes of the files, one at a time.
    private val fileMutex = Mutex()

    // Guards the bookkeeping below; [save] runs on the caller's thread.
    private val lock = Any()
    private val pending = HashMap<String, PendingWrite>()
    private val writers = HashMap<String, Job>()

    // The feed each profile's file holds as of this process's last write: an
    // equal feed isn't written again.
    private val written = HashMap<String, Pair<String, HomeFeedDto>>()

    // Deleted profiles: a publish that was already on its way can't write
    // their snapshot back (profile ids are never reused).
    private val deleted = HashSet<String>()

    private class PendingWrite(val provider: String, val feed: () -> HomeFeedDto)

    /** [profileId]'s snapshot, or null when there is none or it can't be read (then it is deleted). */
    suspend fun read(profileId: String): HomeSnapshot? = withContext(ioDispatcher) {
        val perf = YoinPerf.begin("home.snapshot")
        var result = "miss"
        var bytes = 0
        var snapshot: HomeSnapshot? = null
        try {
            fileMutex.withLock {
                val file = fileFor(profileId)
                if (!file.exists()) return@withLock
                val text = try {
                    file.readText()
                } catch (_: IOException) {
                    result = "error"
                    return@withLock
                }
                bytes = text.length
                when (val decoded = decode(text, profileId)) {
                    is Decoded.Ok -> {
                        result = "hit"
                        snapshot = decoded.snapshot
                    }
                    is Decoded.Unreadable -> {
                        result = decoded.reason
                        file.delete()
                    }
                }
            }
        } finally {
            YoinPerf.end(
                perf,
                "result" to result,
                "bytes" to bytes,
                "age_s" to snapshot?.let { (clock() - it.savedAt) / 1_000L }
            )
        }
        snapshot
    }

    /**
     * Queue [profileId]'s feed for writing, built by [feed] when the write
     * runs (off the caller's thread). Conflated: the latest call wins. The
     * first write waits [settleMs] for a burst to settle; after a write the
     * next lands no sooner than [minWriteIntervalMs] later.
     */
    internal fun save(profileId: String, provider: String, feed: () -> HomeFeedDto) {
        synchronized(lock) {
            if (profileId in deleted) return
            pending[profileId] = PendingWrite(provider, feed)
            if (profileId in writers) return
            val writer = scope.launch(ioDispatcher) { drain(profileId) }
            writers[profileId] = writer
            // However it ends (done, cancelled, or never started), it frees the slot.
            writer.invokeOnCompletion {
                synchronized(lock) { if (writers[profileId] === writer) writers.remove(profileId) }
            }
        }
    }

    /** Drop [profileId]'s snapshot and any write still queued for it (the profile is being deleted). */
    suspend fun delete(profileId: String) {
        val writer = synchronized(lock) {
            deleted += profileId
            pending.remove(profileId)
            written.remove(profileId)
            writers.remove(profileId)
        }
        writer?.cancelAndJoin()
        withContext(ioDispatcher) {
            fileMutex.withLock {
                fileFor(profileId).delete()
                tempFor(profileId).delete()
            }
        }
    }

    private suspend fun drain(profileId: String) {
        while (true) {
            delay(settleMs)
            val next = synchronized(lock) { pending.remove(profileId) } ?: return
            write(profileId, next)
            delay(minWriteIntervalMs)
            synchronized(lock) {
                // Nothing new while it waited: this writer is done, its slot
                // freed under the same lock [save] checks, so no call slips by.
                if (profileId !in pending) {
                    writers.remove(profileId)
                    return
                }
            }
        }
    }

    private suspend fun write(profileId: String, next: PendingWrite) {
        val perf = YoinPerf.begin("home.snapshot.save")
        var result = "error"
        var bytes = 0
        try {
            val feed = next.feed()
            val unchanged = synchronized(lock) { written[profileId] == next.provider to feed }
            if (unchanged) {
                result = "same"
                return
            }
            val text = json.encodeToString(
                HomeSnapshotFileDto.serializer(),
                HomeSnapshotFileDto(
                    version = FORMAT_VERSION,
                    profileId = profileId,
                    provider = next.provider,
                    savedAt = clock(),
                    feed = feed
                )
            )
            bytes = text.length
            fileMutex.withLock {
                if (synchronized(lock) { profileId in deleted }) {
                    result = "deleted"
                    return
                }
                val dir = directory().apply { mkdirs() }
                val temp = File(dir, tempName(profileId))
                temp.writeText(text)
                moveIntoPlace(temp, File(dir, fileName(profileId)))
            }
            synchronized(lock) { if (profileId !in deleted) written[profileId] = next.provider to feed }
            result = "ok"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Best effort: the next publish writes again, and a read of a
            // half-written file can't happen (the move is the commit).
        } finally {
            YoinPerf.end(perf, "result" to result, "bytes" to bytes)
        }
    }

    private fun decode(text: String, profileId: String): Decoded = try {
        val element = json.parseToJsonElement(text)
        val version = element.jsonObject["version"]?.jsonPrimitive?.intOrNull
        if (version != FORMAT_VERSION) {
            Decoded.Unreadable("version")
        } else {
            val file = json.decodeFromJsonElement(HomeSnapshotFileDto.serializer(), element)
            if (file.profileId != profileId || file.provider.isBlank()) {
                Decoded.Unreadable("profile")
            } else {
                Decoded.Ok(HomeSnapshot(file.profileId, file.provider, file.savedAt, file.feed))
            }
        }
    } catch (_: SerializationException) {
        Decoded.Unreadable("corrupt")
    } catch (_: IllegalArgumentException) {
        // Not a JSON object, or a field of the wrong shape.
        Decoded.Unreadable("corrupt")
    }

    private sealed interface Decoded {
        class Ok(val snapshot: HomeSnapshot) : Decoded
        class Unreadable(val reason: String) : Decoded
    }

    private fun fileFor(profileId: String): File = File(directory(), fileName(profileId))

    private fun tempFor(profileId: String): File = File(directory(), tempName(profileId))

    private fun fileName(profileId: String): String = "${fileStem(profileId)}$SUFFIX"

    private fun tempName(profileId: String): String = "${fileStem(profileId)}$SUFFIX$TEMP_SUFFIX"

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** The file format. Bump on any change an older reader can't take as-is; old files are then dropped. */
        const val FORMAT_VERSION = 1

        /** Under noBackupFilesDir. */
        const val DIRECTORY_NAME = "home_snapshot"

        const val SETTLE_MS = 2_000L
        const val MIN_WRITE_INTERVAL_MS = 30_000L

        private const val SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private val SafeStem = Regex("[A-Za-z0-9_-]{1,64}")

        /** A profile id as a file name: itself when it is a plain id (UUIDs are), else its SHA-256. */
        internal fun fileStem(profileId: String): String = if (SafeStem.matches(profileId)) {
            profileId
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest(profileId.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
