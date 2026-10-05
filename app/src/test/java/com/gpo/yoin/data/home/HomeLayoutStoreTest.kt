package com.gpo.yoin.data.home

import com.gpo.yoin.data.local.HomeLayoutDao
import com.gpo.yoin.data.local.HomeLayoutPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLayoutStoreTest {

    private val dao = FakeHomeLayoutDao()
    private val store = HomeLayoutStore(dao, clock = { 42L })

    private val prefs = listOf(
        HomeSectionPref(id = "recently_added", enabled = true),
        HomeSectionPref(id = "activities", enabled = false),
        HomeSectionPref(id = "jump_back_in", enabled = true),
    )

    @Test
    fun should_roundTripPrefs_when_setThenRead() = runTest {
        store.setLayout(PROFILE, prefs)

        assertEquals(prefs, store.layoutFlow(PROFILE).first())
    }

    @Test
    fun should_writeVersionOne_when_encoding() = runTest {
        store.setLayout(PROFILE, prefs)

        val document = Json.parseToJsonElement(dao.raw(PROFILE)!!).jsonObject
        assertEquals(1, document.getValue("version").jsonPrimitive.int)
    }

    @Test
    fun should_readLegacyRow_when_versionFieldMissing() = runTest {
        dao.seed(PROFILE, """{"sections":[{"id":"jump_back_in","enabled":false},{"id":"activities","enabled":true}]}""")

        assertEquals(
            listOf(
                HomeSectionPref(id = "jump_back_in", enabled = false),
                HomeSectionPref(id = "activities", enabled = true),
            ),
            store.layoutFlow(PROFILE).first(),
        )
    }

    @Test
    fun should_dropOnlyMalformedEntry_when_oneEntryIsBad() = runTest {
        dao.seed(
            PROFILE,
            """{"version":1,"sections":[{"id":"activities","enabled":true},{"id":"jump_back_in"},""" +
                """"garbage",{"id":"recently_added","enabled":false}]}""",
        )

        assertEquals(
            listOf(
                HomeSectionPref(id = "activities", enabled = true),
                HomeSectionPref(id = "recently_added", enabled = false),
            ),
            store.layoutFlow(PROFILE).first(),
        )
    }

    @Test
    fun should_emitNull_when_documentUnreadable() = runTest {
        dao.seed(PROFILE, "not json at all")
        assertNull(store.layoutFlow(PROFILE).first())

        dao.seed(PROFILE, """{"version":1,"sections":"activities"}""")
        assertNull(store.layoutFlow(PROFILE).first())
    }

    @Test
    fun should_keepUnknownIds_when_decodingAndReencoding() = runTest {
        val withFuture = listOf(
            HomeSectionPref(id = "activities", enabled = true),
            HomeSectionPref(id = "your_tracks", enabled = true),
        )
        dao.seed(PROFILE, store.encode(withFuture))

        val decoded = store.layoutFlow(PROFILE).first()
        assertEquals(withFuture, decoded)
        assertEquals(withFuture, store.decode(store.encode(decoded!!)))
    }

    @Test
    fun should_keepConfigVerbatimAndIgnoreUnknownFields_when_newerBuildWritesThem() = runTest {
        dao.seed(
            PROFILE,
            """{"version":2,"sections":[{"id":"activities","enabled":true,"config":{"rows":2}}],"theme":"dense"}""",
        )

        // The settings bag (D1 row presets) is opaque here: even a value this build can't read rides along.
        assertEquals(
            listOf(
                HomeSectionPref(
                    id = "activities",
                    enabled = true,
                    config = JsonObject(mapOf("rows" to JsonPrimitive(2))),
                ),
            ),
            store.layoutFlow(PROFILE).first(),
        )
    }

    @Test
    fun should_skipUpsert_when_prefsEqualStoredRow() = runTest {
        store.setLayout(PROFILE, prefs)
        store.setLayout(PROFILE, prefs.toList())

        assertEquals(1, dao.upserts)
    }

    @Test
    fun should_upsert_when_prefsDiffer() = runTest {
        store.setLayout(PROFILE, prefs)
        val moved = listOf(prefs[2], prefs[0], prefs[1])
        store.setLayout(PROFILE, moved)

        assertEquals(2, dao.upserts)
        assertEquals(moved, store.layoutFlow(PROFILE).first())
    }

    @Test
    fun should_deleteRow_when_clearLayout() = runTest {
        store.setLayout(PROFILE, prefs)
        store.setLayout(OTHER_PROFILE, prefs)

        store.clearLayout(PROFILE)

        assertNull(store.layoutFlow(PROFILE).first())
        assertEquals(prefs, store.layoutFlow(OTHER_PROFILE).first())
    }

    @Test
    fun should_swallowFailure_when_daoUpsertThrows() = runTest {
        dao.failWrites = true

        store.setLayout(PROFILE, prefs)
        store.clearLayout(PROFILE)

        assertNull(store.layoutFlow(PROFILE).first())
    }

    private companion object {
        const val PROFILE = "profile-a"
        const val OTHER_PROFILE = "profile-b"
    }
}

/** In-memory [HomeLayoutDao] that counts upserts; shared with the Home VM tests. */
internal class FakeHomeLayoutDao : HomeLayoutDao {
    private val rows = MutableStateFlow<Map<String, HomeLayoutPreference>>(emptyMap())

    var upserts = 0
        private set
    var failWrites = false

    override fun getForProfile(profileId: String): Flow<HomeLayoutPreference?> = rows.map { it[profileId] }

    override suspend fun upsert(preference: HomeLayoutPreference) {
        if (failWrites) error("disk full")
        upserts += 1
        rows.value = rows.value + (preference.profileId to preference)
    }

    override suspend fun delete(profileId: String) {
        if (failWrites) error("disk full")
        rows.value = rows.value - profileId
    }

    fun seed(profileId: String, sectionsJson: String) {
        rows.value = rows.value + (profileId to HomeLayoutPreference(profileId, sectionsJson, updatedAt = 0L))
    }

    fun raw(profileId: String): String? = rows.value[profileId]?.sectionsJson
}
