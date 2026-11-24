package com.gpo.yoin.data.sync.backup

import com.gpo.yoin.shared.portability.BackupFormat
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A backup written on iOS imports here: `backup-export-ios.zip` is what the iOS zip writer
 * (iosApp/Yoin/Data/Sync/BackupZip.swift) makes of the en-UTC golden export (YoinTests
 * BackupPortabilityTests writes it and checks it still matches). Android's own [BackupReader] reads it,
 * :shared checks it, and every entry is the golden text, in BackupWriter's order.
 */
class IosBackupZipInteropTest {
    private val golden: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "shared").isDirectory && File(it, "settings.gradle.kts").isFile }
        File(root, "shared/src/commonTest/resources/golden")
    }

    private fun expected(): JsonObject {
        val cases = Json.parseToJsonElement(File(golden, "backup-export.json").readText()).jsonObject.getValue("cases").jsonArray
        return cases.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "en-UTC" }
            .getValue("expected").jsonObject
    }

    @Test
    fun should_importTheZipIosWrites_withAndroidsReader() {
        val expected = expected()
        val readable = expected.getValue("readable").jsonArray.map { it.jsonObject }

        val backup = File(golden, "backup-export-ios.zip").inputStream().use(BackupReader::read)

        assertEquals(readable.size, backup.readableFiles)
        assertEquals(listOf("s-alice", "local-p2", "local-p3", "s-cloud"), backup.manifest.accounts.map { it.scope })
        assertEquals(BackupFormat.LOCATION_GOOGLE_DRIVE, backup.manifest.source.location)
        assertEquals(backup.manifest.snapshot.records, backup.snapshot.records.size)
    }

    @Test
    fun should_holdTheGoldenEntries_inBackupWritersOrder() {
        val expected = expected()
        val readable = expected.getValue("readable").jsonArray.map { it.jsonObject }
        val want = listOf(
            BackupFormat.MANIFEST_PATH to expected.getValue("manifestJson").jsonPrimitive.content,
            BackupFormat.SNAPSHOT_PATH to expected.getValue("snapshotJson").jsonPrimitive.content,
        ) + readable.map { it.getValue("path").jsonPrimitive.content to it.getValue("text").jsonPrimitive.content }

        val entries = ArrayList<Pair<String, String>>()
        ZipInputStream(File(golden, "backup-export-ios.zip").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name to zip.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertEquals(want, entries)
    }
}
