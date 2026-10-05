package com.gpo.yoin.data.home

import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row presets (D1) through the store: the settings bag is optional, opaque and backward compatible. */
class HomeLayoutStoreRowsTest {

    private val dao = FakeHomeLayoutDao()
    private val store = HomeLayoutStore(dao, clock = { 7L })

    @Test
    fun should_notWriteConfig_when_everySectionIsAtItsDefaultRows() = runTest {
        store.setLayout(PROFILE, HomeLayout.Default.moved(HomeSection.Rediscover, 0).toPrefs())

        val raw = dao.raw(PROFILE)!!
        assertFalse(raw, raw.contains("config"))
    }

    @Test
    fun should_writeOnlyTheResizedSectionsConfig_when_rowsChange() = runTest {
        val layout = HomeLayout.Default.withRows(HomeSection.JumpBackIn, HomeRowPreset.XL)
        store.setLayout(PROFILE, layout.toPrefs())

        val sections = Json.parseToJsonElement(dao.raw(PROFILE)!!).jsonObject.getValue("sections").jsonArray
        val configs = sections.associate { entry ->
            val obj = entry.jsonObject
            (obj.getValue("id") as JsonPrimitive).content to obj["config"]
        }
        assertEquals(JsonObject(mapOf("rows" to JsonPrimitive("xl"))), configs.getValue("jump_back_in"))
        assertEquals(null, configs.getValue("activities"))
        assertEquals(null, configs.getValue("recently_added"))
    }

    @Test
    fun should_roundTripRows_when_setThenReconciled() = runTest {
        val layout = HomeLayout.Default
            .withRows(HomeSection.Activities, HomeRowPreset.S)
            .withRows(HomeSection.JumpBackIn, HomeRowPreset.M)
        store.setLayout(PROFILE, layout.toPrefs())

        val read = HomeLayout.reconcile(store.layoutFlow(PROFILE).first())
        assertTrue(read.sameSectionsAs(layout))
        assertEquals(HomeRowPreset.S, read.rowsOf(HomeSection.Activities))
        assertEquals(HomeRowPreset.M, read.rowsOf(HomeSection.JumpBackIn))
    }

    @Test
    fun should_readPreRowsDocument_when_noConfigWasEverWritten() = runTest {
        dao.seed(
            PROFILE,
            """{"version":1,"sections":[{"id":"jump_back_in","enabled":true},{"id":"activities","enabled":true}]}""",
        )

        val layout = HomeLayout.reconcile(store.layoutFlow(PROFILE).first())
        assertEquals(HomeRowPreset.L, layout.rowsOf(HomeSection.JumpBackIn))
        assertEquals(HomeRowPreset.L, layout.rowsOf(HomeSection.Activities))
    }

    @Test
    fun should_keepUnknownConfigKeys_when_rowsAreRewritten() = runTest {
        dao.seed(
            PROFILE,
            """{"version":1,"sections":[""" +
                """{"id":"jump_back_in","enabled":true,"config":{"rows":"m","density":"airy"}}]}""",
        )
        val read = HomeLayout.reconcile(store.layoutFlow(PROFILE).first())
        assertEquals(HomeRowPreset.M, read.rowsOf(HomeSection.JumpBackIn))

        store.setLayout(PROFILE, read.withRows(HomeSection.JumpBackIn, HomeRowPreset.L).toPrefs())

        val written = store.layoutFlow(PROFILE).first()!!.first { it.id == "jump_back_in" }
        assertEquals(JsonObject(mapOf("density" to JsonPrimitive("airy"))), written.config)
    }

    @Test
    fun should_keepUnreadableRowsValue_when_reencodedUntouched() = runTest {
        dao.seed(PROFILE, """{"version":1,"sections":[{"id":"activities","enabled":true,"config":{"rows":"xxl"}}]}""")
        val read = HomeLayout.reconcile(store.layoutFlow(PROFILE).first())
        // Unknown → today's composition, and the value a newer build wrote survives a re-encode.
        assertEquals(HomeRowPreset.L, read.rowsOf(HomeSection.Activities))

        store.setLayout(PROFILE, read.withEnabled(HomeSection.Rediscover, false).toPrefs())

        val written = store.layoutFlow(PROFILE).first()!!.first { it.id == "activities" }
        assertEquals(JsonObject(mapOf("rows" to JsonPrimitive("xxl"))), written.config)
    }

    @Test
    fun should_skipTheWrite_when_onlyTheRowsInstanceDiffers() = runTest {
        val layout = HomeLayout.Default.withRows(HomeSection.JumpBackIn, HomeRowPreset.XL)
        store.setLayout(PROFILE, layout.toPrefs())
        val before = dao.upserts

        store.setLayout(PROFILE, HomeLayout.reconcile(store.layoutFlow(PROFILE).first()).toPrefs())

        assertEquals(before, dao.upserts)
    }

    private companion object {
        const val PROFILE = "profile-rows"
    }
}
