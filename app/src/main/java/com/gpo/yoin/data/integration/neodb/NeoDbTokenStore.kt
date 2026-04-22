package com.gpo.yoin.data.integration.neodb

import com.gpo.yoin.data.profile.CredentialsCipher
import com.gpo.yoin.data.profile.EncryptedBlob
import java.io.File

/**
 * NeoDB 的 personal access token 的加密落地。**不放 Room**，因为 Room 的
 * `yoin-database.db` 会被 Auto Backup 打包 —— token 属于敏感凭据，不应
 * 随云端 / 跨设备备份走，语义对齐 profile credentials。
 *
 * 文件位置：`noBackupFilesDir/neodb/token.bin`。Android 自动把
 * `noBackupFilesDir` 里的内容排除在 `<cloud-backup>` / `<device-transfer>`
 * 之外，不用再在 `data_extraction_rules.xml` 里单独列。
 *
 * 和 [com.gpo.yoin.data.profile.FileBackedProfileCredentialsStore] 共享同
 * 一套 AES-GCM cipher，所以 key 也和 profile credentials 一起在
 * AndroidKeyStore 换机时失效 —— 用户重装后 [readToken] 返回 null，UI 走
 * 「重新登录 NeoDB」流程，不抛 AEADBadTagException 上来。
 */
internal class NeoDbTokenStore(
    private val cipher: CredentialsCipher,
    private val storageDir: File,
) {
    private val tokenFile: File get() = File(storageDir, TOKEN_FILE_NAME)

    /**
     * 读取 token；读不出（文件不存在 / AEAD bad tag / 空文件）都返回 null。
     * 调用方判 null == 未登录，不需要区分「不存在」和「解不开」。
     */
    fun readToken(): String? {
        val file = tokenFile
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < IV_LENGTH + 1) return null
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val plaintext = cipher.decrypt(EncryptedBlob(iv = iv, ciphertext = ciphertext))
            String(plaintext, Charsets.UTF_8).takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    /**
     * 写入 / 覆盖 token。空串会删除文件 ——「登出」语义。
     *
     * 写入走 `write-then-rename` 模式避免写到一半崩掉留下半截文件：
     * 先写 `.tmp`，成功后 atomic rename。
     */
    fun writeToken(token: String) {
        storageDir.mkdirs()
        if (token.isEmpty()) {
            tokenFile.delete()
            return
        }
        val blob = cipher.encrypt(token.toByteArray(Charsets.UTF_8))
        val tmp = File(storageDir, "$TOKEN_FILE_NAME.tmp")
        tmp.writeBytes(blob.iv + blob.ciphertext)
        if (!tmp.renameTo(tokenFile)) {
            // 少见情况（目标被其它进程锁住）：退回覆盖写，损失原子性但不丢数据。
            tmp.copyTo(tokenFile, overwrite = true)
            tmp.delete()
        }
    }

    fun clear() {
        tokenFile.delete()
    }

    companion object {
        private const val TOKEN_FILE_NAME = "token.bin"
        // AES-GCM standard IV size (AndroidKeyStoreCredentialsCipher).
        private const val IV_LENGTH = 12
    }
}
