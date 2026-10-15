import java.io.File
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * AGP only hands [MusicKitLargeIdFix] the classes it asks for. If an SDK update renamed or dropped the
 * webPlayback parser, no visitor would run, its own check with it, and the build would pass without the fix.
 * This reads the playback AAR the app ships and fails unless the parser's int helper is there and reads
 * `songId` with `nextInt()` exactly once: what the rewrite expects to find.
 */
abstract class VerifyMusicKitLargeIdTarget : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val playbackAars: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val aars = playbackAars.files.sortedBy(File::getName)
        val aar = aars.singleOrNull() ?: fail(
            "expected one app/libs/mediaplayback-release-*.aar, found ${aars.map(File::getName)}",
        )
        val parser = parserClass(aar) ?: fail("${aar.name} has no $WEB_PLAYBACK_PARSER")
        val reads = songIdIntReads(parser)
        if (reads != 1) fail("$WEB_PLAYBACK_PARSER in ${aar.name} has $reads matching nextInt calls, expected 1")
        report.get().asFile.writeText(
            "${aar.name}: $WEB_PLAYBACK_PARSER.$INT_HELPER_NAME$INT_HELPER_DESCRIPTOR reads songId with nextInt()\n",
        )
    }

    private fun fail(reason: String): Nothing = throw GradleException(
        "MusicKit large catalog id fix can't apply: $reason. " +
            "Re-check it against the SDK: docs/integrations/apple-music.md, \"Catalog ids above 2^31\".",
    )

    private fun parserClass(aar: File): ByteArray? {
        val entry = WEB_PLAYBACK_PARSER.replace('.', '/') + ".class"
        ZipInputStream(aar.inputStream().buffered()).use { outer ->
            generateSequence { outer.nextEntry }.firstOrNull { it.name == "classes.jar" } ?: return null
            val inner = ZipInputStream(outer)
            generateSequence { inner.nextEntry }.firstOrNull { it.name == entry } ?: return null
            return inner.readBytes()
        }
    }

    private fun songIdIntReads(classBytes: ByteArray): Int {
        var reads = 0
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (!isIntHelper(name, descriptor)) return null
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (isSongIdIntRead(opcode, owner, name, descriptor)) reads++
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return reads
    }
}
