package com.gpo.yoin.player.continueon

import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueOnPauseChannelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = mutableListOf<String>()

    /** What this device is on when it is asked to pause. */
    @Volatile private var nowPlaying: PausedAt? = PausedAt("t1", 42_000L)

    @Volatile private var stillNeeded = false

    private val servers = mutableListOf<ContinueOnPauseServer>()
    private val server = server()

    private fun server(lifetimeMs: Long = ContinueOnPauseServer.LIFETIME_MS) = ContinueOnPauseServer(
        scope = scope,
        onPause = { trackId ->
            synchronized(paused) { paused += trackId }
            nowPlaying
        },
        main = Dispatchers.IO,
        lifetimeMs = lifetimeMs,
        stillNeeded = { stillNeeded },
    ).also { servers += it }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        scope.cancel()
    }

    private fun channel(token: String? = null, of: ContinueOnPauseServer = server): ContinueOnCursor.PauseChannel {
        val (port, secret) = requireNotNull(of.open())
        return ContinueOnCursor.PauseChannel(listOf("127.0.0.1"), port, token ?: secret)
    }

    @Test
    fun should_pauseAndSayWhere_when_tokenMatches() = runBlocking {
        val answer = ContinueOnPauseClient.requestPause(channel(), "t1")
        assertEquals(ContinueOnPauseClient.Answer.Paused(42_000L, "t1"), answer)
        assertEquals(listOf("t1"), paused)
    }

    @Test
    fun should_sayWhichSongItIsOnByNow_when_itHasMovedOnSinceTheHandOff() = runBlocking {
        nowPlaying = PausedAt("t6", 7_000L)
        val answer = ContinueOnPauseClient.requestPause(channel(), "t2")
        assertEquals(ContinueOnPauseClient.Answer.Paused(7_000L, "t6"), answer)
        assertEquals(listOf("t2"), paused)
    }

    @Test
    fun should_pauseWithoutAPosition_when_noSongIsLoadedAnyMore() = runBlocking {
        nowPlaying = null
        val answer = ContinueOnPauseClient.requestPause(channel(), "t1")
        assertEquals(ContinueOnPauseClient.Answer.Paused(null), answer)
    }

    @Test
    fun should_notPause_when_tokenIsWrong() = runBlocking {
        val answer = ContinueOnPauseClient.requestPause(channel(token = "wrong"), "t1")
        assertEquals(ContinueOnPauseClient.Answer.Unreachable, answer)
        assertEquals(emptyList<String>(), paused)
    }

    @Test
    fun should_closeAfterOnePause_when_asked() = runBlocking {
        val first = channel()
        ContinueOnPauseClient.requestPause(first, "t1")
        val again = ContinueOnPauseClient.requestPause(first, "t1", connectTimeoutMs = 300)
        assertEquals(ContinueOnPauseClient.Answer.Unreachable, again)
        assertEquals(1, paused.size)
    }

    @Test
    fun should_keepTheSameToken_when_openedAgainWhileOpen() {
        val first = server.open()
        val second = server.open()
        assertEquals(first, second)
    }

    @Test
    fun should_stayOpenPastItsTime_when_theSongIsStillHere() = runBlocking {
        // The system hands a cursor over long after it read it (the window had gone to the background).
        stillNeeded = true
        val channel = channel(of = server(lifetimeMs = 100L))
        delay(600L)
        val answer = ContinueOnPauseClient.requestPause(channel, "t1")
        assertEquals(ContinueOnPauseClient.Answer.Paused(42_000L, "t1"), answer)
    }

    @Test
    fun should_closeWhenItsTimeRunsOut_when_noSongIsHereAnyMore() = runBlocking {
        stillNeeded = false
        val channel = channel(of = server(lifetimeMs = 100L))
        delay(600L)
        val answer = ContinueOnPauseClient.requestPause(channel, "t1", connectTimeoutMs = 300)
        assertEquals(ContinueOnPauseClient.Answer.Unreachable, answer)
        assertEquals(emptyList<String>(), paused)
    }

    @Test
    fun should_tieTheSocketToTheLocalNetwork_when_itHasNotConnectedYet() = runBlocking {
        val connectedWhenBound = mutableListOf<Boolean>()
        val answer = ContinueOnPauseClient.requestPause(
            channel(),
            "t1",
            bind = { socket -> connectedWhenBound += socket.isConnected },
        )
        assertEquals(ContinueOnPauseClient.Answer.Paused(42_000L, "t1"), answer)
        assertEquals(listOf(false), connectedWhenBound)
    }

    @Test
    fun should_reportUnreachable_when_nothingListens() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val answer = ContinueOnPauseClient.requestPause(
            ContinueOnCursor.PauseChannel(listOf("127.0.0.1"), port, "tok"),
            "t1",
            connectTimeoutMs = 300,
        )
        assertEquals(ContinueOnPauseClient.Answer.Unreachable, answer)
    }

    @Test
    fun should_parseRepliesStrictly() {
        assertEquals(ContinueOnPauseClient.Answer.Paused(5L, "t6"), ContinueOnPauseClient.parseReply("OK 5 t6"))
        // No song named: the one that was asked about.
        assertEquals(ContinueOnPauseClient.Answer.Paused(5L), ContinueOnPauseClient.parseReply("OK 5"))
        assertEquals(ContinueOnPauseClient.Answer.Paused(null), ContinueOnPauseClient.parseReply("OK -1"))
        assertNull(ContinueOnPauseClient.parseReply("NO"))
        assertNull(ContinueOnPauseClient.parseReply("OK"))
        assertNull(ContinueOnPauseClient.parseReply("OK soon t6"))
        assertNull(PauseRequest.parse("PAUSE onlytoken"))
        assertEquals(PauseRequest("a", "b"), PauseRequest.parse("PAUSE a b\n"))
    }

    @Test
    fun should_neverSendASpaceOrALineBreak_when_theSongsIdHasOne() {
        assertEquals("OK 5 t6x\n", pausedLine(PausedAt("t6 \nx", 5L)))
        assertEquals("OK 0 t6\n", pausedLine(PausedAt("t6", -3L)))
        assertEquals("OK -1\n", pausedLine(null))
        assertEquals("OK -1\n", pausedLine(PausedAt(" ", 5L)))
    }
}
