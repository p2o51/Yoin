package com.gpo.yoin.shared.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Skipped unless YOIN_SUBSONIC_SMOKE_URL is set (see [SubsonicLiveSmoke]). OkHttp engine. */
class SubsonicLiveSmokeTest {
    @Test
    fun should_talkToLiveServer_when_smokeUrlSet() = runTest(timeout = 60.seconds) {
        val url = System.getenv(SubsonicLiveSmoke.ENV)?.takeIf { it.isNotBlank() } ?: return@runTest
        SubsonicLiveSmoke.run(url).forEach { println("[smoke/jvm] $it") }
    }
}
