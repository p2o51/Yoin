package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeConnection
import com.gpo.yoin.player.SpotifyAppRemotePlayer.ProbeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug library-state probe's notes on its connection (Q17): the client
 * id is the app's one setting, not an account's, and an account that isn't
 * Spotify's is a fact of its own — never given as the reason for a missing
 * client id. An account whose source is still being built is told from no
 * account at all, and what became of the connection is said as it turned
 * out, only where the probe connected.
 */
class SpotifyLibraryStateProbeTest {

    @Test
    fun should_pointToTheAppsSetting_when_noClientIdIsConfiguredOnASpotifyAccount() {
        val notes = probe(ProbeConnection.NoClientId, MediaId.PROVIDER_SPOTIFY, ProbeLeft.NotConnected)
            .connectionNotes(8_000L)

        assertEquals(1, notes.size)
        assertTrue(notes.single(), notes.single().startsWith("no Spotify client id configured"))
        assertTrue(notes.single(), "Settings › Spotify" in notes.single())
        assertFalse(notes.single(), "profile" in notes.single())
    }

    @Test
    fun should_tellTheAccountAndTheClientIdApart_when_neitherIsSpotifys() {
        val notes = probe(ProbeConnection.NoClientId, MediaId.PROVIDER_SUBSONIC, ProbeLeft.NotConnected)
            .connectionNotes(8_000L)

        assertEquals(
            listOf(
                "active account is subsonic, not Spotify",
                "no Spotify client id configured: set one in Settings › Spotify (one for the app, not per account)"
            ),
            notes
        )
    }

    @Test
    fun should_sayTheConnectionClosed_when_theProbeOpenedItAloneOnAnotherService() {
        val notes = probe(ProbeConnection.Connected, MediaId.PROVIDER_APPLE_MUSIC, ProbeLeft.Closed)
            .connectionNotes(8_000L)

        assertEquals(
            listOf(
                "active account is applemusic, not Spotify",
                "App Remote was opened for the probe alone: closed after it"
            ),
            notes
        )
    }

    @Test
    fun should_tellABuildingSourceFromNoAccountAndSayItWasKept_when_theProbeRacesAColdStart() {
        // Broadcast right after a cold start: the Spotify account's source is still being built as the
        // probe connects, then its warm-up takes the connection over.
        val probe = probe(ProbeConnection.Connected, SpotifyLibraryStateProbe.ACCOUNT_BUILDING, ProbeLeft.Kept)
        val notes = probe.connectionNotes(8_000L)

        assertEquals(
            listOf(
                "the active account's source was still being built",
                "App Remote was opened for the probe and kept: the account's warm-up or a play wanted it meanwhile"
            ),
            notes
        )
        assertTrue(notes.none { note -> "closed" in note || "none" in note })
    }

    @Test
    fun should_tellNoSourceFromNoAccount_when_theAccountsCredentialsDidNotOpen() {
        fun firstNote(account: String) =
            probe(ProbeConnection.TimedOut, account, ProbeLeft.NotConnected).connectionNotes(8_000L).first()

        assertEquals(
            "the active account has no source: its credentials didn't open",
            firstNote(SpotifyLibraryStateProbe.ACCOUNT_UNAVAILABLE)
        )
        assertEquals("no active account", firstNote(SpotifyLibraryStateProbe.ACCOUNT_NONE))
    }

    @Test
    fun should_sayNothingOfTheConnectionsFate_when_theProbeNeverConnected() {
        val notes = probe(ProbeConnection.NoHost, MediaId.PROVIDER_SUBSONIC, ProbeLeft.NotConnected)
            .connectionNotes(8_000L)

        assertEquals(
            listOf(
                "active account is subsonic, not Spotify",
                "App Remote did not connect: open Yoin (an Activity must be started) and retry"
            ),
            notes
        )
    }

    @Test
    fun should_sayItWasLeftAsItWas_when_anotherServicesAccountFoundItConnected() {
        val notes = probe(ProbeConnection.Connected, MediaId.PROVIDER_SUBSONIC, ProbeLeft.AsFound)
            .connectionNotes(8_000L)

        assertEquals(
            listOf("active account is subsonic, not Spotify", "App Remote was already connected: left as it was"),
            notes
        )
    }

    @Test
    fun should_noteNothing_when_connectedOnASpotifyAccount() {
        val notes = probe(ProbeConnection.Connected, MediaId.PROVIDER_SPOTIFY, ProbeLeft.AsFound)
            .connectionNotes(8_000L)

        assertEquals(emptyList<String>(), notes)
    }

    @Test
    fun should_sayHowLongItWaited_when_theConnectTimedOut() {
        val notes = probe(ProbeConnection.TimedOut, MediaId.PROVIDER_SPOTIFY, ProbeLeft.NotConnected)
            .connectionNotes(8_000L)

        assertEquals(listOf("App Remote did not connect within 8 s: open Spotify and retry"), notes)
    }

    private fun probe(connection: ProbeConnection, account: String, left: ProbeLeft) =
        SpotifyLibraryStateProbe(connection, connectMs = 0L, readings = emptyList(), account = account, left = left)
}
