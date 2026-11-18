package com.gpo.yoin.shared.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.posix.getenv
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Skipped unless YOIN_SUBSONIC_SMOKE_URL reaches the simulator process
 * (export SIMCTL_CHILD_YOIN_SUBSONIC_SMOKE_URL). NSURLSession (Darwin) engine.
 */
class SubsonicLiveSmokeTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun should_talkToLiveServer_when_smokeUrlSet() = runTest(timeout = 60.seconds) {
        val url = getenv(SubsonicLiveSmoke.ENV)?.toKString()?.takeIf { it.isNotBlank() } ?: return@runTest
        SubsonicLiveSmoke.run(url).forEach { println("[smoke/ios] $it") }
    }
}
