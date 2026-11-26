package com.gpo.yoin.shared.share

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonPrimitive

/**
 * The CJK gate's tables ([ShareScriptTables]) against the JVM's own `Character.isLetter` and
 * `Character.UnicodeScript` — what Android's `isCjkLyric` read before the move — at every code point, and the
 * rule itself on every single code point. Regenerate the table file after a JDK (Unicode) upgrade:
 * `YOIN_WRITE_SHARE_SCRIPTS=1 ./gradlew :shared:testAndroidHostTest --tests '*ShareScriptsTableTest*'`.
 */
class ShareScriptsTableTest {

    private fun jvmCjk(cp: Int): Boolean = Character.isLetter(cp) && when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
        -> true
        else -> false
    }

    /** Android's rule before the move, verbatim. */
    private fun jvmIsCjkLyric(text: String): Boolean {
        var cjk = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            if (jvmCjk(cp)) cjk = true else return false
        }
        return cjk
    }

    @Test
    fun should_matchTheJvm_atEveryCodePoint() {
        if (System.getenv("YOIN_WRITE_SHARE_SCRIPTS") == "1") File(root(), FILE).writeText(render())
        var mismatches = 0
        val samples = mutableListOf<String>()
        for (cp in 0..0x10FFFF) {
            val letter = ShareScripts.isLetter(cp) == Character.isLetter(cp)
            val cjk = ShareScripts.isCjkLetter(cp) == jvmCjk(cp)
            val text = String(Character.toChars(cp))
            val rule = ShareScripts.isCjk(text) == jvmIsCjkLyric(text) && ShareScripts.isCjk("中$text") == jvmIsCjkLyric("中$text")
            if (!letter || !cjk || !rule) {
                mismatches++
                if (samples.size < 20) samples += "U+%04X".format(cp)
            }
        }
        assertEquals(0, mismatches, "regenerate $FILE (YOIN_WRITE_SHARE_SCRIPTS=1): ${samples.joinToString()}")
    }

    @Test
    fun should_matchTheJvm_onTheGoldenTexts() {
        ShareCardsGoldenFixtures.inputs.getValue("isCjk").forEach { v ->
            val text = v.getValue("text").jsonPrimitive.content
            assertEquals(jvmIsCjkLyric(text), ShareScripts.isCjk(text), text)
        }
    }

    private fun ranges(test: (Int) -> Boolean): String = buildString {
        var start = -1
        var count = 0
        for (cp in 0..0x110000) {
            val inside = cp <= 0x10FFFF && test(cp)
            if (inside && start < 0) start = cp
            if (!inside && start >= 0) {
                if (count % 6 == 0) append("\n       ")
                append(" 0x%X, 0x%X,".format(start, cp - 1))
                count++
                start = -1
            }
        }
    }

    private fun render(): String = buildString {
        append("// Generated from the JVM's Character (Java ").append(System.getProperty("java.version"))
        append(") by ShareScriptsTableTest (androidHostTest). Do not edit.\n")
        append("package com.gpo.yoin.shared.share\n\n")
        append("/**\n")
        append(" * The two code point sets the CJK rule reads ([ShareScripts]), as inclusive [first, last] pairs in order: every\n")
        append(" * letter (`Character.isLetter`, general category L*), and the letters whose script (`Character.UnicodeScript`, not\n")
        append(" * Script_Extensions) is Han, Hiragana, Katakana, Hangul or Bopomofo.\n")
        append(" */\n")
        append("internal object ShareScriptTables {\n")
        append("    val letters: IntArray = intArrayOf(").append(ranges(Character::isLetter)).append("\n    )\n\n")
        append("    val cjkLetters: IntArray = intArrayOf(").append(ranges(::jvmCjk)).append("\n    )\n}\n")
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "shared").isDirectory && File(it, "settings.gradle.kts").isFile }

    private companion object {
        const val FILE = "shared/src/commonMain/kotlin/com/gpo/yoin/shared/share/ShareScriptTables.kt"
    }
}
