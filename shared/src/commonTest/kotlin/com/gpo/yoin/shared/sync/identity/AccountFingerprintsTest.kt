package com.gpo.yoin.shared.sync.identity

import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertNotEquals
import com.gpo.yoin.shared.sync.testing.assertNull
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test

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

    /** Hosts as OkHttp 5's HttpUrl (Android's fingerprint) canonicalises them, read off HttpUrl.host. */
    @Test
    fun should_canonicaliseHostsLikeAndroid_when_hostIsInternationalOrIpv6() {
        val android = listOf(
            "https://bücher.example" to "xn--bcher-kva.example",
            "https://BÜCHER.example/" to "xn--bcher-kva.example",
            "https://%62ücher.example" to "xn--bcher-kva.example",
            "https://bücher。example" to "xn--bcher-kva.example",
            "https://xn--BCHER-KVA.example" to "xn--bcher-kva.example",
            "https://Straße.example" to "xn--strae-oqa.example",
            "https://例え.テスト" to "xn--r8jz45g.xn--zckzah",
            "https://ｍｕｓｉｃ.example" to "music.example",
            "https://Ⅷ.example" to "viii.example",
            "https://münchen.de." to "xn--mnchen-3ya.de.",
            "https://MUSIC.Example.COM." to "music.example.com.",
            "https://a_b.example" to "a_b.example",
            "https://[2001:DB8:0:0:0:0:0:1]" to "[2001:db8::1]",
            "https://[0:0:0:0:0:0:0:1]" to "[::1]",
            "https://[::0:0:1]" to "[::1]",
            "https://[0:0:0:0:0:0:0:0]" to "[::]",
            "https://[fe80:0:0:0:0:0:0:0]" to "[fe80::]",
            "https://[2001:0db8:0000:0000:0001:0000:0000:0001]" to "[2001:db8::1:0:0:1]",
            "https://[1:0:0:2:0:0:0:3]" to "[1:0:0:2::3]",
            "https://[1:0:0:2:0:0:3:4]" to "[1::2:0:0:3:4]",
            "https://[::1:2:3:4:5:6:7]" to "[0:1:2:3:4:5:6:7]",
            "https://[1:2:3:4:5:6:7::]" to "[1:2:3:4:5:6:7:0]",
            "https://[1:2:3:4:5:6:1.2.3.4]" to "[1:2:3:4:5:6:102:304]",
            "https://[::ffff:192.168.1.2]" to "192.168.1.2",
            "https://[::FFFF:c0a8:0102]" to "192.168.1.2",
        )
        for ((url, host) in android) {
            assertEquals(url, "subsonic:v1:$host\u001Falice", AccountFingerprints.subsonic(url, "alice"))
        }
        assertEquals(
            "subsonic:v1:[2001:db8::1]:4533/x\u001Falice",
            AccountFingerprints.subsonic("https://[2001:DB8:0:0::1]:4533/x/", "alice"),
        )
        // HttpUrl refuses these hosts, so there is no fingerprint.
        for (url in listOf(
            "https://bücher..example", "https://xn--zz.example", "https://[1:2:3:4:5:6:7:8:9]",
            "https://[1:2:3:4:5:6::7:8]", "https://[::ffff:1.2.3.04]", "https://[12345::1]", "https://a b.example",
        )) {
            assertNull(url, AccountFingerprints.subsonic(url, "alice"))
        }
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
