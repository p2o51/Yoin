package com.gpo.yoin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden fixture the iOS port of MCU (iosApp/Yoin/Theme/MCU) is tested
 * against: every role [ExpressiveColorSchemeFactory] maps (SchemeExpressive,
 * SPEC_2025, contrast 0, phone) for a set of seeds, light and dark.
 *
 * The fixture lives at iosApp/YoinTests/Fixtures/mcu-golden.json and is read
 * by the iOS `McuGoldenTests`. This test fails when the Kotlin output drifts
 * from the checked-in fixture; regenerate it with
 * `YOIN_WRITE_MCU_GOLDEN=1 ./gradlew :app:testDebugUnitTest --tests '*McuGoldenFixtureTest*'`.
 */
class McuGoldenFixtureTest {
    @Test
    fun should_match_checked_in_ios_fixture_when_generating_expressive_roles() {
        val json = goldenJson()
        val fixture = fixtureFile()
        if (System.getenv("YOIN_WRITE_MCU_GOLDEN") == "1" || !fixture.exists()) {
            fixture.parentFile?.mkdirs()
            fixture.writeText(json)
        }
        assertEquals(fixture.readText(), json)
    }

    private fun fixtureFile(): File {
        // Gradle runs unit tests from the module directory (app/).
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "iosApp").isDirectory }
        return File(root, "iosApp/YoinTests/Fixtures/mcu-golden.json")
    }

    private fun goldenJson(): String = buildString {
        append("{\n")
        append("  \"spec\": \"SchemeExpressive SPEC_2025, contrast 0, Platform.PHONE\",\n")
        append("  \"schemes\": [\n")
        val entries = Seeds.flatMap { seed -> listOf(seed to false, seed to true) }
        entries.forEachIndexed { index, (seed, dark) ->
            val scheme = ExpressiveColorSchemeFactory.fromSeed(seed.toInt(), isDark = dark)
            append("    {\"seed\": \"${hex(seed.toInt())}\", \"dark\": $dark, \"roles\": {")
            append(
                roles(scheme).entries.joinToString(", ") { (name, argb) -> "\"$name\": \"${hex(argb)}\"" },
            )
            append("}}")
            append(if (index == entries.lastIndex) "\n" else ",\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun hex(argb: Int): String = "%08X".format(argb)

    private fun roles(s: ColorScheme): Map<String, Int> = linkedMapOf(
        "primary" to s.primary.toArgb(),
        "onPrimary" to s.onPrimary.toArgb(),
        "primaryContainer" to s.primaryContainer.toArgb(),
        "onPrimaryContainer" to s.onPrimaryContainer.toArgb(),
        "inversePrimary" to s.inversePrimary.toArgb(),
        "secondary" to s.secondary.toArgb(),
        "onSecondary" to s.onSecondary.toArgb(),
        "secondaryContainer" to s.secondaryContainer.toArgb(),
        "onSecondaryContainer" to s.onSecondaryContainer.toArgb(),
        "tertiary" to s.tertiary.toArgb(),
        "onTertiary" to s.onTertiary.toArgb(),
        "tertiaryContainer" to s.tertiaryContainer.toArgb(),
        "onTertiaryContainer" to s.onTertiaryContainer.toArgb(),
        "background" to s.background.toArgb(),
        "onBackground" to s.onBackground.toArgb(),
        "surface" to s.surface.toArgb(),
        "onSurface" to s.onSurface.toArgb(),
        "surfaceVariant" to s.surfaceVariant.toArgb(),
        "onSurfaceVariant" to s.onSurfaceVariant.toArgb(),
        "surfaceTint" to s.surfaceTint.toArgb(),
        "inverseSurface" to s.inverseSurface.toArgb(),
        "inverseOnSurface" to s.inverseOnSurface.toArgb(),
        "error" to s.error.toArgb(),
        "onError" to s.onError.toArgb(),
        "errorContainer" to s.errorContainer.toArgb(),
        "onErrorContainer" to s.onErrorContainer.toArgb(),
        "outline" to s.outline.toArgb(),
        "outlineVariant" to s.outlineVariant.toArgb(),
        "scrim" to s.scrim.toArgb(),
        "surfaceBright" to s.surfaceBright.toArgb(),
        "surfaceDim" to s.surfaceDim.toArgb(),
        "surfaceContainer" to s.surfaceContainer.toArgb(),
        "surfaceContainerHigh" to s.surfaceContainerHigh.toArgb(),
        "surfaceContainerHighest" to s.surfaceContainerHighest.toArgb(),
        "surfaceContainerLow" to s.surfaceContainerLow.toArgb(),
        "surfaceContainerLowest" to s.surfaceContainerLowest.toArgb(),
    )

    private companion object {
        /** The iOS mock covers' palette.a, plus seeds that reach the yellow / cyan / grey branches. */
        val Seeds = listOf(
            0xFF7A4FD6, 0xFFC2412D, 0xFF1F7A8C, 0xFF4E8A3E,
            0xFFD9733B, 0xFFE0457B, 0xFF55606E, 0xFFF2B035,
            0xFF3459D6, 0xFF8C2F5A, 0xFF6C8CFF, 0xFF2EA37A,
            0xFFD8C84A, 0xFF22B8C8, 0xFF808080, 0xFFFFFF00,
        )
    }
}
