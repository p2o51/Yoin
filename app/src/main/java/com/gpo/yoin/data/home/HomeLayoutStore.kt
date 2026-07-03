package com.gpo.yoin.data.home

import com.gpo.yoin.data.local.HomeLayoutDao
import com.gpo.yoin.data.local.HomeLayoutPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One persisted section choice: its stable [id] and whether it renders. Position
 * in the persisted list is the render order. Kept UI-agnostic (a plain id string,
 * not a `HomeSection`) so this data-layer store never depends on the section
 * catalog — the UI layer reconciles ids → sections. See `HomeSection.reconcile`.
 */
@Serializable
data class HomeSectionPref(
    val id: String,
    val enabled: Boolean,
)

/**
 * Per-profile persistence for the customizable home layout. Reads emit `null`
 * when the profile has never customized (the caller falls back to catalog
 * defaults); writes serialize the ordered pref list to the `home_layout` Room
 * row. Corrupt / incompatible JSON is treated as "never set" rather than a crash.
 */
class HomeLayoutStore(
    private val dao: HomeLayoutDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Persisted ordered section prefs for [profileId], or null if never set / unreadable. */
    fun layoutFlow(profileId: String): Flow<List<HomeSectionPref>?> =
        dao.getForProfile(profileId).map { row -> row?.sectionsJson?.let(::decode) }

    suspend fun setLayout(profileId: String, sections: List<HomeSectionPref>) {
        try {
            dao.upsert(
                HomeLayoutPreference(
                    profileId = profileId,
                    sectionsJson = json.encodeToString(SectionsDto.serializer(), SectionsDto(sections)),
                    updatedAt = clock(),
                ),
            )
        } catch (cancellation: CancellationException) {
            // Rethrow so cooperative cancellation isn't swallowed mid-write.
            throw cancellation
        } catch (_: Exception) {
            // Best-effort persistence: the editor's in-memory draft stays
            // authoritative for the session and the next write self-heals.
        }
    }

    private fun decode(raw: String): List<HomeSectionPref>? =
        runCatching { json.decodeFromString(SectionsDto.serializer(), raw).sections }.getOrNull()

    @Serializable
    private data class SectionsDto(val sections: List<HomeSectionPref>)
}
