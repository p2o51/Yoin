package com.gpo.yoin.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicCacheKeyTest {

    private val credentials = ServerCredentials(
        serverUrl = "https://music.example.com/navidrome",
        username = "demo",
        password = "secret"
    )

    /** The same url as the next process builds it: SubsonicAuth draws a new salt per process. */
    private fun signedByAnotherProcess(url: String): String {
        val (token, salt) = SubsonicAuth.generateToken(credentials.password)
        return url.toHttpUrl().newBuilder()
            .setQueryParameter("t", token)
            .setQueryParameter("s", salt)
            .build()
            .toString()
    }

    private fun cover(id: String = "al-1", size: Int? = 300, of: ServerCredentials = credentials) =
        SubsonicApiFactory.buildCoverArtUrl(of, id, size)

    @Test
    fun should_giveOneKey_when_anotherProcessSignsTheCoverWithANewSalt() {
        val thisProcess = cover()
        val nextProcess = signedByAnotherProcess(thisProcess)

        assertNotEquals(thisProcess, nextProcess)
        assertEquals(SubsonicCacheKey.of(thisProcess), SubsonicCacheKey.of(nextProcess))
    }

    @Test
    fun should_holdNoCredential_when_keyIsBuilt() {
        val url = cover().toHttpUrl()
        val key = checkNotNull(SubsonicCacheKey.of(url.toString())).toHttpUrl()

        assertNull(key.queryParameter("t"))
        assertNull(key.queryParameter("s"))
        listOf("t", "s").forEach { name ->
            assertFalse(key.toString().contains(checkNotNull(url.queryParameter(name))))
        }
        // What names the cover stays: the server, the user, the id and the size.
        val expected = "https://music.example.com/navidrome/rest/getCoverArt.view?id=al-1&size=300&u=demo"
        assertEquals(expected, key.toString())
    }

    @Test
    fun should_keepCoversApart_when_idSizeUserOrServerDiffer() {
        val keys = listOf(
            cover(),
            cover(id = "al-2"),
            cover(size = 600),
            cover(size = null),
            cover(of = credentials.copy(username = "other")),
            cover(of = credentials.copy(serverUrl = "https://music.example.org/navidrome"))
        ).map { url -> checkNotNull(SubsonicCacheKey.of(url)) }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun should_returnNull_when_urlIsNotASignedSubsonicUrl() {
        listOf(
            "https://i.scdn.co/image/ab67616d0000b273d1f2e3",
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/cover.jpg/600x600bb.jpg",
            "https://music.example.com/rest/getCoverArt.view?id=al-1",
            "content://media/external/images/1",
            "not a url"
        ).forEach { url -> assertNull(url, SubsonicCacheKey.of(url)) }
    }
}
