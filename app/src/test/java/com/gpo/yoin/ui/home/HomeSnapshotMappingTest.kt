package com.gpo.yoin.ui.home

import com.gpo.yoin.R
import com.gpo.yoin.data.home.HomeCardTargetDto
import com.gpo.yoin.data.home.HomeFeedDto
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSnapshotMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** What the account's source resolves a key to: a Subsonic URL with its credentials. */
    private val source = { key: String ->
        CoverRef.fromStorageKey(key)?.let { ref ->
            when (ref) {
                is CoverRef.Url -> ref.url
                is CoverRef.SourceRelative ->
                    "https://music.example/rest/getCoverArt?id=${ref.coverArtId}&u=me&t=tok&s=salt"
            }
        }
    }

    private val track = Track(
        id = MediaId.subsonic("song-1"),
        title = "Song 1",
        artist = "Artist",
        artistId = MediaId.subsonic("artist-1"),
        album = "Album 1",
        albumId = MediaId.subsonic("al-1"),
        coverArt = CoverRef.SourceRelative("al-1"),
        durationSec = 200,
        trackNumber = 3,
        year = 2020,
        genre = "Rock",
        userRating = 4,
        isStarred = true,
        extras = mapOf("suffix" to "flac"),
        addedAt = "2026-10-01T10:00:00Z"
    )

    private val album = Album(
        id = MediaId.subsonic("al-2"),
        name = "Album 2",
        artist = "Artist",
        artistId = MediaId.subsonic("artist-1"),
        coverArt = CoverRef.SourceRelative("al-2"),
        songCount = 10,
        durationSec = 2_400,
        year = 2019,
        genre = null,
        addedAt = "2026-10-02T10:00:00Z"
    )

    private val playlist = Playlist(
        id = MediaId.subsonic("pl-1"),
        name = "Playlist 1",
        owner = "me",
        coverArt = CoverRef.Url("https://images.example/pl-1.jpg"),
        songCount = 5,
        durationSec = 900
    )

    private fun feed(cover: (String?) -> String?) = HomeUiState.Content(
        activities = listOf(
            ActivityEvent(
                id = 7,
                entityType = "ALBUM",
                actionType = "PLAYED",
                entityId = "al-2",
                profileId = "p",
                provider = MediaId.PROVIDER_SUBSONIC,
                title = "Album 2",
                subtitle = "Artist",
                coverArtId = "al-2",
                albumId = "al-2",
                timestamp = 1_700_000_000_000L
            )
        ),
        activitiesFromRemote = false,
        activityHeroFootnote = "2019",
        activityHeroYear = 2019,
        activityHeroSongCount = 10,
        activityHeroMinutes = 40,
        widgetGrid = listOf(
            HomeWidgetCard(
                stableId = "grid-memory:subsonic:al-2",
                entityType = MemoryEntityType.ALBUM,
                title = "Album 2",
                subtitle = "Artist",
                subtitleText = UiText.Raw("Artist"),
                coverArtUrl = cover("al-2"),
                ratingText = "8.0",
                ratingBasis = "Based on 4/10 tracks",
                ratingBasisText = UiText.Res(R.string.home_widget_basis_tracks, listOf(4, 10)),
                comment = "A title",
                commentIsHeadline = true,
                commentSerif = false,
                expanded = true,
                target = HomeWidgetTarget.MemoryFocus(42L),
                coverKey = "al-2"
            ),
            HomeWidgetCard(
                stableId = "grid-song:${track.id}",
                entityType = MemoryEntityType.SONG,
                title = "Song 1",
                subtitle = "Artist",
                subtitleText = UiText.Raw("Artist"),
                coverArtUrl = cover("al-1"),
                ratingBasisDateMillis = 1_700_000_000_000L,
                ratingUnavailable = true,
                ratingText = "N/A",
                target = HomeWidgetTarget.PlaySong(track),
                coverKey = "al-1"
            ),
            HomeWidgetCard(
                stableId = "grid-playlist:${playlist.id}",
                entityType = MemoryEntityType.PLAYLIST,
                title = "Playlist 1",
                subtitle = "me",
                coverArtUrl = cover("https://images.example/pl-1.jpg"),
                target = HomeWidgetTarget.PlaylistDetail(playlist.id.toString()),
                coverKey = "https://images.example/pl-1.jpg"
            ),
            HomeWidgetCard(
                stableId = "grid-album:${album.id}",
                entityType = MemoryEntityType.ALBUM,
                title = "Album 2",
                subtitle = "",
                coverArtUrl = null,
                target = HomeWidgetTarget.AlbumDetail(album.id.toString())
            )
        ),
        recentlyAddedTracks = listOf(track),
        recentlyAddedAlbums = listOf(album),
        memoryPill = HomeMemoryPill(
            latest = HomeMemoryPill.Latest(
                sessionId = 42L,
                albumId = album.id,
                albumName = "Album 2",
                artistName = "Artist",
                coverArtUrl = cover("al-2"),
                scoreKind = MemoryScoreKind.ALBUM_RATING,
                scoreText = "8.0",
                writtenAtMillis = 1_700_000_000_000L,
                memoryTitle = "A title",
                coverKey = "al-2"
            ),
            noteCount = 3,
            scope = "subsonic|p",
            newsKey = "1700000000000#3"
        ),
        rediscover = listOf(
            HomeRediscoverItem(
                albumId = MediaId.subsonic("al-1"),
                albumName = "Album 1",
                artistName = "Artist",
                coverArtUrl = cover("al-1"),
                score = 8f,
                scoreText = "8.0",
                scoreKind = MemoryScoreKind.ALBUM_RATING,
                lastPlayedAt = 1_600_000_000_000L,
                firstPlayedAt = 1_500_000_000_000L,
                playCount = 7,
                noteCount = 1,
                song = track,
                noteSnippet = "worth the wait",
                coverKey = "al-1"
            )
        ),
        recentlyPlayed = listOf(album),
        playlists = listOf(playlist)
    )

    private fun roundTrip(content: HomeUiState.Content): HomeFeedDto {
        val written = json.encodeToString(HomeFeedDto.serializer(), content.toSnapshotFeed())
        return json.decodeFromString(HomeFeedDto.serializer(), written)
    }

    @Test
    fun should_restoreTheSameFeed_when_writtenAndReadBackWithTheSource() {
        val original = feed { key -> key?.let(source) }

        val restored = roundTrip(original).toHomeContent(source)

        assertEquals(original, restored)
    }

    @Test
    fun should_writeCoverKeysButNoResolvedUrl_when_feedHasSubsonicCovers() {
        val written = json.encodeToString(HomeFeedDto.serializer(), feed { key -> key?.let(source) }.toSnapshotFeed())

        assertFalse(written, written.contains("getCoverArt"))
        assertFalse(written, written.contains("t=tok"))
        assertTrue(written.contains("\"coverKey\":\"al-2\""))
    }

    @Test
    fun should_showTextWithUrlCoversOnly_when_readBeforeTheSource() {
        // No source yet: a URL key is its own URL, a Subsonic id waits.
        val restored = roundTrip(feed { key -> key?.let(source) })
            .toHomeContent { key -> (CoverRef.fromStorageKey(key) as? CoverRef.Url)?.url }

        assertEquals(listOf("Album 2", "Song 1", "Playlist 1", "Album 2"), restored.widgetGrid.map { it.title })
        assertEquals(
            listOf(null, null, "https://images.example/pl-1.jpg", null),
            restored.widgetGrid.map { it.coverArtUrl }
        )
        assertNull(restored.memoryPill?.latest?.coverArtUrl)
        assertNull(restored.rediscover.single().coverArtUrl)

        // The source arrives: every waiting cover resolves.
        val resolved = restored.withResolvedCovers(source)
        assertEquals(feed { key -> key?.let(source) }, resolved)
    }

    @Test
    fun should_keepTheSameFeed_when_noCoverIsLeftToResolve() {
        val content = feed { key -> key?.let(source) }

        assertSame(content, content.withResolvedCovers(source))
    }

    @Test
    fun should_dropOnlyTheEntry_when_oneDoesNotReadBack() {
        val written = feed { key -> key?.let(source) }.toSnapshotFeed()
        val damaged = written.copy(
            widgetGrid = written.widgetGrid.mapIndexed { index, card ->
                when (index) {
                    0 -> card.copy(entityType = "PODCAST")
                    1 -> card.copy(target = HomeCardTargetDto(kind = "radio"))
                    else -> card
                }
            },
            rediscover = written.rediscover.map { item -> item.copy(albumId = "no-provider") }
        )

        val restored = damaged.toHomeContent(source)

        assertEquals(listOf("Playlist 1", "Album 2"), restored.widgetGrid.map { it.title })
        assertTrue(restored.rediscover.isEmpty())
        assertEquals(listOf(album.id), restored.recentlyPlayed.map { it.id })
    }

    @Test
    fun should_neverKeepACredentialUrl_when_aCoverKeyCarriesOne() {
        val leaked = "https://music.example/rest/getCoverArt?id=al-9&u=me&t=tok&s=salt"
        val content = HomeUiState.Content(
            activities = listOf(
                ActivityEvent(
                    entityType = "ALBUM",
                    actionType = "PLAYED",
                    entityId = "al-9",
                    provider = MediaId.PROVIDER_SUBSONIC,
                    title = "Album 9",
                    subtitle = "",
                    coverArtId = leaked,
                    timestamp = 1L
                )
            ),
            widgetGrid = listOf(
                HomeWidgetCard(
                    stableId = "grid-album:subsonic:al-9",
                    entityType = MemoryEntityType.ALBUM,
                    title = "Album 9",
                    subtitle = "",
                    coverArtUrl = leaked,
                    target = HomeWidgetTarget.AlbumDetail("subsonic:al-9"),
                    coverKey = leaked
                )
            ),
            recentlyPlayed = listOf(album.copy(coverArt = CoverRef.Url(leaked)))
        )

        val written = json.encodeToString(HomeFeedDto.serializer(), content.toSnapshotFeed())

        assertFalse(written, written.contains("t=tok"))
    }

    @Test
    fun should_dropAlbumTrackLists_when_writingShelves() {
        val withTracks = feed { key -> key?.let(source) }
            .copy(recentlyPlayed = listOf(album.copy(tracks = listOf(track))))

        assertTrue(withTracks.toSnapshotFeed().recentlyPlayed.single().tracks.isEmpty())
    }
}
