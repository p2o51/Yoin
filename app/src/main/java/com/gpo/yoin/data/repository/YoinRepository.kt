package com.gpo.yoin.data.repository

import androidx.room.withTransaction
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumNoteDao
import com.gpo.yoin.data.local.AlbumNoteKey
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.GeminiConfigDao
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsCacheDao
import com.gpo.yoin.data.local.MemoryCopyCache
import com.gpo.yoin.data.local.MemoryCopyCacheDao
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongAboutEntryDao
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SongNoteDao
import com.gpo.yoin.data.local.SongNoteKey
import com.gpo.yoin.data.local.SpotifyHomeAlbumCache
import com.gpo.yoin.data.local.SpotifyHomeArtistCache
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.integration.neodb.NeoDBSyncService
import com.gpo.yoin.data.lyrics.LrcParser
import com.gpo.yoin.data.lyrics.LyricsProviderRegistry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.PlaylistItemRef
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.remote.GeminiService
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.data.source.spotify.SpotifyMusicSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Provider-agnostic orchestrator over local Room + the currently active
 * [MusicSource]. Remote calls are dispatched through `activeSource`; local
 * persistence (ratings, history, activity, song info) stays in Room.
 *
 * Adding a new backend means implementing [MusicSource] — nothing in this
 * class should need to change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YoinRepository(
    private val activeSource: StateFlow<MusicSource?>,
    private val activeProfileId: StateFlow<String?>,
    private val database: YoinDatabase,
    private val geminiService: GeminiService,
    private val songAboutEntryDao: SongAboutEntryDao,
    private val geminiConfigDao: GeminiConfigDao,
    private val lyricsCacheDao: LyricsCacheDao,
    private val songNoteDao: SongNoteDao,
    private val albumNoteDao: AlbumNoteDao,
    private val albumRatingDao: AlbumRatingDao,
    private val memoryCopyCacheDao: MemoryCopyCacheDao,
    private val neoDbSyncService: NeoDBSyncService,
    private val lyricsProviderRegistry: LyricsProviderRegistry = LyricsProviderRegistry(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class SpotifyHomeJumpBackInCacheSnapshot(
        val albums: List<Album>,
        val artists: List<com.gpo.yoin.data.model.Artist>,
    )

    sealed interface AboutLoadResult {
        /** Canonical rows were already present (or successfully fetched). */
        data object Success : AboutLoadResult
        data object ApiKeyMissing : AboutLoadResult
        data class Error(val message: String) : AboutLoadResult
    }

    sealed interface AskAboutResult {
        data class Success(val answer: String) : AskAboutResult
        data object ApiKeyMissing : AskAboutResult
        data class Error(val message: String) : AskAboutResult
    }

    /** True when a configured profile is currently active. */
    val isConfigured: Boolean
        get() = activeSource.value != null

    /**
     * Capability set of the currently active [MusicSource], or empty when no
     * profile is active. Single source of truth for UI gating — feature
     * composables should collect this (e.g. via `stateIn` in a ViewModel)
     * rather than reaching for `AppContainer.profileManager.activeSource`.
     */
    val capabilities: Flow<Set<Capability>> =
        activeSource.map { source -> source?.capabilities ?: emptySet() }

    /** Snapshot of [capabilities] for synchronous reads (e.g. click handlers). */
    fun currentCapabilities(): Set<Capability> =
        activeSource.value?.capabilities ?: emptySet()

    /**
     * Provider id of the currently active source (e.g. `"subsonic"` /
     * `"spotify"`), or `null` when no profile is active. Exposed as a flow
     * so backend-specific code (e.g. PlaybackManager) can react to
     * profile switches without peeking inside ProfileManager.
     */
    val activeProviderId: Flow<String?> =
        activeSource.map { source -> source?.id }

    /** Synchronous snapshot of [activeProviderId]. */
    fun currentProviderId(): String? = activeSource.value?.id

    /** Synchronous snapshot of the active profile id. */
    fun currentProfileId(): String? = activeProfileId.value

    private fun requireSource(): MusicSource = activeSource.value
        ?: throw SubsonicException(
            code = -1,
            message = "No profile configured. Open Settings to add one.",
        )

    // ── Albums ─────────────────────────────────────────────────────────

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0): List<Album> =
        requireSource().library().getAlbumList(type, size, offset)

    suspend fun getAlbum(id: MediaId): Album? =
        requireSource().library().getAlbum(id)

    // ── Artists ────────────────────────────────────────────────────────

    suspend fun getArtists(): List<ArtistIndex> =
        requireSource().library().getArtists()

    suspend fun getArtist(id: MediaId): ArtistDetail? =
        requireSource().library().getArtist(id)

    // ── Search ─────────────────────────────────────────────────────────

    suspend fun search(query: String): SearchResults =
        requireSource().library().search(query)

    // ── Favorites ──────────────────────────────────────────────────────

    suspend fun setFavorite(id: MediaId, favorite: Boolean): Result<Unit> =
        requireSource().writeActions().setFavorite(id, favorite)

    suspend fun getStarred(): Starred =
        requireSource().library().getStarred()

    // ── Random ─────────────────────────────────────────────────────────

    suspend fun getRandomSongs(size: Int = 20): List<Track> =
        requireSource().library().getRandomSongs(size)

    // ── Spotify Home cache ────────────────────────────────────────────

    suspend fun getCachedSpotifyHomeJumpBackIn(
        profileId: String,
        maxAgeMs: Long,
    ): SpotifyHomeJumpBackInCacheSnapshot {
        val minCachedAt = System.currentTimeMillis() - maxAgeMs
        val dao = database.spotifyHomeCacheDao()
        return SpotifyHomeJumpBackInCacheSnapshot(
            albums = dao.getFreshAlbums(profileId, minCachedAt).map(SpotifyHomeAlbumCache::toAlbum),
            artists = dao.getFreshArtists(profileId, minCachedAt).map(SpotifyHomeArtistCache::toArtist),
        )
    }

    suspend fun replaceSpotifyHomeJumpBackInCache(
        profileId: String,
        albums: List<Album>,
        artists: List<com.gpo.yoin.data.model.Artist>,
        cachedAt: Long = System.currentTimeMillis(),
    ) {
        database.withTransaction {
            val dao = database.spotifyHomeCacheDao()
            dao.deleteAlbumsForProfile(profileId)
            dao.deleteArtistsForProfile(profileId)
            dao.insertAlbums(
                albums
                    .distinctBy { album -> album.id }
                    .mapIndexed { index, album ->
                        album.toSpotifyHomeAlbumCache(
                            profileId = profileId,
                            sortOrder = index,
                            cachedAt = cachedAt,
                        )
                    },
            )
            dao.insertArtists(
                artists
                    .distinctBy { artist -> artist.id }
                    .mapIndexed { index, artist ->
                        artist.toSpotifyHomeArtistCache(
                            profileId = profileId,
                            sortOrder = index,
                            cachedAt = cachedAt,
                        )
                    },
            )
        }
    }

    // ── Playlists ──────────────────────────────────────────────────────

    suspend fun getPlaylists(): List<Playlist> =
        requireSource().library().getPlaylists()

    suspend fun getPlaylist(id: MediaId): Playlist? =
        requireSource().library().getPlaylist(id)

    suspend fun createPlaylist(name: String, description: String? = null): Result<Playlist> =
        requireSource().writeActions().createPlaylist(name = name, description = description)

    suspend fun renamePlaylist(
        id: MediaId,
        name: String,
        description: String? = null,
    ): Result<Unit> =
        requireSource().writeActions().renamePlaylist(id = id, name = name, description = description)

    suspend fun deletePlaylist(id: MediaId): Result<Unit> =
        requireSource().writeActions().deletePlaylist(id)

    suspend fun addTracksToPlaylist(
        playlistId: MediaId,
        tracks: List<MediaId>,
    ): Result<String?> =
        requireSource().writeActions().addTracksToPlaylist(playlistId = playlistId, tracks = tracks)

    suspend fun removeTracksFromPlaylist(
        playlistId: MediaId,
        items: List<PlaylistItemRef>,
        snapshotId: String? = null,
    ): Result<String?> =
        requireSource().writeActions().removeTracksFromPlaylist(
            playlistId = playlistId,
            items = items,
            snapshotId = snapshotId,
        )

    // ── Rating (local-first, best-effort server sync) ──────────────────

    suspend fun setRating(trackId: MediaId, rating: Float) {
        val localRating = rating.coerceIn(0f, 10f)
        val serverRating = (localRating / 2f).roundToInt().coerceIn(0, 5)
        val pending = LocalRating(
            songId = trackId.rawId,
            provider = trackId.provider,
            rating = localRating,
            serverRating = serverRating,
            needsSync = true,
        )
        database.localRatingDao().upsert(pending)
        val source = activeSource.value ?: return
        if (source.id != trackId.provider) return
        source.writeActions().setRating(trackId, serverRating).onSuccess {
            database.localRatingDao().upsert(pending.copy(needsSync = false))
        }
    }

    fun getRating(trackId: MediaId): Flow<LocalRating?> =
        database.localRatingDao().getRating(trackId.rawId, trackId.provider)

    suspend fun getRatings(trackIds: Collection<MediaId>): Map<MediaId, LocalRating> {
        if (trackIds.isEmpty()) return emptyMap()
        return trackIds.asSequence()
            .distinct()
            .groupBy { it.provider }
            .flatMap { (provider, ids) ->
                database.localRatingDao().getRatings(ids.map { it.rawId }, provider)
            }
            .associateBy { MediaId(it.provider, it.songId) }
    }

    suspend fun syncPendingRatings() {
        val source = activeSource.value ?: return
        val pending = database.localRatingDao().getRatingsNeedingSync().first()
        for (rating in pending) {
            if (rating.provider != source.id) continue
            val trackId = MediaId(rating.provider, rating.songId)
            source.writeActions().setRating(trackId, rating.serverRating).onSuccess {
                database.localRatingDao().upsert(rating.copy(needsSync = false))
            }
        }
    }

    // ── Notes ──────────────────────────────────────────────────────────

    fun observeNotes(trackId: MediaId): Flow<List<SongNote>> =
        songNoteDao.observeForTrack(trackId.rawId, trackId.provider)

    fun observeCrossProviderNotes(
        trackId: MediaId,
        title: String,
        artist: String,
    ): Flow<List<SongNote>> {
        val normalizedTitle = title.trim()
        val normalizedArtist = artist.trim()
        if (normalizedTitle.isEmpty() || normalizedArtist.isEmpty()) {
            return flowOf(emptyList())
        }
        return songNoteDao.observeCrossProvider(
            title = normalizedTitle,
            artist = normalizedArtist,
            trackId = trackId.rawId,
            provider = trackId.provider,
        )
    }

    fun observeTracksWithNotes(trackIds: Collection<MediaId>): Flow<Set<MediaId>> {
        val distinctTrackIds = trackIds.distinct()
        if (distinctTrackIds.isEmpty()) {
            return flowOf(emptySet())
        }

        val groupedFlows = distinctTrackIds
            .groupBy(MediaId::provider)
            .map { (provider, ids) ->
                songNoteDao.observeKeys(
                    trackIds = ids.map(MediaId::rawId),
                    provider = provider,
                )
            }

        if (groupedFlows.size == 1) {
            return groupedFlows.first().map { keys -> keys.toMediaIdSet() }
        }

        return combine(groupedFlows) { groups ->
            buildSet {
                groups.forEach { keys ->
                    addAll(keys.toMediaIdSet())
                }
            }
        }
    }

    /** User tapped Save —— 为当前曲目追加一条新的笔记。content 空串会被忽略。 */
    suspend fun addNote(track: Track, content: String): SongNote? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val now = clock()
        val note = SongNote(
            id = java.util.UUID.randomUUID().toString(),
            trackId = track.id.rawId,
            provider = track.id.provider,
            content = trimmed,
            createdAt = now,
            updatedAt = now,
            title = track.title.orEmpty(),
            artist = track.artist.orEmpty(),
        )
        songNoteDao.insert(note)
        return note
    }

    suspend fun updateNote(note: SongNote, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            songNoteDao.deleteById(note.id)
            return
        }
        songNoteDao.update(note.copy(content = trimmed, updatedAt = clock()))
    }

    suspend fun deleteNoteById(id: String) {
        songNoteDao.deleteById(id)
    }

    // ── Album notes ────────────────────────────────────────────────────

    fun observeAlbumNotes(albumId: MediaId): Flow<List<AlbumNote>> =
        albumNoteDao.observeForAlbum(albumId.rawId, albumId.provider)

    fun observeAlbumsWithNotes(albumIds: Collection<MediaId>): Flow<Set<MediaId>> {
        val distinct = albumIds.distinct()
        if (distinct.isEmpty()) return flowOf(emptySet())
        val grouped = distinct.groupBy(MediaId::provider)
            .map { (provider, ids) ->
                albumNoteDao.observeKeys(ids.map(MediaId::rawId), provider)
            }
        if (grouped.size == 1) {
            return grouped.first().map { keys -> keys.toAlbumMediaIdSet() }
        }
        return combine(grouped) { groups ->
            buildSet { groups.forEach { addAll(it.toAlbumMediaIdSet()) } }
        }
    }

    suspend fun addAlbumNote(album: Album, content: String): AlbumNote? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        val now = clock()
        val note = AlbumNote(
            id = java.util.UUID.randomUUID().toString(),
            albumId = album.id.rawId,
            provider = album.id.provider,
            content = trimmed,
            createdAt = now,
            updatedAt = now,
            albumName = album.name,
            artist = album.artist.orEmpty(),
        )
        albumNoteDao.insert(note)
        return note
    }

    suspend fun updateAlbumNote(note: AlbumNote, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            albumNoteDao.deleteById(note.id)
            return
        }
        albumNoteDao.update(note.copy(content = trimmed, updatedAt = clock()))
    }

    suspend fun deleteAlbumNoteById(id: String) {
        albumNoteDao.deleteById(id)
    }

    /**
     * 把该专辑的单曲笔记原文按 (曲目序 → content) 拼起来 —— 只用于 AlbumDetail
     * 的默认聚合展示和 Review 草稿灵感。不推到 NeoDB。
     */
    suspend fun aggregateSongNotesForAlbum(album: Album): String {
        if (album.tracks.isEmpty()) return ""
        val trackIds = album.tracks.map(Track::id)
        return trackIds.asSequence()
            .distinct()
            .groupBy(MediaId::provider)
            .flatMap { (provider, ids) ->
                ids.flatMap { id ->
                    songNoteDao.observeForTrack(id.rawId, provider).first()
                }
            }
            .sortedBy(SongNote::createdAt)
            .joinToString(separator = "\n\n") { note -> note.content }
    }

    // ── Album ratings / review ─────────────────────────────────────────

    fun observeAlbumRating(albumId: MediaId): Flow<AlbumRating?> =
        albumRatingDao.observe(albumId.rawId, albumId.provider)

    suspend fun setAlbumRating(album: Album, rating: Float) {
        val existing = albumRatingDao.get(album.id.rawId, album.id.provider)
        val entry = (existing ?: AlbumRating(
            albumId = album.id.rawId,
            provider = album.id.provider,
            rating = 0f,
            review = null,
            neoDbReviewUuid = null,
        )).copy(
            rating = rating.coerceIn(0f, 10f),
            ratingNeedsSync = true,
            updatedAt = clock(),
        )
        albumRatingDao.upsert(entry)
    }

    suspend fun setAlbumReview(album: Album, review: String?) {
        val normalized = review?.trim().takeUnless { it.isNullOrEmpty() }
        val existing = albumRatingDao.get(album.id.rawId, album.id.provider)
        val entry = (existing ?: AlbumRating(
            albumId = album.id.rawId,
            provider = album.id.provider,
            rating = 0f,
            review = null,
            neoDbReviewUuid = null,
        )).copy(
            review = normalized,
            reviewNeedsSync = true,
            updatedAt = clock(),
        )
        albumRatingDao.upsert(entry)
    }

    suspend fun pushAlbumToNeoDB(album: Album): Result<Unit> =
        neoDbSyncService.pushAlbum(album)

    suspend fun pullAlbumFromNeoDB(album: Album): Result<AlbumRating?> =
        neoDbSyncService.pullAlbum(album)

    suspend fun isNeoDBConfigured(): Boolean = neoDbSyncService.isConfigured()

    // ── Memory copy cache (Gemini 感性文案) ─────────────────────────────

    /**
     * 读/生成 Memory 专辑卡片的 Gemini 短评。命中缓存直接返回；否则
     * 后台调用 Gemini 并落库。Key 为 (albumId, provider, signal hash)；
     * signal 变了会重算（如均分或覆盖数变化）。
     *
     * 返回 null 表示无 API key / 没开 BYOK —— 调用方应默默降级，不弹错。
     */
    suspend fun getOrGenerateAlbumMemoryCopy(
        album: Album,
        averageRating: Float?,
        ratedSongCount: Int,
        totalSongCount: Int,
    ): String? {
        val signal = buildString {
            append(album.id.toString()).append('|')
            append(album.name).append('|')
            append(album.artist.orEmpty()).append('|')
            append(album.year ?: 0).append('|')
            append(averageRating ?: 0f).append('|')
            append(ratedSongCount).append('/').append(totalSongCount)
        }
        val hash = signal.hashCode().toString()

        val cached = memoryCopyCacheDao.get(
            provider = album.id.provider,
            entityType = MemoryCopyCache.ENTITY_ALBUM,
            entityId = album.id.rawId,
        )
        if (cached != null && cached.promptHash == hash) return cached.copy

        val apiKey = geminiConfigDao.getConfig().first()?.apiKey
        if (apiKey.isNullOrBlank()) return cached?.copy

        return runCatching {
            val generated = geminiService.generateAlbumMemoryCopy(
                apiKey = apiKey,
                albumName = album.name,
                artist = album.artist,
                year = album.year,
                averageRating = averageRating,
                ratedSongCount = ratedSongCount,
                totalSongCount = totalSongCount,
            )
            memoryCopyCacheDao.upsert(
                MemoryCopyCache(
                    provider = album.id.provider,
                    entityType = MemoryCopyCache.ENTITY_ALBUM,
                    entityId = album.id.rawId,
                    copy = generated,
                    promptHash = hash,
                    generatedAt = clock(),
                ),
            )
            generated
        }.getOrElse { cached?.copy }
    }

    // ── Devices ───────────────────────────────────────────────────────

    suspend fun listSpotifyDevices(): List<YoinDevice.SpotifyConnect> {
        val source = activeSource.value as? SpotifyMusicSource ?: return emptyList()
        return source.listDevices()
            .mapNotNull { device ->
                val id = device.id ?: return@mapNotNull null
                YoinDevice.SpotifyConnect(
                    id = id,
                    name = device.name,
                    isActive = device.isActive,
                    spotifyType = device.type,
                    isSelectable = !device.isRestricted,
                    statusText = when {
                        device.isRestricted -> "Unavailable from Spotify"
                        device.isPrivateSession -> "Private session"
                        else -> null
                    },
                )
            }
            .sortedByDescending(YoinDevice.SpotifyConnect::isActive)
    }

    suspend fun transferSpotifyPlayback(deviceId: String, play: Boolean = true) {
        val source = activeSource.value as? SpotifyMusicSource ?: return
        source.transferPlayback(deviceId = deviceId, play = play)
    }

    // ── Lyrics ─────────────────────────────────────────────────────────

    /**
     * Subsonic 走自家 `getLyricsBySongId.view`（服务端快，不走本地缓存）；其他
     * provider（目前只有 Spotify）没有服务器歌词，先查 [lyricsCacheDao] 30 天
     * 内的命中，否则走 [LyricsProviderRegistry] 串行兜底（QQ → 网易云 → LRCLIB）
     * 并把原始 LRC 落表。
     *
     * 需要 [title] + [artist] 做搜索；任一为空就直接返回 null。
     */
    suspend fun getLyrics(
        trackId: MediaId,
        title: String? = null,
        artist: String? = null,
    ): Lyrics? {
        val source = requireSource()
        if (source.id == MediaId.PROVIDER_SUBSONIC) {
            return source.metadata().getLyrics(trackId)
        }
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        if (t.isEmpty() || a.isEmpty()) return null

        val now = clock()
        val minCachedAt = now - LYRICS_CACHE_TTL_MS
        lyricsCacheDao
            .getFresh(trackId.provider, trackId.rawId, minCachedAt)
            ?.let { return LrcParser.parse(it.lrc) }

        val hit = lyricsProviderRegistry.fetchLyric(t, a) ?: return null
        lyricsCacheDao.upsert(
            LyricsCache(
                trackProvider = trackId.provider,
                trackRawId = trackId.rawId,
                lyricsProvider = hit.providerName,
                lrc = hit.lrc,
                cachedAt = now,
            ),
        )
        return LrcParser.parse(hit.lrc)
    }

    /**
     * Observe every About entry (canonical + ask) for the given song,
     * ordered canonical-first then ask by `updatedAt desc`. The same song
     * played from a different profile/provider will emit identical rows
     * because the key is derived from normalized `title + artist + album`.
     */
    fun observeAbout(
        title: String,
        artist: String,
        album: String,
    ): Flow<List<SongAboutEntry>> = songAboutEntryDao.observe(
        titleKey = SongAboutEntry.normalize(title),
        artistKey = SongAboutEntry.normalize(artist),
        albumKey = SongAboutEntry.normalize(album),
    )

    /**
     * Ensure the 6 canonical About rows exist for this song. No-op when
     * canonical rows are already cached unless [retry] is true, in which
     * case the call re-fetches and overwrites.
     *
     * Called lazily on first About open (compact or fullscreen), not per
     * track change.
     */
    suspend fun ensureCanonicalAbout(
        title: String,
        artist: String,
        album: String,
        retry: Boolean = false,
    ): AboutLoadResult {
        val titleKey = SongAboutEntry.normalize(title)
        val artistKey = SongAboutEntry.normalize(artist)
        val albumKey = SongAboutEntry.normalize(album)

        if (!retry) {
            val existing = songAboutEntryDao.getCanonical(titleKey, artistKey, albumKey)
            if (existing.isNotEmpty()) return AboutLoadResult.Success
        }

        val apiKey = geminiConfigDao.getConfig().first()?.apiKey
        if (apiKey.isNullOrBlank()) return AboutLoadResult.ApiKeyMissing

        return runCatching {
            val values = geminiService.generateCanonicalAbout(
                apiKey = apiKey,
                title = title,
                artist = artist,
                album = album,
            )
            val now = clock()
            val rows = values.map { value ->
                SongAboutEntry(
                    titleKey = titleKey,
                    artistKey = artistKey,
                    albumKey = albumKey,
                    titleDisplay = title,
                    artistDisplay = artist,
                    albumDisplay = album,
                    kind = SongAboutEntry.KIND_CANONICAL,
                    entryKey = value.entryKey,
                    promptText = null,
                    titleText = null,
                    answerText = value.answer,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            if (rows.isNotEmpty()) songAboutEntryDao.upsertAll(rows)
            AboutLoadResult.Success
        }.getOrElse { error ->
            AboutLoadResult.Error(error.message ?: "Failed to load song about info")
        }
    }

    /**
     * Ask Gemini a free-form question about the song. On success, upsert
     * an `ask` row keyed by the normalized question. Re-asking the same
     * normalized question updates the answer and `updatedAt` while
     * preserving the original `createdAt`.
     */
    suspend fun askAboutSong(
        title: String,
        artist: String,
        album: String,
        question: String,
    ): AskAboutResult {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            return AskAboutResult.Error("Question is empty")
        }

        val apiKey = geminiConfigDao.getConfig().first()?.apiKey
        if (apiKey.isNullOrBlank()) return AskAboutResult.ApiKeyMissing

        val titleKey = SongAboutEntry.normalize(title)
        val artistKey = SongAboutEntry.normalize(artist)
        val albumKey = SongAboutEntry.normalize(album)
        val questionKey = SongAboutEntry.normalize(trimmedQuestion)

        return runCatching {
            val reply = geminiService.askAboutSong(
                apiKey = apiKey,
                title = title,
                artist = artist,
                album = album,
                question = trimmedQuestion,
            )
            val now = clock()
            val existing = songAboutEntryDao.getAsk(titleKey, artistKey, albumKey, questionKey)
            val row = SongAboutEntry(
                titleKey = titleKey,
                artistKey = artistKey,
                albumKey = albumKey,
                titleDisplay = title,
                artistDisplay = artist,
                albumDisplay = album,
                kind = SongAboutEntry.KIND_ASK,
                entryKey = questionKey,
                promptText = trimmedQuestion,
                titleText = reply.title,
                answerText = reply.answer,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            songAboutEntryDao.upsert(row)
            AskAboutResult.Success(reply.answer)
        }.getOrElse { error ->
            AskAboutResult.Error(error.message ?: "Failed to ask Gemini")
        }
    }

    // ── Play history / activity ────────────────────────────────────────

    suspend fun recordPlay(
        track: Track,
        durationMs: Long,
        completedPercent: Float,
        activityContext: ActivityContext = ActivityContext.None,
    ) {
        val profileId = activeProfileId.value ?: return
        database.playHistoryDao().insert(
            PlayHistory(
                songId = track.id.rawId,
                profileId = profileId,
                provider = track.id.provider,
                title = track.title.orEmpty(),
                artist = track.artist.orEmpty(),
                album = track.album.orEmpty(),
                albumId = track.albumId?.rawId.orEmpty(),
                // Store URL for Spotify (CoverRef.Url) or raw id for Subsonic
                // (CoverRef.SourceRelative). Readers use CoverRef.fromStorageKey
                // to reconstitute, so both providers round-trip correctly.
                coverArtId = CoverRef.toStorageKey(track.coverArt),
                durationMs = durationMs,
                completedPercent = completedPercent,
            ),
        )
        database.activityEventDao().insert(buildPlaybackActivity(track, activityContext, profileId))
    }

    fun getRecentHistory(limit: Int = 50): Flow<List<PlayHistory>> =
        combine(activeSource, activeProfileId) { source, profileId ->
            source?.id to profileId
        }.flatMapLatest { (provider, profileId) ->
            if (provider == null || profileId.isNullOrBlank()) {
                return@flatMapLatest flowOf(emptyList())
            }
            database.playHistoryDao().getRecentHistory(profileId, provider, limit)
        }

    fun getRecentActivities(limit: Int = 20): Flow<List<ActivityEvent>> =
        combine(activeSource, activeProfileId) { source, profileId ->
            source?.id to profileId
        }.flatMapLatest { (provider, profileId) ->
            if (provider == null || profileId.isNullOrBlank()) {
                return@flatMapLatest flowOf(emptyList())
            }
            database.activityEventDao().getRecentEvents(profileId, provider, limit * 8)
        }
            .map { events -> collapseToLatestUnique(events).take(limit) }

    suspend fun getRecentMemoryActivities(limit: Int = 48): List<ActivityEvent> {
        val provider = activeSource.value?.id ?: return emptyList()
        val profileId = activeProfileId.value ?: return emptyList()
        return database.activityEventDao()
            .getRecentEvents(profileId, provider, limit * 10)
            .first()
            .filter { it.actionType == ActivityActionType.PLAYED.name }
            .filter { it.entityType == ActivityEntityType.ALBUM.name }
            .let(::collapseToLatestUnique)
            .asSequence()
            .take(limit)
            .toList()
    }

    suspend fun getMostRecentPlay(trackId: MediaId): PlayHistory? =
        activeProfileId.value?.let { profileId ->
            database.playHistoryDao().getMostRecentPlay(trackId.rawId, trackId.provider, profileId)
        }

    suspend fun recordAlbumVisit(album: Album) {
        val profileId = activeProfileId.value ?: return
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = album.id.rawId,
                profileId = profileId,
                provider = album.id.provider,
                title = album.name,
                subtitle = album.artist.orEmpty(),
                // Storage key encodes both provider flavours — URL for Spotify,
                // raw id for Subsonic. Fallback to the album's raw id only when
                // a SourceRelative artwork ref was missing (legacy Subsonic
                // path where cover id == album id).
                coverArtId = CoverRef.toStorageKey(album.coverArt)
                    ?: album.id.rawId.takeIf { album.id.provider == MediaId.PROVIDER_SUBSONIC },
                albumId = album.id.rawId,
                artistId = album.artistId?.rawId,
            ),
        )
    }

    suspend fun recordArtistVisit(artist: ArtistDetail) {
        val profileId = activeProfileId.value ?: return
        database.activityEventDao().insert(
            ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = artist.id.rawId,
                profileId = profileId,
                provider = artist.id.provider,
                title = artist.name,
                subtitle = "Artist",
                coverArtId = CoverRef.toStorageKey(artist.coverArt),
                artistId = artist.id.rawId,
            ),
        )
    }

    // ── URLs (provider-agnostic) ───────────────────────────────────────

    fun resolveCoverUrl(ref: CoverRef?, size: Int? = null): String? =
        ref?.let { activeSource.value?.resolveCoverUrl(it, size) }

    /** Convenience for legacy Subsonic cover-art-id callers that haven't
     *  migrated to [CoverRef] yet — wraps as [CoverRef.SourceRelative]. */
    fun resolveSubsonicCoverUrl(coverArtId: String?, size: Int? = null): String? =
        coverArtId?.let { resolveCoverUrl(CoverRef.SourceRelative(it), size) }

    // ── Server test ────────────────────────────────────────────────────

    suspend fun testConnection(): Boolean =
        requireSource().library().ping()

    // ── Internal helpers ───────────────────────────────────────────────

    private fun buildPlaybackActivity(
        track: Track,
        activityContext: ActivityContext,
        profileId: String,
    ): ActivityEvent {
        val trackProvider = track.id.provider
        return when (activityContext) {
            is ActivityContext.Album -> ActivityEvent(
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.albumId,
                profileId = profileId,
                provider = trackProvider,
                title = activityContext.albumName,
                subtitle = activityContext.artistName.orEmpty()
                    .ifBlank { track.artist.orEmpty() },
                coverArtId = activityContext.coverArtId
                    ?: CoverRef.toStorageKey(track.coverArt)
                    ?: fallbackCoverKeyForSubsonic(trackProvider, track.albumId?.rawId),
                songId = track.id.rawId,
                albumId = activityContext.albumId,
                artistId = activityContext.artistId ?: track.artistId?.rawId,
            )

            is ActivityContext.Artist -> ActivityEvent(
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.artistId,
                profileId = profileId,
                provider = trackProvider,
                title = activityContext.artistName,
                subtitle = "Artist",
                coverArtId = activityContext.coverArtId
                    ?: CoverRef.toStorageKey(track.coverArt),
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = activityContext.artistId,
            )

            is ActivityContext.Playlist -> ActivityEvent(
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = activityContext.playlistId,
                profileId = profileId,
                provider = trackProvider,
                title = activityContext.playlistName,
                subtitle = activityContext.owner.orEmpty().ifBlank { "Playlist" },
                coverArtId = activityContext.coverArtId
                    ?: CoverRef.toStorageKey(track.coverArt)
                    ?: fallbackCoverKeyForSubsonic(trackProvider, track.albumId?.rawId),
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = track.artistId?.rawId,
            )

            ActivityContext.None -> ActivityEvent(
                entityType = ActivityEntityType.SONG.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = track.id.rawId,
                profileId = profileId,
                provider = trackProvider,
                title = track.title.orEmpty(),
                subtitle = track.artist.orEmpty(),
                coverArtId = CoverRef.toStorageKey(track.coverArt)
                    ?: fallbackCoverKeyForSubsonic(trackProvider, track.albumId?.rawId),
                songId = track.id.rawId,
                albumId = track.albumId?.rawId,
                artistId = track.artistId?.rawId,
            )
        }
    }

    /**
     * Last-resort fallback for activity rows whose track ships without any
     * cover-art ref. On Subsonic the album id is usable as a cover-art id
     * (they're the same namespace on most servers), but on Spotify the
     * album id is a bare Spotify id with no URL shape — using it would
     * poison the storage key, so we return null instead and let the UI
     * render a placeholder.
     */
    private fun fallbackCoverKeyForSubsonic(provider: String, albumRawId: String?): String? =
        albumRawId?.takeIf { provider == MediaId.PROVIDER_SUBSONIC }

