package com.gpo.yoin.widget

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Play widget's "keep listening" order: plays of what its button resumes (an album, a
 * playlist) first, then visits and an artist's plays (the button only opens an artist's page),
 * each newest first.
 */
class WidgetKeepListeningOrderTest {

    @Test
    fun should_putAnArtistsPlaysWithTheVisits_when_anArtistPagePlayIsNewest() {
        val artistPlay = played(ActivityEntityType.ARTIST, "ar1", at = 50L)
        val albumVisit = visited(ActivityEntityType.ALBUM, "al2", at = 40L)
        val albumPlay = played(ActivityEntityType.ALBUM, "al1", at = 30L)
        val playlistPlay = played(ActivityEntityType.PLAYLIST, "pl1", at = 20L)
        val artistVisit = visited(ActivityEntityType.ARTIST, "ar2", at = 10L)
        val newestFirst = listOf(artistPlay, albumVisit, albumPlay, playlistPlay, artistVisit)

        val ordered = WidgetData.keepListeningOrder(newestFirst)

        assertEquals(listOf(albumPlay, playlistPlay, artistPlay, albumVisit, artistVisit), ordered)
    }

    @Test
    fun should_keepTheNewestFirstOrder_when_onlyAlbumAndPlaylistPlaysExist() {
        val newestFirst = listOf(
            played(ActivityEntityType.PLAYLIST, "pl1", at = 30L),
            played(ActivityEntityType.ALBUM, "al1", at = 20L),
            played(ActivityEntityType.ALBUM, "al2", at = 10L)
        )

        assertEquals(newestFirst, WidgetData.keepListeningOrder(newestFirst))
    }

    private fun played(type: ActivityEntityType, rawId: String, at: Long) =
        event(type, ActivityActionType.PLAYED, rawId, at)

    private fun visited(type: ActivityEntityType, rawId: String, at: Long) =
        event(type, ActivityActionType.VISITED, rawId, at)

    private fun event(type: ActivityEntityType, action: ActivityActionType, rawId: String, at: Long) = ActivityEvent(
        entityType = type.name,
        actionType = action.name,
        entityId = MediaId.spotify(rawId).toString(),
        provider = MediaId.PROVIDER_SPOTIFY,
        title = rawId,
        subtitle = "",
        timestamp = at
    )
}
