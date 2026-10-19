import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Makes MusicKit's native libraries safe on 16 KB memory pages. See docs/integrations/apple-music.md,
 * "16 KB pages: RELRO".
 *
 * The playback AAR (mediaplayback 1.1.1) ships `libappleMusicSDK.so` and its own `libc++_shared.so`, linked by
 * Apple in 2019 (clang 8). Their segments are 64 KB aligned, so Play accepts them, but each ends `PT_GNU_RELRO`
 * inside a 16 KB page with writable data right after it. On a 16 KB Android 17 device the linker refuses
 * `libc++_shared.so` outright ("program alignment (8192) cannot be smaller than system page size"), and in
 * `libappleMusicSDK.so` the read-only RELRO covers that data, so its first write there crashes. Either way
 * Apple Music can't play. Play flagged release 8 for it ("Your app could crash on 16 KB devices").
 *
 * [PatchMusicKitRelro] writes a copy of the AAR whose 64-bit libraries end RELRO on a 16 KB page
 * ([alignRelroEnd]); :app depends on that copy instead of `app/libs`. It fails the build unless both libraries
 * needed it and got it, so an SDK that changes them is re-checked rather than shipped unpatched or patched
 * blindly. `-Pyoin.musickit.relroFix=false` copies the AAR unchanged.
 */
class MusicKitRelroPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val align = target.providers.gradleProperty(RELRO_FIX_PROPERTY).orNull != "false"
        if (!align) {
            target.logger.warn(
                "MusicKit RELRO fix is OFF ($RELRO_FIX_PROPERTY=false): Apple Music won't load on 16 KB pages.",
            )
        }
        target.tasks.register(TASK_NAME, PatchMusicKitRelro::class.java) {
            playbackAars.from(target.fileTree("libs") { include("mediaplayback-release-*.aar") })
            alignRelro.set(align)
            patchedAar.set(target.layout.buildDirectory.file("musickit-relro/mediaplayback-release.aar"))
            report.set(target.layout.buildDirectory.file("musickit-relro/report.txt"))
        }
    }

    private companion object {
        const val TASK_NAME = "patchMusicKitRelro"
    }
}

@CacheableTask
abstract class PatchMusicKitRelro : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val playbackAars: ConfigurableFileCollection

    @get:Input
    abstract val alignRelro: Property<Boolean>

    @get:OutputFile
    abstract val patchedAar: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun patch() {
        val aars = playbackAars.files.sortedBy(File::getName)
        val aar = aars.singleOrNull() ?: fail(
            "expected one app/libs/mediaplayback-release-*.aar, found ${aars.map(File::getName)}",
        )
        val lines = mutableListOf<String>()
        val shortened = mutableSetOf<String>()
        ZipFile(aar).use { source ->
            ZipOutputStream(patchedAar.get().asFile.outputStream().buffered()).use { out ->
                for (entry in source.entries()) {
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    if (alignRelro.get() && isNativeLibrary(entry.name)) {
                        val result = try {
                            alignRelroEnd(bytes, PAGE_SIZE)
                        } catch (failure: IllegalStateException) {
                            fail("${entry.name}: ${failure.message}")
                        }
                        lines += "${entry.name}: ${result.describe()}"
                        if (result is RelroAlignment.Shortened) shortened += entry.name
                    }
                    out.putNextEntry(copyOf(entry, bytes))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        if (alignRelro.get()) {
            val missing = EXPECTED_LIBRARIES - shortened
            if (missing.isNotEmpty()) fail("${aar.name}: no misaligned RELRO to fix in ${missing.sorted()}")
        } else {
            lines += "unchanged: -P$RELRO_FIX_PROPERTY=false"
        }
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun isNativeLibrary(name: String) = name.startsWith("jni/") && name.endsWith(".so")

    private fun RelroAlignment.describe(): String = when (this) {
        is RelroAlignment.Shortened ->
            "RELRO 0x${start.hex()}-0x${oldEnd.hex()} now ends at 0x${newEnd.hex()}"
        RelroAlignment.AlreadyAligned -> "RELRO already ends on a 16 KB page"
        RelroAlignment.NotElf64 -> "not 64-bit, left as is"
        RelroAlignment.NoRelro -> "no RELRO"
    }

    /** [entry] as written again: same name, time and method; a stored entry carries its new size and CRC. */
    private fun copyOf(entry: ZipEntry, bytes: ByteArray) = ZipEntry(entry.name).apply {
        time = entry.time
        method = entry.method
        if (method == ZipEntry.STORED) {
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
    }

    private fun fail(reason: String): Nothing = throw GradleException(
        "MusicKit RELRO fix can't apply: $reason. " +
            "Re-check it against the SDK: docs/integrations/apple-music.md, \"16 KB pages: RELRO\".",
    )

    private companion object {
        const val PAGE_SIZE = 16_384L

        /** The libraries the fix is for; an SDK where either needs no fix, or can't take it, is re-checked. */
        val EXPECTED_LIBRARIES = setOf("jni/arm64-v8a/libappleMusicSDK.so", "jni/arm64-v8a/libc++_shared.so")
    }
}

internal const val RELRO_FIX_PROPERTY = "yoin.musickit.relroFix"
