package com.gpo.yoin.data.home

import com.gpo.yoin.ui.home.HomeLayout
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity p4 (owner decision, plan Q7): the store keeps every key it doesn't read — the document's and each
 * entry's — through a read → edit → write round trip, as iOS and HarmonyOS already did and design.md
 * promises. Before :shared's codec, Android dropped them (ignoreUnknownKeys).
 */
class HomeLayoutStoreUnknownKeysTest {

    private val dao = FakeHomeLayoutDao()
    private val store = HomeLayoutStore(dao, clock = { 7L })

    private val newer = """{"version":1,"sections":[""" +
        """{"id":"activities","enabled":true,"pin":true,"config":{"rows":"s","tint":"red"}},""" +
        """{"id":"your_tracks","enabled":false,"since":3},""" +
        """{"id":"jump_back_in","enabled":true}""" +
        """],"wide":{"pairs":[["activities","jump_back_in"]],"gap":"wide"},"theme":"dense","flags":[1,2]}"""

    @Test
    fun should_keepUnknownDocumentAndEntryKeys_when_aLayoutIsReadEditedAndWritten() = runTest {
        dao.seed(PROFILE, newer)

        val read = HomeLayout.fromDocument(store.readDocument(PROFILE))
        val edited = read.withEnabled(HomeSection.RecentlyAdded, false)
        store.setDocument(PROFILE, edited.toDocument())

        val raw = Json.parseToJsonElement(dao.raw(PROFILE)!!).jsonObject
        assertEquals(JsonPrimitive("dense"), raw["theme"])
        assertEquals(Json.parseToJsonElement("[1,2]"), raw["flags"])
        val sections = raw.getValue("sections").jsonArray.map { it.jsonObject }
        val activities = sections.first { it["id"] == JsonPrimitive("activities") }
        assertEquals(JsonPrimitive(true), activities["pin"])
        assertEquals(JsonPrimitive("red"), activities.getValue("config").jsonObject["tint"])
        val retained = sections.first { it["id"] == JsonPrimitive("your_tracks") }
        assertEquals(JsonPrimitive(3), retained["since"])
        assertEquals(JsonPrimitive("wide"), raw.getValue("wide").jsonObject["gap"])
        // Known keys still lead, in the order every platform writes them.
        assertEquals(listOf("version", "sections", "wide", "theme", "flags"), raw.keys.toList())
        assertEquals(listOf("id", "enabled", "config", "pin"), activities.keys.toList())
    }

    @Test
    fun should_dropOnlyTheMalformedEntry_when_oneEntryIsBad() = runTest {
        dao.seed(
            PROFILE,
            """{"version":1,"sections":[{"id":"activities","enabled":true,"x":1},{"id":5,"enabled":true},""" +
                """{"id":"rediscover"},{"id":"jump_back_in","enabled":false,"config":"s"},"loose",""" +
                """{"id":"recently_added","enabled":false,"config":null}]}""",
        )

        val document = store.readDocument(PROFILE)!!
        assertEquals(listOf("activities", "recently_added"), document.sections.map { it.id })
        assertEquals(JsonObject(mapOf("x" to JsonPrimitive(1))), document.sections[0].extras)
        assertNull(document.sections[1].config)
        assertNull(document.extras)
    }

    @Test
    fun should_readNoMemory_when_wideIsNotAnObject_andWriteOneBack() = runTest {
        dao.seed(PROFILE, """{"version":1,"sections":[{"id":"activities","enabled":true}],"wide":[1],"note":"x"}""")

        val document = store.readDocument(PROFILE)!!
        assertNull(document.wide)
        assertEquals(JsonObject(mapOf("note" to JsonPrimitive("x"))), document.extras)
        store.setDocument(PROFILE, HomeLayout.fromDocument(document).toDocument())
        val raw = Json.parseToJsonElement(dao.raw(PROFILE)!!).jsonObject
        // A layout's document always carries its memory, empty or not.
        assertTrue(raw.getValue("wide").jsonObject.containsKey("pairs"))
        assertEquals(JsonPrimitive("x"), raw["note"])
    }

    @Test
    fun should_neverWriteConfigAsNull() = runTest {
        store.setDocument(PROFILE, HomeLayout.Default.moved(HomeSection.Rediscover, 0).toDocument())

        val raw = dao.raw(PROFILE)!!
        assertFalse(raw.contains("\"config\":null"))
        assertTrue(raw.contains("\"wide\":{\"pairs\":"))
    }

    @Test
    fun should_clearTheRow_when_aDefaultLayoutCarriesOnlyDocumentKeys() = runTest {
        dao.seed(PROFILE, """{"version":1,"sections":[],"theme":"dense"}""")

        val read = HomeLayout.fromDocument(store.readDocument(PROFILE))
        assertTrue(read.isDefault)
        assertEquals(JsonObject(mapOf("theme" to JsonPrimitive("dense"))), read.retainedDocument)
        // Reset everywhere clears the row: kept keys go with it, as on iOS and HarmonyOS.
        assertEquals(
            com.gpo.yoin.shared.home.HomePersistAction.Clear,
            com.gpo.yoin.shared.home.homePersistAction(read),
        )
    }

    @Test
    fun should_notBeDefault_when_aKnownEntryCarriesUnknownKeys() = runTest {
        val prefs = HomeLayout.Default.toPrefs().map {
            if (it.id == "activities") it.copy(extras = JsonObject(mapOf("pin" to JsonPrimitive(true)))) else it
        }
        dao.seed(PROFILE, store.encode(prefs, HomeLayout.Default.wideDocument()))

        val read = HomeLayout.fromDocument(store.documentFlow(PROFILE).first())
        assertFalse(read.isDefault)
        assertEquals(prefs, read.toPrefs())
    }

    private companion object {
        const val PROFILE = "profile-unknown"
    }
}
