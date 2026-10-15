import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Lets Apple's MusicKit for Android playback SDK (mediaplayback 1.1.1) play songs whose catalog id is above
 * Int.MAX_VALUE. See docs/integrations/apple-music.md, "Catalog ids above 2^31".
 *
 * The SDK asks webPlayback for a song's assets with the id as a long, then reads the reply's `songId` back
 * through `JsonReader.nextInt()` (`playback.g.h`, private `a(JsonReader, int)`). An id like 6805970194 throws
 * there; the request reports "REQUEST EXCEPTION", `Integer.valueOf` of that string throws again, and the asset
 * task, run through `ExecutorService.submit`, dies inside a Future no one reads: no asset, no error, the song
 * buffers at 0:00 forever. The parsed `songId` is stored in a field nothing reads, so reading it as a long and
 * narrowing it is enough. The AAR on disk stays untouched; this rewrites that one call at build time.
 *
 * `-Pyoin.musickit.largeIdFix=false` builds without the rewrite.
 */
class MusicKitLargeIdPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        if (target.providers.gradleProperty(DISABLE_PROPERTY).orNull == "false") {
            target.logger.warn("MusicKit large-id fix is OFF ($DISABLE_PROPERTY=false): new-range Apple Music songs won't play.")
            return
        }
        target.pluginManager.withPlugin("com.android.application") {
            target.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
                // The variant and its unit / instrumented tests, so a JVM test can parse with the fixed class.
                variant.components.forEach { component ->
                    component.instrumentation.transformClassesWith(
                        MusicKitLargeIdFix::class.java,
                        InstrumentationScope.ALL,
                    ) {}
                    // The SDK's classes ship without StackMapTable (dex doesn't need one). Computing frames for the
                    // one rewritten class lets the JVM unit tests load it; D8 ignores frames either way.
                    component.instrumentation.setAsmFramesComputationMode(
                        FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES,
                    )
                }
            }
        }
    }

    private companion object {
        const val DISABLE_PROPERTY = "yoin.musickit.largeIdFix"
    }
}

abstract class MusicKitLargeIdFix : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        SongIdAsLong(instrumentationContext.apiVersion.get(), nextClassVisitor)

    override fun isInstrumentable(classData: ClassData): Boolean = classData.className == WEB_PLAYBACK_PARSER
}

/** Rewrites `nextInt()` in the parser's int helper to `(int) nextLong()`, and fails the build if it isn't there. */
private class SongIdAsLong(private val asmApi: Int, next: ClassVisitor) : ClassVisitor(asmApi, next) {
    private var rewritten = 0

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (name != INT_HELPER_NAME || descriptor != INT_HELPER_DESCRIPTOR) return visitor
        return object : MethodVisitor(asmApi, visitor) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                if (opcode == Opcodes.INVOKEVIRTUAL && owner == JSON_READER && name == "nextInt" && descriptor == "()I") {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, JSON_READER, "nextLong", "()J", false)
                    super.visitInsn(Opcodes.L2I)
                    rewritten++
                } else {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                }
            }

            // A long takes two stack slots where the int took one (recomputed anyway with frames).
            override fun visitMaxs(maxStack: Int, maxLocals: Int) = super.visitMaxs(maxStack + 1, maxLocals)
        }
    }

    override fun visitEnd() {
        check(rewritten == 1) {
            "MusicKit's $WEB_PLAYBACK_PARSER changed (rewrote $rewritten nextInt calls, expected 1). " +
                "Re-check the large catalog id fix: docs/integrations/apple-music.md."
        }
        super.visitEnd()
    }
}

private const val WEB_PLAYBACK_PARSER = "com.apple.android.music.playback.g.h"
private const val INT_HELPER_NAME = "a"
private const val INT_HELPER_DESCRIPTOR = "(Landroid/util/JsonReader;I)I"
private const val JSON_READER = "android/util/JsonReader"
