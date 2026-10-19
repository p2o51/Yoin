package com.gpo.yoin.data.backup

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
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

/** What following one backup found. */
enum class FollowResult { Changed, Unchanged, Gone, Skipped }

data class FollowSummary(val checked: Int, val changed: Int, val gone: Int)

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

    /**
     * Starts [playlistId]'s cloud backup on the active profile, or — when it
     * already has one — backs it up again now. Read whole, afresh.
     */
    suspend fun backUp(playlistId: MediaId): PlaylistBackupRecord {
        val profile = checkNotNull(profiles.activeProfile()) { "No active profile" }
        val playlist = profiles.withSource(profile.id) { it.library().getPlaylistInFull(playlistId) }
            ?: throw IllegalStateException("Playlist not found")
        // Spotify refused its songs (someone else's playlist, development mode): nothing to back up.
        check(!playlist.tracksUnavailable) { "Playlist songs unavailable" }
        val now = clock()
        val existing = store.findBySource(profile.provider, playlist.id.toString())
        val record = existing
            ?.let { PlaylistSnapshots.refreshed(it, playlist, profile.id, profile.displayName, now) }
            ?: PlaylistSnapshots.backup(newId(), playlist, profile.provider, profile.id, profile.displayName, now)
        store.save(record)
        return record
    }

    /** The cloud backup that follows [playlistId] on [provider], if any (the playlist page's menu). */
    suspend fun backupOf(provider: String, playlistId: MediaId): PlaylistBackupRecord? =
        store.findBySource(provider, playlistId.toString())

    /**
     * Follows every backup this device can follow that hasn't been checked
     * for [interval]: each playlist read again and saved when it changed, or
     * marked gone when its service no longer lists it. A profile that can't
     * be reached (offline, signed out) is left for next time.
     */
    suspend fun followDue(interval: Long = FOLLOW_INTERVAL_MS): FollowSummary {
        val now = clock()
        val local = profiles.profiles.first().associateBy { it.id }
        val due = store.getAll().filter { backup ->
            backup.following && backup.sourceProfileId in local && now - backup.lastBackedUpAt >= interval
        }
        var changed = 0
        var gone = 0
        due.groupBy { it.sourceProfileId!! }.forEach { (profileId, backups) ->
            val profile = local.getValue(profileId)
            val results = runCatching {
                profiles.withSource(profileId) { source -> backups.map { follow(it, profile, source) } }
            }.getOrNull().orEmpty()
            changed += results.count { it == FollowResult.Changed }
            gone += results.count { it == FollowResult.Gone }
        }
        return FollowSummary(checked = due.size, changed = changed, gone = gone)
    }

    /**
     * [backup] read again now, from this device's profile that follows it:
     * Back up now on its page. Throws when no profile here can read it.
     */
    suspend fun followNow(backup: PlaylistBackupRecord): FollowResult {
        val profileId = checkNotNull(backup.sourceProfileId) { "Not followed on this device" }
        val profile = checkNotNull(profiles.profiles.first().firstOrNull { it.id == profileId }) { "Profile gone" }
        return checkNotNull(profiles.withSource(profileId) { follow(backup, profile, it) }) { "Profile unavailable" }
    }

    internal suspend fun follow(backup: PlaylistBackupRecord, profile: Profile, source: MusicSource): FollowResult {
        val id = MediaId.parseOrNull(backup.sourcePlaylistId) ?: return FollowResult.Skipped
        // Gone = the service answers with its list and the playlist isn't in it; a failed answer proves nothing.
        val listed = runCatching { source.library().getPlaylists() }.getOrNull() ?: return FollowResult.Skipped
        val now = clock()
        if (listed.none { it.id == id }) {
            store.save(backup.copy(sourceGoneAt = now, updatedAt = maxOf(now, backup.updatedAt + 1)))
            return FollowResult.Gone
        }
        val playlist = runCatching { source.library().getPlaylistInFull(id) }.getOrNull() ?: return FollowResult.Skipped
        // Refused songs aren't an empty playlist: keep the backup as it was.
        if (playlist.tracksUnavailable) return FollowResult.Skipped
        val next = PlaylistSnapshots.refreshed(backup, playlist, profile.id, profile.displayName, now)
        store.save(next)
        return if (next.lastChangedAt == now) FollowResult.Changed else FollowResult.Unchanged
    }

    /** Every profile whose service takes a new playlist, the backup's own included. */
    fun restoreTargets(): Flow<List<RestoreTarget>> = profiles.profiles.map(::restoreTargets)

    /** The backup's tracks resolved on [target], in backup order. */
    suspend fun match(
        backup: PlaylistBackupRecord,
        target: RestoreTarget,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResolved: (TrackResolution) -> Unit = {},
    ): List<TrackResolution> = profiles.withSource(target.profileId) { source ->
        resolve(backup, target.provider, source, onProgress, onResolved)
    } ?: throw IllegalStateException("Profile unavailable")

    internal suspend fun resolve(
        backup: PlaylistBackupRecord,
        targetProvider: String,
        target: MusicSource,
        onProgress: (done: Int, total: Int) -> Unit,
        onResolved: (TrackResolution) -> Unit = {},
    ): List<TrackResolution> {
        val tracks = backup.content.tracks.map(PlaylistSnapshots::toTrack)
        // Spotify ids are the same in every account: a Spotify backup goes back by its own ids, and only
        // the tracks without one are matched.
        if (targetProvider != MediaId.PROVIDER_SPOTIFY) return transfer.resolve(tracks, target, onProgress, onResolved)
        val own = tracks.withIndex().filter { it.value.id.provider == MediaId.PROVIDER_SPOTIFY }
        val others = tracks.withIndex().filter { it.value.id.provider != MediaId.PROVIDER_SPOTIFY }
        val matched = others.takeIf { it.isNotEmpty() }
            ?.let { list ->
                transfer.resolve(
                    list.map { it.value },
                    target,
                    onProgress = { done, _ -> onProgress(own.size + done, tracks.size) },
                    onResolved = { resolution -> onResolved(resolution.copy(position = list[resolution.position].index)) },
                ).mapIndexed { i, resolution -> resolution.copy(position = list[i].index) }
            }
            .orEmpty()
        onProgress(tracks.size, tracks.size)
        val byId = own.map { (index, track) ->
            TrackResolution(index, track, MatchDecision.Matched(track, MatchMethod.SourceId)).also(onResolved)
        }
        return (byId + matched).sortedBy { it.position }
    }

    /** Songs on [target] for [query]: the review's search for a song the match didn't find. */
    suspend fun search(target: RestoreTarget, query: String): List<Track> =
        profiles.withSource(target.profileId) { it.library().search(query).tracks }.orEmpty()

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
        /** How often a followed playlist is read again (on app start or return, never in the background). */
        const val FOLLOW_INTERVAL_MS = 6 * 60 * 60 * 1000L

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
