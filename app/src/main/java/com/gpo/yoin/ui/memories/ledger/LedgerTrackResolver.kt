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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/*
 * A song note or score whose song Yoin never played, nor opened its album, has nothing local to name its album
 * (owner 2026-11-12: a row of 「歌曲评分 7.9 · 不知道是哪张专辑」 with a blank cover). Scores pulled from a
 * Subsonic server, or synced from another device, are like that. The ledger asks the song's service once: a
 * Subsonic account's own server (any account, not only the one in use), or the account in use for the others.
 * Answers live for the process; a song the service doesn't know is not asked again.
 *
 * Home's Memories grid reads the same ledger, so this runs when the app opens, not only on the page. A
 * server of one's own is asked a few songs at a time. A streaming service counts every request against the
 * account (Spotify: one `GET /tracks/{id}` a song), so it is asked as TrackAlbumLinkEnricher asks it: one
 * song at a time with a breath between, the newest few each pass, and the ledger shows each pass's answers
 * before asking for more. An account whose lookup fails (offline, rate limited, signed out) is left alone
 * until the ledger next reloads.
 */
internal class LedgerTrackResolver(
    private val profiles: ProfileManager,
    private val spacingMs: Long = SpacingMs,
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

    /** What a pass brought: [answered] songs have an answer now, and [found] says one named an album or a song. */
    data class Pass(val answered: Int, val found: Boolean)

    /** Asks for [wanted]: every song on a Subsonic server, the first few of a streaming service's. */
    suspend fun resolve(wanted: List<Wanted>): Pass {
        if (wanted.isEmpty()) return Pass(answered = 0, found = false)
        val sources = HashMap<String, MusicSource?>()
        wanted.map { it.profileId }.distinct().forEach { sources[it] = sourceFor(it) }
        val failed = ConcurrentHashMap.newKeySet<String>()

        // Null: no answer (the account failed); else whether the answer named an album or a song.
        suspend fun ask(want: Wanted): Boolean? {
            val source = sources[want.profileId] ?: return null
            if (want.profileId in failed) return null
            val track = try {
                source.library().getTrack(MediaId(want.provider, want.songId))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Offline, rate limited or signed out: the account's other songs would fail the same way.
                // Asked again on the next pass.
                failed += want.profileId
                return null
            }
            val answer = track?.toLedgerTrack() ?: Unknown
            answers[want.key] = answer
            return answer.isNews
        }
        val (servers, services) = wanted
            .filter { sources[it.profileId]?.id == it.provider }
            .partition { it.provider == MediaId.PROVIDER_SUBSONIC }
        val results = coroutineScope {
            val gate = Semaphore(Parallel)
            val fromServers = servers.map { want -> async { gate.withPermit { ask(want) } } }
            val fromServices = async {
                val asked = ArrayList<Boolean?>()
                if (services.isEmpty()) return@async asked
                // Home's ledger and the page's are two readers of one list: they take turns, so a streaming
                // service still sees one lookup at a time.
                serviceLane.withLock {
                    var requests = 0
                    for (want in services.take(ServicePerPass)) {
                        if (want.profileId in failed) break
                        // Answered by the other reader while this one waited its turn: news here as well.
                        val already = answers[want.key]
                        if (already != null) {
                            asked += already.isNews
                            continue
                        }
                        if (requests++ > 0 && spacingMs > 0) delay(spacingMs)
                        asked += ask(want)
                    }
                }
                asked
            }
            fromServers.awaitAll() + fromServices.await()
        }
        return Pass(answered = results.count { it != null }, found = results.any { it == true })
    }

    /** The answer for a song already asked about, or null. */
    fun known(profileId: String, provider: String, songId: String): LedgerTrack? =
        answers["$profileId|$provider|$songId"]?.takeIf { it !== Unknown }

    private suspend fun sourceFor(profileId: String): MusicSource? {
        val active = profiles.activeSource.value
        // During a switch the id in use and the source in use disagree for a beat: only a source built for
        // this account answers for it (another server's "no such song" would be kept as the answer).
        val builtFor = active?.profileId ?: profileId
        if (profiles.activeProfileId.value == profileId && active != null && builtFor == profileId) return active
        val profile = profiles.profiles.first().firstOrNull { it.id == profileId } ?: return null
        val credentials = profiles.decodeCredentials(profile) as? ProfileCredentials.Subsonic ?: return null
        return SubsonicMusicSource(
            credentials = ServerCredentials(credentials.serverUrl, credentials.username, credentials.password),
            profileId = profileId,
        )
    }

    private companion object {
        /** Lookups in flight on Subsonic servers. */
        const val Parallel = 4

        /** A streaming service's songs asked in one pass, newest first. */
        const val ServicePerPass = 20

        /** The enricher's breath between a streaming service's lookups. */
        const val SpacingMs = 250L

        /** Shared by every visit while the process lives. */
        val answers = ConcurrentHashMap<String, LedgerTrack>()

        /** One streaming-service pass at a time, whoever asks. */
        val serviceLane = Mutex()

        /** The service doesn't know the song. */
        val Unknown = LedgerTrack(title = null, albumId = null, albumName = null, artist = null, coverKey = null)
    }
}

/** Whether the answer changes what the ledger shows. A name alone does: the song stops reading as unknown. */
private val LedgerTrack.isNews: Boolean get() = !albumId.isNullOrBlank() || !title.isNullOrBlank()

private fun Track.toLedgerTrack(): LedgerTrack = LedgerTrack(
    title = title,
    albumId = albumId?.rawId,
    albumName = album,
    artist = artist,
    coverKey = CoverRef.toStorageKey(coverArt),
)
