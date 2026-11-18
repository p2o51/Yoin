package com.gpo.yoin.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class CanonicalJsonTest {
    @Test
    fun should_matchKnownSha256Digests_when_hashingText() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CanonicalJson.sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CanonicalJson.sha256Hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            CanonicalJson.sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        // Multi-block, non-ASCII (UTF-8 bytes, as MessageDigest over toByteArray(UTF_8)).
        assertEquals(64, CanonicalJson.sha256Hex("余韻".repeat(100)).length)
    }

    @Test
    fun should_sortKeysRecursively_when_canonicalising() {
        val element = Json.parseToJsonElement("""{"z":1,"a":{"y":[3,{"c":null,"b":"x"}],"x":true}}""")

        assertEquals("""{"a":{"x":true,"y":[3,{"b":"x","c":null}]},"z":1}""", CanonicalJson.canonical(element))
    }

    @Test
    fun should_routeShardsStably_when_hashingKeys() {
        // Pinned values: any change re-routes every user's artifact records.
        assertEquals(CanonicalJson.stableHash("abc"), 0x3a7816bf)
        assertEquals(SyncFormat.artifactShard("abc"), 0x3a7816bf % 4)
    }

    @Test
    fun should_formatUtcDate_when_buildingConflictLabels() {
        assertEquals("1970-01-01", isoUtcDate(0))
        assertEquals("2026-10-04", isoUtcDate(1_791_101_730_123L))
        assertEquals("1969-12-31", isoUtcDate(-1))
        assertEquals(1_791_101_730_123L, parseRfc3339Millis("2026-10-04T08:15:30.123Z"))
        assertEquals(1_791_101_730_000L, parseRfc3339Millis("2026-10-04T17:15:30+09:00"))
    }
}
