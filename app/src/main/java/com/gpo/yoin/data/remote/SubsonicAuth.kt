package com.gpo.yoin.data.remote

import java.security.MessageDigest

data class ServerCredentials(
    val serverUrl: String,
    val username: String,
    val password: String,
)

object SubsonicAuth {
    /**
     * Generates a Subsonic auth token pair.
     * @return (token, salt) where token = MD5(password + salt)
     */
    fun generateToken(password: String): Pair<String, String> {
        val salt = generateSalt()
        val token = md5("$password$salt")
        return token to salt
    }

    internal fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateSalt(): String {
        val chars = "abcdef0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }
}
