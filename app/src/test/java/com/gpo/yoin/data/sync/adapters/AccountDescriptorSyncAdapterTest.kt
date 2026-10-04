package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.RecordVersion
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncRecord
import com.gpo.yoin.data.sync.SyncRecordEntity
import com.gpo.yoin.data.sync.identity.CloudAccountDescriptor
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import com.gpo.yoin.data.sync.testing.hashOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountDescriptorSyncAdapterTest {
    private val fixtures = SyncDomainFixtures()
    private val profiles = listOf(
        Profile("sub", "subsonic", "Home server", "store:v1", createdAt = 10L),
        Profile("apple", "applemusic", "Apple Music", "store:v1", createdAt = 20L),
        Profile("paused", "subsonic", "Old", "store:v1", createdAt = 30L),
    )
    private val credentials = mapOf(
        "sub" to ProfileCredentials.Subsonic("http://Alice:pw@Music.Example.com:4533/navidrome/?x=1", "Alice", "pw"),
        "apple" to ProfileCredentials.AppleMusic("https://endpoint.example/token", "mut"),
        "paused" to ProfileCredentials.Subsonic("https://old.example.com", "bob", "pw"),
    )
    private var credentialsReadable = true
    private var appleStorefront: String? = "jp"
    private val adapter = AccountDescriptorSyncAdapter(
        syncDb = fixtures.syncDb,
        profiles = { profiles },
        decodeCredentials = { if (credentialsReadable) credentials[it.id] else null },
        storefront = { id -> if (id == "apple") appleStorefront else null },
    )

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_describeActiveBindingsOnly_when_reading() = runTest {
        bind("sub", "s-sub")
        bind("apple", "a-apple")
        bind("paused", "s-old", state = SyncBindingEntity.STATE_PAUSED_MISMATCH)

        val rows = adapter.readAll(null).associateBy { it.key }

        assertEquals(setOf("s-sub", "a-apple"), rows.keys)
        val subsonic = rows.getValue("s-sub").projection
        assertEquals("Alice @ music.example.com:4533", subsonic["hint"]!!.jsonPrimitive.content)
        assertEquals("Home server", subsonic["displayName"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, subsonic["storefront"])
        val apple = rows.getValue("a-apple").projection
        assertEquals("jp", apple["storefront"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, apple["hint"])
    }

    @Test
    fun should_keepProjectionStable_when_deviceNameDecorated() = runTest {
        bind("sub", "s-sub")
        val row = adapter.readAll(null).single()

        val decorated = adapter.decoratePayload(row.projection, "Pixel Tablet")

        assertEquals("Pixel Tablet", decorated["deviceName"]!!.jsonPrimitive.content)
        assertEquals(hashOf(row.projection), hashOf(adapter.project(decorated)))
    }

    @Test
    fun should_onlyAcknowledge_when_applyingRemoteDescriptor() = runTest {
        bind("sub", "s-sub")
        val row = adapter.readAll(null).single()
        val remote = adapter.decoratePayload(row.projection, "Phone")

        assertEquals(
            ApplyOutcome.Skipped(SkipReason.POLICY, hashOf(row.projection)),
            adapter.apply(null, "s-sub", remote, 1L, expectedLocalHash = null),
        )
        assertEquals(
            ApplyOutcome.Skipped(SkipReason.POLICY, null),
            adapter.apply(null, "s-unknown", remote, 1L, expectedLocalHash = null),
        )
    }

    @Test
    fun should_parseCloudDescriptor_when_readingAccountRecord() = runTest {
        bind("sub", "s-sub")
        val decorated = adapter.decoratePayload(adapter.readAll(null).single().projection, "Phone")
        val record = SyncRecord(
            kind = SyncKinds.ACCOUNT,
            kindVersion = 1,
            scope = SyncFormat.GLOBAL_SCOPE,
            key = "s-sub",
            version = RecordVersion(1L, "dev"),
            prev = null,
            deleted = false,
            payload = CanonicalJson.canonical(decorated),
        )

        val descriptor = CloudAccountDescriptor.fromRecord(record)!!

        assertEquals("s-sub", descriptor.syncProfileId)
        assertEquals("subsonic", descriptor.provider)
        assertEquals("Alice @ music.example.com:4533", descriptor.hint)
        assertEquals("Phone", descriptor.deviceName)
        assertNull(CloudAccountDescriptor.fromRecord(record.copy(deleted = true, payload = null)))
        assertNull(CloudAccountDescriptor.fromRecord(record.copy(kind = SyncKinds.SETTING)))
    }

    @Test
    fun should_keepPublishedHint_when_credentialsUnreadableThisCycle() = runTest {
        bind("sub", "s-sub")
        val published = adapter.readAll(null).single()
        publish("s-sub", adapter.decoratePayload(published.projection, "Pixel Tablet"))
        credentialsReadable = false

        val row = adapter.readAll(null).single()

        assertEquals("s-sub", row.key)
        assertEquals(hashOf(published.projection), hashOf(row.projection))
    }

    @Test
    fun should_waitForCredentials_when_nothingPublishedYet() = runTest {
        bind("sub", "s-sub")
        credentialsReadable = false

        assertEquals(emptyList<Any>(), adapter.readAll(null))
    }

    @Test
    fun should_keepPublishedStorefront_when_storefrontNotLoadedInThisProcess() = runTest {
        bind("apple", "a-apple")
        val published = adapter.readAll(null).single()
        publish("a-apple", adapter.decoratePayload(published.projection, "Pixel Tablet"))
        appleStorefront = null

        val row = adapter.readAll(null).single()

        assertEquals(hashOf(published.projection), hashOf(row.projection))
        assertEquals("jp", row.projection["storefront"]!!.jsonPrimitive.content)
    }

    private suspend fun publish(key: String, payload: JsonObject) {
        fixtures.syncDb.syncDao().upsertRecord(
            SyncRecordEntity(
                kind = SyncKinds.ACCOUNT,
                scope = SyncFormat.GLOBAL_SCOPE,
                key = key,
                kindVersion = 1,
                ts = 10L,
                device = "this-device",
                prevTs = null,
                prevDevice = null,
                deleted = false,
                payload = payload.toString(),
                payloadHash = CanonicalJson.hash(payload),
            ),
        )
    }

    private suspend fun bind(localProfileId: String, scope: String, state: String = SyncBindingEntity.STATE_ACTIVE) {
        fixtures.syncDb.syncDao().upsertBinding(
            SyncBindingEntity(
                localProfileId = localProfileId,
                syncProfileId = scope,
                provider = profiles.first { it.id == localProfileId }.provider,
                fingerprintHash = null,
                state = state,
                observedFingerprintHash = null,
                boundAt = 1L,
            ),
        )
    }
}
