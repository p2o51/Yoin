package com.gpo.yoin.ui.library

import android.app.Application
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.component.FastScrollSection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sort pass with the fast scroller's index as the app builds it: the
 * platform ICU in the app's language (LibraryViewModelTest stands in a JVM
 * alphabet), and the fallback when that index can't be built — the lists
 * still come, in the sorter's order, with the handle alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryViewModelScrollIndexTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var savedLocale: Locale

    @Before
    fun pinTheAppLanguage() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreTheLocale() {
        Locale.setDefault(savedLocale)
    }

    private val names = listOf("Zedd", "2NE1", "あいみょん", "The Beatles", "Björk", "ABBA")

    @Test
    fun should_orderAndCutByThePlatformAlphabet_when_theIndexIsTheDefault() = runTest {
        val viewModel = LibraryViewModel(
            repository = subsonicLibrary(),
            sortDispatcher = mainDispatcherRule.dispatcher
        )
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Artists)
        viewModel.selectSort(LibraryTab.Artists, LibrarySort.Alphabetical)
        advanceUntilIdle()

        val state = content(viewModel)
        // Letters, then the kana row, then "#"; Subsonic files The Beatles under B.
        assertEquals(
            listOf("ABBA", "The Beatles", "Björk", "Zedd", "あいみょん", "2NE1"),
            state.artists.orEmpty().map { it.name }
        )
        assertEquals(
            listOf(section("A", 0), section("B", 1), section("Z", 3), section("あ", 4), section("#", 5)),
            state.scrollSections[LibraryTab.Artists]
        )
    }

    @Test
    fun should_keepTheSortersOrderWithTheHandleAlone_when_theIndexCannotBeBuilt() = runTest {
        val viewModel = LibraryViewModel(
            repository = subsonicLibrary(),
            sortDispatcher = mainDispatcherRule.dispatcher,
            scrollIndex = { BrokenScrollIndex }
        )
        advanceUntilIdle()
        viewModel.selectTab(LibraryTab.Artists)
        viewModel.selectSort(LibraryTab.Artists, LibrarySort.Alphabetical)
        viewModel.selectSort(LibraryTab.Albums, LibrarySort.RecentlyAdded)
        advanceUntilIdle()

        val artistsState = content(viewModel)
        // The collator's own order: digits first, The Beatles still under B.
        assertEquals(
            listOf("2NE1", "ABBA", "The Beatles", "Björk", "Zedd", "あいみょん"),
            artistsState.artists.orEmpty().map { it.name }
        )
        assertEquals(emptyList<FastScrollSection>(), artistsState.scrollSections[LibraryTab.Artists])

        viewModel.selectTab(LibraryTab.Albums)
        advanceUntilIdle()
        val albumsState = content(viewModel)
        assertEquals(listOf("new", "old"), albumsState.albums.orEmpty().map { it.id.rawId })
        assertEquals(emptyList<FastScrollSection>(), albumsState.scrollSections[LibraryTab.Albums])
    }

    /** An index whose ICU data is missing: every call throws. */
    private object BrokenScrollIndex : LibraryScrollIndex {
        override fun <T> alphabetical(
            items: List<T>,
            name: (T) -> String,
            ignoredArticles: List<String>
        ): LibraryIndex.Sorted<T> = throw IllegalStateException("no ICU data")

        override fun timeline(datesMs: List<Long?>): List<FastScrollSection> =
            throw IllegalStateException("no ICU data")

        override fun recents(lastSeenMs: List<Long?>, nowMs: Long): List<FastScrollSection> =
            throw IllegalStateException("no ICU data")
    }

    private fun content(viewModel: LibraryViewModel) = viewModel.uiState.value as LibraryUiState.Content

    private fun section(label: String, start: Int) = FastScrollSection(label, startIndex = start)

    private fun subsonicLibrary(): YoinRepository = mockk<YoinRepository>(relaxed = true).also { repository ->
        val capabilities = setOf(Capability.FAVORITES, Capability.RANDOM_SONGS, Capability.PLAYLISTS_READ)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.currentCapabilities() } returns capabilities
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
        every { repository.capabilities } returns flowOf(capabilities)
        every { repository.favoriteOverrides } returns MutableStateFlow(emptyMap())
        every { repository.trackLibraryStates } returns flowOf(emptyMap())
        every { repository.currentProfileId() } returns "test-profile"
        every { repository.currentProfileIdFlow } returns flowOf("test-profile")
        every { repository.libraryRevision } returns flowOf(0L)
        coEvery { repository.getArtists() } returns listOf(
            ArtistIndex("*", names.mapIndexed { i, name -> Artist(MediaId.subsonic("ar$i"), name, null, null) })
        )
        coEvery { repository.getAlbumList("newest", size = 500) } returns listOf(
            album("old", "Aurora", added = "2019-09-01T00:00:00Z"),
            album("new", "Bloom", added = "2024-03-01T00:00:00Z")
        )
        coEvery { repository.getPlaylists() } returns listOf(
            Playlist(MediaId.subsonic("pl"), "Playlist", "me", null, null, null)
        )
    }

    private fun album(id: String, name: String, added: String) = Album(
        id = MediaId.subsonic(id),
        name = name,
        artist = "Someone",
        artistId = null,
        coverArt = null,
        songCount = null,
        durationSec = null,
        year = null,
        genre = null,
        libraryAddedAt = added
    )
}
