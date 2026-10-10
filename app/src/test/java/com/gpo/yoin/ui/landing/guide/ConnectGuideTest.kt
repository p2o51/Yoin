package com.gpo.yoin.ui.landing.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectGuideTest {

    @Test
    fun should_formatFingerprintAsSpotifyTakesIt_when_digestIsGiven() {
        val digest = byteArrayOf(0xE7.toByte(), 0x47, 0x0B, 0x00, 0xFF.toByte())
        assertEquals("E7:47:0B:00:FF", formatFingerprint(digest))
    }

    @Test
    fun should_copyThePackageThenItsFingerprint_when_spotifyGuideReachesAndroidPackages() {
        val copies = SpotifyGuideSteps.mapNotNull { it.copies }
        assertEquals(
            listOf(GuideCopy.RedirectUri, GuideCopy.AppRemoteRedirectUri, GuideCopy.PackageName, GuideCopy.Sha1),
            copies,
        )
        // Android must be ticked before its package rows show up on the dashboard.
        val apis = SpotifyGuideSteps.indexOfFirst { "Android" in it.labels }
        val packageStep = SpotifyGuideSteps.indexOfFirst { it.copies == GuideCopy.PackageName }
        assertTrue(apis in 0 until packageStep)
    }

    @Test
    fun should_endOnTheClientId_when_spotifyGuideIsWalked() {
        assertTrue("Client ID" in SpotifyGuideSteps.last().labels)
        assertEquals(null, SpotifyGuideSteps.last().then)
    }

    @Test
    fun should_offerNoCopyActions_when_guidingAppleMusicSignIn() {
        assertTrue(AppleMusicGuideSteps.all { it.copies == null })
        assertTrue(AppleMusicRetryStep in AppleMusicGuideSteps.indices)
    }
}
