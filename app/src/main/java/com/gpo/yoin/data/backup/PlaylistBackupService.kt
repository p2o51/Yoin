package com.gpo.yoin.data.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.MatchMethod
import com.gpo.yoin.data.transfer.PlaylistTransfer
import com.gpo.yoin.data.transfer.PlaylistWrite
import com.gpo.yoin.data.transfer.RestoreReview
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.ReviewStatus
import com.gpo.yoin.data.transfer.TrackResolution
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The profiles a backup reads from and restores into; [ProfileManager] in the app, a fake in tests. */
interface BackupProfiles {
    val profiles: Flow<List<Profile>>

    suspend fun activeProfile(): Profile?

    /** [block] with profile [profileId]'s source; null when the profile is gone or can't be signed in. */
    suspend fun <T> withSource(profileId: String, block: suspend (MusicSource) -> T): T?
}

internal class ProfileManagerBackupProfiles(private val manager: ProfileManager) : BackupProfiles {
    override val profiles: Flow<List<Profile>> get() = manager.profiles

    override suspend fun activeProfile(): Profile? {
        val id = manager.activeProfileId.value ?: return null
        return manager.profiles.first().firstOrNull { it.id == id }
    }

    override suspend fun <T> withSource(profileId: String, block: suspend (MusicSource) -> T): T? =
        manager.withSourceFor(profileId, block)
}

/** A profile a backup can be restored into. */
data class RestoreTarget(
    val profileId: String,
    val displayName: String,
    val provider: String,
)

/** What a restore wrote, and the history entry it left on the backup. */
data class RestoreOutcome(
    val record: PlaylistRestoreRecord,
    val write: PlaylistWrite,
)

/**
 * Playlist backup and restore (docs/playlist-transfer.md §7). Back up: the
 * active profile's playlist, read whole, saved as a [PlaylistBackupRecord].
 * Restore, in two steps with the user's review between them: [match] the
 * backup's tracks on a target profile, then [create] a playlist there from
 * the rows the review left checked, recording the result in the backup's
 * history. Sources for other profiles are built per step and disposed after
 * it; the active profile never changes.
 */
class PlaylistBackupService(
    private val profiles: BackupProfiles,
    private val store: PlaylistBackupStore,
    private val transfer: PlaylistTransfer = PlaylistTransfer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(manager: ProfileManager, store: PlaylistBackupStore) :
        this(ProfileManagerBackupProfiles(manager), store)

    /** Reads [playlistId] afresh from the active profile, whole, and saves it as a new backup. */
    suspend fun backUp(playlistId: MediaId): PlaylistBackupRecord {
        val profile = checkNotNull(profiles.activeProfile()) { "No active profile" }
        val playlist = profiles.withSource(profile.id) { it.library().getPlaylistInFull(playlistId) }
            ?: throw IllegalStateException("Playlist not found")
        val record = PlaylistSnapshots.backup(
            id = newId(),
            playlist = playlist,
            provider = profile.provider,
            profileLabel = profile.displayName,
            now = clock(),
        )
        store.save(record)
        return record
    }

    /** Every profile whose service takes a new playlist, the backup's own included. */
    fun restoreTargets(): Flow<List<RestoreTarget>> = profiles.profiles.map(::restoreTargets)

    /** The backup's tracks resolved on [target], in backup order. */
    suspend fun match(
        backup: PlaylistBackupRecord,
        target: RestoreTarget,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<TrackResolution> = profiles.withSource(target.profileId) { source ->
        resolve(backup, target.provider, source, onProgress)
    } ?: throw IllegalStateException("Profile unavailable")

    internal suspend fun resolve(
        backup: PlaylistBackupRecord,
        targetProvider: String,
        target: MusicSource,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<TrackResolution> {
        val tracks = backup.content.tracks.map(PlaylistSnapshots::toTrack)
        // Spotify ids are the same in every account: a Spotify backup goes back by its own ids, and only
        // the tracks without one are matched.
        if (targetProvider != MediaId.PROVIDER_SPOTIFY) return transfer.resolve(tracks, target, onProgress)
        val own = tracks.withIndex().filter { it.value.id.provider == MediaId.PROVIDER_SPOTIFY }
        val others = tracks.withIndex().filter { it.value.id.provider != MediaId.PROVIDER_SPOTIFY }
        val matched = others.takeIf { it.isNotEmpty() }
            ?.let { list ->
                transfer.resolve(list.map { it.value }, target) { done, _ -> onProgress(own.size + done, tracks.size) }
                    .mapIndexed { i, resolution -> resolution.copy(position = list[i].index) }
            }
            .orEmpty()
        onProgress(tracks.size, tracks.size)
        val byId = own.map { TrackResolution(it.index, it.value, MatchDecision.Matched(it.value, MatchMethod.SourceId)) }
        return (byId + matched).sortedBy { it.position }
    }

    /**
     * A playlist on [target] named like the backup, holding [rows]' checked
     * tracks in backup order, and the restore added to the backup's history
     * (a failed write is recorded too).
     */
    suspend fun create(
        backup: PlaylistBackupRecord,
        target: RestoreTarget,
        rows: List<ReviewRow>,
    ): RestoreOutcome {
        val ids = RestoreReview.trackIdsToWrite(rows)
        val write = try {
            profiles.withSource(target.profileId) { source ->
                transfer.writeTracks(backup.name, backup.content.description, ids, source)
            } ?: PlaylistWrite(null, 0, ids.size, IllegalStateException("Profile unavailable"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PlaylistWrite(null, 0, ids.size, failure)
        }
        val record = restoreRecord(target, rows, write, clock())
        store.recordRestore(backup.id, record)
        return RestoreOutcome(record, write)
    }

    companion object {
        internal fun restoreTargets(profiles: List<Profile>): List<RestoreTarget> = profiles
            .filter { Capability.PLAYLIST_IMPORT in ServiceFeatureCatalog.forProvider(it.provider).capabilities }
            .map { RestoreTarget(it.id, it.displayName, it.provider) }

        /** What [write] made of [rows] on [target], as the backup's history keeps it. */
        internal fun restoreRecord(
            target: RestoreTarget,
            rows: List<ReviewRow>,
            write: PlaylistWrite,
            now: Long,
        ): PlaylistRestoreRecord {
            val notFound = rows.count { it.status == ReviewStatus.NotFound }
            return PlaylistRestoreRecord(
                at = now,
                targetProvider = target.provider,
                targetLabel = target.displayName,
                added = write.added,
                skipped = (rows.size - notFound - write.added).coerceAtLeast(0),
                notFound = notFound,
                failed = write.created == null,
            )
        }
    }
}
