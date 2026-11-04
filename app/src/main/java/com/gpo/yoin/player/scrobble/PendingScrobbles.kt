package com.gpo.yoin.player.scrobble

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** One play the server hasn't heard yet. [timeMs] is when it was listened to (epoch ms). */
@Serializable
data class PendingScrobble(
    val profileId: String,
    val songId: String,
    val timeMs: Long,
)

/** The offline queue's rules, pure so they can move to :shared with [ScrobbleTracker]. */
object PendingScrobbles {
    /** Months of offline listening; past this the oldest plays go first. */
    const val MAX_PENDING = 2_000

    /** Plays sent in one scrobble call (Subsonic takes repeated id/time pairs). */
    const val BATCH_SIZE = 50

    fun add(queue: List<PendingScrobble>, play: PendingScrobble): List<PendingScrobble> =
        if (play in queue) queue else (queue + play).takeLast(MAX_PENDING)

    /** The oldest plays for [profileId]: a play is only ever sent to the account it was heard on. */
    fun batchFor(queue: List<PendingScrobble>, profileId: String, size: Int = BATCH_SIZE): List<PendingScrobble> =
        queue.asSequence().filter { it.profileId == profileId }.take(size).toList()

    fun remove(queue: List<PendingScrobble>, sent: Collection<PendingScrobble>): List<PendingScrobble> {
        if (sent.isEmpty()) return queue
        val gone = sent.toHashSet()
        return queue.filterNot { it in gone }
    }
}

/** Where the offline queue lives between launches. */
interface PendingScrobbleStore {
    fun load(): List<PendingScrobble>
    fun save(queue: List<PendingScrobble>)
}

/** A JSON file, replaced whole on each save; an unreadable file reads as empty. */
class FilePendingScrobbleStore(private val file: File) : PendingScrobbleStore {
    private val serializer = ListSerializer(PendingScrobble.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<PendingScrobble> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (error: IOException) {
        emptyList()
    } catch (error: IllegalArgumentException) {
        // SerializationException: a half-written or older file. Losing the queue beats crashing.
        emptyList()
    }

    override fun save(queue: List<PendingScrobble>) {
        file.parentFile?.mkdirs()
        if (queue.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, queue))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
