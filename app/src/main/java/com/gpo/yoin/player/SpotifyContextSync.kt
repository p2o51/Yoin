package com.gpo.yoin.player

import android.os.SystemClock
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext

/**
 * Keeps the "playing from" context in step with what App Remote reports (its PlayerState and
 * PlayerContext subscriptions). Each call returns the context to show, or null to keep the
 * current one. Owned by [PlaybackManager]; main thread.
 *
 * A context a Yoin play set (album / playlist / artist page, a memory, …) names the queue Yoin
 * handed Spotify, and it stands while Spotify plays that queue. Spotify knows nothing about where
 * such a queue came from whenever it started as a plain `uris` list (an artist page always does;
 * an album or playlist whose context start failed falls back to it), so what it reports then — no
 * context, an empty or blank uri, one Yoin can't name — is no news and never replaces Yoin's.
 *
 * Until Spotify plays a song of Yoin's queue the start is unconfirmed: the first PlayerState and
 * PlayerContext a cold connect delivers describe what Spotify played BEFORE the start, so nothing
 * Spotify reports counts yet — until Spotify has plainly moved past it: a song outside Yoin's
 * queue [SPOTIFY_START_CONFIRM_TIMEOUT_MS] after Spotify was first heard on this connection, or
 * the [SPOTIFY_START_OVERRIDE_SONGS]th such song, means the start was overridden (the user played
 * something else in the Spotify app while Yoin was away) or never landed, and the context follows
 * Spotify as below. Once confirmed:
 * - an album or playlist Spotify names, and a different one, is shown; an unnamed report while
 *   Spotify is still on Yoin's queue brings Yoin's back, so a stale name doesn't stick;
 * - a song outside Yoin's queue means Spotify moved on (the user took over in the Spotify app, or
 *   it played on past the list): from then on the context follows Spotify, as for playback adopted
 *   from it — the album or playlist it last named on this connection, else none. A song with the
 *   title and artist of one in Yoin's queue is that song (a copy Spotify relinked: another
 *   market's, under another id); one Spotify relinked otherwise still plays from the album or
 *   playlist Spotify names, so Yoin's label stays;
 * - Yoin's Liked Songs starts Spotify's whole collection but mirrors only a window of it: a liked
 *   song past the window, while Spotify still says it plays the collection (`spotify:collection:
 *   tracks`, `spotify:user:<id>:collection`), is still Yoin's start, not a move.
 *
 * A dropped connection (the host stopped) voids what Spotify last said: it may have moved on
 * unheard. Back on a fresh connection its PlayerState comes before its PlayerContext, so a song
 * outside Yoin's queue then waits for that fresh word before anything is handed over (Spotify
 * still on Yoin's album or playlist, as a 300+ song playlist played past the songs Yoin mirrors
 * is, keeps Yoin's label), and while it waits the play isn't recorded ([settled]). No word in
 * [SPOTIFY_CONTEXT_REPORT_GRACE_MS] ([onReportOverdue]): Spotify moved on, to somewhere unnamed.
 */
