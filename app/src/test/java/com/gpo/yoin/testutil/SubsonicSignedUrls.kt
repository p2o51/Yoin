package com.gpo.yoin.testutil

import com.gpo.yoin.data.remote.SubsonicAuth
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * [url], a signed Subsonic url, as a process whose salt is [salt] signs it: SubsonicAuth draws
 * the salt once per process, so the next process asks for the same resource under a new url.
 */
fun signedWithSalt(url: String, password: String, salt: String): String = url.toHttpUrl().newBuilder()
    .setQueryParameter("t", SubsonicAuth.md5("$password$salt"))
    .setQueryParameter("s", salt)
    .build()
    .toString()
