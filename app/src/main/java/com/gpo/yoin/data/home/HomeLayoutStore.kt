package com.gpo.yoin.data.home

import com.gpo.yoin.data.local.HomeLayoutDao
import com.gpo.yoin.data.local.HomeLayoutPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
 * row. The document is versioned and decoded entry by entry: an unreadable
 * document is treated as "never set", a bad entry drops only itself.
 */
class HomeLayoutStore(
    private val dao: HomeLayoutDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Fair (FIFO): a move → undo pair lands in call order, and each equality
    // check below sees the write queued before it.
    private val writeMutex = Mutex()

    /** Persisted ordered section prefs for [profileId], or null if never set / unreadable. */
    fun layoutFlow(profileId: String): Flow<List<HomeSectionPref>?> =
        dao.getForProfile(profileId).map { row -> row?.sectionsJson?.let(::decode) }

    /** Persist [sections] for [profileId]; skipped when the stored row already holds them. */
    suspend fun setLayout(profileId: String, sections: List<HomeSectionPref>) {
        bestEffort {
            writeMutex.withLock {
                val stored = dao.getForProfile(profileId).first()?.sectionsJson?.let(::decode)
                if (stored == sections) return@withLock
                dao.upsert(
                    HomeLayoutPreference(
                        profileId = profileId,
                        sectionsJson = encode(sections),
                        updatedAt = clock(),
                    ),
                )
            }
        }
    }

    /** Back to "never customized" (new sections follow their defaults again); also the profile-delete hook. */
    suspend fun clearLayout(profileId: String) {
        bestEffort {
            writeMutex.withLock { dao.delete(profileId) }
        }
    }

    internal fun encode(sections: List<HomeSectionPref>): String = json.encodeToString(
        SectionsDto.serializer(),
        SectionsDto(
            version = SCHEMA_VERSION,
            sections = sections.map { json.encodeToJsonElement(HomeSectionPref.serializer(), it) },
        ),
    )

    /** Unreadable document → null ("never set"); a bad entry drops only itself. */
    internal fun decode(raw: String): List<HomeSectionPref>? {
        val dto = runCatching { json.decodeFromString(SectionsDto.serializer(), raw) }.getOrNull()
            ?: return null
        return dto.sections.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement(HomeSectionPref.serializer(), entry) }.getOrNull()
        }
    }

    private inline fun bestEffort(block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            // Rethrow so cooperative cancellation isn't swallowed mid-write.
            throw cancellation
        } catch (_: Exception) {
            // Best-effort persistence: the edit draft stays authoritative for
            // the session and the next write self-heals.
        }
    }

    @Serializable
    private data class SectionsDto(
        // Absent in pre-versioned rows → 1; older builds skip it (ignoreUnknownKeys).
        val version: Int = SCHEMA_VERSION,
        // Raw entries, decoded one by one (see [decode]).
        val sections: List<JsonElement>,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
