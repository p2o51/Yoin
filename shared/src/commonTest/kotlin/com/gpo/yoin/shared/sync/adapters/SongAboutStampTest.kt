package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.testing.FakeSyncDomain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android SongAboutSyncAdapter stampOf: own entries are filed under "*", Gemini's under the device's language. */
class SongAboutStampTest {
    private fun row(kind: String, entryKey: String) = SongAboutRow(
        titleKey = "t", artistKey = "a", albumKey = "b", titleDisplay = "T", artistDisplay = "A", albumDisplay = "B",
        kind = kind, entryKey = entryKey, promptText = null, titleText = "Heading", answerText = "text",
        createdAt = 1L, updatedAt = 2L,
    )

    @Test
    fun should_fileOwnEntriesUnderAnyLanguage_when_reading() = runTest {
        val domain = FakeSyncDomain("Japanese")
        domain.putSongAbout(row("own", "heading"))
        domain.putSongAbout(row("ask", "q"))
        val rows = SongAboutSyncAdapter(domain).readAll(null)
        val own = rows.single { it.key.contains("own") }
        val ask = rows.single { it.key.contains("ask") }
        assertTrue(own.key.startsWith("*"), own.key)
        assertTrue(ask.key.startsWith("Japanese"), ask.key)
    }

    @Test
    fun should_applyAnOwnEntry_when_theDeviceReadsAnotherLanguage() = runTest {
        val writer = FakeSyncDomain("English").apply { putSongAbout(row("own", "heading")) }
        val sent = SongAboutSyncAdapter(writer).readAll(null).single()
        val reader = FakeSyncDomain("Japanese")
        val adapter = SongAboutSyncAdapter(reader)
        val payload = sent.projection
        val outcome = adapter.apply(null, sent.key, payload, 2L, null)
        assertTrue(outcome is ApplyOutcome.Applied, outcome.toString())
        assertEquals("text", reader.songAbouts().single().answerText)
    }
}
