package com.gpo.yoin.data.openin

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenInRoutingTest {

    private val spotifyTrack = YoinMusicLinkParser.parse("https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b")!!
    private val appleSong = YoinMusicLinkParser.parse("https://music.apple.com/us/album/x/1499378108?i=1499378615")!!
    private val appleAlbum = YoinMusicLinkParser.parse("https://music.apple.com/us/album/after-hours/1499378108")!!

    private fun profile(id: String, provider: String, createdAt: Long = 0L) =
        Profile(id = id, provider = provider, displayName = "$provider $id", credentialsJson = "{}", createdAt = createdAt)

    @Test
    fun should_hitTheLinksService_when_userHasAnAccountThere() {
        val profiles = listOf(profile("nav", MediaId.PROVIDER_SUBSONIC), profile("sp", MediaId.PROVIDER_SPOTIFY))

        val target = OpenInRouting.route(spotifyTrack, profiles, activeProfileId = "nav")

        assertEquals(OpenInTarget.Hit("sp", "spotify sp"), target)
    }

    @Test
    fun should_beFound_when_noAccountIsOnTheLinksService() {
        val profiles = listOf(profile("nav", MediaId.PROVIDER_SUBSONIC), profile("sp", MediaId.PROVIDER_SPOTIFY))

        assertEquals(OpenInTarget.Found, OpenInRouting.route(appleSong, profiles, activeProfileId = "sp"))
        assertEquals(OpenInTarget.Found, OpenInRouting.route(spotifyTrack, emptyList(), activeProfileId = null))
    }

    @Test
    fun should_sendEachLinkToItsOwnService_when_userHasBothSpotifyAndAppleMusic() {
        val profiles = listOf(profile("sp", MediaId.PROVIDER_SPOTIFY), profile("am", MediaId.PROVIDER_APPLE_MUSIC))

        assertEquals("am", (OpenInRouting.route(appleSong, profiles, "sp") as OpenInTarget.Hit).profileId)
        assertEquals("sp", (OpenInRouting.route(spotifyTrack, profiles, "am") as OpenInTarget.Hit).profileId)
    }

    @Test
    fun should_preferTheAccountInUse_when_severalAreOnTheService() {
        val profiles = listOf(
            profile("sp-old", MediaId.PROVIDER_SPOTIFY, createdAt = 1),
            profile("sp-new", MediaId.PROVIDER_SPOTIFY, createdAt = 2),
        )

        assertEquals("sp-new", (OpenInRouting.route(spotifyTrack, profiles, "sp-new") as OpenInTarget.Hit).profileId)
        assertEquals("sp-old", (OpenInRouting.route(spotifyTrack, profiles, "nav") as OpenInTarget.Hit).profileId)
    }

    @Test
    fun should_playSongsAndOpenEverythingElse_when_hitOpens() {
        assertEquals(
            OpenInHitAction.PlayTrack(MediaId(MediaId.PROVIDER_SPOTIFY, "0VjIjW4GlUZAMYd2vXMi3b")),
            OpenInRouting.hitAction(spotifyTrack),
        )
        assertEquals(
            OpenInHitAction.PlayTrack(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1499378615")),
            OpenInRouting.hitAction(appleSong),
        )
        assertEquals(
            OpenInHitAction.OpenAlbum(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1499378108")),
            OpenInRouting.hitAction(appleAlbum),
        )
        assertEquals(
            OpenInHitAction.OpenArtist(MediaId(MediaId.PROVIDER_SPOTIFY, "1Xyo4u8uXC1ZmMpatF05PJ")),
            OpenInRouting.hitAction(YoinMusicLinkParser.parse("spotify:artist:1Xyo4u8uXC1ZmMpatF05PJ")!!),
        )
        assertEquals(
            OpenInHitAction.OpenPlaylist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "pl.f4d106fed2bd41149aaacabb233eb5eb")),
            OpenInRouting.hitAction(
                YoinMusicLinkParser.parse("https://music.apple.com/us/playlist/x/pl.f4d106fed2bd41149aaacabb233eb5eb")!!,
            ),
        )
    }

    @Test
    fun should_haveNoAction_when_shortLinkIsNotFollowedYet() {
        assertNull(OpenInRouting.hitAction(YoinMusicLinkParser.parse("https://spotify.link/ZbMi8QkTZDb")!!))
    }
}
