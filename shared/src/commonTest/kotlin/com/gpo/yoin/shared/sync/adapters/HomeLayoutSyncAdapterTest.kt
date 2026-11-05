package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.CanonicalJson
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import com.gpo.yoin.shared.sync.testing.assertEquals
import com.gpo.yoin.shared.sync.testing.assertNull
import com.gpo.yoin.shared.sync.testing.assertTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Android's HomeLayoutSyncAdapterTest, plus the Reset tombstone. */
class HomeLayoutSyncAdapterTest {
    private val domain = FakeSyncDomain()
    private val adapter = HomeLayoutSyncAdapter(domain)
    private val doc = """{"version":1,"sections":[{"id":"jump_back_in","enabled":true},{"id":"activities","enabled":false}]}"""

    private fun payload(json: String) = JsonObject(mapOf("sectionsJson" to JsonPrimitive(json)))
    private fun hashOf(o: JsonObject?) = o?.let(CanonicalJson::hash)

    @Test
    fun should_readBackSameProjectionAtVersionTs_when_layoutApplied() = runTest {
        val outcome = adapter.apply("p1", HomeLayoutSyncAdapter.KEY, payload(doc), versionTs = 700L, expectedLocalHash = null)
        assertTrue(outcome is ApplyOutcome.Applied)
        val row = adapter.readAll("p1").single()
        assertEquals(HomeLayoutSyncAdapter.KEY, row.key)
        assertEquals(700L, row.rowTs)
        assertEquals(hashOf(adapter.project(payload(doc))), hashOf(row.projection))
        // Kept as received: another encoder's spelling is not rewritten.
        assertEquals(doc, domain.homeLayout("p1")!!.sectionsJson)
    }

    @Test
    fun should_skipChangedLocally_when_layoutChangedSinceExpectedHash() = runTest {
        domain.putHomeLayout("p1", HomeLayoutRow("""{"version":1,"sections":[]}""", 10L))
        val outcome = adapter.apply("p1", HomeLayoutSyncAdapter.KEY, payload(doc), versionTs = 700L, expectedLocalHash = null)
        assertTrue(outcome is ApplyOutcome.Skipped && outcome.reason == SkipReason.CHANGED_LOCALLY)
        assertEquals(10L, domain.homeLayout("p1")!!.updatedAt)
    }

    @Test
    fun should_skipUnsupported_when_keyIsUnknown() = runTest {
        val outcome = adapter.apply("p1", "other", payload(doc), versionTs = 700L, expectedLocalHash = null)
        assertTrue(outcome is ApplyOutcome.Skipped && outcome.reason == SkipReason.UNSUPPORTED)
        assertNull(domain.homeLayout("p1"))
    }

    @Test
    fun should_goBackToDefault_when_tombstoneArrives() = runTest {
        domain.putHomeLayout("p1", HomeLayoutRow(doc, 10L))
        val hash = hashOf(adapter.readAll("p1").single().projection)
        val outcome = adapter.delete("p1", HomeLayoutSyncAdapter.KEY, expectedLocalHash = hash)
        assertTrue(outcome is ApplyOutcome.Applied)
        assertNull(domain.homeLayout("p1"))
        assertTrue(adapter.readAll("p1").isEmpty())
    }

    @Test
    fun should_keepLayoutOnOtherProfile_when_appliedPerAccount() = runTest {
        adapter.apply("p1", HomeLayoutSyncAdapter.KEY, payload(doc), versionTs = 700L, expectedLocalHash = null)
        assertTrue(adapter.readAll("p2").isEmpty())
    }
}
