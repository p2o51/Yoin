package com.gpo.yoin.player.applemusic

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicKit 1.1.1 reads the developer token once, when its controller is created, and sent that one-hour JWT for as
 * long as the session lived: an hour on, every new queue failed with a queue error (type 3). The token it holds is
 * now renewed ahead of time and before each call that sends MusicKit to Apple.
 */
class MusicKitDeveloperTokenTest {

    private var now = 10_000L
    private val events = mutableListOf<String>()
    private var fetches = 0

    private fun TestScope.token(
        installed: String,
        scope: CoroutineScope = backgroundScope,
        fetch: suspend () -> String,
    ) = MusicKitDeveloperToken(
        installed = installed,
        scope = scope,
        fetch = {
            fetches++
            fetch()
        },
        install = { events += "install ${expiryOf(it)}" },
        nowEpochSeconds = { now },
    )

    @Test
    fun should_runAtOnce_when_tokenHasTimeLeft() = runTest {
        val token = token(jwt(now + 3_000)) { error("no refresh expected") }

        token.run { events += "prepare" }

        assertEquals(listOf("prepare"), events)
        assertEquals(0, fetches)
    }

    @Test
    fun should_installNewTokenFirst_when_tokenExpired() = runTest {
        val token = token(jwt(now - 1)) { jwt(now + 3_600) }

        token.run { events += "prepare" }
        assertTrue(events.isEmpty())
        assertTrue(token.holding)
        runCurrent()

        assertEquals(listOf("install ${now + 3_600}", "prepare"), events)
        assertFalse(token.holding)
        assertEquals(1, fetches)
    }

    @Test
    fun should_renewFirst_when_tokenRunsOutWithinRenewWindow() = runTest {
        val token = token(jwt(now + MUSICKIT_TOKEN_RENEW_BEFORE_S - 1)) { jwt(now + 3_600) }

        token.run { events += "prepare" }
        runCurrent()

        assertEquals(listOf("install ${now + 3_600}", "prepare"), events)
    }

    @Test
    fun should_keepOrderAndFetchOnce_when_severalCallsWait() = runTest {
        val token = token(jwt(now - 1)) { jwt(now + 3_600) }

        token.run { events += "prepare" }
        token.run { events += "add" }
        token.run { events += "play" }
        runCurrent()

        assertEquals(listOf("install ${now + 3_600}", "prepare", "add", "play"), events)
        assertEquals(1, fetches)
        token.run { events += "skip" }
        assertEquals("skip", events.last())
    }

    @Test
    fun should_runAfterTheOthers_when_aWaitingCallStartsAnother() = runTest {
        val token = token(jwt(now - 1)) { jwt(now + 3_600) }

        token.run {
            events += "prepare"
            token.run { events += "nested" }
        }
        token.run { events += "add" }
        runCurrent()

        assertEquals(listOf("install ${now + 3_600}", "prepare", "add", "nested"), events)
        assertEquals(1, fetches)
    }

    @Test
    fun should_stillRunCalls_when_refreshFails() = runTest {
        val token = token(jwt(now + 30)) { throw java.io.IOException("offline") }

        token.run { events += "prepare" }
        runCurrent()

        assertEquals(listOf("prepare"), events)
        assertFalse(token.holding)
    }

    @Test
    fun should_stillRunCalls_when_refreshHangs() = runTest {
        val token = token(jwt(now - 1)) { awaitCancellation() }

        token.run { events += "prepare" }
        advanceTimeBy(MUSICKIT_TOKEN_FETCH_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("prepare"), events)
    }

    @Test
    fun should_dropWaitingCalls_when_queueReplaced() = runTest {
        val token = token(jwt(now - 1)) { jwt(now + 3_600) }

        token.run { events += "old queue" }
        token.dropWaiting()
        assertFalse(token.holding)
        runCurrent()

        // The new token still reaches MusicKit; the old queue's call doesn't run.
        assertEquals(listOf("install ${now + 3_600}"), events)
    }

    @Test
    fun should_renewAhead_when_tickerSeesTokenRunningShort() = runTest {
        val token = token(jwt(now + 3_000)) { jwt(now + 3_600) }

        token.keepFresh()
        runCurrent()
        assertEquals(0, fetches)

        now += 3_000 - MUSICKIT_TOKEN_RENEW_BEFORE_S
        token.keepFresh()
        runCurrent()

        assertEquals(1, fetches)
        assertEquals(listOf("install ${now + 3_600}"), events)
    }

    @Test
    fun should_waitBeforeRetrying_when_backgroundRefreshFailed() = runTest {
        var offline = true
        val token = token(jwt(now - 1)) {
            if (offline) throw java.io.IOException("offline") else jwt(now + 3_600)
        }

        token.keepFresh()
        runCurrent()
        token.keepFresh()
        runCurrent()
        assertEquals(1, fetches)

        offline = false
        now += MUSICKIT_TOKEN_RETRY_S
        token.keepFresh()
        runCurrent()

        assertEquals(2, fetches)
        assertEquals(listOf("install ${now + 3_600}"), events)
    }

    @Test
    fun should_refetchForCall_when_backgroundRefreshFailedMomentsAgo() = runTest {
        var offline = true
        val token = token(jwt(now - 1)) {
            if (offline) throw java.io.IOException("offline") else jwt(now + 3_600)
        }
        token.keepFresh()
        runCurrent()

        // Someone tapped a song: that call doesn't wait out the background retry delay.
        offline = false
        token.run { events += "prepare" }
        runCurrent()

        assertEquals(2, fetches)
        assertEquals(listOf("install ${now + 3_600}", "prepare"), events)
    }

    @Test
    fun should_notReinstall_when_fetchReturnsTheSameToken() = runTest {
        val same = jwt(now + MUSICKIT_TOKEN_RENEW_BEFORE_S + 60)
        val token = token(same) { same }

        now += 61
        token.keepFresh()
        runCurrent()

        assertEquals(1, fetches)
        assertTrue(events.isEmpty())
    }

    @Test
    fun should_matchTheSdksTokenPreferences() {
        // MusicKit 1.1.1 keeps the developer token in `playback.d.b`. Its classes ship without stack map frames, so
        // the JVM can't load them; the class file's constants are checked instead. If an SDK update moves the token,
        // installMusicKitDeveloperToken would write where nothing reads, and every session would go stale again.
        val classFile = javaClass.classLoader!!
            .getResourceAsStream("com/apple/android/music/playback/d/b.class")!!
            .use { it.readBytes().toString(Charsets.ISO_8859_1) }

        assertTrue(classFile.contains(MUSICKIT_AUTH_PREFERENCES))
        assertTrue(classFile.contains(MUSICKIT_DEVELOPER_TOKEN_KEY))
    }

    private fun jwt(exp: Long): String {
        fun encode(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":$exp}""")}.test-signature"
    }

    private fun expiryOf(token: String): Long =
        com.gpo.yoin.data.remote.applemusic.developerTokenExpiry(token)!!
}
