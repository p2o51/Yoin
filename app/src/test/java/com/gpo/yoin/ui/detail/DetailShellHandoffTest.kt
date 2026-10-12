package com.gpo.yoin.ui.detail

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.MainActivity
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The shell → detail hand-off lives in a process-wide store, but belongs to the detail window (ticket 4, Samsung
 * S26): a page destroyed after it had already stopped — the task swiped from recents while music kept the process
 * alive — never sent the settled tick, so the next shell opened 96dp to the left under the detail bar.
 */
@RunWith(RobolectricTestRunner::class)
// Below API 30: enableYoinEdgeToEdge's display-mode request needs a display Robolectric's activities lack.
@Config(sdk = [26])
class DetailShellHandoffTest {

    private val store: ExperienceSessionStore =
        ApplicationProvider.getApplicationContext<YoinApplication>().container.experienceSessionStore

    /** The shell's tap: arm the hand-off, then launch the page as the Compact shell does. */
    private fun launchAlbumFromShell(): Intent {
        val shell = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        store.beginDetailHandoff()
        launchDetailFromShell(shell, AlbumDetailActivity.intent(shell, "subsonic:album:1"))
        return shadowOf(shell).nextStartedActivity
    }

    @Test
    fun should_stampHandoffGeneration_when_launchedFromShell() {
        val launched = launchAlbumFromShell()

        assertTrue(launched.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false))
        assertEquals(store.detailHandoffGeneration, launched.getLongExtra(DETAIL_EXTRA_HANDOFF_GENERATION, 0L))
    }

    @Test
    fun should_restoreNavChrome_when_stoppedShellDetailIsDestroyedForGood() {
        val detail = Robolectric.buildActivity(AlbumDetailActivity::class.java, launchAlbumFromShell()).create()
        assertTrue(store.state.value.detailChromeActive)

        // Already stopped (Home pressed on the page), then the task is removed: no finishing onStop follows.
        detail.get().finish()
        detail.destroy()

        assertFalse(store.state.value.detailChromeActive)
    }

    @Test
    fun should_keepHandoff_when_detailWindowIsDestroyedWithoutFinishing() {
        val detail = Robolectric.buildActivity(AlbumDetailActivity::class.java, launchAlbumFromShell()).create()

        detail.destroy()

        assertTrue(store.state.value.detailChromeActive)
    }

    @Test
    fun should_keepNextPageHandoff_when_earlierDetailIsDestroyedLate() {
        val earlier = Robolectric.buildActivity(AlbumDetailActivity::class.java, launchAlbumFromShell()).create()
        launchAlbumFromShell()

        earlier.get().finish()
        earlier.destroy()

        assertTrue(store.state.value.detailChromeActive)
    }

    @Test
    fun should_openAtNavPose_when_newShellFindsStaleHandoff() {
        store.beginDetailHandoff()
        runBlocking { store.shellBarChromeMorph.snapTo(1f) }

        Robolectric.buildActivity(MainActivity::class.java).create()

        assertFalse(store.state.value.detailChromeActive)
        assertEquals(0f, store.shellBarChromeMorph.value)
    }

    @Test
    fun should_keepHandoff_when_shellIsRecreatedUnderItsDetail() {
        store.beginDetailHandoff()
        runBlocking { store.shellBarChromeMorph.snapTo(1f) }

        Robolectric.buildActivity(MainActivity::class.java).create(Bundle())

        assertTrue(store.state.value.detailChromeActive)
        assertEquals(1f, store.shellBarChromeMorph.value)
    }

    @Test
    fun should_keepHandoff_when_nestedDetailStopsAndIsDestroyed() {
        val outer = Robolectric.buildActivity(AlbumDetailActivity::class.java, launchAlbumFromShell()).create().get()
        launchDetailFromDetail(outer, ArtistDetailActivity.intent(outer, "subsonic:artist:1"), fromNowPlaying = false)
        val nestedIntent = shadowOf(outer).nextStartedActivity
        val nested = Robolectric.buildActivity(ArtistDetailActivity::class.java, nestedIntent).create()

        nested.get().finish()
        nested.get().publishShellHandoffSettled()
        nested.destroy()

        assertTrue(store.state.value.detailChromeActive)
    }

    @Test
    fun should_keepNextHandoff_when_earlierDetailStopsLate() {
        val earlier = Robolectric.buildActivity(AlbumDetailActivity::class.java, launchAlbumFromShell()).create()
        launchAlbumFromShell()

        earlier.get().finish()
        earlier.get().publishShellHandoffSettled()
        store.detailWindowSettledGeneration.value?.let(store::endDetailHandoff)

        assertTrue(store.state.value.detailChromeActive)
    }

    @Test
    fun should_keepHandoff_when_aSecondMainActivityIsCreated() {
        Robolectric.buildActivity(MainActivity::class.java).create()
        store.beginDetailHandoff()
        runBlocking { store.shellBarChromeMorph.snapTo(1f) }

        Robolectric.buildActivity(MainActivity::class.java).create()

        assertTrue(store.state.value.detailChromeActive)
        assertEquals(1f, store.shellBarChromeMorph.value)
    }

    @Test
    fun should_resetPose_when_aNewMainActivityReplacesTheDestroyedTask() {
        val shell = Robolectric.buildActivity(MainActivity::class.java).create()
        store.beginDetailHandoff()
        runBlocking { store.shellBarChromeMorph.snapTo(1f) }
        shell.get().finish()
        shell.destroy()

        Robolectric.buildActivity(MainActivity::class.java).create()

        assertFalse(store.state.value.detailChromeActive)
        assertEquals(0f, store.shellBarChromeMorph.value)
    }
}
