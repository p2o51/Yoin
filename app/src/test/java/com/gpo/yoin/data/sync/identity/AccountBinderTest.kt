package com.gpo.yoin.data.sync.identity

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.sync.ActiveBinding
import com.gpo.yoin.data.sync.SyncAccountStatus
import com.gpo.yoin.data.sync.SyncBindingEntity
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncLocalStateEntity
import com.gpo.yoin.data.sync.SyncMetaEntity
import com.gpo.yoin.data.sync.SyncMetaKeys
import com.gpo.yoin.data.sync.SyncRecordEntity
import com.gpo.yoin.data.sync.testing.SyncDomainFixtures
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountBinderTest {
    private val fixtures = SyncDomainFixtures()
    private val syncDao = fixtures.syncDb.syncDao()
    private val profiles = mutableListOf<Profile>()
    private val credentials = mutableMapOf<String, ProfileCredentials>()
    private val now = 5_000_000L

    // Unreachable base: Spotify ids in these tests come from the sync_meta cache or not at all.
    private val spotify = SpotifyIdentityResolver(fixtures.syncDb, OkHttpClient(), "http://127.0.0.1:1/v1/") { now }
    private val binder = AccountBinder(
        syncDb = fixtures.syncDb,
        profiles = { profiles.toList() },
        decodeCredentials = { credentials[it.id] },
        spotify = spotify,
        clock = { now },
    )

    @After
    fun tearDown() = fixtures.close()

    @Test
    fun should_bindDeterministicScope_when_subsonicProfileFingerprinted() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val expectedScope = AccountFingerprints.deterministicId(
            AccountFingerprints.subsonic("music.example.com", "alice")!!,
        )

        val first = binder.refresh(emptyList())
        val second = binder.refresh(emptyList())

        val binding = ActiveBinding("p1", expectedScope, "subsonic")
        assertEquals(listOf(binding), first.active)
        assertEquals(listOf(binding), first.newlyBound)
        assertEquals(listOf(binding), second.active)
        assertTrue(second.newlyBound.isEmpty())
        assertEquals(SyncAccountStatus.Syncing, first.rows.single().status)
        val stored = syncDao.bindingFor("p1")!!
        assertEquals(SyncBindingEntity.STATE_ACTIVE, stored.state)
        assertEquals(now, stored.boundAt)
    }

    @Test
    fun should_bindSpotifyFromCachedUserId_when_tokenExpired() = runTest {
        profiles += Profile("sp", "spotify", "Me", "store:v1", createdAt = 1L)
        credentials["sp"] = ProfileCredentials.Spotify("at", "rt", expiresAtEpochMs = 0L, scopes = emptyList())
        syncDao.putMeta(
            SyncMetaEntity(
                SyncMetaKeys.SPOTIFY_UID_PREFIX + "sp",
                "alice|" + SpotifyIdentityResolver.refreshTokenHash("rt"),
            ),
        )

        val snapshot = binder.refresh(emptyList())

        val scope = AccountFingerprints.deterministicId(AccountFingerprints.spotify("alice"))
        assertEquals(listOf(ActiveBinding("sp", scope, "spotify")), snapshot.active)
    }

    @Test
    fun should_reportNeedsIdentity_when_spotifyIdUnknown() = runTest {
        profiles += Profile("sp", "spotify", "Me", "store:v1", createdAt = 1L)
        credentials["sp"] = ProfileCredentials.Spotify("at", "rt", expiresAtEpochMs = 0L, scopes = emptyList())

        val snapshot = binder.refresh(emptyList())

        assertTrue(snapshot.active.isEmpty())
        assertEquals(SyncAccountStatus.NeedsIdentity, snapshot.rows.single().status)
        assertNull(syncDao.bindingFor("sp"))
    }

    @Test
    fun should_leaveNewerDuplicateUnbound_when_twoProfilesShareAnAccount() = runTest {
        addSubsonic("newer", "http://MUSIC.example.com/", "Alice", createdAt = 20L, name = "Second copy")
        addSubsonic("older", "https://music.example.com", "alice", createdAt = 10L, name = "Home")

        val snapshot = binder.refresh(emptyList())

        assertEquals(listOf("older"), snapshot.active.map { it.localProfileId })
        val statuses = snapshot.rows.associate { it.localProfileId to it.status }
        assertEquals(SyncAccountStatus.Syncing, statuses["older"])
        assertEquals(SyncAccountStatus.DuplicateOf("Home"), statuses["newer"])
        assertEquals(listOf("older", "newer"), snapshot.rows.map { it.localProfileId })
        assertNull(syncDao.bindingFor("newer"))
    }

    @Test
    fun should_neverAutoBindAppleMusic_when_refreshing() = runTest {
        addApple("apl")
        val cloudApple = CloudAccountDescriptor("a-cloud", "applemusic", "Apple Music", null, "us", "Phone")

        val snapshot = binder.refresh(listOf(cloudApple))

        assertTrue(snapshot.active.isEmpty())
        assertNull(syncDao.bindingFor("apl"))
        val status = snapshot.rows.single().status as SyncAccountStatus.NotSynced
        assertEquals(listOf("a-cloud"), status.linkCandidates.map { it.syncProfileId })
        assertEquals(listOf("a-cloud"), snapshot.cloudOnly.map { it.syncProfileId })
    }

    @Test
    fun should_bindRandomAppleScope_when_startSyncing() = runTest {
        addApple("apl")

        val binding = binder.startSyncing("apl")!!
        val snapshot = binder.refresh(emptyList())

        assertTrue(binding.syncProfileId.startsWith("a-"))
        assertEquals(listOf(binding), snapshot.active)
        assertNull(binder.startSyncing("apl"))
        assertEquals(SyncAccountStatus.Syncing, snapshot.rows.single().status)
    }

    @Test
    fun should_refuseStartSyncing_when_profileIsNotAppleMusic() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)

        assertNull(binder.startSyncing("p1"))
    }

    @Test
    fun should_linkAppleProfile_when_cloudAccountMatchesProvider() = runTest {
        addApple("apl")
        putAccountRecord("a-cloud", provider = "applemusic")
        putAccountRecord("s-other", provider = "subsonic")

        assertFalse(binder.link("apl", "s-other"))
        assertFalse(binder.link("apl", "a-missing"))
        assertTrue(binder.link("apl", "a-cloud"))
        val snapshot = binder.refresh(
            listOf(CloudAccountDescriptor("a-cloud", "applemusic", "Apple Music", null, null, "Phone")),
        )

        assertEquals(listOf(ActiveBinding("apl", "a-cloud", "applemusic")), snapshot.active)
        assertTrue(snapshot.cloudOnly.isEmpty())
        assertNull(syncDao.bindingFor("apl")!!.fingerprintHash)
    }

    @Test
    fun should_pauseOnMismatch_when_fingerprintChanges() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        binder.refresh(emptyList())
        credentials["p1"] = ProfileCredentials.Subsonic("https://music.example.com", "bob", "pw")

        val snapshot = binder.refresh(emptyList())

        assertTrue(snapshot.active.isEmpty())
        assertEquals(SyncAccountStatus.AccountChanged, snapshot.rows.single().status)
        val stored = syncDao.bindingFor("p1")!!
        assertEquals(SyncBindingEntity.STATE_PAUSED_MISMATCH, stored.state)
        val bobFingerprint = AccountFingerprints.subsonic("https://music.example.com", "bob")!!
        val bobHash = AccountFingerprints.fingerprintHash(bobFingerprint)
        assertEquals(bobHash, stored.observedFingerprintHash)
    }

    @Test
    fun should_resumeSameScope_when_keepAsBefore() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val scope = binder.refresh(emptyList()).active.single().syncProfileId
        credentials["p1"] = ProfileCredentials.Subsonic("https://music.example.org", "alice", "pw")
        binder.refresh(emptyList())

        assertTrue(binder.keepAsBefore("p1"))
        val snapshot = binder.refresh(emptyList())

        assertEquals(listOf(ActiveBinding("p1", scope, "subsonic")), snapshot.active)
        assertFalse(binder.keepAsBefore("p1"))
    }

    @Test
    fun should_resumeAutomatically_when_originalAccountReturns() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        binder.refresh(emptyList())
        credentials["p1"] = ProfileCredentials.Subsonic("https://music.example.com", "bob", "pw")
        binder.refresh(emptyList())
        credentials["p1"] = ProfileCredentials.Subsonic("https://music.example.com", "alice", "pw")

        val snapshot = binder.refresh(emptyList())

        assertEquals(1, snapshot.active.size)
        assertEquals(SyncBindingEntity.STATE_ACTIVE, syncDao.bindingFor("p1")!!.state)
    }

    @Test
    fun should_rebindToNewAccount_when_startFreshCompletes() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val oldScope = binder.refresh(emptyList()).active.single().syncProfileId
        syncDao.upsertLocalState(localState(oldScope, "n1", pending = false))
        credentials["p1"] = ProfileCredentials.Subsonic("https://music.example.com", "bob", "pw")
        binder.refresh(emptyList())

        val plan = binder.prepareStartFresh("p1")!!
        val done = binder.completeStartFresh(plan)

        val bobScope = AccountFingerprints.deterministicId(AccountFingerprints.subsonic("music.example.com", "bob")!!)
        assertEquals(oldScope, plan.oldScope)
        assertEquals(bobScope, plan.newSyncProfileId)
        assertEquals(0, plan.pendingChanges)
        assertTrue(done)
        assertTrue(syncDao.localStatesInScope(oldScope).isEmpty())
        assertEquals(listOf(ActiveBinding("p1", bobScope, "subsonic")), binder.refresh(emptyList()).active)
    }

    @Test
    fun should_refuseStartFresh_when_localChangesPending() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val oldScope = binder.refresh(emptyList()).active.single().syncProfileId
        syncDao.upsertLocalState(localState(oldScope, "n1", pending = true))
        credentials["p1"] = ProfileCredentials.Subsonic("https://music.example.com", "bob", "pw")
        binder.refresh(emptyList())

        val plan = binder.prepareStartFresh("p1")!!

        assertEquals(1, plan.pendingChanges)
        assertFalse(binder.completeStartFresh(plan))
        assertEquals(oldScope, syncDao.bindingFor("p1")!!.syncProfileId)
        assertNotNull(syncDao.getLocalState(SyncKinds.SONG_NOTE, oldScope, "n1"))
    }

    @Test
    fun should_unbindAndDropLocalState_when_profileRemoved() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val scope = binder.refresh(emptyList()).active.single().syncProfileId
        syncDao.upsertLocalState(localState(scope, "n1", pending = false))
        syncDao.upsertRecord(record(scope, "n1"))
        profiles.clear()

        val snapshot = binder.refresh(emptyList())

        assertTrue(snapshot.active.isEmpty())
        assertNull(syncDao.bindingFor("p1"))
        assertTrue(syncDao.localStatesInScope(scope).isEmpty())
        assertNotNull(syncDao.getRecord(SyncKinds.SONG_NOTE, scope, "n1"))
    }

    @Test
    fun should_forgetDescriptorState_when_scopeStopsBeingDescribed() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        addSubsonic("p2", "https://other.example.com", "bob", createdAt = 2L)
        val scopes = binder.refresh(emptyList()).active.associate { it.localProfileId to it.syncProfileId }
        val global = SyncFormat.GLOBAL_SCOPE
        syncDao.upsertLocalState(localState(scopes.getValue("p1"), "", pending = false).asDescriptor())
        syncDao.upsertLocalState(localState(scopes.getValue("p2"), "", pending = false).asDescriptor())
        syncDao.upsertLocalState(localState("s-unrelated", "", pending = false).asDescriptor())

        // p1 is removed, p2 now signs in as someone else: neither is described any more.
        profiles.removeAll { it.id == "p1" }
        credentials["p2"] = ProfileCredentials.Subsonic("https://other.example.com", "carol", "pw")
        binder.refresh(emptyList())

        assertNull(syncDao.getLocalState(SyncKinds.ACCOUNT, global, scopes.getValue("p1")))
        assertNull(syncDao.getLocalState(SyncKinds.ACCOUNT, global, scopes.getValue("p2")))
        assertNotNull(syncDao.getLocalState(SyncKinds.ACCOUNT, global, "s-unrelated"))
    }

    @Test
    fun should_keepBindingButSkip_when_credentialsMissing() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val scope = binder.refresh(emptyList()).active.single().syncProfileId
        credentials.remove("p1")

        val snapshot = binder.refresh(emptyList())

        assertTrue(snapshot.active.isEmpty())
        assertEquals(SyncAccountStatus.NeedsReconnect, snapshot.rows.single().status)
        assertEquals(scope, syncDao.bindingFor("p1")!!.syncProfileId)
    }

    @Test
    fun should_dropStaleLocalState_when_scopeBindsAgain() = runTest {
        val scope = AccountFingerprints.deterministicId(AccountFingerprints.subsonic("music.example.com", "alice")!!)
        syncDao.upsertLocalState(localState(scope, "leftover", pending = false))
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)

        binder.refresh(emptyList())

        assertTrue(syncDao.localStatesInScope(scope).isEmpty())
    }

    @Test
    fun should_listOnlyUnboundCloudAccounts_when_refreshing() = runTest {
        addSubsonic("p1", "https://music.example.com", "alice", createdAt = 1L)
        val scope = AccountFingerprints.deterministicId(AccountFingerprints.subsonic("music.example.com", "alice")!!)
        val cloud = listOf(
            CloudAccountDescriptor(scope, "subsonic", "Home", "alice @ music.example.com", null, "Phone"),
            CloudAccountDescriptor("s-elsewhere", "spotify", "Work", null, null, "Laptop"),
            CloudAccountDescriptor("s-elsewhere", "spotify", "Work (dup)", null, null, "Tablet"),
        )

        val snapshot = binder.refresh(cloud)

        assertEquals(listOf("s-elsewhere"), snapshot.cloudOnly.map { it.syncProfileId })
        assertEquals("Laptop", snapshot.cloudOnly.single().fromDeviceName)
    }

    private fun addSubsonic(id: String, url: String, user: String, createdAt: Long, name: String = id) {
        profiles += Profile(id, "subsonic", name, "store:v1", createdAt = createdAt)
        credentials[id] = ProfileCredentials.Subsonic(url, user, "pw")
    }

    private fun addApple(id: String) {
        profiles += Profile(id, "applemusic", "Apple Music", "store:v1", createdAt = 1L)
        credentials[id] = ProfileCredentials.AppleMusic("https://endpoint.example", "mut")
    }

    private suspend fun putAccountRecord(scope: String, provider: String) {
        syncDao.upsertRecord(
            SyncRecordEntity(
                kind = SyncKinds.ACCOUNT,
                scope = SyncFormat.GLOBAL_SCOPE,
                key = scope,
                kindVersion = 1,
                ts = 1L,
                device = "other",
                prevTs = null,
                prevDevice = null,
                deleted = false,
                payload = """{"provider":"$provider","displayName":"X","createdAt":1,"deviceName":"Phone"}""",
                payloadHash = "h",
            ),
        )
    }

    private fun localState(scope: String, key: String, pending: Boolean) = SyncLocalStateEntity(
        kind = SyncKinds.SONG_NOTE,
        scope = scope,
        key = key,
        boundProfileId = "p1",
        localHash = "h",
        appliedTs = 1L,
        appliedDevice = "d",
        pending = pending,
        pendingPrevTs = null,
        pendingPrevDevice = null,
    )

    /** The global account-descriptor state for the scope this state's [SyncLocalStateEntity.scope] names. */
    private fun SyncLocalStateEntity.asDescriptor() =
        copy(kind = SyncKinds.ACCOUNT, scope = SyncFormat.GLOBAL_SCOPE, key = scope, boundProfileId = "")

    private fun record(scope: String, key: String) = SyncRecordEntity(
        kind = SyncKinds.SONG_NOTE,
        scope = scope,
        key = key,
        kindVersion = 1,
        ts = 1L,
        device = "d",
        prevTs = null,
        prevDevice = null,
        deleted = false,
        payload = "{}",
        payloadHash = "h",
    )
}
