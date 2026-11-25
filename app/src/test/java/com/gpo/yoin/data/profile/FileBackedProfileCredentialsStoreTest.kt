package com.gpo.yoin.data.profile

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IO behaviour of [FileBackedProfileCredentialsStore]. The codec is a
 * real [EncryptedProfileCredentialsCodec] over [InMemoryCredentialsCipher]
 * so we exercise the envelope round-trip but skip `AndroidKeyStore`
 * (host-JVM tests can't reach it).
 */
class FileBackedProfileCredentialsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): FileBackedProfileCredentialsStore =
        FileBackedProfileCredentialsStore(
            storageDir = tempFolder.newFolder("profile_credentials"),
            codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher()),
        )

    @Test
    fun read_returns_null_when_no_file_exists() {
        assertNull(newStore().read("never-written"))
    }

    @Test
    fun write_then_read_round_trips_credentials() {
        val store = newStore()
        val creds = ProfileCredentials.Subsonic("https://x.test", "u", "p")
        store.write("profile-a", creds)
        assertEquals(creds, store.read("profile-a"))
    }

    @Test
    fun write_overwrites_previous_value() {
        val store = newStore()
        store.write("p", ProfileCredentials.Subsonic("a", "b", "c"))
        val updated = ProfileCredentials.Subsonic("a", "b", "new-password")
        store.write("p", updated)
        assertEquals(updated, store.read("p"))
    }

    @Test
    fun delete_removes_the_file() {
        val store = newStore()
        store.write("p", ProfileCredentials.Subsonic("a", "b", "c"))
        store.delete("p")
        assertNull(store.read("p"))
    }

    @Test
    fun two_profiles_are_isolated() {
        val store = newStore()
        val s = ProfileCredentials.Subsonic("a", "u", "pass")
        val sp = ProfileCredentials.Spotify("at", "rt", 999L, listOf("scope"))
        store.write("subsonic-id", s)
        store.write("spotify-id", sp)
        assertEquals(s, store.read("subsonic-id"))
        assertEquals(sp, store.read("spotify-id"))
        store.delete("subsonic-id")
        assertNull(store.read("subsonic-id"))
        assertEquals("delete must be per-profile, not blanket", sp, store.read("spotify-id"))
    }

    @Test
    fun file_on_disk_is_not_plaintext() {
        val store = newStore()
        val creds = ProfileCredentials.Subsonic("https://music.example", "alice", "hunter2")
        store.write("p", creds)
        // Find the file the store wrote — it lives directly under the
        // temp folder used by the constructor.
        val onDisk = tempFolder.root
            .walkTopDown()
            .first { it.name == "p.bin" }
            .readText(Charsets.UTF_8)
        assertTrue(
            "file should start with the v1 envelope sigil",
            onDisk.startsWith(EncryptedProfileCredentialsCodec.PREFIX_V1),
        )
        assertTrue(
            "plaintext password must not appear in the encrypted file",
            !onDisk.contains("hunter2"),
        )
    }

    @Test
    fun read_falls_back_to_backup_when_primary_file_is_missing() {
        val storageDir = tempFolder.newFolder("profile_credentials")
        val codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())
        val store = FileBackedProfileCredentialsStore(
            storageDir = storageDir,
            codec = codec,
        )
        val creds = ProfileCredentials.Subsonic("https://music.example", "alice", "hunter2")
        java.io.File(storageDir, "p.bin.bak").writeText(codec.encode(creds), Charsets.UTF_8)

        assertEquals(creds, store.read("p"))
    }

    @Test
    fun delete_removes_backup_file_too() {
        val storageDir = tempFolder.newFolder("profile_credentials")
        val codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())
        val store = FileBackedProfileCredentialsStore(
            storageDir = storageDir,
            codec = codec,
        )
        val backup = java.io.File(storageDir, "p.bin.bak")
        backup.writeText(
            codec.encode(ProfileCredentials.Subsonic("https://music.example", "alice", "hunter2")),
            Charsets.UTF_8,
        )

        store.delete("p")

        assertTrue(!backup.exists())
        assertNull(store.read("p"))
    }

    @Test
    fun should_listEveryProfileWithAFile_when_askedForTheStoredIds() {
        val storageDir = tempFolder.newFolder("profile_credentials")
        val store = FileBackedProfileCredentialsStore(
            storageDir = storageDir,
            codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())
        )
        store.write("written", ProfileCredentials.Subsonic("a", "b", "c"))
        // What writes cut short leave, beside (or instead of) a .bin.
        java.io.File(storageDir, "cut-short.bin.tmp").writeText("x")
        java.io.File(storageDir, "moved-aside.bin.bak").writeText("x")
        // Not the store's: never listed, so never swept.
        java.io.File(storageDir, "hidden.bin.qahidden").writeText("x")
        java.io.File(storageDir, "notes.txt").writeText("x")

        assertEquals(setOf("written", "cut-short", "moved-aside"), store.storedProfileIds())
    }

    @Test
    fun should_listNothing_when_theDirectoryWasNeverMade() {
        val store = FileBackedProfileCredentialsStore(
            storageDir = java.io.File(tempFolder.root, "never-made"),
            codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())
        )

        assertEquals(emptySet<String>(), store.storedProfileIds())
    }

    @Test
    fun should_serveCachedCredentialsWithoutRepeatedDecryption_when_readRepeatedly() {
        val dir = tempFolder.newFolder("cached_creds")
        val decrypts = AtomicInteger(0)
        val delegate = InMemoryCredentialsCipher()
        val cipher = object : CredentialsCipher {
            override fun encrypt(plaintext: ByteArray) = delegate.encrypt(plaintext)
            override fun decrypt(blob: EncryptedBlob): ByteArray {
                decrypts.incrementAndGet()
                return delegate.decrypt(blob)
            }
        }
        val creds = ProfileCredentials.Spotify("at-1", "rt-1", 100L, listOf("s"))
        FileBackedProfileCredentialsStore(dir, EncryptedProfileCredentialsCodec(cipher)).write("p1", creds)

        val coldStore = FileBackedProfileCredentialsStore(dir, EncryptedProfileCredentialsCodec(cipher))
        repeat(10) {
            assertEquals(creds, coldStore.read("p1"))
        }

        assertEquals(1, decrypts.get())
    }

    @Test
    fun should_serveNewCredentials_when_aColdReadRacesAWrite() {
        val dir = File(tempFolder.root, "race-write")
        val inDecrypt = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNextDecrypt = AtomicBoolean(false)
        val cipher = object : CredentialsCipher {
            override fun encrypt(plaintext: ByteArray) = EncryptedBlob(ByteArray(12) { 1 }, plaintext)
            override fun decrypt(blob: EncryptedBlob): ByteArray {
                if (blockNextDecrypt.getAndSet(false)) {
                    inDecrypt.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return blob.ciphertext
            }
        }
        val old = ProfileCredentials.Spotify(
            accessToken = "access-OLD",
            refreshToken = "refresh-OLD",
            expiresAtEpochMs = 1L,
            scopes = emptyList(),
            clientId = "cid",
        )
        val fresh = old.copy(accessToken = "access-NEW", refreshToken = "refresh-NEW", expiresAtEpochMs = 2L)

        FileBackedProfileCredentialsStore(dir, EncryptedProfileCredentialsCodec(cipher)).write("p1", old)
        val store = FileBackedProfileCredentialsStore(dir, EncryptedProfileCredentialsCodec(cipher))

        blockNextDecrypt.set(true)
        var readerResult: ProfileCredentials? = null
        val reader = Thread { readerResult = store.read("p1") }
        reader.start()
        assertTrue(inDecrypt.await(5, TimeUnit.SECONDS))
        store.write("p1", fresh)
        release.countDown()
        reader.join(5_000)

        assertEquals("refresh-NEW", (readerResult as ProfileCredentials.Spotify).refreshToken)
        assertEquals("refresh-NEW", (store.read("p1") as ProfileCredentials.Spotify).refreshToken)
    }

    @Test
    fun should_returnNull_when_aColdReadRacesADelete() {
        val dir = File(tempFolder.root, "race-delete")
        val inDecrypt = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockNextDecrypt = AtomicBoolean(false)
        val cipher = object : CredentialsCipher {
            override fun encrypt(plaintext: ByteArray) = EncryptedBlob(ByteArray(12) { 1 }, plaintext)
            override fun decrypt(blob: EncryptedBlob): ByteArray {
                if (blockNextDecrypt.getAndSet(false)) {
                    inDecrypt.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                return blob.ciphertext
            }
        }
        val old = ProfileCredentials.Subsonic("https://music.example", "alice", "pw")
        FileBackedProfileCredentialsStore(dir, EncryptedProfileCredentialsCodec(cipher)).write("p1", old)
        val store = FileBackedProfileCredentialsStore(dir, EncryptedProfileCredentialsCodec(cipher))

        blockNextDecrypt.set(true)
        var readerResult: ProfileCredentials? = old
        val reader = Thread { readerResult = store.read("p1") }
        reader.start()
        assertTrue(inDecrypt.await(5, TimeUnit.SECONDS))
        store.delete("p1")
        release.countDown()
        reader.join(5_000)

        assertNull(readerResult)
        assertNull(store.read("p1"))
    }
}