private fun collapseToLatestUnique(events: List<ActivityEvent>): List<ActivityEvent> {
        val seenKeys = mutableSetOf<String>()
        val collapsed = mutableListOf<ActivityEvent>()
        events.forEach { event ->
            val stableKey = "${event.actionType}:${event.entityType}:${event.entityId}:${event.provider}"
            if (seenKeys.add(stableKey)) {
                collapsed += event
            }
        }
        return collapsed
    }

    companion object {
        /** 歌词缓存 TTL：30 天。Provider 返回的内容在这个窗口内复用不重拉。 */
        private val LYRICS_CACHE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
    }
}

private fun List<SongNoteKey>.toMediaIdSet(): Set<MediaId> =
    mapTo(linkedSetOf()) { key -> MediaId(key.provider, key.trackId) }

private fun List<AlbumNoteKey>.toAlbumMediaIdSet(): Set<MediaId> =
    mapTo(linkedSetOf()) { key -> MediaId(key.provider, key.albumId) }

private fun Album.toSpotifyHomeAlbumCache(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): SpotifyHomeAlbumCache = SpotifyHomeAlbumCache(
    profileId = profileId,
    albumId = id.toString(),
    name = name,
    artist = artist,
    artistId = artistId?.toString(),
    coverArtKey = CoverRef.toStorageKey(coverArt),
    songCount = songCount,
    year = year,
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)

private fun SpotifyHomeAlbumCache.toAlbum(): Album = Album(
    id = MediaId.parse(albumId),
    name = name,
    artist = artist,
    artistId = artistId?.let(MediaId::parse),
    coverArt = CoverRef.fromStorageKey(coverArtKey),
    songCount = songCount,
    durationSec = null,
    year = year,
    genre = null,
    tracks = emptyList(),
)

private fun com.gpo.yoin.data.model.Artist.toSpotifyHomeArtistCache(
    profileId: String,
    sortOrder: Int,
    cachedAt: Long,
): SpotifyHomeArtistCache = SpotifyHomeArtistCache(
    profileId = profileId,
    artistId = id.toString(),
    name = name,
    coverArtKey = CoverRef.toStorageKey(coverArt),
    sortOrder = sortOrder,
    cachedAt = cachedAt,
)

private fun SpotifyHomeArtistCache.toArtist(): com.gpo.yoin.data.model.Artist =
    com.gpo.yoin.data.model.Artist(
        id = MediaId.parse(artistId),
        name = name,
        albumCount = null,
        coverArt = CoverRef.fromStorageKey(coverArtKey),
        isStarred = false,
    )
