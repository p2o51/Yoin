package com.gpo.yoin.ui.openin

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.openin.MusicLinkLookup
import com.gpo.yoin.data.openin.MusicLinkMetadata
import com.gpo.yoin.data.transfer.FakeTransferTarget
import com.gpo.yoin.shared.openin.YoinMusicLink
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OpenInResultsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val link = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"

    private class FakeLookup(val metadata: MusicLinkMetadata?) : MusicLinkLookup {
        var metadataReads = 0
        override suspend fun resolve(link: YoinMusicLink): YoinMusicLink = link
        override suspend fun metadata(link: YoinMusicLink): MusicLinkMetadata? {
            metadataReads++
            return metadata
        }
    }

    private fun track(id: String, title: String, artist: String, duration: Int, isrc: String? = null) = Track(
        id = MediaId.subsonic(id),
        title = title,
        artist = artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = duration,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = isrc?.let { mapOf(Track.EXTRA_ISRC to it) }.orEmpty(),
    )

    private val metadata =
        MusicLinkMetadata(YoinMusicLinkKind.Track, "Blinding Lights", "The Weeknd", "After Hours", "USUG11904206", 200)

    @Test
    fun should_leadWithIsrcMatchAndListTheRest_when_linkIsFound() = runTest {
        val isrcMatch = track("isrc", "Blinding Lights", "The Weeknd", 200, isrc = "USUG11904206")
        val live = track("live", "Blinding Lights (Live)", "The Weeknd", 250)
        val target = FakeTransferTarget(
            isrc = mapOf("USUG11904206" to listOf(isrcMatch)),
            search = mapOf("Blinding Lights The Weeknd" to listOf(live, isrcMatch)),
            id = MediaId.PROVIDER_SUBSONIC,
        )
        val lookup = FakeLookup(metadata)

        val viewModel = OpenInResultsViewModel(link, metadata, lookup) { target }

        val state = viewModel.state.value as OpenInResultsState.Results
        assertEquals(listOf(isrcMatch, live), state.rows.map { (it as OpenInResultRow.Song).track })
        assertTrue(state.exact)
        assertEquals(MediaId.PROVIDER_SUBSONIC, state.provider)
        // The card's metadata is used as it came: no second read.
        assertEquals(0, lookup.metadataReads)
    }

    @Test
    fun should_sayItCouldNotConfirm_when_onlyOtherVersionsAreFound() = runTest {
        val live = track("live", "Blinding Lights - Live", "The Weeknd", 250)
        val target = FakeTransferTarget(search = mapOf("Blinding Lights The Weeknd" to listOf(live)))

        val viewModel = OpenInResultsViewModel(link, null, FakeLookup(metadata.copy(isrc = null))) { target }

        val state = viewModel.state.value as OpenInResultsState.Results
        assertFalse(state.exact)
        assertEquals(1, state.rows.size)
    }

    @Test
    fun should_searchTitleAlone_when_titleAndArtistFindNothing() = runTest {
        val romanised = track("1", "Blinding Lights", "ザ・ウィークエンド", 200)
        val target = FakeTransferTarget(search = mapOf("Blinding Lights" to listOf(romanised)))

        val viewModel = OpenInResultsViewModel(link, metadata.copy(isrc = null), FakeLookup(null)) { target }

        val state = viewModel.state.value as OpenInResultsState.Results
        assertEquals(listOf("Blinding Lights The Weeknd", "Blinding Lights"), target.searches)
        assertEquals(1, state.rows.size)
    }

    @Test
    fun should_beEmpty_when_nothingIsFound() = runTest {
        val target = FakeTransferTarget(id = MediaId.PROVIDER_SUBSONIC)

        val viewModel = OpenInResultsViewModel(link, metadata, FakeLookup(metadata)) { target }

        assertEquals(OpenInResultsState.Empty(MediaId.PROVIDER_SUBSONIC), viewModel.state.value)
    }

    @Test
    fun should_beEmpty_when_linkCannotBeRead() = runTest {
        val target = FakeTransferTarget(search = mapOf("x" to listOf(track("1", "x", "y", 1))))

        val viewModel = OpenInResultsViewModel(link, null, FakeLookup(null)) { target }

        assertTrue(viewModel.state.value is OpenInResultsState.Empty)
        assertTrue(target.searches.isEmpty())
    }
}
