package com.gpo.yoin.player.continueon

import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueOnPauseChannelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = mutableListOf<String>()
    private val server = ContinueOnPauseServer(
        scope = scope,
        onPause = { trackId ->
            synchronized(paused) { paused += trackId }
            if (trackId == "t1") 42_000L else null
        },
        main = Dispatchers.IO,
    )

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun channel(token: String? = null): ContinueOnCursor.PauseChannel {
        val (port, secret) = requireNotNull(server.open())
        return ContinueOnCursor.PauseChannel(listOf("127.0.0.1"), port, token ?: secret)
    }

    @Test
    fun should_pauseAndSayWhere_when_tokenMatches() = runBlocking {
        val answer = ContinueOnPauseClient.requestPause(channel(), "t1")
        assertEquals(ContinueOnPauseClient.Answer.Paused(42_000L), answer)
        assertEquals(listOf("t1"), paused)
    }

    @Test
    fun should_pauseWithoutAPosition_when_anotherSongPlaysNow() = runBlocking {
        val answer = ContinueOnPauseClient.requestPause(channel(), "t9")
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
        assertEquals(ContinueOnPauseClient.Answer.Paused(5L), ContinueOnPauseClient.parseReply("OK 5"))
        assertEquals(ContinueOnPauseClient.Answer.Paused(null), ContinueOnPauseClient.parseReply("OK -1"))
        assertNull(ContinueOnPauseClient.parseReply("NO"))
        assertNull(ContinueOnPauseClient.parseReply("OK"))
        assertNull(PauseRequest.parse("PAUSE onlytoken"))
        assertEquals(PauseRequest("a", "b"), PauseRequest.parse("PAUSE a b\n"))
    }
}
