package com.gpo.yoin.data.home

import com.gpo.yoin.data.local.HomeLayoutDao
import com.gpo.yoin.data.local.HomeLayoutPreference
import com.gpo.yoin.shared.home.HomeLayoutCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * One persisted section choice ([com.gpo.yoin.shared.home.HomeSectionPref]): its stable id, whether it
 * renders, its settings bag (`config`) and the entry's other keys, kept verbatim. Position in the persisted
 * list is the render order. The UI layer reconciles ids → sections (`HomeLayout.reconcile`).
 */
typealias HomeSectionPref = com.gpo.yoin.shared.home.HomeSectionPref

/**
 * The whole stored layout ([com.gpo.yoin.shared.home.HomeLayoutDocument]): the sections, the two-column
 * memory `wide` (null: written by a build without it, derived again on read) and the document's other keys.
 */
typealias HomeLayoutDocument = com.gpo.yoin.shared.home.HomeLayoutDocument

/**
 * Per-profile persistence for the customizable home layout. Reads emit `null`
 * when the profile has never customized (the caller falls back to catalog
 * defaults); writes serialize the ordered pref list (and the two-column memory,
 * [HomeLayoutDocument.wide]) to the `home_layout` Room row, through the shared [HomeLayoutCodec]: an
 * unreadable document is treated as "never set", a bad entry drops only itself, and keys this build doesn't
 * read (the document's and each entry's) are written back as they were.
 */
class HomeLayoutStore(
    private val dao: HomeLayoutDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
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
                        sectionsJson = HomeLayoutCodec.encode(document),
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

    internal fun encode(sections: List<HomeSectionPref>, wide: JsonObject? = null): String =
        HomeLayoutCodec.encode(HomeLayoutDocument(sections, wide))

    /** Unreadable document → null ("never set"); a bad entry drops only itself. */
    internal fun decode(raw: String): List<HomeSectionPref>? = decodeDocument(raw)?.sections

    /** [decode] with the two-column memory and the kept keys; a `wide` that isn't an object reads as none. */
    internal fun decodeDocument(raw: String): HomeLayoutDocument? = HomeLayoutCodec.decode(raw)

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
}
