package com.gpo.yoin.data.album

import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.TrackAlbumLinkDao
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.MusicSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Finds the album of each track the account in use noted or rated without one known (docs/spotoolfy-migration.md
 * §4.6): an imported Spotoolfy note carries a track id only, and until its album is linked the track can't reach
 * Memories or Rediscover. One `GET /tracks/{id}` per track, one at a time, so Spotify's rate limit gate paces it.
 *
 * Resumable: each answer is stored as it comes (a track that is gone as a link with no album, never asked again),
 * and a failure (offline, rate limited, signed out) ends the pass; the next one picks up where it stopped. Spotify
 * only: the other providers' tracks reach their albums through plays and album pages.
 */
class TrackAlbumLinkEnricher(
    private val links: TrackAlbumLinkDao,
    private val notes: SongNoteDao,
    private val activeSource: StateFlow<MusicSource?>,
    private val activeProfileId: StateFlow<String?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val spacingMs: Long = SPACING_MS,
) {
    private var job: Job? = null

    @Volatile
    private var again = false

    /**
     * Asks for a pass for the account in use: on foreground, on an account switch, right after an import. A kick
     * during a pass runs one more after it, so a switch mid-pass still gets the new account its own.
     */
    @Synchronized
    fun kick() {
        again = true
        if (job?.isActive == true) return
        job = scope.launch {
            while (again) {
                again = false
                runPass()
            }
        }
    }

    /** Links as many tracks as it can; returns how many it looked up. */
    suspend fun runPass(): Int {
        val source = activeSource.value ?: return 0
        val profileId = activeProfileId.value ?: return 0
        val provider = source.id
        if (provider != MediaId.PROVIDER_SPOTIFY) return 0
        var looked = 0
        while (true) {
            val batch = links.getUnlinkedTracks(profileId, provider, BATCH)
            if (batch.isEmpty()) return looked
            for (trackId in batch) {
                // The account changed under the pass: the next pass belongs to the new one.
                if (activeProfileId.value != profileId) return looked
                val track = try {
                    source.library().getTrack(MediaId(provider, trackId))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return looked
                }
                links.upsert(
                    TrackAlbumLink(
                        profileId = profileId,
                        provider = provider,
                        trackId = trackId,
                        albumId = track?.albumId?.rawId.orEmpty(),
                        source = TrackAlbumLink.SOURCE_IMPORT,
                        updatedAt = clock(),
                    ),
                )
                val title = track?.title?.takeIf { it.isNotBlank() }
                if (title != null) notes.fillMissingSong(trackId, provider, profileId, title, track?.artist.orEmpty())
                looked++
                if (spacingMs > 0) delay(spacingMs)
            }
        }
    }

    private companion object {
        const val BATCH = 50

        /** A breath between lookups: a big import shouldn't crowd out the requests the user is waiting on. */
        const val SPACING_MS = 250L
    }
}
