package com.gpo.yoin.data.home

import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-column memory in the stored document: an optional `wide` key, additive both ways. */
class HomeLayoutStoreWideTest {

    private val dao = FakeHomeLayoutDao()
    private val store = HomeLayoutStore(dao, clock = { 7L })

    /** A portrait edit that split Activities | Jump Back In: remembered, not shown. */
    private val split = HomeLayout.WideDefault.moved(HomeSection.MemoryGrid, 1)

    @Test
    fun should_roundTripTheMemory_when_writtenAsADocument() = runTest {
        store.setDocument(PROFILE, split.toDocument())

        val raw = Json.parseToJsonElement(dao.raw(PROFILE)!!).jsonObject
        assertEquals(1, raw.getValue("version").toString().toInt())
        assertEquals(split.wideDocument(), raw.getValue("wide"))
        val read = HomeLayout.fromDocument(store.documentFlow(PROFILE).first())
        assertTrue(read.sameSectionsAs(split))
        assertEquals(read, HomeLayout.fromDocument(store.readDocument(PROFILE)))
    }

    @Test
    fun should_skipTheWrite_when_theDocumentIsUnchanged() = runTest {
        store.setDocument(PROFILE, split.toDocument())
        store.setDocument(PROFILE, split.toDocument())
        assertEquals(1, dao.upserts)
        // Only the memory changed: written.
        store.setDocument(PROFILE, split.rememberingShownPairs().toDocument())
        assertEquals(2, dao.upserts)
    }

    @Test
    fun should_readTheSections_when_anOlderDecoderReadsTheNewDocument() = runTest {
        store.setDocument(PROFILE, split.toDocument())
        val raw = dao.raw(PROFILE)!!

        // The decoder of builds before the memory: version 1, ignoreUnknownKeys.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(OldSectionsDto.serializer(), raw)
        assertEquals(1, old.version)
        assertEquals(split.toPrefs().size, old.sections.size)
        // This build's list-only read (what the landing used to call) sees the same sections.
        assertEquals(split.toPrefs(), store.decode(raw))
    }

    @Test
    fun should_deriveTheMemory_when_anOldDocumentHasNoWideKey() = runTest {
        dao.seed(PROFILE, store.encode(split.toPrefs()))

        val document = store.documentFlow(PROFILE).first()!!
        assertNull(document.wide)
        // The flags show Lyric Cards | Recently Added and Your Playlists | Rediscover: that is the memory now.
        val read = HomeLayout.fromDocument(document)
        assertEquals(mapOf(HomeSection.LyricHighlight to HomeSection.RecentlyAdded, HomeSection.YourPlaylists to HomeSection.Rediscover), read.widePairs)
        assertEquals(split.sections, read.sections)
    }

    @Test
    fun should_keepTheLayoutReadable_when_wideIsNotAnObject() = runTest {
        dao.seed(PROFILE, """{"version":1,"sections":[{"id":"activities","enabled":false}],"wide":3}""")

        val document = store.readDocument(PROFILE)!!
        assertNull(document.wide)
        assertFalse(HomeLayout.fromDocument(document).sections.first { it.section == HomeSection.Activities }.enabled)
    }

    @Test
    fun should_dropTheMemory_when_anOlderStyleWriterRewritesTheLayout() = runTest {
        store.setDocument(PROFILE, split.toDocument())
        // setLayout writes sections only, as builds before the memory (and iOS / HarmonyOS) do.
        store.setLayout(PROFILE, split.withEnabled(HomeSection.OtherProfiles, false).toPrefs())

        assertFalse(dao.raw(PROFILE)!!.contains("wide"))
        assertNull(store.readDocument(PROFILE)!!.wide)
    }

    @Serializable
    private data class OldSectionsDto(val version: Int = 1, val sections: List<JsonElement>)

    private companion object {
        const val PROFILE = "profile-wide"
    }
}
