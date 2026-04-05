package com.gpo.yoin.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicAuthTest {
    @Test
    fun should_produceMd5Hash_when_givenKnownInput() {
        val hash = SubsonicAuth.md5("sesame")
        assertEquals("c8dae1c50e092f3d877192fc555b1dcf", hash)
    }

    @Test
    fun should_produceMd5Hash_when_givenPasswordPlusSalt() {
        val hash = SubsonicAuth.md5("mypasswordsalt123")
        assertEquals("a27a0274d6cbf6f96c0fad75a96771a9", hash)
    }

    @Test
    fun should_returnTokenAndSalt_when_generateTokenCalled() {
        val (token, salt) = SubsonicAuth.generateToken("testpassword")
        assertTrue("Token should be 32 hex chars", token.matches(Regex("[a-f0-9]{32}")))
        assertTrue("Salt should be 16 hex chars", salt.matches(Regex("[a-f0-9]{16}")))
    }

    @Test
    fun should_matchManualMd5_when_tokenRecomputed() {
        val password = "secretpass"
        val (token, salt) = SubsonicAuth.generateToken(password)
        val expected = SubsonicAuth.md5("$password$salt")
        assertEquals(expected, token)
    }

    @Test
    fun should_generateDifferentSalts_when_calledMultipleTimes() {
        val (_, salt1) = SubsonicAuth.generateToken("pass")
        val (_, salt2) = SubsonicAuth.generateToken("pass")
        assertNotEquals("Salts should differ between calls", salt1, salt2)
    }
}
