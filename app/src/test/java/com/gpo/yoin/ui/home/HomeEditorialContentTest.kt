package com.gpo.yoin.ui.home

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeEditorialContentTest {

    @Test
    fun dedupeActivitiesForHome_keeps_only_most_recent_entry_per_entity() {
        val activities = listOf(
            activity(
                id = 10L,
                entityType = ActivityEntityType.ALBUM.name,
                entityId = "album-1",
                title = "Describe",
            ),
            activity(
                id = 9L,
                entityType = ActivityEntityType.ALBUM.name,
                entityId = "album-1",
                title = "Describe",
            ),
            activity(
                id = 8L,
                entityType = ActivityEntityType.SONG.name,
                entityId = "event-song-1",
                songId = "song-1",
                title = "Background",
            ),
            activity(
                id = 7L,
                entityType = ActivityEntityType.SONG.name,
                entityId = "another-row-id",
                songId = "song-1",
                title = "Background",
            ),
            activity(
                id = 6L,
                entityType = ActivityEntityType.ALBUM.name,
                entityId = "album-2",
                title = "Fobia",
            ),
        )

        val deduped = dedupeActivitiesForHome(activities)

        assertEquals(listOf(10L, 8L, 6L), deduped.map(ActivityEvent::id))
    }

    private fun activity(
        id: Long,
        entityType: String,
        entityId: String,
        title: String,
        songId: String? = null,
    ) = ActivityEvent(
        id = id,
        entityType = entityType,
        actionType = ActivityActionType.PLAYED.name,
        entityId = entityId,
        title = title,
        subtitle = "Test Artist",
        songId = songId,
        timestamp = 1_000L - id,
    )
}