internal class SpotifyContextSync(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    /** The context a Yoin play set, while Spotify plays its queue; null while following Spotify. */
    private var yoinContext: ActivityContext? = null

    /** Spotify has played a song of Yoin's queue since the start. */
    private var confirmed = false

    /** What Spotify last said it plays from, on this connection; null once it drops. */
    private var reported: SpotifyPlaybackContext? = null

    /** The connection dropped since Spotify last said what it plays from. */
    private var reportStale = false

    /** A song outside Yoin's queue played while [reportStale]: the hand-over waits for Spotify's word. */
    private var takeoverPending = false

    /** While unconfirmed: when Spotify was first heard on this connection ([clock]), and the songs outside Yoin's queue. */
    private var firstHeardAtMs: Long? = null
    private val strangers = HashSet<MediaId>()

    /** Waiting for Spotify's fresh PlayerContext to say whether it moved on ([onReportOverdue] if none). */
    val awaitingReport: Boolean
        get() = takeoverPending

    /** The song Spotify plays now may be recorded under the context shown. */
    val settled: Boolean
        get() = !takeoverPending

    /** A Yoin play set [context] and handed Spotify (or a local player) its queue. */
    fun onYoinStart(context: ActivityContext) {
        yoinContext = context
        confirmed = false
        takeoverPending = false
        firstHeardAtMs = null
        strangers.clear()
    }

    /** Playback Spotify started on its own was adopted: its context is Spotify's to name. */
    fun followSpotify() {
        yoinContext = null
        confirmed = false
        takeoverPending = false
    }

    /** The App Remote connection dropped (host stopped, an error): Spotify may move on unheard. */
    fun onDisconnected() {
        reported = null
        reportStale = true
        // A start still queued for the next connection lands then: its wait starts over.
        firstHeardAtMs = null
    }

    /** Spotify reported playing [track] while Yoin mirrors [queue] for it. */
    fun onPlayerState(current: ActivityContext, track: Track?, queue: List<Track>): ActivityContext? {
        val yoin = yoinContext ?: return null
        track ?: return null
        if (queue.any { queued -> queued.isSameSongAs(track) }) {
            // Back on Yoin's queue: whatever played outside it is no question any more.
            takeoverPending = false
            if (!confirmed) {
                confirmed = true
                // Said before the start landed: about what Spotify played before, unless it
                // already names Yoin's own album, playlist or Liked Songs.
                if (!reported.names(yoin)) reported = null
            }
            return null
        }
        // A song from before the start, still playing while the start lands.
        if (!confirmed && !startOverriddenBy(track)) return null
        // What Spotify said before the connection dropped is void: its fresh word decides.
        if (reportStale) {
            takeoverPending = true
            return null
        }
        return movedOn(current, yoin)
    }

    /** Spotify reported playing from [context] (App Remote's PlayerContext). */
    fun onReported(current: ActivityContext, context: SpotifyPlaybackContext?): ActivityContext? {
        reported = context
        reportStale = false
        val yoin = yoinContext
        if (takeoverPending && yoin != null) {
            takeoverPending = false
            return movedOn(current, yoin)
        }
        val next = when {
            yoin == null -> deriveSpotifyActivityContext(context) ?: ActivityContext.None
            !confirmed -> return null
            else -> deriveSpotifyActivityContext(context)?.takeUnless { it.namesSameAs(yoin) } ?: yoin
        }
        return next.takeUnless { it.namesSameAs(current) }
    }

    /**
     * [track], outside Yoin's queue, plays while the start is unconfirmed: whether Spotify has
     * long since moved past the start ([SPOTIFY_START_CONFIRM_TIMEOUT_MS] after it was first heard
     * on this connection, or the [SPOTIFY_START_OVERRIDE_SONGS]th such song).
     */
    private fun startOverriddenBy(track: Track): Boolean {
        val now = clock()
        val heardAt = firstHeardAtMs ?: now.also { firstHeardAtMs = it }
        strangers += track.id
        return now - heardAt >= SPOTIFY_START_CONFIRM_TIMEOUT_MS || strangers.size >= SPOTIFY_START_OVERRIDE_SONGS
    }

    /**
     * No PlayerContext came [SPOTIFY_CONTEXT_REPORT_GRACE_MS] after a song outside Yoin's queue
     * on a fresh connection: Spotify plays from somewhere it doesn't name.
     */
    fun onReportOverdue(current: ActivityContext): ActivityContext? {
        val yoin = yoinContext
        if (!takeoverPending || yoin == null) return null
        takeoverPending = false
        reportStale = false
        return movedOn(current, yoin)
    }

    /**
     * Spotify played a song outside Yoin's queue (after the start was confirmed, or long after an
     * unconfirmed one): unless it still plays from [yoin] past the songs Yoin mirrors (Liked
     * Songs), the context follows Spotify.
     */
    private fun movedOn(current: ActivityContext, yoin: ActivityContext): ActivityContext? {
        // Past Yoin's window of Liked Songs, still in the collection Yoin started.
        if (yoin is ActivityContext.LikedSongs && reported.names(yoin)) {
            confirmed = true
            return null
        }
        followSpotify()
        val next = deriveSpotifyActivityContext(reported) ?: ActivityContext.None
        return next.takeUnless { it.namesSameAs(current) }
    }
}

/** How long a fresh connection's PlayerContext may trail its PlayerState ([SpotifyContextSync.onReportOverdue]). */
internal const val SPOTIFY_CONTEXT_REPORT_GRACE_MS = 2_000L

/**
 * How long an unconfirmed start may wait for Spotify to play a song of Yoin's queue, from when
 * Spotify was first heard on the connection: a Web API start (and its App Remote fallback)
 * lands well within it.
 */
