package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A backup carries the Home layout's two-column memory (the document's optional `wide` key) verbatim. */
@RunWith(RobolectricTestRunner::class)
class BackupHomeLayoutTest {
    private val phone = BackupTestDevice("dev-a", "Pixel 9")
    private val tablet = BackupTestDevice("dev-b", "Pixel Tablet")
    private val url = "https://music.example.com"

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
    }

    @Test
    fun should_restoreTheTwoColumnMemory_when_aBackupIsImported() = runTest {
        val p = phone.addSubsonic("p1", url, "alice")
        // A portrait edit that split Activities | Jump Back In: remembered, not shown.
        val layout = HomeLayout.WideDefault.moved(HomeSection.MemoryGrid, 1)
        HomeLayoutStore(phone.db.homeLayoutDao()).setDocument(p, layout.toDocument())
        val backup = BackupTestDevice.parse(phone.exportZip().first)
        val t = tablet.addSubsonic("t1", url, "alice")

        tablet.importer().apply(tablet.importer().plan(backup, replica = null))

        val restored = HomeLayoutStore(tablet.db.homeLayoutDao()).readDocument(t)!!
        assertEquals(layout.wideDocument(), restored.wide)
        assertTrue(HomeLayout.fromDocument(restored).sameSectionsAs(layout))
    }
}
