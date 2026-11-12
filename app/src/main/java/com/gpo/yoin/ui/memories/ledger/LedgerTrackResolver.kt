package com.gpo.yoin.ui.memories.ledger

import com.gpo.yoin.data.memory.LedgerTrack
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/*
 * A song note or score whose song Yoin never played, nor opened its album, has nothing local to name its album
 * (owner 2026-11-12: a row of 「歌曲评分 7.9 · 不知道是哪张专辑」 with a blank cover). Scores pulled from a
 * Subsonic server, or synced from another device, are like that. The ledger asks the song's service once: a
 * Subsonic account's own server (any account, not only the one in use), or the account in use for the others.
 * Answers live for the process; a song the service doesn't know is not asked again.
 */
internal class LedgerTrackResolver(
    private val profiles: ProfileManager,
) {
    /** What a lookup needs: [key] names the song in its own account. */
    data class Wanted(val profileId: String, val provider: String, val songId: String) {
        val key: String get() = "$profileId|$provider|$songId"
    }

    /**
     * The songs in [entries] whose album isn't known and that haven't been asked about. [providers] maps an
     * account to its service (an account has one).
     */
    fun pending(entries: List<MemoryLedgerEntry>, providers: Map<String, String>): List<Wanted> = entries.asSequence()
        .filter { it.album == null && it.songId != null }
        .filter { it.kind == MemoryLedgerKind.TrackRating || it.kind == MemoryLedgerKind.SongNote }
        .mapNotNull { entry -> providers[entry.profileId]?.let { Wanted(entry.profileId, it, entry.songId!!) } }
        .distinctBy { it.key }
        .filter { !answers.containsKey(it.key) }
        .toList()

    /** Asks for each of [wanted], a few at a time; returns whether any answer named an album or a song. */
    suspend fun resolve(wanted: List<Wanted>): Boolean {
        if (wanted.isEmpty()) return false
        val sources = HashMap<String, MusicSource?>()
        wanted.map { it.profileId }.distinct().forEach { sources[it] = sourceFor(it) }
        val gate = Semaphore(Parallel)
        val found = coroutineScope {
            wanted.map { want ->
                async {
                    gate.withPermit {
                        val source = sources[want.profileId]?.takeIf { it.id == want.provider } ?: return@withPermit false
                        val track = try {
                            source.library().getTrack(MediaId(want.provider, want.songId))
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // Offline or signed out: asked again on the next visit.
                            return@withPermit false
                        }
                        val answer = track?.toLedgerTrack() ?: Unknown
                        answers[want.key] = answer
                        // A name alone is news too: the song stops reading as unknown.
                        !answer.albumId.isNullOrBlank() || !answer.title.isNullOrBlank()
                    }
                }
            }.awaitAll()
        }
        return found.any { it }
    }

    /** The answer for a song already asked about, or null. */
    fun known(profileId: String, provider: String, songId: String): LedgerTrack? =
        answers["$profileId|$provider|$songId"]?.takeIf { it !== Unknown }

    private suspend fun sourceFor(profileId: String): MusicSource? {
        val active = profiles.activeSource.value
        if (profiles.activeProfileId.value == profileId && active != null) return active
        val profile = profiles.profiles.first().firstOrNull { it.id == profileId } ?: return null
        val credentials = profiles.decodeCredentials(profile) as? ProfileCredentials.Subsonic ?: return null
        return SubsonicMusicSource(
            credentials = ServerCredentials(credentials.serverUrl, credentials.username, credentials.password),
            profileId = profileId,
        )
    }

    private companion object {
        const val Parallel = 4

        /** Shared by every visit while the process lives. */
        val answers = ConcurrentHashMap<String, LedgerTrack>()

        /** The service doesn't know the song. */
        val Unknown = LedgerTrack(title = null, albumId = null, albumName = null, artist = null, coverKey = null)
    }
}

private fun Track.toLedgerTrack(): LedgerTrack = LedgerTrack(
    title = title,
    albumId = albumId?.rawId,
    albumName = album,
    artist = artist,
    coverKey = CoverRef.toStorageKey(coverArt),
)
