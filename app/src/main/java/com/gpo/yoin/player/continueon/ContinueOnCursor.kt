package com.gpo.yoin.player.continueon

/**
 * What one Continue On hand-off carries from the device playing to the one
 * taking over (Android 17's cross-device handoff, docs/continue-on.md): a
 * light cursor, never media or credentials. The other device reads the
 * songs again from its own copy of the same account.
 *
 * Ids are the provider's raw ids ([com.gpo.yoin.data.model.MediaId.rawId]);
 * [provider] names whose. [accountKey] is the account's id as every device
 * knows it ([com.gpo.yoin.data.sync.identity.AccountKeys]); null when this
 * device has none for it (Spotify or Apple Music not syncing), and the other
 * side then goes by [provider].
 *
 * [pause] is how the other side asks this device to stop once the person
 * says "continue here" (owner 2026-11-08): the system sends nothing back.
 */
data class ContinueOnCursor(
    val provider: String,
    val accountKey: String?,
    val trackId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** [com.gpo.yoin.data.model.CoverRef.toStorageKey] of the track's cover: an id or a public image URL. */
    val cover: String?,
    /** A window of the queue around the track, in play order; [index] is the track's place in it. */
    val queueIds: List<String>,
    val index: Int,
    val positionMs: Long,
    /** Wall clock when [positionMs] was read: a playing track has moved on by the time the other side starts. */
    val capturedAtMs: Long,
    val playing: Boolean,
    val repeatMode: Int,
    val shuffle: Boolean,
    val context: Context?,
    /** What the other side calls this device ("Pixel 9"). */
    val deviceName: String?,
    val pause: PauseChannel?,
) {
    /** Where the queue came from, so the other side can read it in one go. */
    data class Context(val kind: Kind, val id: String?, val name: String?) {
        enum class Kind { Album, Playlist, Artist, LikedSongs }
    }

    /** This device on the local network, waiting for one "pause" that carries [token]. */
    data class PauseChannel(val hosts: List<String>, val port: Int, val token: String)

    /** Where [positionMs] has got to at [nowMs], within the track as far as is known ([durationMs] 0 = unknown). */
    fun positionAt(nowMs: Long, durationMs: Long = 0L): Long {
        val moved = if (playing) (nowMs - capturedAtMs).coerceIn(0L, MAX_DRIFT_MS) else 0L
        val position = (positionMs + moved).coerceAtLeast(0L)
        return if (durationMs > 0L) position.coerceAtMost((durationMs - END_GUARD_MS).coerceAtLeast(0L)) else position
    }

    /** Flattened for a PersistableBundle (strings, ints, longs, booleans and string arrays only). */
    fun toEntries(): Map<String, Any> = buildMap {
        put(KEY_VERSION, VERSION)
        put(KEY_PROVIDER, provider)
        accountKey?.let { put(KEY_ACCOUNT, it) }
        put(KEY_TRACK, trackId)
        title?.let { put(KEY_TITLE, it.take(MAX_TEXT)) }
        artist?.let { put(KEY_ARTIST, it.take(MAX_TEXT)) }
        album?.let { put(KEY_ALBUM, it.take(MAX_TEXT)) }
        cover?.let { put(KEY_COVER, it) }
        put(KEY_QUEUE, queueIds.toTypedArray())
        put(KEY_INDEX, index)
        put(KEY_POSITION, positionMs)
        put(KEY_CAPTURED_AT, capturedAtMs)
        put(KEY_PLAYING, playing)
        put(KEY_REPEAT, repeatMode)
        put(KEY_SHUFFLE, shuffle)
        context?.let { ctx ->
            put(KEY_CONTEXT_KIND, ctx.kind.name)
            ctx.id?.let { put(KEY_CONTEXT_ID, it) }
            ctx.name?.let { put(KEY_CONTEXT_NAME, it.take(MAX_TEXT)) }
        }
        deviceName?.let { put(KEY_DEVICE, it.take(MAX_TEXT)) }
        pause?.let { channel ->
            put(KEY_PAUSE_HOSTS, channel.hosts.toTypedArray())
            put(KEY_PAUSE_PORT, channel.port)
            put(KEY_PAUSE_TOKEN, channel.token)
        }
    }

    companion object {
        const val VERSION = 1

        /** Android caps a hand-off's extras below 50 KB; the queue window keeps well under it. */
        const val MAX_EXTRAS_BYTES = 40_000
        const val MAX_QUEUE = 200
        private const val MAX_TEXT = 200

        /** A cursor older than this is a stale hand-off: its position is no longer worth catching up. */
        private const val MAX_DRIFT_MS = 10 * 60_000L

        /** Never start a hand-off in the track's last moments (it would end at once). */
        private const val END_GUARD_MS = 3_000L

        private const val PREFIX = "yoin.continueOn."
        const val KEY_VERSION = PREFIX + "v"
        const val KEY_PROVIDER = PREFIX + "provider"
        const val KEY_ACCOUNT = PREFIX + "account"
        const val KEY_TRACK = PREFIX + "track"
        const val KEY_TITLE = PREFIX + "title"
        const val KEY_ARTIST = PREFIX + "artist"
        const val KEY_ALBUM = PREFIX + "album"
        const val KEY_COVER = PREFIX + "cover"
        const val KEY_QUEUE = PREFIX + "queue"
        const val KEY_INDEX = PREFIX + "index"
        const val KEY_POSITION = PREFIX + "position"
        const val KEY_CAPTURED_AT = PREFIX + "capturedAt"
        const val KEY_PLAYING = PREFIX + "playing"
        const val KEY_REPEAT = PREFIX + "repeat"
        const val KEY_SHUFFLE = PREFIX + "shuffle"
        const val KEY_CONTEXT_KIND = PREFIX + "contextKind"
        const val KEY_CONTEXT_ID = PREFIX + "contextId"
        const val KEY_CONTEXT_NAME = PREFIX + "contextName"
        const val KEY_DEVICE = PREFIX + "device"
        const val KEY_PAUSE_HOSTS = PREFIX + "pauseHosts"
        const val KEY_PAUSE_PORT = PREFIX + "pausePort"
        const val KEY_PAUSE_TOKEN = PREFIX + "pauseToken"

        /** Every key a cursor may put in an intent. */
        val KEY_PREFIXED: List<String> = listOf(
            KEY_VERSION, KEY_PROVIDER, KEY_ACCOUNT, KEY_TRACK, KEY_TITLE, KEY_ARTIST, KEY_ALBUM, KEY_COVER,
            KEY_QUEUE, KEY_INDEX, KEY_POSITION, KEY_CAPTURED_AT, KEY_PLAYING, KEY_REPEAT, KEY_SHUFFLE,
            KEY_CONTEXT_KIND, KEY_CONTEXT_ID, KEY_CONTEXT_NAME, KEY_DEVICE, KEY_PAUSE_HOSTS, KEY_PAUSE_PORT,
            KEY_PAUSE_TOKEN,
        )

        /**
         * The cursor in [read]'s entries (an Intent's extras), or null when they hold none, or one
         * from a newer Yoin whose format this one doesn't know, or one that doesn't make sense.
         */
        fun fromEntries(read: (String) -> Any?): ContinueOnCursor? {
            val version = read(KEY_VERSION) as? Int ?: return null
            if (version != VERSION) return null
            val provider = (read(KEY_PROVIDER) as? String)?.takeIf { it.isNotBlank() } ?: return null
            val trackId = (read(KEY_TRACK) as? String)?.takeIf { it.isNotBlank() } ?: return null
            val queue = (read(KEY_QUEUE) as? Array<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }
                .orEmpty()
            val rawIndex = read(KEY_INDEX) as? Int ?: -1
            // A queue that doesn't hold the track at its index is the track alone.
            val (queueIds, index) = if (queue.getOrNull(rawIndex) == trackId) {
                queue to rawIndex
            } else {
                listOf(trackId) to 0
            }
            val context = (read(KEY_CONTEXT_KIND) as? String)
                ?.let { kind -> Context.Kind.entries.firstOrNull { it.name == kind } }
                ?.let { kind -> Context(kind, read(KEY_CONTEXT_ID) as? String, read(KEY_CONTEXT_NAME) as? String) }
            val pauseHosts = (read(KEY_PAUSE_HOSTS) as? Array<*>)?.filterIsInstance<String>().orEmpty()
            val pausePort = read(KEY_PAUSE_PORT) as? Int ?: 0
            val pauseToken = read(KEY_PAUSE_TOKEN) as? String
            val pause = if (pauseHosts.isNotEmpty() && pausePort in 1..65535 && !pauseToken.isNullOrBlank()) {
                PauseChannel(pauseHosts, pausePort, pauseToken)
            } else {
                null
            }
            return ContinueOnCursor(
                provider = provider,
                accountKey = read(KEY_ACCOUNT) as? String,
                trackId = trackId,
                title = read(KEY_TITLE) as? String,
                artist = read(KEY_ARTIST) as? String,
                album = read(KEY_ALBUM) as? String,
                cover = read(KEY_COVER) as? String,
                queueIds = queueIds,
                index = index,
                positionMs = (read(KEY_POSITION) as? Long ?: 0L).coerceAtLeast(0L),
                capturedAtMs = read(KEY_CAPTURED_AT) as? Long ?: 0L,
                playing = read(KEY_PLAYING) as? Boolean ?: false,
                repeatMode = read(KEY_REPEAT) as? Int ?: 0,
                shuffle = read(KEY_SHUFFLE) as? Boolean ?: false,
                context = context,
                deviceName = read(KEY_DEVICE) as? String,
                pause = pause,
            )
        }

        /**
         * The part of [queue] a cursor carries: at most [MAX_QUEUE] ids around [index], the track's own
         * place kept, trimmed further while the ids alone would pass [MAX_EXTRAS_BYTES] (about half of it,
         * leaving the rest to the other fields). Returns the window and the track's index in it.
         */
        fun queueWindow(queue: List<String>, index: Int): Pair<List<String>, Int> {
            if (index !in queue.indices) return emptyList<String>() to -1
            var before = minOf(index, MAX_QUEUE / 4)
            var after = minOf(queue.lastIndex - index, MAX_QUEUE - 1 - before)
            // A short tail leaves room for more of what came before.
            before = minOf(index, MAX_QUEUE - 1 - after)
            fun bytes() = (index - before..index + after).sumOf { queue[it].length * 2 + 8 }
            while (bytes() > MAX_EXTRAS_BYTES / 2 && before + after > 0) {
                if (after >= before) after-- else before--
            }
            return queue.subList(index - before, index + after + 1).toList() to before
        }
    }
}
