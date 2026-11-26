package com.gpo.yoin.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The stored document's contract every platform shares (parity p4, plan §1.3 #1): nothing a newer build wrote is lost. */
class HomeLayoutCodecTest {

    @Test
    fun should_keepUnknownDocumentAndEntryKeys_through_aLayoutRoundTrip() {
        val raw = """{"theme":"dense","version":1,"sections":[{"id":"activities","enabled":true,"pin":1,"config":{"tint":"red"}},""" +
            """{"id":"your_tracks","enabled":false,"since":3}],"wide":{"pairs":[],"gap":2}}"""
        val layout = HomeLayout.fromDocument(HomeLayoutCodec.decode(raw))
        val written = HomeLayoutCodec.encode(layout.withEnabled(HomeSection.Rediscover, false).toDocument())
        val reread = HomeLayoutCodec.decode(written)!!
        assertEquals(JsonObject(mapOf("theme" to JsonPrimitive("dense"))), reread.extras)
        val activities = reread.sections.first { it.id == "activities" }
        assertEquals(JsonObject(mapOf("pin" to JsonPrimitive(1))), activities.extras)
        assertEquals(JsonPrimitive("red"), activities.config?.get("tint"))
        assertEquals(JsonObject(mapOf("since" to JsonPrimitive(3))), reread.sections.first { it.id == "your_tracks" }.extras)
        assertEquals(JsonPrimitive(2), reread.wide?.get("gap"))
        assertTrue(written.startsWith("""{"version":1,"sections":[{"id":"activities","enabled":true,"config":"""))
    }

    @Test
    fun should_dropOnlyTheBadEntry_and_readANonObjectWideAsNone() {
        val doc = HomeLayoutCodec.decode("""{"sections":[{"id":"activities"},{"id":"rediscover","enabled":false}],"wide":"x"}""")!!
        assertEquals(listOf("rediscover"), doc.sections.map { it.id })
        assertNull(doc.wide)
        assertNull(doc.extras)
        assertNull(HomeLayoutCodec.decode("""{"version":"one","sections":[]}"""))
    }

    @Test
    fun should_neverWriteConfigNull_and_alwaysWriteALayoutsMemory() {
        val written = HomeLayoutCodec.encode(HomeLayout.Default.moved(HomeSection.MadeForYou, 0).toDocument())
        assertTrue(!written.contains("\"config\":null"))
        assertTrue(written.contains("\"wide\":{\"pairs\":"))
    }

    @Test
    fun should_reportUnchanged_when_aFacadeEditChangesNothing() {
        val state = YoinHomeLayout.defaultState()
        val result = YoinHomeLayout.withEnabled(state, "activities", true)
        assertSame(state, result.state)
        assertEquals(false, result.changed)
        assertEquals(false, YoinHomeLayout.withHalf(state, "future_x").changed)
        assertEquals(true, YoinHomeLayout.persist(state).clear)
    }
}
