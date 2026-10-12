package com.gpo.yoin.data.profile

import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.local.ProfileDao
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The startup sweep ProfileManager's create() promises (TD-1: three orphan
 * `.bin` files, an old Spotify token among them, outlived dozens of cold
 * starts): the credentials of profile ids no row names go; an existing
 * profile's — readable or not — stay; and a create() under way, secret
 * written and row still to come, is waited for, not swept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagerOrphanCredentialsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun should_deleteTheCredentialsNoProfileNames_when_theAppStarts() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryProfileCredentialsStore()
        val dao = InMemoryProfileDao()
        dao.upsert(row(KEPT))
        store.write(KEPT, subsonic())
        // A row lost without a delete (a Room wipe, a create cut short).
        store.write(ORPHAN, ProfileCredentials.Spotify("access", "refresh", 1L, listOf("user-read-private")))
        val manager = manager(scope, dao, store)

        manager.runStartupMigrations()
        scope.advanceUntilIdle()

        assertEquals(setOf(KEPT), store.storedProfileIds())
        assertEquals(subsonic(), store.snapshot(KEPT))
        scope.cancelChildren()
    }

    @Test
    fun should_keepAnExistingProfilesFilesAndWhatIsNotTheStores_when_theAppStarts() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val dir = tempFolder.newFolder("profile_credentials")
        val store = FileBackedProfileCredentialsStore(
            storageDir = dir,
            codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())
        )
        val dao = InMemoryProfileDao()
        dao.upsert(row(KEPT))
        dao.upsert(row(BROKEN))
        store.write(KEPT, subsonic())
        // An existing profile's secret that won't read (its key didn't come along).
        File(dir, "$BROKEN.bin").writeText("not an envelope")
        store.write(ORPHAN, subsonic())
        File(dir, "$ORPHAN_CUT_SHORT.bin.tmp").writeText("half a write")
        File(dir, "$KEPT.bin.qahidden").writeText("QA's copy")
        val manager = manager(scope, dao, store)

        manager.runStartupMigrations()
        scope.advanceUntilIdle()

        assertEquals(setOf(KEPT, BROKEN), store.storedProfileIds())
        assertEquals(subsonic(), store.read(KEPT))
        assertTrue(File(dir, "$BROKEN.bin").exists())
        assertTrue(File(dir, "$KEPT.bin.qahidden").exists())
        scope.cancelChildren()
    }

    @Test
    fun should_leaveACreatesSecret_when_theSweepRunsBeforeItsRowIsIn() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryProfileCredentialsStore()
        val dao = InMemoryProfileDao()
        val manager = manager(scope, dao, store)
        scope.advanceUntilIdle()

        // A create() whose secret is written and whose row is still on its way.
        dao.holdUpserts = CompletableDeferred()
        val created = CompletableDeferred<Profile>()
        scope.launch { created.complete(manager.create("qa", subsonic())) }
        scope.runCurrent()
        assertEquals(1, store.size)

        // The launch's sweep meanwhile: it waits for that row.
        val swept = scope.launch { manager.runStartupMigrations() }
        scope.runCurrent()
        assertEquals(1, store.size)

        dao.holdUpserts?.complete(Unit)
        scope.advanceUntilIdle()

        assertTrue(swept.isCompleted)
        val profile = created.await()
        assertEquals(setOf(profile.id), store.storedProfileIds())
        assertEquals(subsonic(), store.snapshot(profile.id))
        scope.cancelChildren()
    }

    @Test
    fun should_leaveEverything_when_theProfilesCannotBeRead() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = InMemoryProfileCredentialsStore()
        val dao = InMemoryProfileDao()
        dao.upsert(row(KEPT))
        store.write(KEPT, subsonic())
        store.write(ORPHAN, subsonic())
        val manager = manager(scope, dao, store)
        scope.advanceUntilIdle()
        dao.failReads = true

        manager.pruneOrphanCredentials()
        scope.advanceUntilIdle()

        // No list of rows to sweep against: nothing goes (the next launch tries again).
        assertEquals(setOf(KEPT, ORPHAN), store.storedProfileIds())
        scope.cancelChildren()
    }

    private fun manager(scope: CoroutineScope, dao: ProfileDao, store: ProfileCredentialsStore) = ProfileManager(
        profileDao = dao,
        activeIdStore = InMemoryActiveIdStore(),
        credentialsStore = store,
        legacyCodec = PlaintextProfileCredentialsCodec(),
        scope = scope
    )

    private fun row(id: String) = Profile(
        id = id,
        provider = "subsonic",
        displayName = id,
        credentialsJson = ProfileManager.STORE_MARKER_V1,
        createdAt = 0L
    )

    private fun subsonic() = ProfileCredentials.Subsonic("https://music.example.test", "qa", "pw")

    private fun CoroutineScope.cancelChildren() {
        coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    // ── fakes ──────────────────────────────────────────────────────────

    private class InMemoryProfileDao : ProfileDao {
        private val byId = linkedMapOf<String, Profile>()
        private val flow = MutableStateFlow<List<Profile>>(emptyList())

        /** While set and not complete, an upsert waits on it (a Room write still on its way). */
        var holdUpserts: CompletableDeferred<Unit>? = null

        /** One-shot reads fail (a database that can't be opened). */
        var failReads = false

        override fun observeAll(): Flow<List<Profile>> = flow.asStateFlow()
        override suspend fun getAll(): List<Profile> {
            check(!failReads) { "database unavailable" }
            return byId.values.toList()
        }
        override suspend fun getById(id: String): Profile? {
            check(!failReads) { "database unavailable" }
            return byId[id]
        }
        override suspend fun count(): Int = byId.size
        override suspend fun upsert(profile: Profile) {
            holdUpserts?.await()
            byId[profile.id] = profile
            flow.value = byId.values.toList()
        }
        override suspend fun delete(profile: Profile) {
            byId.remove(profile.id)
            flow.value = byId.values.toList()
        }
        override suspend fun deleteById(id: String) {
            byId.remove(id)
            flow.value = byId.values.toList()
        }
    }

    private class InMemoryActiveIdStore : ProfileActiveIdStore {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(id: String?) {
            value = id
        }
    }

    private companion object {
        const val KEPT = "kept"
        const val BROKEN = "broken"
        const val ORPHAN = "orphan"
        const val ORPHAN_CUT_SHORT = "orphan-cut-short"
    }
}
