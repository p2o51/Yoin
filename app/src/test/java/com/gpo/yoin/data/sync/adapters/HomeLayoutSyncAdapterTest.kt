package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.HomeLayoutPreference
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import com.gpo.yoin.data.sync.testing.payload
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeLayoutSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val adapter = HomeLayoutSyncAdapter(fixtures.db)
    private val dao = fixtures.db.syncDomainDao()
    private val remote = payload(
        """{"sectionsJson":"{\"sections\":[{\"id\":\"activities\",\"enabled\":false}]}"}""",
    )

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_readBackSameProjectionAtVersionTs_when_layoutApplied() = runTest {
        val outcome = adapter.apply("p1", HomeLayoutSyncAdapter.KEY, remote, versionTs = 42L, expectedLocalHash = null)

        val row = adapter.readAll("p1").single()
        assertEquals(ApplyOutcome.Applied(hashOf(adapter.project(remote))), outcome)
        assertEquals(hashOf(adapter.project(remote)), hashOf(row.projection))
        assertEquals(42L, dao.homeLayout("p1")?.updatedAt)
        assertEquals(42L, row.rowTs)
    }

    @Test
    fun should_skipChangedLocally_when_layoutChangedSinceExpectedHash() = runTest {
        dao.upsertHomeLayout(HomeLayoutPreference("p1", "{\"sections\":[]}", 10L))
        val current = hashOf(adapter.readAll("p1").single().projection)

        val outcome = adapter.apply("p1", HomeLayoutSyncAdapter.KEY, remote, versionTs = 42L, expectedLocalHash = "old")

        assertEquals(ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, current), outcome)
        assertEquals("{\"sections\":[]}", dao.homeLayout("p1")?.sectionsJson)
    }

    @Test
    fun should_skipUnsupported_when_keyIsUnknown() = runTest {
        val outcome = adapter.apply("p1", "other", remote, versionTs = 42L, expectedLocalHash = null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
    }
}
