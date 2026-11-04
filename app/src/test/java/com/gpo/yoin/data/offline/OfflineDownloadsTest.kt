package com.gpo.yoin.data.offline

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The parts of [OfflineDownloads] that run without the download manager:
 * what each account holds (which keeps downloaded pages in the detail cache),
 * the network a download waits for, and what a song is stored with.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineDownloadsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val activeProfile = MutableStateFlow<String?>(PROFILE)
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var index: DefaultDownloadIndex

    private val album = OfflineCollection(OfflineCollection.Kind.Album, "subsonic:al-1", "Album", "Artist", "al-1")
    private val playlist = OfflineCollection(OfflineCollection.Kind.Playlist, "subsonic:pl-1", "Mix")

    @Before
    fun setup() {
        database = StandaloneDatabaseProvider(context)
        index = DefaultDownloadIndex(database)
    }

    @After
    fun cleanup() {
        scope.cancel()
        database.close()
    }

    private fun downloads() = OfflineDownloads(
        context = context,
        settingsStore = DownloadSettingsStore(context),
        activeProfileId = activeProfile,
        activeSubsonic = { null },
        scope = scope,
    )

    private fun put(id: String, payload: OfflinePayload, state: Int = Download.STATE_COMPLETED) {
        val request = DownloadRequest.Builder(id, Uri.parse("yoin-offline://song?id=$id&q=320&k=$id"))
            .setCustomCacheKey(id)
            .setData(payload.encode())
            .build()
        index.putDownload(Download(request, state, 1L, 1L, 100L, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun payload(profileId: String, song: String, vararg collections: OfflineCollection) =
        collections.fold(OfflinePayload(profileId = profileId, songId = "subsonic:$song")) { p, c -> p.withCollection(c) }

    @Test
    fun should_holdAProfilesAlbumAndPlaylist_beforeTheFirstRefresh() = runBlocking {
        put("offline:a", payload(PROFILE, "tr-1", album))
        put("offline:b", payload(PROFILE, "tr-2", playlist))
        val downloads = downloads()

        // Read straight from the index: the startup purge of the detail cache can't drop these pages.
        assertTrue(downloads.holds(PROFILE, OfflineCollection.Kind.Album, album.id))
        assertTrue(downloads.holds(PROFILE, OfflineCollection.Kind.Playlist, playlist.id))
    }

    @Test
    fun should_notHold_anotherAccountsDownload() = runBlocking {
        put("offline:a", payload(OTHER, "tr-1", album))

        assertFalse(downloads().holds(PROFILE, OfflineCollection.Kind.Album, album.id))
        assertTrue(downloads().holds(OTHER, OfflineCollection.Kind.Album, album.id))
    }

    @Test
    fun should_notHold_otherKindsOrIds() = runBlocking {
        put("offline:a", payload(PROFILE, "tr-1", album))
        val downloads = downloads()

        assertFalse(downloads.holds(PROFILE, OfflineCollection.Kind.Playlist, album.id))
        assertFalse(downloads.holds(PROFILE, OfflineCollection.Kind.Album, "subsonic:al-2"))
    }

    @Test
    fun should_holdNothing_when_nothingDownloaded() = runBlocking {
        assertFalse(downloads().holds(PROFILE, OfflineCollection.Kind.Album, album.id))
    }

    @Test
    fun should_holdAlbum_while_itsSongsStillDownload() = runBlocking {
        put("offline:a", payload(PROFILE, "tr-1", album), state = Download.STATE_QUEUED)

        assertTrue(downloads().holds(PROFILE, OfflineCollection.Kind.Album, album.id))
    }

    @Test
    fun should_skipDownloadsWithoutPayload() = runBlocking {
        val request = DownloadRequest.Builder("foreign", Uri.parse("https://example.com/a.mp3")).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 1L, 1L, 100L, 0, Download.FAILURE_REASON_NONE))
        put("offline:a", payload(PROFILE, "tr-1", album))

        assertTrue(downloads().holds(PROFILE, OfflineCollection.Kind.Album, album.id))
    }

    @Test
    fun should_queueNothing_when_activeAccountIsNotSubsonic() = runBlocking {
        val queued = downloads().download(listOf(track()), album)

        assertEquals(0, queued)
    }

    @Test
    fun should_waitForWifi_unlessMobileDataAllowed() {
        val wifiOnly = OfflineDownloads.requirementsFor(DownloadSettings(overMobileData = false))
        val anyNetwork = OfflineDownloads.requirementsFor(DownloadSettings(overMobileData = true))

        assertTrue(wifiOnly.isUnmeteredNetworkRequired)
        assertTrue(anyNetwork.isNetworkRequired)
        assertFalse(anyNetwork.isUnmeteredNetworkRequired)
        assertEquals(Requirements.NETWORK_UNMETERED, wifiOnly.requirements and Requirements.NETWORK_UNMETERED)
    }

    @Test
    fun should_storeWhatPlaysOffline_andNoSignedCover() {
        val payload = OfflineDownloads.payloadOf(track(), PROFILE, DownloadQuality.Kbps192)

        assertEquals(PROFILE, payload.profileId)
        assertEquals("subsonic:tr-1", payload.songId)
        assertEquals("Song", payload.title)
        assertEquals("Artist", payload.artist)
        assertEquals("Album", payload.album)
        assertEquals("subsonic:al-1", payload.albumId)
        assertEquals("al-1", payload.cover)
        assertEquals(215, payload.durationSec)
        assertEquals(3, payload.trackNumber)
        assertEquals(2, payload.discNumber)
        assertEquals(DownloadQuality.Kbps192, payload.downloadQuality)
        // Collections are added by the download, not the song.
        assertTrue(payload.collections.isEmpty())
    }

    @Test
    fun should_storeNoCover_when_trackHasNone() {
        val payload = OfflineDownloads.payloadOf(track(cover = null), PROFILE, DownloadQuality.Original)

        assertNull(payload.cover)
        assertEquals(DownloadQuality.Original, payload.downloadQuality)
    }

    private fun track(cover: CoverRef? = CoverRef.SourceRelative("al-1")) = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, "tr-1"),
        title = "Song",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = MediaId(MediaId.PROVIDER_SUBSONIC, "al-1"),
        coverArt = cover,
        durationSec = 215,
        trackNumber = 3,
        year = 2020,
        genre = null,
        userRating = null,
        discNumber = 2,
    )

    private companion object {
        const val PROFILE = "profile-1"
        const val OTHER = "profile-2"
    }
}
