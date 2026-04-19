package com.gpo.yoin.data.profile

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class EncryptedCredentialsRoundTripTest {

    @Test
    fun encrypted_store_round_trip_preserves_credentials() {
        val tempDir = Files.createTempDirectory("yoin-enc-roundtrip").toFile()
        val codec = EncryptedProfileCredentialsCodec(InMemoryCredentialsCipher())
        val store = FileBackedProfileCredentialsStore(storageDir = tempDir, codec = codec)
        val original = ProfileCredentials.Subsonic(
            serverUrl = "https://music.example",
            username = "alice",
            password = "secret",
        )

        store.write("profile-1", original)
        val restored = store.read("profile-1")

        assertEquals(original, restored)
    }
}
