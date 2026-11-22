package com.gpo.yoin.data.notes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.TrackAlbumLink
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteBacklinksTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sub = MediaId.PROVIDER_SUBSONIC
    private val titanic = BacklinkTarget.Album(
        id = MediaId.subsonic("al-weyes"),
        title = "Titanic Rising",
        artist = "Weyes Blood",
        tracks = listOf(
            BacklinkTrack(MediaId.subsonic("t-alot"), "A Lot's Gonna Change", "Weyes Blood"),
            BacklinkTrack(MediaId.subsonic("t-andromeda"), "Andromeda", "Weyes Blood"),
        ),
    )

    private fun link(kind: MentionKind, provider: String, id: String, title: String, artist: String? = null, album: String? = null) =
        NoteMarkup.serializeMention(NoteMention(kind, provider, id, title, artist, album))

    // ── pure matching ───────────────────────────────────────────────────

    @Test
    fun should_countAlbumAndTrackMentions_byIdOnTheSameProvider() {
        val writings = listOf(
            songNoteWriting("n1", "t-x", "像 ${link(MentionKind.Album, sub, "al-weyes", "Titanic Rising")} 的白天", at = 3L),
            songNoteWriting("n2", "t-y", "后来在 ${link(MentionKind.Track, sub, "t-andromeda", "Andromeda")} 里", at = 5L),
            songNoteWriting("n3", "t-z", "another ${link(MentionKind.Album, sub, "al-other", "Titanic Rising", "Weyes Blood")}", at = 9L),
        )

        val links = findBacklinks(writings, titanic)

        // newest first; the same-named album with another id on this provider is another album
        assertEquals(listOf("n2", "n1"), links.map { (it.source as BacklinkSource.SongNote).noteId })
        assertEquals("后来在 Andromeda 里", links.first().snippet)
        assertEquals("t-andromeda", links.first().mentions.single().id)
    }

    @Test
    fun should_matchOtherProvidersByName_includingATracksAlbum() {
        val writings = listOf(
            songNoteWriting("apple-album", "t-x", link(MentionKind.Album, "applemusic", "1", "Titanic Rising", "Weyes Blood"), at = 1L),
            songNoteWriting("spotify-track", "t-y", link(MentionKind.Track, "spotify", "sp", "Movies", "Weyes Blood", "Titanic Rising"), at = 2L),
            songNoteWriting("spotify-listed", "t-z", link(MentionKind.Track, "spotify", "sp2", "Andromeda", "Weyes Blood"), at = 3L),
            songNoteWriting("wrong-artist", "t-w", link(MentionKind.Album, "spotify", "x", "Titanic Rising", "Somebody Else"), at = 4L),
            songNoteWriting("artist", "t-v", link(MentionKind.Artist, "spotify", "ar", "Weyes Blood"), at = 5L),
        )

        val ids = findBacklinks(writings, titanic).map { (it.source as BacklinkSource.SongNote).noteId }

        assertEquals(listOf("spotify-listed", "spotify-track", "apple-album"), ids)
    }

    @Test
    fun should_matchByNameWithinSpotifyAndAppleMusic_butNotWithinSubsonic() {
        val apple = BacklinkTarget.Album(MediaId("applemusic", "1440"), "Titanic Rising", "Weyes Blood")
        val spotifyTrack = BacklinkTarget.Track(MediaId.spotify("sp-andromeda"), "Andromeda", "Weyes Blood")
        val writings = listOf(
            // a library id against the catalogue id
            songNoteWriting("library", "t-1", link(MentionKind.Album, "applemusic", "library:l.x", "Titanic Rising", "Weyes Blood"), at = 1L),
            // a reissue's track id, same song
            songNoteWriting("reissue", "t-2", link(MentionKind.Track, "spotify", "sp-reissue", "Andromeda", "Weyes Blood"), at = 2L),
        )
        assertEquals(listOf("library"), findBacklinks(writings, apple).map { (it.source as BacklinkSource.SongNote).noteId })
        assertEquals(listOf("reissue"), findBacklinks(writings, spotifyTrack).map { (it.source as BacklinkSource.SongNote).noteId })

        // Subsonic: another id on the same server is another item, whatever its name.
        val subsonic = listOf(
            songNoteWriting("same-name", "t-3", link(MentionKind.Album, sub, "al-other", "Titanic Rising", "Weyes Blood"), at = 3L),
        )
        assertTrue(findBacklinks(subsonic, titanic).isEmpty())
    }

    @Test
    fun should_leaveOutWritingsOnTheAlbumItself() {
        val mention = link(MentionKind.Album, sub, "al-weyes", "Titanic Rising")
        val writings = listOf(
            songNoteWriting("on-track", "t-andromeda", mention, at = 1L),
            songNoteWriting("linked", "t-unlisted", mention, at = 2L, albumId = MediaId.subsonic("al-weyes")),
            BacklinkWriting(BacklinkSource.AlbumNote("an", MediaId.subsonic("al-weyes"), "Titanic Rising", "Weyes Blood"), mention, 3L),
            BacklinkWriting(BacklinkSource.Review(MediaId.subsonic("al-weyes"), null, null), mention, 4L),
            BacklinkWriting(BacklinkSource.Review(MediaId.subsonic("al-charm"), "Charm", "Clairo"), mention, 5L),
        )

        val links = findBacklinks(writings, titanic)

        assertEquals(listOf(BacklinkSource.Review(MediaId.subsonic("al-charm"), "Charm", "Clairo")), links.map { it.source })
    }

    @Test
    fun should_matchTracksAndArtists_andSkipTheTracksOwnNotes() {
        val andromeda = BacklinkTarget.Track(MediaId.subsonic("t-andromeda"), "Andromeda", "Weyes Blood", "Titanic Rising")
        val writings = listOf(
            songNoteWriting("own", "t-andromeda", link(MentionKind.Track, sub, "t-andromeda", "Andromeda"), at = 1L),
            songNoteWriting("other", "t-x", link(MentionKind.Track, sub, "t-andromeda", "Andromeda"), at = 2L),
            songNoteWriting("by-name", "t-y", link(MentionKind.Track, "spotify", "s", "Andromeda", "Weyes Blood"), at = 3L),
            songNoteWriting("album-only", "t-z", link(MentionKind.Album, sub, "al-weyes", "Titanic Rising"), at = 4L),
        )
        assertEquals(
            listOf("by-name", "other"),
            findBacklinks(writings, andromeda).map { (it.source as BacklinkSource.SongNote).noteId },
        )

        val artist = BacklinkTarget.Artist(MediaId.subsonic("ar-weyes"), "Weyes Blood")
        val artistWritings = listOf(
            songNoteWriting("id", "t-x", link(MentionKind.Artist, sub, "ar-weyes", "Weyes Blood"), at = 1L),
            songNoteWriting("name", "t-y", link(MentionKind.Artist, "spotify", "sp", "weyes blood"), at = 2L),
            songNoteWriting("track", "t-z", link(MentionKind.Track, sub, "t-andromeda", "Andromeda", "Weyes Blood"), at = 3L),
        )
        assertEquals(
            listOf("name", "id"),
            findBacklinks(artistWritings, artist).map { (it.source as BacklinkSource.SongNote).noteId },
        )
    }

    // ── Room: the account's writings, pre-filtered and parsed ──────────

    private lateinit var database: YoinDatabase
    private val profileId = MutableStateFlow<String?>("profile-a")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_readEveryKindOfWriting_forTheAccountInUse() = runTest {
        val mention = link(MentionKind.Album, sub, "al-weyes", "Titanic Rising", "Weyes Blood")
        database.songNoteDao().insert(songNote("n1", "t-charm-1", "整张像 $mention", createdAt = 10L))
        database.songNoteDao().insert(songNote("plain", "t-charm-1", "no link here", createdAt = 11L))
        database.songNoteDao().insert(songNote("other-account", "t-charm-1", mention, profile = "profile-b"))
        database.trackAlbumLinkDao().upsert(
            TrackAlbumLink("profile-a", sub, "t-charm-1", "al-charm", TrackAlbumLink.SOURCE_PLAY, 1L),
        )
        database.albumNoteDao().insert(albumNote("an1", "al-charm", "Charm", "Clairo", "周日下午，$mention", createdAt = 20L))
        database.albumRatingDao().upsert(
            AlbumRating(
                profileId = "profile-a",
                albumId = "al-cocteau",
                provider = sub,
                rating = 9f,
                review = "# 天堂\n听到 $mention 的温度",
                neoDbReviewUuid = null,
                updatedAt = 99L,
                reviewUpdatedAt = 30L,
            ),
        )
        database.playHistoryDao().insert(play("t-cocteau-5", "al-cocteau", "Heaven or Las Vegas", "Cocteau Twins"))

        val links = NoteBacklinkRepository(database.noteMentionDao(), profileId).observe(titanic).first()

        assertEquals(3, links.size)
        val review = links[0].source as BacklinkSource.Review
        assertEquals(MediaId.subsonic("al-cocteau"), review.albumId)
        assertEquals("Heaven or Las Vegas", review.albumName)
        assertEquals(30L, links[0].at)
        assertEquals("天堂 · 听到 Titanic Rising 的温度", links[0].snippet)
        assertEquals(BacklinkSource.AlbumNote("an1", MediaId.subsonic("al-charm"), "Charm", "Clairo"), links[1].source)
        val note = links[2].source as BacklinkSource.SongNote
        assertEquals(MediaId.subsonic("al-charm"), note.albumId)
        assertEquals("n1", note.noteId)
    }

    @Test
    fun should_reemit_when_aMentionIsWritten() = runTest {
        NoteBacklinkRepository(database.noteMentionDao(), profileId).observe(titanic).test {
            assertTrue(awaitItem().isEmpty())
            database.songNoteDao().insert(
                songNote("n1", "t-x", link(MentionKind.Track, sub, "t-andromeda", "Andromeda")),
            )
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun songNoteWriting(id: String, trackId: String, text: String, at: Long, albumId: MediaId? = null) =
        BacklinkWriting(
            BacklinkSource.SongNote(id, MediaId.subsonic(trackId), "Song", "Artist", null, albumId),
            text,
            at,
        )

    private fun songNote(id: String, trackId: String, content: String, profile: String = "profile-a", createdAt: Long = 1L) =
        SongNote(
            id = id,
            profileId = profile,
            trackId = trackId,
            provider = sub,
            content = content,
            createdAt = createdAt,
            updatedAt = createdAt,
            title = "Song",
            artist = "Artist",
        )

    private fun albumNote(id: String, albumId: String, name: String, artist: String, content: String, createdAt: Long) =
        AlbumNote(
            id = id,
            profileId = "profile-a",
            albumId = albumId,
            provider = sub,
            content = content,
            createdAt = createdAt,
            updatedAt = createdAt,
            albumName = name,
            artist = artist,
        )

    private fun play(songId: String, albumId: String, album: String, artist: String) = PlayHistory(
        songId = songId,
        profileId = "profile-a",
        provider = sub,
        title = "Song",
        artist = artist,
        album = album,
        albumId = albumId,
        coverArtId = null,
        playedAt = 5L,
        durationMs = 200_000L,
        completedPercent = 1f,
    )
}
