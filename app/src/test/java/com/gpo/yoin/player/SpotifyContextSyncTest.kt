package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify's reports against the context a Yoin play set. Nothing counts before Spotify plays a
 * song of Yoin's queue; then no context, an empty or blank uri or one Yoin can't name leaves (or
 * brings back) Yoin's, a different album or playlist Spotify names is shown, and a song outside
 * Yoin's queue hands the context to Spotify, unless Spotify still names Yoin's own album or
 * playlist on this connection (a relinked copy). Playback adopted from Spotify follows Spotify.
 */
class SpotifyContextSyncTest {

    private val album = ActivityContext.Album(
        albumId = MediaId.spotify("al1").toString(),
        albumName = "TUNE & PLAY",
        artistName = "TUIDE",
        coverArtId = "https://i.scdn.co/image/al1"
    )
    private val artist = ActivityContext.Artist(artistId = MediaId.spotify("ar1").toString(), artistName = "TUIDE")
    private val likedSongs = ActivityContext.LikedSongs()

    private val queue = (1..5).map { track("t$it") }
    private val before = track("before")
    private val elsewhere = track("elsewhere")

    /** What the manager shows: every non-null answer replaces it. */
    private var shown: ActivityContext = ActivityContext.None
    private val sync = SpotifyContextSync()

    @Test
    fun should_keepYoinsAlbum_when_spotifyReportsNoContextOrAnEmptyOrBlankUri() {
        startAndConfirm(album)

        listOf(null, report(null), report("", ""), report("  ", "x"), report("spotify:album:", "x"))
            .forEach { reported -> context(reported) }

        assertEquals(album, shown)
    }

    @Test
    fun should_keepYoinsContext_when_spotifyReportsAContextYoinCannotName() {
        startAndConfirm(album)

        listOf("spotify:artist:ar1", "spotify:user:me:collection", "spotify:track:t1", "spotify:x")
            .forEach { uri -> context(report(uri, "Radio")) }

        assertEquals(album, shown)
    }

    @Test
    fun should_keepYoinsRicherAlbum_when_spotifyReportsTheSameAlbum() {
        startAndConfirm(album)

        // Spotify's reading carries a bare title; Yoin's keeps its artist and cover.
        assertNull(sync.onReported(shown, report("spotify:album:al1", "")))
        assertEquals(album, shown)
    }

    @Test
    fun should_ignoreEveryReport_when_spotifyHasNotPlayedASongOfYoinsQueueYet() {
        start(artist)

        // A cold connect: Spotify's first state and context are about what it played before.
        state(before)
        context(report("spotify:album:old", "Old"))
        assertEquals(artist, shown)

        // The start lands and Spotify sends no new context for a `uris` list.
        state(queue[0])
        assertEquals(artist, shown)
    }

    @Test
    fun should_keepPlayingFromTheArtist_when_aStaleAlbumThenNothingArrivesAroundTheStart() {
        start(artist)

        state(before)
        context(report("spotify:album:old", "Old"))
        state(queue[0])
        context(report("", ""))

        assertEquals(artist, shown)
    }

    @Test
    fun should_showSpotifysAlbum_when_itNamesAnotherWhileOnYoinsQueue() {
        startAndConfirm(artist)

        context(report("spotify:album:al2", "Other"))

        assertEquals(ActivityContext.Album(albumId = "spotify:al2", albumName = "Other"), shown)
    }

    @Test
    fun should_bringYoinsContextBack_when_anUnnamedReportFollowsANameWhileOnYoinsQueue() {
        // The song Spotify was on before the start is in Yoin's queue too: confirmed early.
        start(artist)
        state(queue[3])
        context(report("spotify:album:old", "Old"))

        state(queue[0])
        context(report("", ""))

        assertEquals(artist, shown)
    }

    @Test
    fun should_followSpotify_when_aSongOutsideYoinsQueuePlaysAfterTheStart() {
        startAndConfirm(album)
        // Liked Songs in the Spotify app: a context Yoin can't name.
        context(report("spotify:user:me:collection", "Liked Songs"))
        assertEquals(album, shown)

        state(elsewhere)

        assertEquals(ActivityContext.None, shown)
    }

