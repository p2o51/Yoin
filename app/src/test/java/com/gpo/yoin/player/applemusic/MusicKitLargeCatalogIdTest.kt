package com.gpo.yoin.player.applemusic

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Apple Music songs added since mid-2026 have catalog ids above Int.MAX_VALUE (Pop Sound: 6805970194).
 * MusicKit 1.1.1 read webPlayback's `songId` with `JsonReader.nextInt()`, threw, and left the song buffering at
 * 0:00 with no error. build-logic's MusicKitLargeIdPlugin rewrites that read at build time; this parses a reply
 * with the SDK's own class (`playback.g.h`), so it fails if the rewrite stops reaching the test classpath. (Without
 * the rewrite the JVM can't even load that class: the SDK ships no stack map frames, and the plugin computes them
 * for the class it rewrites.)
 */
@RunWith(RobolectricTestRunner::class)
class MusicKitLargeCatalogIdTest {

    @Test
    fun should_parseAssets_when_songIdIsAboveIntMax() {
        val assets = parseWebPlayback(reply(songId = 6_805_970_194L))

        assertEquals(1, assets.size)
        assertEquals(ASSET_URL, assets.single().stringGetter("a"))
    }

    @Test
    fun should_parseAssets_when_songIdFitsInt() {
        val assets = parseWebPlayback(reply(songId = 1_440_857_781L))

        assertEquals(1, assets.size)
        assertEquals(ASSET_URL, assets.single().stringGetter("a"))
    }

    private fun parseWebPlayback(json: String): List<Any> {
        val parser = Class.forName("com.apple.android.music.playback.g.h")
        val response = parser.getMethod("a", InputStream::class.java).invoke(null, json.byteInputStream())!!
        @Suppress("UNCHECKED_CAST")
        return response.javaClass.getMethod("a").invoke(response) as List<Any>
    }

    private fun Any.stringGetter(name: String): String? = javaClass.getMethod(name).invoke(this) as String?

    private fun reply(songId: Long) = """
        {"songList":[{
            "hls-key-cert-url":"https://play.itunes.apple.com/cert",
            "hls-key-server-url":"https://play.itunes.apple.com/license",
            "songId":$songId,
            "assets":[{"flavor":"30:cbcp256","URL":"$ASSET_URL","file-size":4096}]
        }]}
    """.trimIndent()

    private companion object {
        const val ASSET_URL = "https://aod.itunes.apple.com/song.m3u8"
    }
}
