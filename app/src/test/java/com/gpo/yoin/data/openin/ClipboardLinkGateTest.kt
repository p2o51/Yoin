package com.gpo.yoin.data.openin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardLinkGateTest {

    private fun facts(
        mime: List<String> = listOf("text/plain"),
        timestamp: Long? = 1_000L,
        url: Float? = null,
        sensitive: Boolean = false,
    ) = ClipDescriptionFacts(mime, timestamp, url, sensitive)

    @Test
    fun should_readClip_when_descriptionIsPlainTextNotSeenBefore() {
        assertTrue(ClipboardLinkGate.shouldRead(facts(), enabled = true, lastSeenTimestampMs = null))
        assertTrue(ClipboardLinkGate.shouldRead(facts(mime = listOf("text/html")), true, 999L))
        assertTrue(ClipboardLinkGate.shouldRead(facts(mime = listOf("text/uri-list")), true, null))
    }

    @Test
    fun should_notRead_when_settingIsOff() {
        assertFalse(ClipboardLinkGate.shouldRead(facts(), enabled = false, lastSeenTimestampMs = null))
    }

    @Test
    fun should_notRead_when_clipboardIsEmpty() {
        assertFalse(ClipboardLinkGate.shouldRead(null, enabled = true, lastSeenTimestampMs = null))
    }

    @Test
    fun should_notRead_when_clipIsNotText() {
        assertFalse(ClipboardLinkGate.shouldRead(facts(mime = listOf("image/png")), true, null))
        assertFalse(ClipboardLinkGate.shouldRead(facts(mime = listOf("text/vnd.android.intent")), true, null))
        assertFalse(ClipboardLinkGate.shouldRead(facts(mime = emptyList()), true, null))
    }

    @Test
    fun should_notRead_when_clipIsMarkedSensitive() {
        assertFalse(ClipboardLinkGate.shouldRead(facts(sensitive = true), true, null))
    }

    @Test
    fun should_notReadAgain_when_clipTimestampWasAlreadySeen() {
        assertFalse(ClipboardLinkGate.shouldRead(facts(timestamp = 1_000L), true, lastSeenTimestampMs = 1_000L))
        assertTrue(ClipboardLinkGate.shouldRead(facts(timestamp = 2_000L), true, lastSeenTimestampMs = 1_000L))
    }

    @Test
    fun should_trustClassifier_when_itRanOnTheClip() {
        assertFalse(ClipboardLinkGate.shouldRead(facts(url = 0f), true, null))
        assertFalse(ClipboardLinkGate.shouldRead(facts(url = 0.2f), true, null))
        assertTrue(ClipboardLinkGate.shouldRead(facts(url = 0.97f), true, null))
        // Not classified (yet), or before API 31: the MIME type decides.
        assertTrue(ClipboardLinkGate.shouldRead(facts(url = null), true, null))
    }

    @Test
    fun should_offerOnce_when_sameLinkComesBack() {
        val hash = ClipboardLinkGate.hash("Spotify:Track:0VjIjW4GlUZAMYd2vXMi3b")

        assertTrue(ClipboardLinkGate.shouldOffer(hash, lastHandledHash = null))
        assertFalse(ClipboardLinkGate.shouldOffer(hash, lastHandledHash = hash))
        assertTrue(ClipboardLinkGate.shouldOffer(hash, ClipboardLinkGate.hash("Spotify:Track:3n3Ppam7vgaVa1iaRUc9Lp")))
    }

    @Test
    fun should_keepOnlyAHash_when_rememberingALink() {
        val hash = ClipboardLinkGate.hash("Spotify:Track:0VjIjW4GlUZAMYd2vXMi3b")

        assertEquals(64, hash.length)
        assertFalse(hash.contains("0VjIjW4GlUZAMYd2vXMi3b"))
        assertNotEquals(hash, ClipboardLinkGate.hash("Spotify:Track:0VjIjW4GlUZAMYd2vXMi3c"))
    }
}
