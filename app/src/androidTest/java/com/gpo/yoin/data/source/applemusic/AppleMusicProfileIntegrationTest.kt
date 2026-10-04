package com.gpo.yoin.data.source.applemusic

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.source.Capability
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in verification of the active account on a physical device.
 * Uses the app's encrypted profile credentials without copying or logging them.
 *
 * am instrument -w -e verifyAppleMusicProfile true \
 *   -e class com.gpo.yoin.data.source.applemusic.AppleMusicProfileIntegrationTest \
 *   com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The separate verifyAppleMusicLibraryAddition=true flag permits one real
 * library addition using the first appleMusicSearchTerm catalog result.
 */
@RunWith(AndroidJUnit4::class)
class AppleMusicProfileIntegrationTest {
    @Test
    fun should_loadCatalogAndPersonalLibraryAndConfirmMembership_when_activeAppleAccountIsAuthorized() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyAppleMusicProfile") == "true")
        runBlocking {
            withTimeout(120_000) {
                val application = ApplicationProvider.getApplicationContext<YoinApplication>()
                val source = withTimeout(15_000) {
                    application.container.profileManager.activeSource.filterNotNull().first()
                }
                assertTrue("Select the authorized Apple Music profile before running this test", source is AppleMusicSource)
                assertTrue(Capability.CATALOG_SEARCH in source.capabilities)
                assertTrue(Capability.LIBRARY_SONGS in source.capabilities)
                assertTrue(Capability.LIBRARY_ADD in source.capabilities)
                assertFalse(Capability.FAVORITES in source.capabilities)

                val library = source.library()
                val catalog = library.search(arguments.getString("appleMusicSearchTerm") ?: "Taylor Swift")
                assertTrue("The catalog search must return playable song identities", catalog.tracks.isNotEmpty())
                assertTrue(catalog.tracks.all {
                    it.id.provider == MediaId.PROVIDER_APPLE_MUSIC && it.extras["appleMusicCatalogId"] != null
                })

                val songs = library.getLibrarySongs(size = 10)
                assertTrue("The signed-in account needs at least one saved song for this integration test", songs.isNotEmpty())
                assertTrue(songs.all { it.id.provider == MediaId.PROVIDER_APPLE_MUSIC })
                assertTrue("Saved songs must retain their distinct library identities", songs.all {
                    it.extras["appleMusicLibraryId"] != null
                })
                assertTrue("Library membership must never become a favorite heart", songs.none { it.isStarred })

                val saved = songs.firstOrNull { !it.title.isNullOrBlank() }
                requireNotNull(saved) { "The personal library needs a named song for the search check" }
                val personal = library.searchLibrary(requireNotNull(saved.title))
                assertTrue("Personal library search must return the existing saved song", personal.tracks.any {
                    it.extras["appleMusicLibraryId"] == saved.extras["appleMusicLibraryId"]
                })
                assertTrue(personal.tracks.all { it.extras["appleMusicLibraryId"] != null })

                val catalogBackedSaved = songs.firstOrNull { it.extras["appleMusicCatalogId"] != null }
                val membershipId = catalogBackedSaved?.id ?: catalog.tracks.first().id
                val membership = source.writeActions().libraryMembership(membershipId).getOrThrow()
                if (catalogBackedSaved != null) {
                    assertEquals("The exact catalog relationship must confirm the saved song", LibraryMembership.Added, membership)
                } else {
                    assertTrue(membership == LibraryMembership.Added || membership == LibraryMembership.NotAdded)
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putInt("appleMusicCatalogSongCount", catalog.tracks.size)
                        putInt("appleMusicLibrarySongCount", songs.size)
                        putInt("appleMusicPersonalSearchSongCount", personal.tracks.size)
                        putString("appleMusicMembership", membership.name)
                    }
                )
            }
        }
    }

    @Test
    fun should_addCatalogSongAndReportConfirmedOrPending_when_libraryAdditionIsExplicitlyEnabled() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("verifyAppleMusicLibraryAddition") == "true")
        var stage = "initializing"
        try {
            runBlocking {
                withTimeout(180_000) {
                    val application = ApplicationProvider.getApplicationContext<YoinApplication>()
                    val source = withTimeout(15_000) {
                        application.container.profileManager.activeSource.filterNotNull().first()
                    }
                    assertTrue("Select the authorized Apple Music profile before the addition test", source is AppleMusicSource)
                    stage = "catalogSearch"
                    val results = source.library().search(arguments.getString("appleMusicSearchTerm") ?: "ocean eyes")
                    val track = requireNotNull(results.tracks.firstOrNull()) { "Catalog search returned no song" }
                    val title = requireNotNull(track.title) { "Catalog search returned an unnamed song" }
                    reportAddition(Bundle().apply {
                        putString("appleMusicAdditionStage", stage)
                        putString("appleMusicAdditionTitle", title)
                        putString("appleMusicAdditionId", track.id.toString())
                    })

                    stage = "membershipBefore"
                    val before = source.writeActions().libraryMembership(track.id).getOrThrow()
                    reportAddition(Bundle().apply {
                        putString("appleMusicAdditionStage", stage)
                        putString("appleMusicAdditionMembershipBefore", before.name)
                    })
                    stage = "addAndConfirm"
                    val after = source.writeActions().addToLibrary(track.id).getOrThrow()
                    reportAddition(Bundle().apply {
                        putString("appleMusicAdditionStage", stage)
                        putString("appleMusicAdditionMembershipAfter", after.name)
                        putBoolean("appleMusicAdditionExactMembershipConfirmed", after == LibraryMembership.Added)
                    })
                    assertTrue("An accepted addition must remain Added or Pending", after == LibraryMembership.Added || after == LibraryMembership.Pending)

                    stage = "personalSearch"
                    var searchAttempt = 0
                    suspend fun checkSearchIndex(): Boolean {
                        val personal = source.library().searchLibrary(title)
                        val exactMatch = personal.tracks.any {
                            it.id == track.id && it.extras["appleMusicLibraryId"] != null
                        }
                        reportAddition(Bundle().apply {
                            putString("appleMusicAdditionStage", stage)
                            putInt("appleMusicAdditionSearchAttempt", ++searchAttempt)
                            putInt("appleMusicAdditionPersonalSearchSongCount", personal.tracks.size)
                            putBoolean("appleMusicAdditionExactLibraryMatch", exactMatch)
                        })
                        return exactMatch
                    }
                    // Membership and search indexing are separate: the exact relationship can
                    // confirm the write before the newly added song appears in search results.
                    val exactMatch = if (after == LibraryMembership.Added) {
                        withTimeoutOrNull(30_000) {
                            for (waitMs in listOf(0L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L)) {
                                delay(waitMs)
                                if (checkSearchIndex()) return@withTimeoutOrNull true
                            }
                            false
                        } ?: false
                    } else {
                        checkSearchIndex()
                    }
                    reportAddition(Bundle().apply {
                        putString("appleMusicAdditionStage", stage)
                        putBoolean("appleMusicAdditionSearchIndexConfirmed", exactMatch)
                    })
                    if (after == LibraryMembership.Added) {
                        assertTrue("Membership is confirmed; personal search must index the exact song within 30 seconds", exactMatch)
                    }
                }
            }
        } catch (error: Exception) {
            val failure = if (error is AppleMusicApiException) error.failure.toString() else error.javaClass.simpleName
            reportAddition(Bundle().apply {
                putString("appleMusicAdditionStage", stage)
                putString("appleMusicAdditionFailure", failure)
            })
            // Do not attach an arbitrary exception or response body to instrumentation output.
            throw AssertionError("Apple Music library-add verification failed at $stage ($failure)")
        }
    }

    private fun reportAddition(status: Bundle) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status)
    }
}
