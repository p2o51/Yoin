package com.gpo.yoin.data.home

import com.gpo.yoin.data.local.HomeLayoutDao
import com.gpo.yoin.data.local.HomeLayoutPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One persisted section choice: its stable [id] and whether it renders. Position
 * in the persisted list is the render order. Kept UI-agnostic (a plain id string,
 * not a `HomeSection`) so this data-layer store never depends on the section
 * catalog — the UI layer reconciles ids → sections. See `HomeSection.reconcile`.
 *
 * [config] is the section's settings bag (`{"rows":"xl"}` today, D1): absent
 * for a section left at its defaults — never written as `"config":null` — and
 * opaque here, so keys a newer build adds ride along verbatim. Older builds
 * skip it (ignoreUnknownKeys); no schema or database bump.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HomeSectionPref(
    val id: String,
    val enabled: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val config: JsonObject? = null,
)

/**
 * The whole stored layout: [sections] (as [HomeSectionPref]s), and [wide], the
 * two-column memory of which sections pair up (`{"pairs":[["left","right"], …]}`,
 * owner 2026-11-23), opaque here like a section's config. Null [wide]: written
 * by a build without it (older Android and iOS drop the key when
 * they write), so the UI layer derives it again. Older readers skip the key.
 */
data class HomeLayoutDocument(
    val sections: List<HomeSectionPref>,
    val wide: JsonObject? = null,
)

/**
 * Per-profile persistence for the customizable home layout. Reads emit `null`
 * when the profile has never customized (the caller falls back to catalog
 * defaults); writes serialize the ordered pref list (and the two-column memory,
 * [HomeLayoutDocument.wide]) to the `home_layout` Room row. The document is versioned and decoded entry by entry: an unreadable
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
        documentFlow(profileId).map { it?.sections }

    /** [layoutFlow] with the two-column memory beside the sections. */
    fun documentFlow(profileId: String): Flow<HomeLayoutDocument?> =
        dao.getForProfile(profileId).map { row -> row?.sectionsJson?.let(::decodeDocument) }

    /**
     * [layoutFlow]'s current value, read once on the caller's dispatcher: a
     * plain query, so it neither hops back to the collector's thread nor waits
     * on Room's change tracking (Home reads it beside a snapshot, off the main
     * thread, before a cold start's first frames are done).
     */
    suspend fun readLayout(profileId: String): List<HomeSectionPref>? = readDocument(profileId)?.sections

    /** [readLayout] with the two-column memory beside the sections. */
    suspend fun readDocument(profileId: String): HomeLayoutDocument? =
        dao.getOnceForProfile(profileId)?.sectionsJson?.let(::decodeDocument)

    /** Persist [sections] for [profileId] without a two-column memory; skipped when the stored row already holds them. */
    suspend fun setLayout(profileId: String, sections: List<HomeSectionPref>) =
        setDocument(profileId, HomeLayoutDocument(sections))

    /** Persist [document] for [profileId]; skipped when the stored row already holds it. */
    suspend fun setDocument(profileId: String, document: HomeLayoutDocument) {
        bestEffort {
            writeMutex.withLock {
                val stored = dao.getForProfile(profileId).first()?.sectionsJson?.let(::decodeDocument)
                if (stored == document) return@withLock
                dao.upsert(
                    HomeLayoutPreference(
                        profileId = profileId,
                        sectionsJson = encode(document.sections, document.wide),
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

    internal fun encode(sections: List<HomeSectionPref>, wide: JsonObject? = null): String = json.encodeToString(
        SectionsDto.serializer(),
        SectionsDto(
            version = SCHEMA_VERSION,
            sections = sections.map { json.encodeToJsonElement(HomeSectionPref.serializer(), it) },
            wide = wide,
        ),
    )

    /** Unreadable document → null ("never set"); a bad entry drops only itself. */
    internal fun decode(raw: String): List<HomeSectionPref>? = decodeDocument(raw)?.sections

    /** [decode] with the two-column memory; a `wide` that isn't an object reads as none. */
    internal fun decodeDocument(raw: String): HomeLayoutDocument? {
        val dto = runCatching { json.decodeFromString(SectionsDto.serializer(), raw) }.getOrNull()
            ?: return null
        val sections = dto.sections.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement(HomeSectionPref.serializer(), entry) }.getOrNull()
        }
        return HomeLayoutDocument(sections, dto.wide as? JsonObject)
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

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class SectionsDto(
        // Absent in pre-versioned rows → 1; older builds skip it (ignoreUnknownKeys).
        val version: Int = SCHEMA_VERSION,
        // Raw entries, decoded one by one (see [decode]).
        val sections: List<JsonElement>,
        // The two-column memory (HomeLayoutDocument.wide). Optional and additive, so the version stays 1:
        // older builds skip it and write it away, which reads as "derive it again".
        @EncodeDefault(EncodeDefault.Mode.NEVER) val wide: JsonElement? = null,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
