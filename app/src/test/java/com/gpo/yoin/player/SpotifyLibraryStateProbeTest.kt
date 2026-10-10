package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug library-state probe's notes on its connection (Q17): the client
 * id is the app's one setting, not an account's, and an account that isn't
 * Spotify's is a fact of its own — never given as the reason for a missing
 * client id.
 */
class SpotifyLibraryStateProbeTest {

    @Test
    fun should_pointToTheAppsSetting_when_noClientIdIsConfiguredOnASpotifyAccount() {
        val notes = probe(ProbeConnection.NoClientId, MediaId.PROVIDER_SPOTIFY).connectionNotes(8_000L)

        assertEquals(1, notes.size)
        assertTrue(notes.single(), notes.single().startsWith("no Spotify client id configured"))
        assertTrue(notes.single(), "Settings › Spotify" in notes.single())
        assertFalse(notes.single(), "profile" in notes.single())
    }

    @Test
    fun should_tellTheAccountAndTheClientIdApart_when_neitherIsSpotifys() {
        val notes = probe(ProbeConnection.NoClientId, MediaId.PROVIDER_SUBSONIC).connectionNotes(8_000L)

        assertEquals(2, notes.size)
        assertTrue(notes[0], notes[0].startsWith("active account is subsonic, not Spotify"))
        assertTrue(notes[1], notes[1].startsWith("no Spotify client id configured"))
    }

    @Test
    fun should_noteOnlyTheAccount_when_theProbeConnectedOnAnotherService() {
        val notes = probe(ProbeConnection.Connected, MediaId.PROVIDER_APPLE_MUSIC).connectionNotes(8_000L)

        assertEquals(1, notes.size)
        assertTrue(notes.single(), notes.single().startsWith("active account is applemusic, not Spotify"))
    }

    @Test
    fun should_noteNothing_when_connectedOnASpotifyAccount() {
        val notes = probe(ProbeConnection.Connected, MediaId.PROVIDER_SPOTIFY).connectionNotes(8_000L)

        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun should_sayHowLongItWaited_when_theConnectTimedOut() {
        val notes = probe(ProbeConnection.TimedOut, MediaId.PROVIDER_SPOTIFY).connectionNotes(8_000L)

        assertEquals(listOf("App Remote did not connect within 8 s: open Spotify and retry"), notes)
    }

    private fun probe(connection: ProbeConnection, providerId: String?) =
        SpotifyLibraryStateProbe(connection, connectMs = 0L, readings = emptyList(), activeProviderId = providerId)
}
