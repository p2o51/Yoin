package com.gpo.yoin.ui.memories.ledger

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.local.MemoryLedgerDao
import com.gpo.yoin.data.memory.LedgerAlbumLookup
import com.gpo.yoin.data.memory.MemoryGrid
import com.gpo.yoin.data.memory.MemoryLedgerAlbum
import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerRows
import com.gpo.yoin.data.memory.MemoryLedgerStats
import com.gpo.yoin.data.memory.buildMemoryGrid
import com.gpo.yoin.data.memory.buildMemoryLedger
import com.gpo.yoin.data.memory.memoryLedgerStats
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.memories.otherProfileCoverUrl
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.profileCardsFlow
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** An account the ledger has memories from: what its filter chip and an entry's "from" line show. */
@Immutable
data class LedgerProfile(
    val id: String,
    val name: String,
    val shape: Int,
    val kind: ProviderKind,
    val isActive: Boolean,
)

/** Everything Home's Memories grid and the All Memories page show. */
@Immutable
data class MemoryLedgerState(
    /** Newest first. */
    val entries: List<MemoryLedgerEntry>,
    val stats: MemoryLedgerStats,
    val grid: MemoryGrid,
    /** Album key ([MemoryLedgerAlbum.key]) → its cover URL, for the albums that have one. */
    val coverUrls: Map<String, String>,
    /** The accounts with any memory, the one in use first. */
    val profiles: List<LedgerProfile>,
    val activeProfileId: String?,
    val today: LocalDate,
    val zone: ZoneId,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    fun profile(id: String): LedgerProfile? = profiles.firstOrNull { it.id == id }
}

/**
 * The memory ledger, kept fresh: every write to a note, review, score or title reloads it (Room re-runs
 * [MemoryLedgerDao.changes]), as does an account coming or going. Reads are local; nothing here writes.
 */
class MemoryLedgerViewModel internal constructor(
    private val dao: MemoryLedgerDao,
    private val profiles: ProfileManager,
    profileCards: Flow<List<ProfileCard>>,
    private val repository: YoinRepository,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MemoryLedgerState?> = combine(dao.changes(), profileCards) { _, cards -> cards }
        .mapLatest(::load)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun load(cards: List<ProfileCard>): MemoryLedgerState {
        val rows = MemoryLedgerRows(
            albumNotes = dao.albumNotes(),
            songNotes = dao.songNotes(),
            albumRatings = dao.albumRatings(),
            trackRatings = dao.trackRatings(),
            titles = dao.titles(),
            albumFacts = dao.albumFacts(),
        )
        // Only accounts that still exist: a removed account's rows aren't anyone's memories now.
        val entries = buildMemoryLedger(
            rows = rows,
            profileIds = cards.mapTo(HashSet()) { it.id },
            lookup = LedgerAlbumLookup { profileId, provider, albumId ->
                repository.getCachedAlbumFor(profileId, MediaId(provider, albumId))?.let { album ->
                    MemoryLedgerAlbum(
                        profileId = profileId,
                        provider = provider,
                        albumId = albumId,
                        name = album.name,
                        artist = album.artist.orEmpty(),
                        coverKey = CoverRef.toStorageKey(album.coverArt),
                    )
                }
            },
        )
        val withMemories = entries.mapTo(HashSet()) { it.profileId }
        val ledgerProfiles = cards
            .filter { it.id in withMemories }
            .sortedByDescending { it.isActive }
            .map { card -> LedgerProfile(card.id, card.title, card.avatarShape, card.provider, card.isActive) }
        val zone = zone()
        val today = today()
        return MemoryLedgerState(
            entries = entries,
            stats = memoryLedgerStats(entries),
            grid = buildMemoryGrid(
                entries = entries,
                today = today,
                zone = zone,
                firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
            ),
            coverUrls = coverUrls(entries, cards),
            profiles = ledgerProfiles,
            activeProfileId = cards.firstOrNull { it.isActive }?.id,
            today = today,
            zone = zone,
        )
    }

    // A URL key is the cover itself; a Subsonic cover id needs its own account's server (read once a pass).
    private suspend fun coverUrls(entries: List<MemoryLedgerEntry>, cards: List<ProfileCard>): Map<String, String> {
        val subsonic = cards.filter { it.provider == ProviderKind.SUBSONIC }.mapTo(HashSet()) { it.id }
        val servers = HashMap<String, ServerCredentials?>()
        val out = HashMap<String, String>()
        entries.mapNotNull { it.album }.distinctBy { it.key }.forEach { album ->
            val ref = CoverRef.fromStorageKey(album.coverKey) ?: return@forEach
            val server = if (ref is CoverRef.SourceRelative && album.profileId in subsonic) {
                servers.getOrPut(album.profileId) { subsonicServer(album.profileId) }
            } else {
                null
            }
            otherProfileCoverUrl(ref, CoverSize, server)?.let { out[album.key] = it }
        }
        return out
    }

    private suspend fun subsonicServer(profileId: String): ServerCredentials? {
        val profile = profiles.profiles.first().firstOrNull { it.id == profileId } ?: return null
        val credentials = profiles.decodeCredentials(profile) as? ProfileCredentials.Subsonic ?: return null
        return ServerCredentials(credentials.serverUrl, credentials.username, credentials.password)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MemoryLedgerViewModel(
            dao = container.database.memoryLedgerDao(),
            profiles = container.profileManager,
            profileCards = profileCardsFlow(container),
            repository = container.repository,
        ) as T
    }

    private companion object {
        /** The size covers are read at for their colours and the page's thumbnails. */
        const val CoverSize = 200
    }
}
