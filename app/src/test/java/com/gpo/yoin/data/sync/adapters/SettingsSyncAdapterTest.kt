package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SpotifyConfig
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncLocalStateEntity
import com.gpo.yoin.data.sync.SyncSettingKeys
import com.gpo.yoin.data.sync.testing.FakeSeamStyleGateway
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import com.gpo.yoin.data.sync.testing.payload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val seam = FakeSeamStyleGateway()
    private val adapter = SettingsSyncAdapter(fixtures.db, seam)
    private val db = fixtures.db

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_captureNothing_when_everySettingIsDefault() = runTest {
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = GeminiConfig.DEFAULT_TARGET_LANGUAGE))
        db.spotifyConfigDao().upsert(SpotifyConfig(clientId = "  "))

        assertTrue(adapter.readAll(null).isEmpty())
    }

    @Test
    fun should_captureExplicitValues_when_userSetThem() = runTest {
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "Japanese"))
        db.spotifyConfigDao().upsert(SpotifyConfig(clientId = "client-123"))
        seam.stored = "dots"

        val rows = adapter.readAll(null).associateBy { it.key }

        assertEquals(setOf(LANGUAGE, CLIENT_ID, SEAM), rows.keys)
        assertEquals(hashOf(adapter.project(value("Japanese"))), hashOf(rows.getValue(LANGUAGE).projection))
        assertNull(rows.getValue(SyncSettingKeys.SEAM_TOP_STYLE).rowTs)
    }

    @Test
    fun should_applyLanguageAndKeepApiKey_when_noAboutEntriesExist() = runTest {
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "secret-key", targetLanguage = "English"))

        val outcome = adapter.apply(null, LANGUAGE, value("Korean"), 1L, null)

        val stored = db.geminiConfigDao().getConfig().first()!!
        assertEquals("Korean", stored.targetLanguage)
        assertEquals("secret-key", stored.apiKey)
        assertEquals(ApplyOutcome.Applied(hashOf(adapter.project(value("Korean")))), outcome)
        assertEquals(hashOf(adapter.project(value("Korean"))), hashOf(adapter.readAll(null).single().projection))
    }

    @Test
    fun should_insertRowWithEmptyApiKey_when_geminiConfigAbsent() = runTest {
        adapter.apply(null, LANGUAGE, value("French"), 1L, null)

        val stored = db.geminiConfigDao().getConfig().first()!!
        assertEquals("French", stored.targetLanguage)
        assertEquals("", stored.apiKey)
    }

    @Test
    fun should_skipLanguageByPolicy_when_aboutEntriesExist() = runTest {
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "German"))
        db.songAboutEntryDao().upsert(aboutEntry())
        val current = hashOf(adapter.readAll(null).single().projection)

        val outcome = adapter.apply(null, LANGUAGE, value("Korean"), 1L, current)

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, current), outcome)
        assertEquals("German", db.geminiConfigDao().getConfig().first()!!.targetLanguage)
        assertEquals(1, db.syncDomainDao().songAboutEntryCount())
    }

    @Test
    fun should_skipByPolicy_when_languageUnknown() = runTest {
        val outcome = adapter.apply(null, LANGUAGE, value("Klingon"), 1L, null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, null), outcome)
        assertNull(db.geminiConfigDao().getConfig().first())
    }

    @Test
    fun should_skipChangedLocally_when_languageSetHereMeanwhile() = runTest {
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "Spanish"))

        val outcome = adapter.apply(null, LANGUAGE, value("Korean"), 1L, null)

        assertTrue((outcome as ApplyOutcome.Skipped).reason == SkipReason.CHANGED_LOCALLY)
    }

    @Test
    fun should_bootstrapClientId_when_noOverrideAndNoSpotifyAccount() = runTest {
        val outcome = adapter.apply(null, CLIENT_ID, value("client-abc"), 1L, null)

        assertEquals("client-abc", db.spotifyConfigDao().getConfig().first()?.clientId)
        assertEquals(ApplyOutcome.Applied(hashOf(adapter.project(value("client-abc")))), outcome)
    }

    @Test
    fun should_skipClientIdByPolicy_when_spotifyAccountExists() = runTest {
        db.profileDao().upsert(Profile(id = "sp", provider = "spotify", displayName = "me", credentialsJson = "x"))

        val outcome = adapter.apply(null, CLIENT_ID, value("client-abc"), 1L, null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, null), outcome)
        assertNull(db.spotifyConfigDao().getConfig().first())
    }

    @Test
    fun should_skipClientIdByPolicy_when_localOverrideExists() = runTest {
        db.spotifyConfigDao().upsert(SpotifyConfig(clientId = "mine"))
        val current = hashOf(adapter.readAll(null).single().projection)

        val outcome = adapter.apply(null, CLIENT_ID, value("theirs"), 1L, current)

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, current), outcome)
        assertEquals("mine", db.spotifyConfigDao().getConfig().first()?.clientId)
    }

    @Test
    fun should_applySeamThroughGateway_when_keyKnown() = runTest {
        val outcome = adapter.apply(null, SEAM, value("cookie"), 1L, null)

        assertEquals(listOf("cookie"), seam.applied)
        assertEquals(ApplyOutcome.Applied(hashOf(adapter.project(value("cookie")))), outcome)
        assertEquals(hashOf(adapter.project(value("cookie"))), hashOf(adapter.readAll(null).single().projection))
    }

    @Test
    fun should_skipSeamByPolicy_when_keyUnknown() = runTest {
        seam.stored = "tide"
        val current = hashOf(adapter.readAll(null).single().projection)

        val outcome = adapter.apply(null, SEAM, value("lava"), 1L, current)

        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, current), outcome)
        assertTrue(seam.applied.isEmpty())
        assertEquals("tide", seam.stored)
    }

    @Test
    fun should_captureDefaultLanguage_when_userSwitchesBackWhileTracked() = runTest {
        val trackedAdapter = SettingsSyncAdapter(db, seam, tracked = { it == LANGUAGE })
        // What SettingsViewModel.saveGeminiTargetLanguage leaves behind after Japanese -> English.
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = GeminiConfig.DEFAULT_TARGET_LANGUAGE))

        val row = trackedAdapter.readAll(null).single()

        assertEquals(LANGUAGE, row.key)
        assertEquals(hashOf(adapter.project(value("English"))), hashOf(row.projection))
    }

    @Test
    fun should_readBackDefaultLanguage_when_defaultAppliedWhileTracked() = runTest {
        val trackedAdapter = SettingsSyncAdapter(db, seam, tracked = { it == LANGUAGE })
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "secret-key", targetLanguage = "Japanese"))
        val japanese = hashOf(trackedAdapter.readAll(null).single().projection)

        val outcome = trackedAdapter.apply(null, LANGUAGE, value("English"), 1L, expectedLocalHash = japanese)

        val english = hashOf(adapter.project(value("English")))
        assertEquals(ApplyOutcome.Applied(english), outcome)
        assertEquals(english, hashOf(trackedAdapter.readAll(null).single().projection))
        assertEquals("secret-key", db.geminiConfigDao().getConfig().first()!!.apiKey)
    }

    @Test
    fun should_notRestoreOldLanguage_when_userSwitchedBackToDefault() = runTest {
        val trackedAdapter = SettingsSyncAdapter(db, seam, tracked = { it == LANGUAGE })
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = "Japanese"))
        val japanese = hashOf(trackedAdapter.readAll(null).single().projection)
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = GeminiConfig.DEFAULT_TARGET_LANGUAGE))

        // The engine re-applies the replica's Japanese with the hash it last stored.
        val outcome = trackedAdapter.apply(null, LANGUAGE, value("Japanese"), 1L, expectedLocalHash = japanese)

        assertEquals(SkipReason.CHANGED_LOCALLY, (outcome as ApplyOutcome.Skipped).reason)
        assertEquals(GeminiConfig.DEFAULT_TARGET_LANGUAGE, db.geminiConfigDao().getConfig().first()!!.targetLanguage)
    }

    @Test
    fun should_captureClearedClientIdAndRefuseBootstrap_when_overrideClearedWhileTracked() = runTest {
        val trackedAdapter = SettingsSyncAdapter(db, seam, tracked = { it == CLIENT_ID })
        db.spotifyConfigDao().upsert(SpotifyConfig(clientId = ""))

        val row = trackedAdapter.readAll(null).single()
        val outcome = trackedAdapter.apply(null, CLIENT_ID, value("old-client"), 1L, hashOf(row.projection))

        assertEquals(hashOf(adapter.project(value(""))), hashOf(row.projection))
        assertEquals(ApplyOutcome.Skipped(SkipReason.POLICY, hashOf(row.projection)), outcome)
        assertEquals("", db.spotifyConfigDao().getConfig().first()?.clientId)
    }

    @Test
    fun should_trackFromSyncLocalState_when_builtByFactory() = runTest {
        val settings = YoinSyncAdapters.create(
            db = db,
            syncDb = fixtures.syncDb,
            seam = seam,
            decodeCredentials = { null },
        ).single { it.kind == SyncKinds.SETTING }
        db.geminiConfigDao().upsert(GeminiConfig(apiKey = "k", targetLanguage = GeminiConfig.DEFAULT_TARGET_LANGUAGE))
        assertTrue(settings.readAll(null).isEmpty())

        fixtures.syncDb.syncDao().upsertLocalState(
            SyncLocalStateEntity(
                kind = SyncKinds.SETTING,
                scope = SyncFormat.GLOBAL_SCOPE,
                key = LANGUAGE,
                boundProfileId = "",
                localHash = hashOf(adapter.project(value("Japanese"))),
                appliedTs = 1L,
                appliedDevice = "d",
                pending = false,
                pendingPrevTs = null,
                pendingPrevDevice = null,
            ),
        )

        assertEquals(hashOf(adapter.project(value("English"))), hashOf(settings.readAll(null).single().projection))
    }

    @Test
    fun should_skipUnsupported_when_settingKeyUnknown() = runTest {
        val outcome = adapter.apply(null, "volume", value("11"), 1L, null)

        assertEquals(ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null), outcome)
    }

    private companion object {
        const val LANGUAGE = SyncSettingKeys.TRANSLATION_LANGUAGE
        const val CLIENT_ID = SyncSettingKeys.SPOTIFY_CLIENT_ID
        const val SEAM = SyncSettingKeys.SEAM_TOP_STYLE
    }

    private fun value(v: String) = payload("""{"value":"$v"}""")

    private fun aboutEntry() = SongAboutEntry(
        titleKey = "t",
        artistKey = "a",
        albumKey = "al",
        titleDisplay = "T",
        artistDisplay = "A",
        albumDisplay = "AL",
        kind = SongAboutEntry.KIND_ASK,
        entryKey = "why",
        promptText = "Why?",
        titleText = null,
        answerText = "Because.",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
