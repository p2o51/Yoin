package com.gpo.yoin.data.openin

import app.cash.turbine.test
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.openin.YoinMusicLink
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenInCoordinatorTest {

    private val spotifyLink = YoinMusicLinkParser.parse("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b")!!
    private val shortLink = YoinMusicLinkParser.parse("https://spotify.link/ZbMi8QkTZDb")!!
    private val metadata = MusicLinkMetadata(YoinMusicLinkKind.Track, "Blinding Lights", "The Weeknd")

    private class FakeLookup(
        val followed: Map<String, YoinMusicLink?> = emptyMap(),
        val metadata: MusicLinkMetadata? = null,
    ) : MusicLinkLookup {
        val resolved = mutableListOf<String>()
        override suspend fun resolve(link: YoinMusicLink): YoinMusicLink? {
            if (!link.needsRedirectResolution) return link
            resolved += link.url
            return followed[link.url]
        }

        override suspend fun metadata(link: YoinMusicLink): MusicLinkMetadata? = metadata
    }

    private fun profile(id: String, provider: String) = Profile(id, provider, "$provider $id", "{}", createdAt = 0)

    private fun TestScope.coordinator(lookup: MusicLinkLookup, profiles: List<Profile>, active: String? = profiles.firstOrNull()?.id) =
        OpenInCoordinator(
            scope = backgroundScope,
            lookup = lookup,
            profiles = { profiles },
            activeProfileId = { active },
        )

    @Test
    fun should_showRichHitPrompt_when_userHasAnAccountOnTheLinksService() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(metadata = metadata), listOf(profile("sp", MediaId.PROVIDER_SPOTIFY)))

        assertTrue(openIn.offerClipboard(spotifyLink))
        advanceUntilIdle()

        val prompt = openIn.prompt.value!!
        assertEquals(OpenInTarget.Hit("sp", "spotify sp"), prompt.target)
        assertEquals(metadata, prompt.metadata)
        assertTrue(prompt.listens)
    }

    @Test
    fun should_showFoundPrompt_when_noAccountIsOnTheLinksService() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(metadata = metadata), listOf(profile("nav", MediaId.PROVIDER_SUBSONIC)))

        openIn.offerClipboard(spotifyLink)
        advanceUntilIdle()

        assertEquals(OpenInTarget.Found, openIn.prompt.value?.target)
    }

    @Test
    fun should_offerNothing_when_thereIsNoAccountYet() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(), emptyList())

        assertFalse(openIn.offerClipboard(spotifyLink))
        assertNull(openIn.prompt.value)
    }

    @Test
    fun should_followShortLink_when_promptIsFilled() = runTest(UnconfinedTestDispatcher()) {
        val lookup = FakeLookup(followed = mapOf(shortLink.url to spotifyLink), metadata = metadata)
        val openIn = coordinator(lookup, listOf(profile("sp", MediaId.PROVIDER_SPOTIFY)))

        openIn.offerClipboard(shortLink)
        advanceUntilIdle()

        assertEquals(spotifyLink, openIn.prompt.value?.link)
        assertTrue(openIn.prompt.value!!.resolved)
    }

    @Test
    fun should_requestHitOpen_when_hitPromptIsAccepted() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(metadata = metadata), listOf(profile("sp", MediaId.PROVIDER_SPOTIFY)))
        openIn.offerClipboard(spotifyLink)
        advanceUntilIdle()

        openIn.requests.test {
            openIn.accept()
            assertEquals(OpenInRequest.OpenHit(spotifyLink, OpenInTarget.Hit("sp", "spotify sp")), awaitItem())
        }
        assertNull(openIn.prompt.value)
    }

    @Test
    fun should_requestResultsNotPlayback_when_foundPromptIsAccepted() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(metadata = metadata), listOf(profile("nav", MediaId.PROVIDER_SUBSONIC)))
        openIn.offerClipboard(spotifyLink)
        advanceUntilIdle()

        openIn.requests.test {
            openIn.accept()
            assertEquals(OpenInRequest.ShowResults(spotifyLink, metadata), awaitItem())
        }
    }

    @Test
    fun should_clearPrompt_when_dismissed() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(), listOf(profile("sp", MediaId.PROVIDER_SPOTIFY)))
        openIn.offerClipboard(spotifyLink)

        openIn.dismiss()

        assertNull(openIn.prompt.value)
    }

    @Test
    fun should_openSharedLinkDirectly_when_itIsAHit() = runTest(UnconfinedTestDispatcher()) {
        val profiles = listOf(profile("nav", MediaId.PROVIDER_SUBSONIC), profile("am", MediaId.PROVIDER_APPLE_MUSIC))
        val openIn = coordinator(FakeLookup(), profiles, active = "nav")
        val apple = YoinMusicLinkParser.parse("https://music.apple.com/us/album/after-hours/1499378108")!!

        openIn.requests.test {
            openIn.offerShared("After Hours on Apple Music ${apple.url}")
            assertEquals(OpenInRequest.OpenHit(apple, OpenInTarget.Hit("am", "applemusic am")), awaitItem())
        }
        assertNull(openIn.prompt.value)
    }

    @Test
    fun should_showResultsForSharedLink_when_itIsFound() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(), listOf(profile("nav", MediaId.PROVIDER_SUBSONIC)))

        openIn.requests.test {
            openIn.offerShared("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=1")
            assertEquals(OpenInRequest.ShowResults(spotifyLink.copy(url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=1"), null), awaitItem())
        }
    }

    @Test
    fun should_searchLibrary_when_sharedTextHasNoMusicLink() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(), listOf(profile("nav", MediaId.PROVIDER_SUBSONIC)))

        openIn.requests.test {
            openIn.offerShared("  Blinding Lights\n The Weeknd ")
            assertEquals(OpenInRequest.SearchLibrary("Blinding Lights The Weeknd"), awaitItem())
        }
    }

    @Test
    fun should_searchTheRestOfTheText_when_sharedShortLinkLeadsNowhere() = runTest(UnconfinedTestDispatcher()) {
        val openIn = coordinator(FakeLookup(), listOf(profile("sp", MediaId.PROVIDER_SPOTIFY)))

        openIn.requests.test {
            openIn.offerShared("Mr. Brightside https://spotify.link/ZbMi8QkTZDb")
            assertEquals(OpenInRequest.SearchLibrary("Mr. Brightside"), awaitItem())
        }
    }
}