    @Test
    fun should_nameSpotifysNewAlbum_when_itsContextArrivedBeforeTheSongOutsideYoinsQueue() {
        startAndConfirm(album)
        context(report("spotify:album:al2", "Other"))

        state(elsewhere)

        assertEquals(ActivityContext.Album(albumId = "spotify:al2", albumName = "Other"), shown)
    }

    @Test
    fun should_handTheContextToSpotify_when_aSongOutsideTheQueueComesAfterADroppedConnection() {
        startAndConfirm(album)
        context(report("spotify:album:al1", "TUNE & PLAY"))

        // Back from the background: Spotify moved on while Yoin wasn't listening, and its old
        // word about Yoin's album no longer vouches for the song.
        sync.onDisconnected()
        state(elsewhere)
        assertEquals(ActivityContext.None, shown)

        // Its fresh report then names where it is.
        context(report("spotify:playlist:pl1", "Mix"))
        assertEquals(ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = "Mix"), shown)
    }

    @Test
    fun should_keepYoinsAlbum_when_spotifyStillNamesItForASongOutsideTheQueue() {
        startAndConfirm(album)
        context(report("spotify:album:al1", "TUNE & PLAY"))

        // A copy of an album song Spotify relinked, under another id and name.
        state(track("t3-relinked", title = "t3 (JP ver.)"))
        assertEquals(album, shown)

        state(queue[3])
        assertEquals(album, shown)
    }

    @Test
    fun should_handTheContextToSpotify_when_itsNextReportNoLongerNamesYoinsAlbum() {
        startAndConfirm(album)
        context(report("spotify:album:al1", "TUNE & PLAY"))
        // The song arrives before the context the user switched to in the Spotify app.
        state(elsewhere)
        assertEquals(album, shown)

        context(report("spotify:user:me:collection", "Liked Songs"))

        assertEquals(ActivityContext.None, shown)
    }

    @Test
    fun should_keepYoinsArtist_when_spotifyPlaysARelinkedCopyOfASongInTheList() {
        startAndConfirm(artist)
        context(report("", ""))

        state(track("t3-relinked", title = "T3"))

        assertEquals(artist, shown)
    }

    @Test
    fun should_notNameAStaleAlbum_when_spotifyLeavesYoinsQueueWithoutANewReport() {
        start(artist)
        state(before)
        context(report("spotify:album:old", "Old"))
        state(queue[0])

        state(elsewhere)

        assertEquals(ActivityContext.None, shown)
    }

    @Test
    fun should_followSpotifyFromThenOn_when_spotifyMovedOffYoinsQueue() {
        startAndConfirm(artist)
        state(elsewhere)
        context(report("spotify:album:al2", "Other"))

        // Back on a song of the old queue, Spotify's word is final: Yoin's start is over.
        state(queue[1])
        context(report("", ""))

        assertEquals(ActivityContext.None, shown)
    }

    @Test
    fun should_keepYoinsContext_when_spotifyPlaysOnThroughYoinsQueue() {
        startAndConfirm(album)

        queue.forEach { song -> state(song) }

        assertEquals(album, shown)
    }

    @Test
    fun should_labelAndThenClearAnAdoptedContext_when_spotifyNamesOneAndThenNothing() {
        sync.followSpotify()

        context(report("spotify:playlist:pl1", "Mix"))
        assertEquals(ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = "Mix"), shown)

        listOf(null, report("", ""), report("spotify:artist:ar1", "Radio")).forEach { reported ->
            context(report("spotify:album:al1", "TUNE & PLAY"))
            context(reported)
            assertEquals(ActivityContext.None, shown)
        }
        assertNull(sync.onReported(ActivityContext.None, null))
    }

    @Test
    fun should_notTreatSongsAsATakeover_when_noYoinPlayStartedThem() {
        sync.followSpotify()
        context(report("spotify:album:al1", "TUNE & PLAY"))

        state(elsewhere)

        assertEquals(ActivityContext.Album(albumId = "spotify:al1", albumName = "TUNE & PLAY"), shown)
    }

    @Test
    fun should_keepYoinsLikedSongs_when_spotifyPlaysALikedSongPastYoinsWindow() {
        startAndConfirm(likedSongs)
        context(report("spotify:user:me:collection", "Liked Songs"))

        // Shuffle (or ~80 songs on): a liked song outside the 100 Yoin handed Spotify.
        state(elsewhere)
        assertEquals(likedSongs, shown)

        context(report(SPOTIFY_LIKED_SONGS_CONTEXT_URI, "Liked Songs"))
        state(track("elsewhere-2"))
        context(report("", ""))
        state(queue[2])
        assertEquals(likedSongs, shown)
    }

    @Test
    fun should_keepYoinsLikedSongs_when_spotifyWasAlreadyInTheCollectionBeforeTheStart() {
        start(likedSongs)
        // Spotify was on Liked Songs already: its context doesn't change, so this is its only word.
        state(before)
        context(report("spotify:user:me:collection", "Liked Songs"))
        state(queue[0])

        state(elsewhere)

        assertEquals(likedSongs, shown)
    }

    @Test
    fun should_handLikedSongsToSpotify_when_itMovesToAnotherContext() {
        startAndConfirm(likedSongs)
        context(report(SPOTIFY_LIKED_SONGS_CONTEXT_URI, "Liked Songs"))

        // The user picks an album in the Spotify app; its song arrives before its context.
        state(elsewhere)
        context(report("spotify:album:al2", "Other"))
        state(track("elsewhere-2"))

        assertEquals(ActivityContext.Album(albumId = "spotify:al2", albumName = "Other"), shown)
    }

    @Test
    fun should_followSpotify_when_aSongPastTheWindowPlaysWithNoWordOfTheCollection() {
        startAndConfirm(likedSongs)
        context(report("spotify:artist:ar1", "Radio"))

        state(elsewhere)

        assertEquals(ActivityContext.None, shown)
    }

    @Test
    fun should_keepAlbumsApartFromLikedSongs_when_spotifyReportsTheCollection() {
        startAndConfirm(album)
        context(report(SPOTIFY_LIKED_SONGS_CONTEXT_URI, "Liked Songs"))

        state(elsewhere)

        assertEquals(ActivityContext.None, shown)
    }

    @Test
    fun should_recogniseEveryFormOfTheCollection_when_readingALikedSongsUri() {
        listOf(
            SPOTIFY_LIKED_SONGS_CONTEXT_URI,
            "spotify:collection",
            "spotify:user:me:collection",
            "spotify:user:31abc:collection:tracks",
            " spotify:user:me:collection "
        ).forEach { uri -> assertTrue(uri, isSpotifyLikedSongsUri(uri)) }
        listOf(
            null,
            "",
            "spotify:user::collection",
            "spotify:user:me:collection:artist:ar1",
            "spotify:collection:your-episodes",
            "spotify:playlist:collection",
            "spotify:album:al1"
        ).forEach { uri -> assertFalse("$uri", isSpotifyLikedSongsUri(uri)) }
    }

    @Test
    fun should_nameTheAlbumOrPlaylist_when_derivingSpotifysContext() {
        assertEquals(
            ActivityContext.Album(albumId = "spotify:al1", albumName = "TUNE & PLAY"),
            deriveSpotifyActivityContext(report("spotify:album:al1", "TUNE & PLAY"))
        )
        assertEquals(
            ActivityContext.Playlist(playlistId = "spotify:pl1", playlistName = ""),
            deriveSpotifyActivityContext(report("spotify:playlist:pl1", "  "))
        )
        assertNull(deriveSpotifyActivityContext(report(SPOTIFY_LIKED_SONGS_CONTEXT_URI, "Liked Songs")))
    }

    private fun start(context: ActivityContext) {
        shown = context
        sync.onYoinStart(context)
    }

    private fun startAndConfirm(context: ActivityContext) {
        start(context)
        state(queue[1])
    }

    /** App Remote's PlayerState: Spotify plays [song]; Yoin mirrors [queue] for it. */
    private fun state(song: Track) {
        sync.onPlayerState(shown, song, queue)?.let { shown = it }
    }

    /** App Remote's PlayerContext. */
    private fun context(reported: SpotifyPlaybackContext?) {
        sync.onReported(shown, reported)?.let { shown = it }
    }

    private fun report(uri: String?, title: String? = null) = SpotifyPlaybackContext(uri = uri, title = title)

    private fun track(rawId: String, title: String = rawId) = Track(
        id = MediaId.spotify(rawId),
        title = title,
        artist = "TUIDE",
        artistId = MediaId.spotify("ar1"),
        album = "TUNE & PLAY",
        albumId = MediaId.spotify("al1"),
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null
    )
}
