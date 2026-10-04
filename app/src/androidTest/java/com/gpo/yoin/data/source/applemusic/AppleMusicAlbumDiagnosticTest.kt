package com.gpo.yoin.data.source.applemusic

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only diagnosis of the active Apple Music profile on a physical device.
 * Reports which album-detail loads succeed or fail (typed failure only), and whether the
 * saved-song list contains duplicate track identities (LazyColumn key collisions).
 *
 * am instrument -w -e diagnoseAppleMusicAlbums true [-e appleMusicSearchTerm <term>] \
 *   -e class com.gpo.yoin.data.source.applemusic.AppleMusicAlbumDiagnosticTest \
 *   com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AppleMusicAlbumDiagnosticTest {
    @Test
    fun should_reportAlbumDetailAndDuplicateIdentityDiagnostics_when_activeAppleAccountIsAuthorized() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("diagnoseAppleMusicAlbums") == "true")
        val term = arguments.getString("appleMusicSearchTerm") ?: "aespa"
        runBlocking {
            withTimeout(240_000) {
                val application = ApplicationProvider.getApplicationContext<YoinApplication>()
                val source = withTimeout(15_000) {
                    application.container.profileManager.activeSource.filterNotNull().first()
                }
                assertTrue("Select the authorized Apple Music profile before running this test", source is AppleMusicSource)
                val library = source.library()

                // 1. Library tab albums → first album detail.
                val libraryAlbums = stage("libraryAlbumList") { library.getAlbumList("alphabeticalByName", 20, 0) }
                report("libraryAlbumListCount", libraryAlbums?.size ?: -1)
                libraryAlbums?.firstOrNull()?.let { album -> probeAlbum("libraryTabAlbum", library, album) }

                // 2. Recently added (Library Albums tab uses "newest").
                val recent = stage("recentlyAdded") { library.getAlbumList("newest", 20, 0) }
                report("recentlyAddedCount", recent?.size ?: -1)
                recent?.firstOrNull()?.let { album -> probeAlbum("recentlyAddedAlbum", library, album) }

                // 3. Personal-library search → first album detail.
                val personal = stage("librarySearch") { library.searchLibrary(term) }
                report("librarySearchAlbumCount", personal?.albums?.size ?: -1)
                report("librarySearchTrackCount", personal?.tracks?.size ?: -1)
                personal?.albums?.firstOrNull()?.let { album -> probeAlbum("librarySearchAlbum", library, album) }

                // 4. Catalog search → first album detail.
                val catalog = stage("catalogSearch") { library.search(term) }
                report("catalogSearchAlbumCount", catalog?.albums?.size ?: -1)
                catalog?.albums?.firstOrNull()?.let { album -> probeAlbum("catalogSearchAlbum", library, album) }

                // 5. Saved songs: duplicate identities collapse into one LazyColumn key.
                val songs = stage("librarySongs") { library.getLibrarySongs(500, 0) }
                if (songs != null) {
                    val duplicates = songs.groupBy { it.id }.filterValues { it.size > 1 }
                    report("librarySongCount", songs.size)
                    report("librarySongDuplicateIdCount", duplicates.size)
                    report("librarySongDuplicateRowCount", duplicates.values.sumOf { it.size })
                    duplicates.entries.take(5).forEachIndexed { index, (id, rows) ->
                        report(
                            "librarySongDuplicate$index",
                            "$id x${rows.size} libraryIds=${rows.map { it.extras["appleMusicLibraryId"] }} " +
                                "albums=${rows.map { it.album }}"
                        )
                    }
                    val imports = songs.filter { it.extras["appleMusicCatalogId"] == null }
                    report("librarySongsWithoutCatalogId", imports.size)
                    imports.take(5).forEachIndexed { index, track ->
                        report("librarySongImport$index", "${track.title} · ${track.artist} · ${track.album}")
                    }
                }
            }
        }
    }

    private suspend fun probeAlbum(label: String, library: com.gpo.yoin.data.source.MusicLibrary, album: Album) {
        report("${label}Id", album.id.toString())
        report("${label}Name", album.name)
        report("${label}TrackCountAttr", album.songCount ?: -1)
        val detail = stage("${label}Detail") { library.getAlbum(album.id) }
        report("${label}DetailLoaded", detail != null)
        if (detail != null) {
            report("${label}DetailTracks", detail.tracks.size)
            report("${label}DetailDuplicateTrackIds", detail.tracks.groupBy { it.id }.count { it.value.size > 1 })
            report("${label}DetailTracksWithoutCatalogId", detail.tracks.count { it.extras["appleMusicCatalogId"] == null })
        }
    }

    private suspend fun <T> stage(name: String, block: suspend () -> T): T? = try {
        block().also { report("${name}Result", "ok") }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AppleMusicApiException) {
        report("${name}Result", "AppleMusicApiException ${error.failure}")
        null
    } catch (error: Exception) {
        // Our own precondition messages are safe; never attach response bodies.
        report("${name}Result", "${error.javaClass.simpleName}: ${error.message?.take(160)}")
        null
    }

    private fun report(key: String, value: Any) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString(key, value.toString()) }
        )
    }
}
