package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.ReleaseType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Apple Music names an EP only by " - EP"; singles and compilations have flags (PROTOTYPE, PR #343). */
class AppleMusicReleaseTypeTest {
    private fun album(attrs: String, catalog: String? = null) = AppleMusicSource.album(
        Json.parseToJsonElement(
            """{"id":"1","type":"${if (catalog != null) "library-albums" else "albums"}","attributes":{$attrs}""" +
                (catalog?.let { ""","relationships":{"catalog":{"data":[{"id":"9","type":"albums","attributes":{$it}}]}}""" } ?: "") + "}",
        ).jsonObject,
    )

    @Test
    fun should_readTheKind_from_appleFlagsAndNameSuffixes() {
        assertEquals(ReleaseType.EP, album(""""name":"Section.80 - EP","isSingle":false""").releaseType)
        assertEquals(ReleaseType.Single, album(""""name":"HUMBLE. - Single","isSingle":true""").releaseType)
        assertEquals(ReleaseType.Compilation, album(""""name":"Now 100","isCompilation":true,"isSingle":false""").releaseType)
        assertEquals(ReleaseType.Album, album(""""name":"DAMN.","isSingle":false""").releaseType)
    }

    @Test
    fun should_readTheCatalogFlags_forALibraryAlbum_andStayUnknownWithout() {
        assertEquals(ReleaseType.Album, album(""""name":"DAMN."""", catalog = """"name":"DAMN.","isSingle":false""").releaseType)
        assertEquals(ReleaseType.EP, album(""""name":"Untitled - EP"""").releaseType)
        assertNull(album(""""name":"DAMN."""").releaseType)
    }
}
