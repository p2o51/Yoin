package com.gpo.yoin.ui.settings.sync

import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.transfer.memories.MemoryCatalog
import com.gpo.yoin.data.transfer.memories.MemorySource
import com.gpo.yoin.data.transfer.memories.TransferPlan
import com.gpo.yoin.shared.portability.ExportSummary
import com.gpo.yoin.shared.portability.ImportPlan
import com.gpo.yoin.shared.portability.ImportResult
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.shared.portability.memories.TransferAccount
import com.gpo.yoin.shared.portability.memories.TransferResult
import com.gpo.yoin.ui.settings.removeProfileCard
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the backup and 转移 Memories screens ask of the data layer ([com.gpo.yoin.data.sync.backup.MemoriesPortability]
 * plus the profiles' music services). An interface so the ViewModels are tested with a fake.
 */
interface MemoriesOps {
    fun backupFileName(): String

    suspend fun export(out: OutputStream): ExportSummary

    /** @throws com.gpo.yoin.shared.portability.BackupReadException */
    suspend fun readBackup(input: InputStream): ImportPlan

    suspend fun import(plan: ImportPlan, choices: Map<String, ImportTarget>): ImportResult

    suspend fun transferSources(): List<Pair<MemorySource, TransferAccount>>

    suspend fun planTransfer(
        source: MemorySource,
        targetProfileId: String,
        catalog: MemoryCatalog?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TransferPlan

    suspend fun runTransfer(plan: TransferPlan): TransferResult

    /**
     * [block] with a catalog over the target profile's service (and the source's, when the source is a profile on
     * this device); null when the target's service can't be built (signed out, credentials missing).
     */
    suspend fun <T> withCatalog(source: MemorySource, targetProfileId: String, block: suspend (MemoryCatalog) -> T): T?

    /** Removes a profile's card from this device, the same way Settings › account › Remove does. */
    suspend fun removeProfile(profileId: String)
}

/** [MemoriesOps] over the app's container. */
class AppMemoriesOps(private val container: AppContainer) : MemoriesOps {
    private val portability get() = container.memoriesPortability

    override fun backupFileName(): String = portability.backupFileName()

    override suspend fun export(out: OutputStream): ExportSummary = portability.export(out)

    override suspend fun readBackup(input: InputStream): ImportPlan = portability.readBackup(input)

    override suspend fun import(plan: ImportPlan, choices: Map<String, ImportTarget>): ImportResult =
        portability.import(plan, choices)

    override suspend fun transferSources(): List<Pair<MemorySource, TransferAccount>> = portability.transferSources()

    override suspend fun planTransfer(
        source: MemorySource,
        targetProfileId: String,
        catalog: MemoryCatalog?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): TransferPlan = portability.planTransfer(source, targetProfileId, catalog, onProgress)

    override suspend fun runTransfer(plan: TransferPlan): TransferResult = portability.runTransfer(plan)

    override suspend fun <T> withCatalog(
        source: MemorySource,
        targetProfileId: String,
        block: suspend (MemoryCatalog) -> T,
    ): T? = container.profileManager.withSourceFor(targetProfileId) { target ->
        val sourceProfile = (source as? MemorySource.LocalProfile)?.profileId
        if (sourceProfile == null) {
            block(ServiceMemoryCatalog(target, null))
        } else {
            // The source service only describes songs the local facts don't (ISRC lookups); missing is fine.
            container.profileManager.withSourceFor(sourceProfile) { from -> block(ServiceMemoryCatalog(target, from)) }
                ?: block(ServiceMemoryCatalog(target, null))
        }
    }

    override suspend fun removeProfile(profileId: String) = removeProfileCard(container, profileId)
}

/**
 * [MemoryCatalog] from the profiles' own services, as playlist restore searches them: the target's search and
 * ISRC lookup, and the source service's description of a song or album. Failures read as "nothing found".
 */
class ServiceMemoryCatalog(
    private val target: MusicSource,
    private val source: MusicSource?,
) : MemoryCatalog {
    override suspend fun describeSourceTrack(provider: String, trackId: String): Track? {
        val from = source?.takeIf { it.id == provider } ?: return null
        val id = MediaId(provider, trackId)
        val track = quietly { from.library().getTrack(id) } ?: return null
        if (track.extras[Track.EXTRA_ISRC] != null) return track
        val isrc = quietly { from.metadata().isrc(id) } ?: return track
        return track.copy(extras = track.extras + (Track.EXTRA_ISRC to isrc))
    }

    override suspend fun describeSourceAlbum(provider: String, albumId: String): Album? {
        val from = source?.takeIf { it.id == provider } ?: return null
        return quietly { from.library().getAlbum(MediaId(provider, albumId)) }
    }

    override suspend fun tracksByIsrc(isrcs: List<String>): Map<String, List<Track>> =
        quietly { target.library().tracksByIsrc(isrcs) }.orEmpty()

    override suspend fun searchTracks(query: String): List<Track> = quietly { target.library().search(query).tracks }.orEmpty()

    override suspend fun searchAlbums(query: String): List<Album> = quietly { target.library().search(query).albums }.orEmpty()

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
