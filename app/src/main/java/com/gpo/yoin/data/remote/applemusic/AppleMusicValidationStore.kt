package com.gpo.yoin.data.remote.applemusic

import android.content.Context
import android.util.AtomicFile
import com.gpo.yoin.data.profile.AndroidKeyStoreCredentialsCipher
import com.gpo.yoin.data.profile.EncryptedBlob
import java.io.File
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Legacy authorization imported into the encrypted per-profile store on upgrade. */
interface AppleMusicLegacyAccountStore {
    fun read(): AppleMusicValidationAccount?
    fun clear()
}

class AppleMusicValidationStore(context: Context) : AppleMusicLegacyAccountStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "apple_music_validation.enc"))
    private val cipher = AndroidKeyStoreCredentialsCipher()

    @Synchronized
    override fun read(): AppleMusicValidationAccount? {
        if (!file.baseFile.exists()) return null
        val parts = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val decoded = cipher.decrypt(
            EncryptedBlob(
                Base64.getDecoder().decode(parts[1]),
                Base64.getDecoder().decode(parts[2])
            )
        )
        return Json.decodeFromString(decoded.toString(Charsets.UTF_8))
    }

    @Synchronized
    fun write(account: AppleMusicValidationAccount) {
        val encrypted = cipher.encrypt(Json.encodeToString(account).toByteArray())
        val data = "v1:${Base64.getEncoder().encodeToString(encrypted.iv)}:" +
            Base64.getEncoder().encodeToString(encrypted.ciphertext)
        val stream = file.startWrite()
        try {
            stream.write(data.toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    override fun clear() = file.delete()
}

@Serializable
class AppleMusicValidationAccount(val endpoint: String, val musicUserToken: String)
