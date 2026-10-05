package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.HomeLayoutPreference
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.LyricsCache
import com.gpo.yoin.data.local.LyricsTranslationCache
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.SpotifyConfig
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.testing.FakeSeamStyleGateway
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Nothing secret may ever reach a projection or a decorated payload, whatever the domain holds. */
@RunWith(RobolectricTestRunner::class)
class SyncSecretSentinelTest {
    private val fixtures = SyncDomainFixtures()
    private val db = fixtures.db

    private val credentials = mapOf(
        "sub" to ProfileCredentials.Subsonic(
            serverUrl = "https://SENTINEL_URL_USER:SENTINEL_URL_PASS@music.example.com/navi/" +
                "?t=SENTINEL_QUERY#SENTINEL_FRAG",
            username = "alice",
            password = "SENTINEL_SUBSONIC_PASSWORD",
        ),
        "spo" to ProfileCredentials.Spotify(
            accessToken = "SENTINEL_SPOTIFY_ACCESS",
            refreshToken = "SENTINEL_SPOTIFY_REFRESH",
            expiresAtEpochMs = Long.MAX_VALUE,
            scopes = listOf("user-read-private"),
        ),
        "apl" to ProfileCredentials.AppleMusic(
            endpoint = "https://SENTINEL_APPLE_ENDPOINT.example/token",
            musicUserToken = "SENTINEL_APPLE_USER_TOKEN",
        ),
    )

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_neverExposeSecrets_when_readingAndDecoratingEveryKind() = runTest {
        seedDomain()
        val adapters = YoinSyncAdapters.create(
            db = db,
            syncDb = fixtures.syncDb,
            seam = FakeSeamStyleGateway(stored = "dots"),
            decodeCredentials = { credentials[it.id] },
            storefront = { "us" },
        )
        assertEquals(
            setOf(
                SyncKinds.SONG_NOTE, SyncKinds.TRACK_RATING, SyncKinds.ALBUM_RATING, SyncKinds.ALBUM_REVIEW,
                SyncKinds.HOME_LAYOUT, SyncKinds.SETTING, SyncKinds.LYRICS_TRANSLATION,
                SyncKinds.MEMORY_COPY, SyncKinds.MEMORY_TITLE, SyncKinds.SONG_ABOUT, SyncKinds.ACCOUNT,
            ),
            adapters.map { it.kind }.toSet(),
        )

        val published = StringBuilder()
        var rowCount = 0
        for (adapter in adapters) {
            val scopes = if (adapter.perAccount) credentials.keys.toList() else listOf(null)
            for (localProfileId in scopes) {
                for (row in adapter.readAll(localProfileId)) {
                    rowCount++
                    published.append(CanonicalJson.canonical(row.projection)).append('\n')
                    published.append(row.key).append('\n')
                    published.append(CanonicalJson.canonical(adapter.decoratePayload(row.projection, "Pixel Tablet")))
                    published.append('\n')
                }
            }
        }

        // Every kind produced something, so the scan below is meaningful.
        assertTrue(rowCount >= 3 * 5 + 3 + 1 + 3)
        assertTrue(published.contains("alice @ music.example.com"))
        assertTrue(published.contains("first line"))
        assertFalse("secret leaked: $published", published.contains("SENTINEL"))
    }

    private suspend fun seedDomain() {
        val dao = db.syncDomainDao()
        for ((id, provider) in listOf("sub" to "subsonic", "spo" to "spotify", "apl" to "applemusic")) {
            // A legacy inline blob in credentialsJson must never travel either.
            db.profileDao().upsert(Profile(id, provider, "Name $id", "SENTINEL_LEGACY_BLOB", createdAt = 1L))
            fixtures.syncDb.syncDao().upsertBinding(
                SyncBindingEntity(id, "scope-$id", provider, "fp", SyncBindingEntity.STATE_ACTIVE, null, 1L),
            )
            dao.writeNote(
                SongNote("note-$id", id, "trk", provider, "note text", 1L, 2L, "Title", "Artist", 3_000L),
            )
            dao.upsertTrackRating(LocalRating(id, "trk", provider, 8f, 4, needsSync = true, updatedAt = 1L))
            dao.insertAlbumRatingIfAbsent(
                AlbumRating(id, "alb", provider, 7f, "review text", "SENTINEL_NEODB_UUID", true, true, 1L),
            )
            dao.upsertHomeLayout(HomeLayoutPreference(id, "{\"sections\":[]}", 1L))
        }
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "SENTINEL_GEMINI_KEY", targetLanguage = "Japanese"))
        db.spotifyConfigDao().upsert(SpotifyConfig(clientId = "public-client-id"))
        dao.insertTranslationIfAbsent(
            LyricsTranslationCache("spotify", "trk", "h", "Japanese", "gemini-3.1-flash-lite", "[\"一\"]", 10L),
        )
        dao.writeLyricsCache(LyricsCache("spotify", "trk", "qq", "42", "[00:01.00]first line", 5L))
    }
}