internal const val SPOTIFY_START_CONFIRM_TIMEOUT_MS = 15_000L

/**
 * Songs outside Yoin's queue an unconfirmed start sees before it gives up: the one Spotify played
 * before the start, and the next if it ended while the start was landing, are no proof yet.
 */
internal const val SPOTIFY_START_OVERRIDE_SONGS = 3

/**
 * Spotify's report names [yoin]: the same album or playlist, or (Yoin's Liked Songs) the user's
 * collection, which Spotify calls by more than one uri.
 */
private fun SpotifyPlaybackContext?.names(yoin: ActivityContext): Boolean =
    deriveSpotifyActivityContext(this)?.namesSameAs(yoin) == true ||
        (yoin is ActivityContext.LikedSongs && isSpotifyLikedSongsUri(this?.uri))

/**
 * Spotify's Liked Songs collection: `spotify:collection:tracks` (what Yoin starts), and the forms
 * Spotify reports back, `spotify:collection`, `spotify:user:<id>:collection` and
 * `spotify:user:<id>:collection:tracks`. Not a collection's other shelves (episodes, an artist's
 * liked songs).
 */
internal fun isSpotifyLikedSongsUri(uri: String?): Boolean {
    val parts = uri?.trim()?.split(':') ?: return false
    if (parts.firstOrNull() != "spotify") return false
    val rest = if (parts.getOrNull(1) == "user") {
        if (parts.getOrNull(2).isNullOrBlank()) return false
        parts.drop(3)
    } else {
        parts.drop(1)
    }
    return rest == listOf("collection") || rest == listOf("collection", "tracks")
}

/** The same song: the same id, or (a relinked copy) the same title and artist. */
private fun Track.isSameSongAs(other: Track): Boolean {
    if (id == other.id) return true
    val title = title?.takeIf { it.isNotBlank() } ?: return false
    val artist = artist?.takeIf { it.isNotBlank() } ?: return false
    return title.equals(other.title, ignoreCase = true) && artist.equals(other.artist, ignoreCase = true)
}

/** Map a Spotify "playing from" context to an [ActivityContext]; null if unrecognised. */
internal fun deriveSpotifyActivityContext(context: SpotifyPlaybackContext?): ActivityContext? {
    val uri = context?.uri?.trim().orEmpty()
    val title = (context?.title?.takeIf { it.isNotBlank() }).orEmpty()
    return when {
        uri.startsWith(SPOTIFY_ALBUM_URI_PREFIX) ->
            uri.removePrefix(SPOTIFY_ALBUM_URI_PREFIX).takeIf { it.isNotBlank() }?.let {
                ActivityContext.Album(albumId = MediaId.spotify(it).toString(), albumName = title)
            }
        uri.startsWith(SPOTIFY_PLAYLIST_URI_PREFIX) ->
            uri.removePrefix(SPOTIFY_PLAYLIST_URI_PREFIX).takeIf { it.isNotBlank() }?.let {
                ActivityContext.Playlist(playlistId = MediaId.spotify(it).toString(), playlistName = title)
            }
        else -> null
    }
}

/**
 * Same context: equal, or the same Spotify album or playlist (Spotify's own reading of Yoin's
 * album carries a bare title; Yoin's keeps its artist and cover).
 */
private fun ActivityContext.namesSameAs(other: ActivityContext): Boolean =
    this == other || spotifyContextUri(this)?.let { it == spotifyContextUri(other) } == true

/** URI of the Spotify album / playlist an [ActivityContext] names, to compare against Spotify's. */
private fun spotifyContextUri(context: ActivityContext): String? = when (context) {
    is ActivityContext.Album -> spotifyRawId(context.albumId)?.let { "$SPOTIFY_ALBUM_URI_PREFIX$it" }
    is ActivityContext.Playlist -> spotifyRawId(context.playlistId)?.let { "$SPOTIFY_PLAYLIST_URI_PREFIX$it" }
    else -> null
}

private fun spotifyRawId(id: String): String? =
    MediaId.parseOrNull(id)?.takeIf { it.provider == MediaId.PROVIDER_SPOTIFY }?.rawId

private const val SPOTIFY_ALBUM_URI_PREFIX = "spotify:album:"
private const val SPOTIFY_PLAYLIST_URI_PREFIX = "spotify:playlist:"
