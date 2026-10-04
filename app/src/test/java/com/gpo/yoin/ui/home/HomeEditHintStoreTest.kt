package com.gpo.yoin.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeEditHintStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun should_countSessions_when_recordEditSessionCalled() {
        for (store in listOf(SharedPrefsHomeEditHintStore(context), HomeEditHintStore.InMemory())) {
            assertEquals(0, store.editSessionCount())
            store.recordEditSession()
            store.recordEditSession()
            assertEquals(2, store.editSessionCount())
        }
    }

    @Test
    fun should_showHeaderHint_when_fewerThanTwoSessions() {
        val store = SharedPrefsHomeEditHintStore(context)
        assertTrue(showEditHeaderHint(store.editSessionCount()))

        store.recordEditSession()

        assertTrue(showEditHeaderHint(store.editSessionCount()))
    }

    @Test
    fun should_hideHeaderHint_when_twoSessionsRecorded() {
        val store = SharedPrefsHomeEditHintStore(context)

        repeat(HomeEditHeaderHintSessions) { store.recordEditSession() }

        assertFalse(showEditHeaderHint(store.editSessionCount()))
    }

    @Test
    fun should_unionSeenIds_when_markSectionsSeen() {
        for (store in listOf(SharedPrefsHomeEditHintStore(context), HomeEditHintStore.InMemory())) {
            store.markSectionsSeen(listOf("rediscover"))
            val before = store.seenSectionIds()
            store.markSectionsSeen(listOf("your_tracks", "rediscover"))

            assertEquals(setOf("rediscover", "your_tracks"), store.seenSectionIds())
            // The earlier snapshot is a copy, never mutated by later writes.
            assertEquals(setOf("rediscover"), before)
        }
    }

    @Test
    fun should_persistAcrossInstances_when_sharedPrefsBacked() {
        SharedPrefsHomeEditHintStore(context).apply {
            recordEditSession()
            markSectionsSeen(listOf("rediscover"))
        }

        val reopened = SharedPrefsHomeEditHintStore(context)

        assertEquals(1, reopened.editSessionCount())
        assertEquals(setOf("rediscover"), reopened.seenSectionIds())
    }
}
