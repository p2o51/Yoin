package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.AlbumPlayHistoryAggregate
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.memory.resolveAlbumMemoryTitle
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

/**
 * An album Memory's title exactly as Memories shows it, for the surfaces that show it outside the deck: Home's
 * Jump Back In memory card and the album page's second page (owner, 2026-10-05, 「允许，统一」: Home and Memories
 * show the same default title, and the album page renames / restores it even when Yoin's own title is only the
 * motif).
 *
 * The title is the user's > Yoin's AI title > Yoin's motif > the album name ([resolveAlbumMemoryTitle]). Yoin's
 * part is composed by the very functions the Memories deck uses ([loadAlbumMemoryFacts], [albumMemoryAiTitle],
 * [composeAlbumMemoryCopy]); give this class the deck's clock, zone and app language (AppContainer does) and the
 * three surfaces cannot drift. One difference, by design: this class never asks Gemini. The AI title is read
 * from memory_copy_cache only — the deck is where it gets generated — and on a miss the motif takes the slot.
 *
 * Reads follow the active profile, as every per-account store. Every read is guarded: a failure costs part of
 * the title (the AI title, the motif's facts, the user's title) and falls one step down, never throws.
 */
class AlbumMemoryTitleResolver(
    private val repository: YoinRepository,
    /** The user's own titles; null = Yoin's titles only (previews, tests). */
    private val titleStore: AlbumMemoryTitleStore?,
    /** play_history: the plays behind the motif when there is no candidate ([resolve] by album id). */
    private val playHistoryDao: PlayHistoryDao,
    /** ProfileManager.activeProfileId: scopes the play_history read. */
    private val activeProfileId: StateFlow<String?>,
    /** "Today" for the motif's date grammar ("since February", a year when it is another one). */
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The prose language when the user has written nothing; must be the Memories deck's own. */
    private val appLanguage: () -> MemoryProseLanguage = { MemoryProseLanguage.EN },
) {
    /**
     * [candidate]'s title (Home's Jump Back In card, which already holds the candidate). Its play history,
     * album score and fallbacks are the candidate's own, exactly as the deck reads them; the album's rows come
     * through the repository's cached album detail plus a handful of local reads.
     */
    suspend fun resolve(candidate: AlbumMemoryCandidate): ResolvedMemoryTitle {
        val albumId = candidate.memoryAlbumId()
        val yoin = yoinTitle(albumId, fallbackName = candidate.albumName) { candidate.memoryBasis() }
        return yoin.withUserTitle(userTitle(albumId))
    }

    /**
     * [albumId]'s title without a candidate (the album page's second page): any album, Memory-eligible or not.
     * What a candidate would carry is read from the album's own rows instead — the album rating row, the
     * loaded tracks, and one play_history aggregate (the query the candidate builder fills its history from) —
     * so an album in the Memory pool gets the same title here as from [resolve] with its candidate.
     */
    suspend fun resolve(albumId: MediaId): ResolvedMemoryTitle {
        val id = memoryAlbumId(albumId)
        return yoinTitleById(id).withUserTitle(userTitle(id))
    }

    /**
     * [resolve] by id, live: the user's title follows [AlbumMemoryTitleStore.observeTitle] (a rename here, in
     * Memories or pulled by sync shows at once); Yoin's part is composed again on a profile switch and whenever
     * a memory signal lands ([YoinRepository.observeMemorySignalStamp]: a song note, a track rating, the album
     * rating / review). A new play does not re-compose it (the motif's play count catches up on the next
     * collection). Emits only changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(albumId: MediaId): Flow<ResolvedMemoryTitle> {
        val id = memoryAlbumId(albumId)
        val signals = repository.observeMemorySignalStamp().catch { emit(0L) }
        val yoin = combine(activeProfileId, signals) { profileId, stamp -> profileId to stamp }
            .distinctUntilChanged()
            .mapLatest { yoinTitleById(id) }
        val user = titleStore?.observeTitle(id)?.catch { emit(null) } ?: flowOf(null)
        return combine(yoin, user) { parts, userTitle -> parts.withUserTitle(userTitle) }.distinctUntilChanged()
    }

    private suspend fun yoinTitleById(albumId: MediaId): YoinTitleParts =
        yoinTitle(albumId, fallbackName = albumId.rawId) { facts ->
            val plays = albumPlays(albumId)
            AlbumMemoryBasis(
                // the candidate builder's chain: the album's name, the history's, the id
                albumName = plays?.albumName?.takeIf(String::isNotBlank) ?: albumId.rawId,
                albumRating = facts.ratingRow?.rating?.takeIf { rating -> rating > 0f },
                ratedTrackCount = facts.rated.size,
                totalTracks = facts.songs.size.takeIf { count -> count > 0 } ?: facts.album?.songCount ?: 0,
                history = plays?.let { row -> memoryPlayHistory(row.playCount, row.firstPlayedAt, row.lastPlayedAt) },
            )
        }

    /** Yoin's own title for [albumId]: the deck's steps, the AI title from the cache only. */
    private suspend fun yoinTitle(
        albumId: MediaId,
        fallbackName: String,
        basisOf: suspend (AlbumMemoryFacts) -> AlbumMemoryBasis,
    ): YoinTitleParts = try {
        val facts = loadAlbumMemoryFacts(repository, albumId)
        val copy = composeAlbumMemoryCopy(
            facts = facts,
            basis = basisOf(facts),
            aiTitle = albumMemoryAiTitle(repository, facts, generate = false),
            now = clock(),
            zone = zone(),
            appLanguage = appLanguage(),
        )
        YoinTitleParts(yoin = copy.yoinTitle, albumName = copy.input.albumName, language = copy.voice.language)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        YoinTitleParts(yoin = null, albumName = fallbackName, language = appLanguage())
    }

    private suspend fun albumPlays(albumId: MediaId): AlbumPlayHistoryAggregate? {
        val profileId = activeProfileId.value?.takeIf(String::isNotBlank) ?: return null
        return guarded {
            playHistoryDao.getAlbumAggregatesFor(profileId, albumId.provider, listOf(albumId.rawId)).firstOrNull()
        }
    }

    private suspend fun userTitle(albumId: MediaId): String? {
        val store = titleStore ?: return null
        return guarded { store.getTitle(albumId) }
    }

    private suspend fun <T> guarded(read: suspend () -> T?): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/**
 * One album Memory's title as every surface shows it ([AlbumMemoryTitleResolver]), with what an editor needs
 * around it: Yoin's own title under the user's, whether it can be restored and what that button says.
 */
data class ResolvedMemoryTitle(
    /** What the title slot shows. */
    val text: String,
    /** Where [text] came from: USER > AI > MOTIF > ALBUM. */
    val source: AlbumMemoryTitleSource,
    /**
     * The user's title is in use and Yoin has one of its own under it (AI or motif): offer "Restore"
     * ([restoreLabel]); restoring is [AlbumMemoryTitleStore.clearTitle]. False over the album name alone
     * (restoring onto it is just clearing the field).
     */
    val canRestoreGenerated: Boolean,
    /** Yoin's own title (the AI title, else the motif), shown or under the user's; null = the album name stands in. */
    val generatedText: String?,
    /** [generatedText]'s source: [AlbumMemoryTitleSource.AI] or [AlbumMemoryTitleSource.MOTIF]; null with it. */
    val generatedSource: AlbumMemoryTitleSource?,
    /** The language Yoin's prose for this album is in (the motif is written in it); not the user title's. */
    val proseLanguage: MemoryProseLanguage,
    /** The album's name: the ALBUM fallback and, when Yoin has no title, the editor's placeholder. */
    val albumName: String,
) {
    /** [source] as Memories' cards type it ([MemoryEntry.memoryTitleKind]): pick the title style from this. */
    val kind: MemoryTitleKind get() = source.toTitleKind()

    /**
     * The serif belongs to titles someone wrote for this memory: the user's own and Yoin's AI title. The motif
     * (rounded) and the album name (sans) are not set in it.
     */
    val isSerif: Boolean get() = source == AlbumMemoryTitleSource.USER || source == AlbumMemoryTitleSource.AI

    /** The user's own title, or null while Yoin's (or the album name) shows. */
    val userTitle: String? get() = text.takeIf { source == AlbumMemoryTitleSource.USER }

    /** The restore button's words ([memoryTitleRestoreLabel]): "Restore AI title" / "Restore Yoin's title". */
    val restoreLabel: String get() = memoryTitleRestoreLabel(generatedSource?.toTitleKind())

    /** What the editor's field opens with: the shown title, or nothing when the album name stands in for one. */
    val draftSeed: String get() = if (source == AlbumMemoryTitleSource.ALBUM) "" else text

    /** The empty field's hint (what an empty save shows): Yoin's own title, else the album name. */
    val placeholder: String get() = generatedText ?: albumName
}

/**
 * The restore button's words when Yoin's own title under the user's is [generatedKind]: Yoin's local motif is
 * not an AI line, so it is not called one. Memories' title editor and the album page both label with this.
 */
fun memoryTitleRestoreLabel(generatedKind: MemoryTitleKind?): String =
    if (generatedKind == MemoryTitleKind.MOTIF) "Restore Yoin's title" else "Restore AI title"

/** Yoin's side of a title before the user's is laid over it ([withUserTitle]). */
internal data class YoinTitleParts(
    /** The AI title or the motif; null when the album name stands in. */
    val yoin: YoinMemoryTitle?,
    val albumName: String,
    val language: MemoryProseLanguage,
) {
    /** [userTitle] (null = none) over Yoin's: the same [resolveAlbumMemoryTitle] the deck's cards go through. */
    fun withUserTitle(userTitle: String?): ResolvedMemoryTitle {
        val resolved = resolveAlbumMemoryTitle(
            userTitle = userTitle,
            aiTitle = yoin?.text?.takeIf { yoin.kind == MemoryTitleKind.AI },
            motif = yoin?.text?.takeIf { yoin.kind == MemoryTitleKind.MOTIF },
            albumName = albumName,
        )
        return ResolvedMemoryTitle(
            text = resolved.text,
            source = resolved.source,
            canRestoreGenerated = resolved.canRestoreGenerated,
            generatedText = yoin?.text,
            generatedSource = when (yoin?.kind) {
                MemoryTitleKind.AI -> AlbumMemoryTitleSource.AI
                MemoryTitleKind.MOTIF -> AlbumMemoryTitleSource.MOTIF
                else -> null
            },
            proseLanguage = language,
            albumName = albumName,
        )
    }
}
