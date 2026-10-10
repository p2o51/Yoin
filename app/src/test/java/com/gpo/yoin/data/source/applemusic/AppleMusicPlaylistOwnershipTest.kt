package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Library's By You on Apple Music: Apple names no playlist owner, so a library
 * playlist's `canEdit` stands for "made by the user"; it never opens edits.
 */
class AppleMusicPlaylistOwnershipTest {
    private lateinit var server: MockWebServer
    private lateinit var source: AppleMusicSource

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        source = AppleMusicSource(
            ProfileCredentials.AppleMusic("https://token.test", "user"),
            api = AppleMusicApiClient({ "developer" }, { "user" }, baseUrl = server.url("/"))
        )
    }

    @After fun cleanup() = server.shutdown()

    @Test fun should_readOwnershipFromCanEdit_when_listingLibraryPlaylists() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
{
  "data": [
    { "id": "p.mine", "type": "library-playlists", "attributes": { "name": "Mine", "canEdit": true } },
    { "id": "p.mix", "type": "library-playlists", "attributes": { "name": "Favorites Mix", "canEdit": false } },
    { "id": "p.old", "type": "library-playlists", "attributes": { "name": "Old" } }
  ]
}
"""
            )
        )

        val playlists = source.getPlaylists().associateBy { it.id.rawId }

        assertEquals(true, playlists.getValue("library:p.mine").ownedByMe)
        assertEquals(false, playlists.getValue("library:p.mix").ownedByMe)
        assertNull(playlists.getValue("library:p.old").ownedByMe)
        // Yoin writes no Apple playlists: canEdit never opens edits.
        playlists.values.forEach { assertFalse(it.canWrite) }
    }

    @Test fun should_leaveOwnershipUnknown_when_playlistIsFromTheCatalog() {
        val catalog = Json.parseToJsonElement(
            """{ "id": "pl.editorial", "type": "playlists", "attributes": { "name": "Today", "canEdit": true } }"""
        ).jsonObject

        assertNull(AppleMusicSource.playlist(catalog).ownedByMe)
    }
}
