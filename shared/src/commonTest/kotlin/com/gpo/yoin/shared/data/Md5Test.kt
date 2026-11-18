package com.gpo.yoin.shared.data

import com.gpo.yoin.shared.data.subsonic.Md5
import com.gpo.yoin.shared.data.subsonic.SubsonicAuth
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Md5Test {

    @Test
    fun should_matchRfc1321Vectors_when_hashingTestSuite() {
        val vectors = mapOf(
            "" to "d41d8cd98f00b204e9800998ecf8427e",
            "a" to "0cc175b9c0f1b6a831c399e269772661",
            "abc" to "900150983cd24fb0d6963f7d28e17f72",
            "message digest" to "f96b697d7cb7938d525a2f31aaf161d0",
            "abcdefghijklmnopqrstuvwxyz" to "c3fcd3d76192e4007dfb496cca67e13b",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to
                "d174ab98d277d9f5a5611c2c9f419d9f",
            "1234567890".repeat(8) to "57edf4a22be3c955ac49da2e2107b67a",
        )
        vectors.forEach { (input, expected) -> assertEquals(expected, Md5.hex(input), "md5(\"$input\")") }
    }

    @Test
    fun should_padCorrectly_when_inputSitsOnBlockBoundaries() {
        assertEquals("ef1772b6dff9a122358552954ad0df65", Md5.hex("a".repeat(55)))
        assertEquals("3b0c8ac703f828b04c6c197006d17218", Md5.hex("a".repeat(56)))
        assertEquals("014842d480b571495a4a0363793f7367", Md5.hex("a".repeat(64)))
        assertEquals("8a7bd0732ed6a28ce75f6dabc90e1613", Md5.hex("a".repeat(119)))
    }

    @Test
    fun should_hashUtf8Bytes_when_passwordIsNotAscii() {
        assertEquals("4ba88eb06de756d7ea21bf80f43f649c", Md5.hex("密码salt"))
    }

    @Test
    fun should_matchSubsonicDocExample_when_buildingToken() {
        // subsonic.org/pages/api.jsp: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicAuth.token("sesame", "c19b2d"))
    }

    @Test
    fun should_produce16HexChars_when_generatingSalt() {
        val salt = SubsonicAuth.newSalt(Random(42))
        assertEquals(16, salt.length)
        assertTrue(salt.all { it in "abcdef0123456789" })
    }
}
