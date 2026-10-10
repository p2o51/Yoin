package com.gpo.yoin.data.home

import com.gpo.yoin.data.cache.PlaylistDto
import com.gpo.yoin.data.model.MediaId
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSnapshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val directory: File by lazy { File(temp.root, HomeSnapshotStore.DIRECTORY_NAME) }

    private fun TestScope.store(): HomeSnapshotStore = HomeSnapshotStore(
        directory = { directory },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime + 1_000_000L }
    )

    private fun feed(vararg playlistNames: String): HomeFeedDto = HomeFeedDto(
        playlists = playlistNames.map { name ->
            PlaylistDto(id = MediaId.subsonic("pl-$name").toString(), name = name, coverArt = "cover-$name")
        }
    )

    private fun TestScope.settle() {
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + 1)
        runCurrent()
    }

    private fun fileOf(profileId: String) = File(directory, "$profileId.json")

    @Test
    fun should_readTheFeedBack_when_itWasSaved() = runTest {
        val store = store()
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("Morning") }
        settle()

        val snapshot = requireNotNull(store.read(PROFILE))
        assertEquals(PROFILE, snapshot.profileId)
        assertEquals(MediaId.PROVIDER_SUBSONIC, snapshot.provider)
        assertEquals(feed("Morning"), snapshot.feed)
        // A fresh store (a new process) reads the same file.
        assertEquals(feed("Morning"), store().read(PROFILE)?.feed)
    }

    @Test
    fun should_readNothing_when_noSnapshotWasSaved() = runTest {
        assertNull(store().read(PROFILE))
    }

    @Test
    fun should_dropAndDeleteTheFile_when_itIsCorrupt() = runTest {
        directory.mkdirs()
        fileOf(PROFILE).writeText("{\"version\":1,\"profileId\":\"$PROFILE\",\"feed\":{\"playl")

        assertNull(store().read(PROFILE))
        assertFalse(fileOf(PROFILE).exists())
    }

    @Test
    fun should_dropAndDeleteTheFile_when_itIsNotAnObject() = runTest {
        directory.mkdirs()
        fileOf(PROFILE).writeText("[1, 2, 3]")

        assertNull(store().read(PROFILE))
        assertFalse(fileOf(PROFILE).exists())
    }

    @Test
    fun should_dropTheSnapshot_when_itsFormatVersionDiffers() = runTest {
        val store = store()
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("Morning") }
        settle()
        val written = fileOf(PROFILE).readText()
        fileOf(PROFILE).writeText(written.replace("\"version\":1", "\"version\":99"))

        assertNull(store.read(PROFILE))
        assertFalse(fileOf(PROFILE).exists())
    }

    @Test
    fun should_dropTheSnapshot_when_theFileIsAnotherProfiles() = runTest {
        val store = store()
        store.save(OTHER, MediaId.PROVIDER_SUBSONIC) { feed("Theirs") }
        settle()
        fileOf(OTHER).copyTo(fileOf(PROFILE))

        assertNull(store.read(PROFILE))
        assertFalse(fileOf(PROFILE).exists())
        assertEquals(feed("Theirs"), store.read(OTHER)?.feed)
    }

    @Test
    fun should_conflateAndCapWrites_when_theFeedKeepsChanging() = runTest {
        val store = store()
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("1") }
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("2") }
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS - 1)
        runCurrent()
        // A burst settles first: nothing on disk yet.
        assertFalse(fileOf(PROFILE).exists())
        advanceTimeBy(2)
        runCurrent()
        // ...then only its latest lands.
        assertEquals(feed("2"), store.read(PROFILE)?.feed)

        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("3") }
        advanceTimeBy(HomeSnapshotStore.MIN_WRITE_INTERVAL_MS - 1)
        runCurrent()
        // Within the interval after a write, the next one waits.
        assertEquals(feed("2"), store.read(PROFILE)?.feed)
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + 2)
        runCurrent()
        assertEquals(feed("3"), store.read(PROFILE)?.feed)
    }

    @Test
    fun should_writeAgain_when_aSaveArrivesAfterTheWriterFinished() = runTest {
        val store = store()
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("1") }
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + HomeSnapshotStore.MIN_WRITE_INTERVAL_MS + 10)
        runCurrent()

        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("2") }
        settle()
        assertEquals(feed("2"), store.read(PROFILE)?.feed)
    }

    @Test
    fun should_buildTheFeedOnlyForTheWriteThatLands_when_savesConflate() = runTest {
        val store = store()
        var builds = 0
        repeat(5) { index ->
            store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) {
                builds++
                feed("$index")
            }
        }
        settle()
        assertEquals(1, builds)
        assertEquals(feed("4"), store.read(PROFILE)?.feed)
    }

    @Test
    fun should_deleteTheFileAndTheQueuedWrite_when_theProfileIsDeleted() = runTest {
        val store = store()
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("1") }
        settle()
        assertTrue(fileOf(PROFILE).exists())
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("2") }

        store.delete(PROFILE)
        // A publish already on its way can't write it back either.
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("3") }
        advanceTimeBy(HomeSnapshotStore.SETTLE_MS + HomeSnapshotStore.MIN_WRITE_INTERVAL_MS * 2)
        runCurrent()

        assertFalse(fileOf(PROFILE).exists())
        assertNull(store.read(PROFILE))
    }

    @Test
    fun should_keepOtherProfilesSnapshots_when_oneIsDeleted() = runTest {
        val store = store()
        store.save(PROFILE, MediaId.PROVIDER_SUBSONIC) { feed("Mine") }
        store.save(OTHER, MediaId.PROVIDER_SPOTIFY) { feed("Theirs") }
        settle()

        store.delete(PROFILE)

        assertNull(store.read(PROFILE))
        assertEquals(MediaId.PROVIDER_SPOTIFY, store.read(OTHER)?.provider)
    }

    @Test
    fun should_nameTheFileByAHash_when_theProfileIdIsNotAPlainId() = runTest {
        val store = store()
        val odd = "../profile one"
        store.save(odd, MediaId.PROVIDER_SUBSONIC) { feed("Odd") }
        settle()

        assertEquals(feed("Odd"), store.read(odd)?.feed)
        val names = directory.list().orEmpty().toList()
        assertEquals(listOf("${HomeSnapshotStore.fileStem(odd)}.json"), names)
        assertTrue(names.single().matches(Regex("[0-9a-f]{64}\\.json")))
    }

    @Test
    fun should_keepOnlyCredentialFreeCovers_when_aKeyIsAUrl() {
        assertEquals("al-1", credentialFreeCoverKey("al-1"))
        assertEquals("https://i.scdn.co/image/ab67", credentialFreeCoverKey("https://i.scdn.co/image/ab67"))
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/a.jpg?w=300&h=300",
            credentialFreeCoverKey("https://is1-ssl.mzstatic.com/image/thumb/a.jpg?w=300&h=300")
        )
        assertNull(credentialFreeCoverKey("https://music.example/rest/getCoverArt?id=al-1&u=me&t=abc&s=salt"))
        assertNull(credentialFreeCoverKey("http://music.example/rest/getCoverArt?id=al-1&apiKey=k"))
        assertNull(credentialFreeCoverKey(""))
        assertNull(credentialFreeCoverKey(null))
    }

    @Test
    fun should_stayOutOfBackupAndTransfer_when_theBackupRulesAreRead() {
        // The snapshot lives under noBackupFilesDir, which Android never backs
        // up; the rules only ever include the database and the active-id prefs,
        // so no rule can reach it either (domain "root" / "file" would).
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val root = if (Files.exists(userDir.resolve("app/src/main"))) userDir else userDir.parent
        val rules = root.resolve("app/src/main/res/xml/data_extraction_rules.xml").toFile()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rules)
        val includes = document.getElementsByTagName("include")
        assertTrue(includes.length > 0)
        val domains = (0 until includes.length).map { index ->
            includes.item(index).attributes.getNamedItem("domain").nodeValue
        }
        assertEquals(setOf("database", "sharedpref"), domains.toSet())
    }

    private companion object {
        const val PROFILE = "5f0e8a52-3c1f-4c55-9d1b-2a7e8d1c0b11"
        const val OTHER = "8d1c0b11-2a7e-4c55-9d1b-5f0e8a523c1f"
    }
}
