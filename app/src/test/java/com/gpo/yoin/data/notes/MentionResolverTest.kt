package com.gpo.yoin.data.notes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.PlayHistory
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MentionResolverTest {

    private val sub = MediaId.PROVIDER_SUBSONIC
    private lateinit var database: YoinDatabase
    private var account: MentionAccount? = MentionAccount("profile-a", sub)
    private lateinit var resolver: LocalMentionResolver

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        resolver = LocalMentionResolver(account = { account }, names = RoomMentionNameLookup(database.noteMentionDao()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun should_resolveById_when_theMentionIsOnTheAccountsProvider() = runTest {
        val mention = NoteMention(MentionKind.Artist, sub, "ar-1", "Weyes Blood")

        assertEquals(
            MentionResolution.Resolved(mention, MediaId.subsonic("ar-1"), MentionResolution.Via.Id),
            resolver.resolve(mention),
        )
    }

    @Test
    fun should_resolveAnotherProvidersTrackByName_fromPlaysAndNotes() = runTest {
        database.playHistoryDao().insert(play("t-andromeda", "Andromeda", "Weyes Blood", "Titanic Rising", "al-1"))
        database.playHistoryDao().insert(play("t-andromeda-live", "Andromeda (Live)", "Weyes Blood", "Live", "al-2"))
        database.songNoteDao().insert(note("n1", "t-ponyboy", "Ponyboy", "SOPHIE"))
        val andromeda = NoteMention(MentionKind.Track, "spotify", "sp1", "Andromeda", "Weyes Blood", "Titanic Rising")
        val ponyboy = NoteMention(MentionKind.Track, "applemusic", "am1", "Ponyboy (feat. Someone)", "SOPHIE")

        assertEquals(
            MentionResolution.Resolved(andromeda, MediaId.subsonic("t-andromeda"), MentionResolution.Via.Name),
            resolver.resolve(andromeda),
        )
        assertEquals(MediaId.subsonic("t-ponyboy"), (resolver.resolve(ponyboy) as MentionResolution.Resolved).id)
    }

    @Test
    fun should_resolveAnotherProvidersAlbumByName_fromPlaysAndAlbumNotes() = runTest {
        database.albumNoteDao().insert(
            AlbumNote(
                id = "an",
                profileId = "profile-a",
                albumId = "al-charm",
                provider = sub,
                content = "x",
                createdAt = 1L,
                updatedAt = 1L,
                albumName = "Charm",
                artist = "Clairo",
            ),
        )
        val charm = NoteMention(MentionKind.Album, "spotify", "sp", "Charm", "Clairo")

        assertEquals(MediaId.subsonic("al-charm"), (resolver.resolve(charm) as MentionResolution.Resolved).id)
    }

    @Test
    fun should_beUnresolved_when_nothingHereAnswers() = runTest {
        database.playHistoryDao().insert(play("t-x", "Andromeda", "Someone Else", "Other", "al-x"))
        database.playHistoryDao().insert(
            play("t-b", "Andromeda", "Weyes Blood", "Titanic Rising", "al-1").copy(profileId = "profile-b"),
        )
        val andromeda = NoteMention(MentionKind.Track, "spotify", "sp1", "Andromeda", "Weyes Blood")
        val artist = NoteMention(MentionKind.Artist, "spotify", "sp2", "Weyes Blood")
        val wildcard = NoteMention(MentionKind.Track, "spotify", "sp3", "%_", null)

        assertEquals(MentionResolution.Unresolved(andromeda), resolver.resolve(andromeda))
        assertEquals(MentionResolution.Unresolved(artist), resolver.resolve(artist))
        assertEquals(MentionResolution.Unresolved(wildcard), resolver.resolve(wildcard))
        account = null
        val own = NoteMention(MentionKind.Track, sub, "t-x", "Andromeda")
        assertEquals(MentionResolution.Unresolved(own), resolver.resolve(own))
    }

    @Test
    fun should_resolveEachDistinctMentionOnce() = runTest {
        var calls = 0
        val counting = MentionResolver { mention ->
            calls++
            MentionResolution.Unresolved(mention)
        }
        val a = NoteMention(MentionKind.Track, "spotify", "1", "A")

        val all = counting.resolveAll(listOf(a, a.copy(title = "A again"), NoteMention(MentionKind.Album, "spotify", "1", "B")))

        assertEquals(2, all.size)
        assertEquals(2, calls)
    }

    @Test
    fun should_escapeLikeWildcards() {
        assertEquals("100\\% \\_x\\\\", likeEscape("100% _x\\"))
    }

    private fun play(songId: String, title: String, artist: String, album: String, albumId: String) = PlayHistory(
        songId = songId,
        profileId = "profile-a",
        provider = sub,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        coverArtId = null,
        playedAt = 1L,
        durationMs = 1L,
        completedPercent = 1f,
    )

    private fun note(id: String, trackId: String, title: String, artist: String) = SongNote(
        id = id,
        profileId = "profile-a",
        trackId = trackId,
        provider = sub,
        content = "c",
        createdAt = 1L,
        updatedAt = 1L,
        title = title,
        artist = artist,
    )
}
