package com.gpo.yoin.player.continueon

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one message Continue On needs that Android doesn't carry: "pause, I've
 * taken over" from the device that took a hand-off back to the one that gave
 * it (owner 2026-11-08: the other device shows a small card first; only when
 * the person taps it does this device stop).
 *
 * Both devices are on the same Wi-Fi (Continue On requires it), so the device
 * handing off listens on a local port and puts its address, the port and a
 * one-time token in the hand-off ([ContinueOnCursor.pause]).
 * One line each way, plain text:
 *
 *     → PAUSE <token> <trackId>
 *     ← OK <positionMs> <trackId>   (the song this device was on by then, and where it stopped)
 *     ← OK -1                       (it had no song loaded any more)
 *     ← NO                          (wrong token)
 *
 * The system takes a hand-off's data from a window as it goes to the
 * background and may give it to the other device much later, so the answer
 * names the song this device is on by then, and the port outlives
 * [lifetimeMs] for as long as [stillNeeded] says so. It closes after one
 * good PAUSE, a few wrong tokens, or the first time [lifetimeMs] runs out
 * with the listener no longer needed; nothing else is ever answered.
 */
internal class ContinueOnPauseServer(
    private val scope: CoroutineScope,
    /**
     * Pauses this device's playback (on the main thread): the song it was on and where it stopped,
     * which need not be [trackId] any more; null when no song is loaded.
     */
    private val onPause: suspend (trackId: String) -> PausedAt?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where [onPause] runs: the player is driven from the main thread. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val lifetimeMs: Long = LIFETIME_MS,
    /**
     * Asked each time [lifetimeMs] runs out: true keeps the listener for another round (this device
     * still has the song it handed off, and the other one may ask about it yet).
     */
    private val stillNeeded: () -> Boolean = { false },
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private var socket: ServerSocket? = null
    private var token: String? = null
    private var acceptJob: Job? = null
    private var expiryJob: Job? = null

    /**
     * The port and token to put in a hand-off: the listener already open (its time restarted), or a new one.
     * Binding a local port is quick and touches no network, so a hand-off request on the main thread may call
     * this; null when it can't be opened.
     */
    fun open(): Pair<Int, String>? = synchronized(lock) {
        val open = socket?.takeIf { !it.isClosed }
        val (server, secret) = if (open != null && token != null) {
            open to token!!
        } else {
            val fresh = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0), BACKLOG)
                }
            }.getOrNull() ?: return null
            val secret = newToken()
            socket = fresh
            token = secret
            acceptJob = scope.launch(io) { acceptLoop(fresh, secret) }
            fresh to secret
        }
        expiryJob?.cancel()
        expiryJob = scope.launch {
            do {
                delay(lifetimeMs)
            } while (stillNeeded())
            close(server)
        }
        server.localPort to secret
    }

    /** Closes the listener now (the account changed, the app is going away). */
    fun close() = synchronized(lock) { socket }?.let(::close)

    private fun close(server: ServerSocket) {
        synchronized(lock) {
            if (socket !== server) return
            socket = null
            token = null
            expiryJob?.cancel()
            expiryJob = null
            acceptJob = null
        }
        runCatching { server.close() }
    }

    private suspend fun acceptLoop(server: ServerSocket, secret: String) {
        var wrong = 0
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            val outcome = client.use { serve(it, secret) }
            when (outcome) {
                Outcome.Paused -> break
                Outcome.Refused -> if (++wrong >= MAX_WRONG) break
                Outcome.Garbled -> Unit
            }
        }
        close(server)
    }

    private suspend fun serve(client: Socket, secret: String): Outcome {
        client.soTimeout = READ_TIMEOUT_MS
        val line = runCatching {
            BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readLineCapped()
        }.getOrNull() ?: return Outcome.Garbled
        val request = PauseRequest.parse(line) ?: return Outcome.Garbled
        val out = client.getOutputStream()
        if (!request.token.constantTimeEquals(secret)) {
            runCatching { out.write("NO\n".toByteArray(StandardCharsets.UTF_8)) }
            return Outcome.Refused
        }
        val stopped = withContext(main) { onPause(request.trackId) }
        runCatching {
            out.write(pausedLine(stopped).toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        return Outcome.Paused
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private enum class Outcome { Paused, Refused, Garbled }

    companion object {
        const val LIFETIME_MS = 2 * 60_000L
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 2_000
        private const val MAX_WRONG = 5
        private const val TOKEN_BYTES = 16
    }
}

/** The other end of [ContinueOnPauseServer]: asks the device that handed off to pause. */
internal object ContinueOnPauseClient {
    /** What the device that handed off answered. */
    sealed interface Answer {
        /**
         * It paused. [trackId] is the song it was on by then (null: the one asked about) and
         * [positionMs] where in it (null: it had no song loaded any more).
         */
        data class Paused(val positionMs: Long?, val trackId: String? = null) : Answer

        /** No answer (not reachable, a router isolating its devices, the port closed). */
        data object Unreachable : Answer
    }

    suspend fun requestPause(
        channel: ContinueOnCursor.PauseChannel,
        trackId: String,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        io: CoroutineDispatcher = Dispatchers.IO,
        /** Called with each socket before it connects: ties it to the network the other device is on. */
        bind: (Socket) -> Unit = {},
    ): Answer = withContext(io) {
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            for (host in channel.hosts) {
                val answer = runCatching { ask(host, channel.port, channel.token, trackId, connectTimeoutMs, bind) }
                    .getOrNull()
                if (answer != null) return@withTimeoutOrNull answer
            }
            null
        } ?: Answer.Unreachable
    }

    private fun ask(
        host: String,
        port: Int,
        token: String,
        trackId: String,
        connectTimeoutMs: Int,
        bind: (Socket) -> Unit,
    ): Answer? {
        Socket().use { socket ->
            bind(socket)
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), connectTimeoutMs)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(PauseRequest(token, trackId).line().toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val reply = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                .readLineCapped() ?: return null
            return parseReply(reply)
        }
    }

    internal fun parseReply(reply: String): Answer? {
        val parts = reply.trim().split(' ')
        if (parts.firstOrNull() != "OK") return null
        val position = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0L }
            ?: return if (parts.getOrNull(1) == NOTHING_LOADED) Answer.Paused(positionMs = null) else null
        return Answer.Paused(position, trackId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    private const val CONNECT_TIMEOUT_MS = 1_200
    private const val READ_TIMEOUT_MS = 2_000
    private const val TOTAL_TIMEOUT_MS = 4_000L
}

/** Where the device that handed off stopped: the song it was on by then, and its position in it. */
internal data class PausedAt(val trackId: String, val positionMs: Long)

/** The answer to a good PAUSE. As in [PauseRequest], a space or line break in a track id is never sent. */
internal fun pausedLine(stopped: PausedAt?): String {
    val trackId = stopped?.trackId?.replace(WHITESPACE, "").orEmpty()
    return if (stopped == null || trackId.isEmpty()) {
        "OK $NOTHING_LOADED\n"
    } else {
        "OK ${stopped.positionMs.coerceAtLeast(0L)} $trackId\n"
    }
}

private const val NOTHING_LOADED = "-1"
private val WHITESPACE = Regex("\\s")

/** One PAUSE line. Track ids are sent as given; a space or line break in one is never sent. */
internal data class PauseRequest(val token: String, val trackId: String) {
    fun line(): String = "PAUSE $token ${trackId.replace(WHITESPACE, "")}\n"

    companion object {
        fun parse(line: String): PauseRequest? {
            val parts = line.trim().split(' ')
            if (parts.size != 3 || parts[0] != "PAUSE" || parts[1].isEmpty() || parts[2].isEmpty()) return null
            return PauseRequest(parts[1], parts[2])
        }
    }
}

private const val MAX_LINE_CHARS = 1_024

/** A line of at most [MAX_LINE_CHARS], so a stray client can't make this read forever. */
private fun BufferedReader.readLineCapped(): String? {
    val builder = StringBuilder()
    while (builder.length < MAX_LINE_CHARS) {
        val next = read()
        if (next == -1) return builder.toString().takeIf { it.isNotEmpty() }
        if (next == '\n'.code) return builder.toString()
        builder.append(next.toChar())
    }
    return null
}

private fun String.constantTimeEquals(other: String): Boolean =
    MessageDigest.isEqual(toByteArray(StandardCharsets.UTF_8), other.toByteArray(StandardCharsets.UTF_8))
