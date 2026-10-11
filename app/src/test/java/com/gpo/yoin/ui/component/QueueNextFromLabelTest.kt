package com.gpo.yoin.ui.component

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.repository.ActivityContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The queue's "Next from" line reads each source's own string: the owner may
 * reword one (an album's, say, to name it as an album) without the others
 * picking it up.
 */
@RunWith(RobolectricTestRunner::class)
class QueueNextFromLabelTest {

    private val real = ApplicationProvider.getApplicationContext<Context>().resources

    /** Answers each string with its resource name, so the test sees which one was asked for. */
    @Suppress("DEPRECATION") // the plain constructor is all a stand-in needs
    private val named = object : Resources(real.assets, real.displayMetrics, real.configuration) {
        override fun getString(id: Int, vararg formatArgs: Any?): String = real.getResourceEntryName(id)

        override fun getString(id: Int): String = real.getResourceEntryName(id)
    }

    @Test
    fun should_readTheArtistsOwnString_when_playingFromAnArtistPage() {
        val artist = ActivityContext.Artist(artistId = "spotify:ar1", artistName = "TUIDE")

        assertEquals("cmp_queue_next_from_artist", queueNextFromLabel(artist, named))
    }

    @Test
    fun should_readEachSourcesOwnString_when_labellingWhatComesNext() {
        assertEquals(
            "cmp_queue_next_from_album",
            queueNextFromLabel(ActivityContext.Album(albumId = "a", albumName = "Lantern Letters"), named)
        )
        assertEquals(
            "cmp_queue_next_from_playlist",
            queueNextFromLabel(ActivityContext.Playlist(playlistId = "p", playlistName = "Mix"), named)
        )
        assertEquals("cmp_queue_next_up", queueNextFromLabel(ActivityContext.None, named))
    }

    @Test
    fun should_nameTheArtist_when_readingTheRealString() {
        val artist = ActivityContext.Artist(artistId = "spotify:ar1", artistName = "TUIDE")

        assertEquals("Next from: TUIDE", queueNextFromLabel(artist, real))
    }
}
