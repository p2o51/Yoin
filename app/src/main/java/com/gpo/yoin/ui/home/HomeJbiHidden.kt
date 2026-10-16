package com.gpo.yoin.ui.home

import android.content.Context
import androidx.core.content.edit
import com.gpo.yoin.data.model.MediaId

// Jump Back In's "not now" (owner 2026-10-13): in edit mode a tap on a card
// opens a bubble over it — hide it for three days or a week. Never for good:
// what Jump Back In picks is in the library, and the library is where it
// leaves for good. Kept per account, by what the card opens, so the same
// album hides whether it comes up as a cover or as its memory.

/** How long a hidden Jump Back In card stays away. */
enum class JbiHideSpan(val days: Int) {
    ThreeDays(3),
    OneWeek(7),
    ;

    val millis: Long get() = days * DAY_MILLIS

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Which Jump Back In cards are away, until when, per account scope (provider|profile). */
interface JbiHiddenStore {
    /** The keys ([jbiHideKey]) hidden in [scope] past [now]. */
    fun hidden(scope: String, now: Long): Set<String>

    fun hide(scope: String, key: String, until: Long)

    /** Process-local fallback for previews and tests. */
    class InMemory : JbiHiddenStore {
        private val until = mutableMapOf<Pair<String, String>, Long>()

        override fun hidden(scope: String, now: Long): Set<String> =
            until.filter { (at, time) -> at.first == scope && time > now }.mapTo(HashSet()) { it.key.second }

        override fun hide(scope: String, key: String, until: Long) {
            this.until[scope to key] = until
        }
    }
}

class SharedPrefsJbiHiddenStore(context: Context) : JbiHiddenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun hidden(scope: String, now: Long): Set<String> {
        val prefix = scope + SEPARATOR
        val expired = mutableListOf<String>()
        val keys = buildSet {
            prefs.all.forEach { (entry, value) ->
                val until = value as? Long ?: return@forEach
                if (until <= now) {
                    expired += entry
                } else if (entry.startsWith(prefix)) {
                    add(entry.removePrefix(prefix))
                }
            }
        }
        if (expired.isNotEmpty()) prefs.edit { expired.forEach(::remove) }
        return keys
    }

    override fun hide(scope: String, key: String, until: Long) {
        prefs.edit { putLong(scope + SEPARATOR + key, until) }
    }

    private companion object {
        const val PREFS_NAME = "home_jbi_hidden"
        const val SEPARATOR = "\u001f"
    }
}

/**
 * What a hidden card is known by: the album, song or playlist it opens. A
 * memory card is its album, a noted-track card its song, so hiding one hides
 * the item wherever Jump Back In would bring it back.
 */
internal fun HomeWidgetCard.jbiHideKey(): String = when (val target = target) {
    is HomeWidgetTarget.AlbumDetail -> jbiAlbumHideKey(target.albumId)
    is HomeWidgetTarget.PlaylistDetail -> "playlist:${target.playlistId}"
    is HomeWidgetTarget.PlaySong -> jbiSongHideKey(target.song.id)
    is HomeWidgetTarget.MemoryFocus -> memoryCardAlbum(stableId)?.let { jbiAlbumHideKey(it.toString()) }
        ?: "memory:${target.sessionId}"
}

internal fun jbiAlbumHideKey(albumId: String): String = "album:$albumId"

internal fun jbiSongHideKey(songId: MediaId): String = "song:$songId"

/** The album a memory 1×2 points at, recovered from its stable id ("grid-memory:<provider>:<rawAlbumId>"). */
internal fun memoryCardAlbum(stableId: String): MediaId? {
    if (!stableId.startsWith(MEMORY_CARD_PREFIX)) return null
    val rest = stableId.removePrefix(MEMORY_CARD_PREFIX)
    // Raw ids may hold ':'.
    val provider = rest.substringBefore(':', missingDelimiterValue = "")
    val rawId = rest.substringAfter(':', missingDelimiterValue = "")
    return if (provider.isNotBlank() && rawId.isNotBlank()) MediaId(provider, rawId) else null
}

internal const val MEMORY_CARD_PREFIX = "grid-memory:"
