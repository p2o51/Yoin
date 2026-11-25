package com.gpo.yoin.data.model

import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSizesTest {

    @Test
    fun bucketsRoundUp_neverDown() {
        assertEquals(160, CoverSizes.bucketUp(1))
        assertEquals(160, CoverSizes.bucketUp(160))
        assertEquals(320, CoverSizes.bucketUp(161))
        assertEquals(1200, CoverSizes.bucketUp(1080))
        assertEquals(1600, CoverSizes.bucketUp(1600))
        // Above the largest bucket: the largest (no 2560 px covers).
        assertEquals(1600, CoverSizes.bucketUp(2208))
    }

    @Test
    fun heroIsTheDisplaysShortSide_roundedUp() {
        assertEquals(1200, CoverSizes.heroFor(1080, 2400)) // phone portrait
        assertEquals(1200, CoverSizes.heroFor(2400, 1080)) // same phone, landscape: same url
        assertEquals(1600, CoverSizes.heroFor(1600, 2560)) // Pixel Tablet
        assertEquals(1600, CoverSizes.heroFor(2560, 1600))
    }

    @Test
    fun rowsAreSizedFromTheirDpAndDensity() {
        assertEquals(160, CoverSizes.forDisplay(56f, 2f)) // tablet, 112 px
        assertEquals(160, CoverSizes.forDisplay(56f, 2.625f)) // 147 px
        assertEquals(320, CoverSizes.forDisplay(56f, 3.5f)) // 196 px: no upscaling
        assertTrue(CoverSizes.rowPx in CoverSizes.BUCKETS)
        assertTrue(CoverSizes.heroPx in CoverSizes.BUCKETS)
    }

    @Test
    fun aSizedCoversKeyMapsBackToThePreSizingKey() {
        val credentials = ServerCredentials("https://music.example", "u", "p")
        val before = SubsonicCacheKey.of(SubsonicApiFactory.buildCoverArtUrl(credentials, "al-1", null))!!
        val sized = SubsonicCacheKey.of(SubsonicApiFactory.buildCoverArtUrl(credentials, "al-1", 1200))!!
        assertEquals(before, SubsonicCacheKey.unsizedCoverKey(sized))
        assertNull(SubsonicCacheKey.unsizedCoverKey(before))
        val stream = SubsonicCacheKey.of(SubsonicApiFactory.buildStreamUrl(credentials, "s-1"))!!
        assertNull(SubsonicCacheKey.unsizedCoverKey(stream))
    }
}
