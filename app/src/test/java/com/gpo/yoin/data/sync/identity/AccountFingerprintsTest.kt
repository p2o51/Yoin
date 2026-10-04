package com.gpo.yoin.data.sync.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountFingerprintsTest {
    private val base = AccountFingerprints.subsonic("https://music.example.com", "alice")

    @Test
    fun should_produceVersionedFingerprint_when_inputIsCanonical() {
        assertEquals("subsonic:v1:music.example.com\u001Falice", base)
    }

    @Test
    fun should_matchCanonicalFingerprint_when_inputOnlyDiffersCosmetically() {
        val equivalents = listOf(
            // scheme dropped
            "http://music.example.com" to "alice",
            // default https port dropped
            "https://music.example.com:443" to "alice",
            // default http port + trailing slash
            "http://music.example.com:80/" to "alice",
            // host case
            "https://MUSIC.Example.COM" to "alice",
            // trailing slash
            "https://music.example.com/" to "alice",
            // username trimmed + case
            "https://music.example.com" to "  Alice ",
            // userinfo dropped
            "https://bob:secret@music.example.com" to "alice",
            // query + fragment dropped
            "https://music.example.com/?u=alice&p=secret#frag" to "alice",
            // no scheme -> https assumed
            "music.example.com" to "alice",
            // surrounding whitespace
            "  music.example.com/  " to "alice",
        )
        for ((url, user) in equivalents) {
            assertEquals("$url / $user", base, AccountFingerprints.subsonic(url, user))
        }
    }

    @Test
    fun should_keepIdentityParts_when_theyDistinguishServers() {
        val distinct = listOf(
            // non-default port
            "https://music.example.com:4533" to "alice",
            // 443 is not http's default
            "http://music.example.com:443" to "alice",
            // path
            "https://music.example.com/navidrome" to "alice",
            // host
            "https://other.example.com" to "alice",
            // user
            "https://music.example.com" to "bob",
        )
        for ((url, user) in distinct) {
            assertNotEquals("$url / $user", base, AccountFingerprints.subsonic(url, user))
        }
        assertEquals(
            "subsonic:v1:music.example.com:4533/navidrome\u001Falice",
            AccountFingerprints.subsonic("music.example.com:4533/navidrome/", "alice"),
        )
    }

    @Test
    fun should_returnNull_when_addressOrUserUnusable() {
        assertNull(AccountFingerprints.subsonic("", "alice"))
        assertNull(AccountFingerprints.subsonic("ftp://music.example.com", "alice"))
        assertNull(AccountFingerprints.subsonic("https://", "alice"))
        assertNull(AccountFingerprints.subsonic("https://music.example.com", "   "))
    }

    @Test
    fun should_mintStablePrefixedId_when_derivingDeterministicId() {
        val id = AccountFingerprints.deterministicId(base!!)

        assertTrue(id.matches(Regex("s-[0-9a-f]{24}")))
        val sameAccount = AccountFingerprints.subsonic("http://MUSIC.example.com/", "ALICE")!!
        assertEquals(id, AccountFingerprints.deterministicId(sameAccount))
        assertNotEquals(id, AccountFingerprints.deterministicId(AccountFingerprints.spotify("alice")))
        assertEquals("spotify:v1:alice", AccountFingerprints.spotify("alice"))
    }

    @Test
    fun should_buildSanitizedHint_when_urlCarriesSecrets() {
        assertEquals(
            "Alice @ music.example.com:4533",
            AccountFingerprints.subsonicHint("http://alice:pw@Music.Example.com:4533/rest/?p=secret#x", " Alice "),
        )
        assertEquals(
            "alice @ music.example.com",
            AccountFingerprints.subsonicHint("music.example.com/navidrome", "alice"),
        )
        assertEquals("alice @ [::1]:4533", AccountFingerprints.subsonicHint("http://[::1]:4533", "alice"))
        assertNull(AccountFingerprints.subsonicHint("not a url at all", "alice"))
    }
}
