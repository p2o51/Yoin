package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single profile's home-screen layout choice, stored as JSON.
 *
 * Keyed by [profileId] so each account (a Spotify power user vs a Subsonic
 * casual listener) remembers its own ordered/toggled sections. The [sectionsJson]
 * payload is a versioned document holding an ordered list of `{id, enabled}`
 * section prefs — order in the list *is* the render order. Unknown ids are
 * retained (re-emitted after the known ones) so a downgrade never loses them;
 * only retired ids drop. See `HomeLayoutStore` and `HomeSection.reconcile`.
 */
@Entity(tableName = "home_layout")
data class HomeLayoutPreference(
    @PrimaryKey val profileId: String,
    val sectionsJson: String,
    val updatedAt: Long,
)
