package com.gpo.yoin.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionGuardrailTest {

    private val rawMotionPatterns = listOf(
        "spring(" to Regex("""(?<!YoinMotion\.)\bspring\("""),
        "fadeIn(" to Regex("""(?<!YoinMotion\.)\bfadeIn\("""),
        "fadeOut(" to Regex("""(?<!YoinMotion\.)\bfadeOut\("""),
        "slideInHorizontally(" to Regex("""(?<!YoinMotion\.)\bslideInHorizontally\("""),
        "slideOutHorizontally(" to Regex("""(?<!YoinMotion\.)\bslideOutHorizontally\("""),
        "slideInVertically(" to Regex("""(?<!YoinMotion\.)\bslideInVertically\("""),
        "slideOutVertically(" to Regex("""(?<!YoinMotion\.)\bslideOutVertically\("""),
        "scaleIn(" to Regex("""(?<!YoinMotion\.)\bscaleIn\("""),
        "scaleOut(" to Regex("""(?<!YoinMotion\.)\bscaleOut\("""),
        "expandHorizontally(" to Regex("""(?<!YoinMotion\.)\bexpandHorizontally\("""),
        "shrinkHorizontally(" to Regex("""(?<!YoinMotion\.)\bshrinkHorizontally\("""),
    )

    private val whitelistedFiles = setOf(
        "app/src/main/java/com/gpo/yoin/ui/theme/Motion.kt",
        "app/src/main/java/com/gpo/yoin/ui/experience/InteractionPrimitives.kt",
    )

    @Test
    fun business_ui_should_not_use_raw_motion_apis() {
        val root = resolveProjectRoot()
        val uiRoot = root.resolve("app/src/main/java/com/gpo/yoin/ui")
        val violations = mutableListOf<String>()

        Files.walk(uiRoot).use { paths ->
            paths.asSequence()
                .filter(Files::isRegularFile)
                .filter { it.extension == "kt" }
                .filterNot { path ->
                    path.relativeTo(root).invariantSeparatorsPathString in whitelistedFiles
                }
                .forEach { file ->
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (
                            trimmed.startsWith("//") ||
                            trimmed.startsWith("/*") ||
                            trimmed.startsWith("*")
                        ) {
                            return@forEachIndexed
                        }
                        rawMotionPatterns.forEach { (pattern, regex) ->
                            if (regex.containsMatchIn(line)) {
                                violations += "${file.relativeTo(root).invariantSeparatorsPathString}:${index + 1} uses $pattern"
                            }
                        }
                    }
                }
        }

        assertTrue(
            "Raw motion API usage found:\n${violations.joinToString(separator = "\n")}",
            violations.isEmpty(),
        )
    }

    private fun resolveProjectRoot(): Path {
        val userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.exists(userDir.resolve("app/src/main/java"))) {
            userDir
        } else {
            userDir.parent
        }
    }

    private fun Path.relativeTo(root: Path): Path = root.relativize(this.normalize())
}
