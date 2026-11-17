// Prints test/theme/fixtures/spec2025_expressive_golden.json from the Kotlin MCU
// sources the Android app vendors. Not part of the Flutter build. To regenerate:
//   M=/Users/gpo/Developer/Yoin/app/src/main/java
//   kotlinc $(find $M/{contrast,dislike,dynamiccolor,hct,palettes,scheme,temperature,utils} -name '*.kt') \
//     Golden.kt -include-runtime -d golden.jar
//   java -cp golden.jar GoldenKt > ../spec2025_expressive_golden.json
// VariantsGolden.kt (same classpath, main class VariantsGolden) prints
// ../spec2025_variants_golden.json.
import dynamiccolor.ColorSpec
import dynamiccolor.DynamicScheme
import hct.Hct
import scheme.SchemeExpressive

fun main() {
  val seeds = listOf(0xFF7B3FE4, 0xFF1DB954, 0xFFE0B000, 0xFF00B8D4, 0xFF3355FF, 0xFFFF5A5F, 0xFF808080)
  val sb = StringBuilder()
  sb.append("{\n  \"spec\": \"SPEC_2025\",\n  \"variant\": \"EXPRESSIVE\",\n  \"platform\": \"PHONE\",\n  \"contrastLevel\": 0.0,\n  \"schemes\": [\n")
  val entries = mutableListOf<String>()
  for (seed in seeds) for (dark in listOf(false, true)) {
    val s = SchemeExpressive(Hct.fromInt(seed.toInt()), dark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE)
    val roles = linkedMapOf(
      "primaryPaletteKeyColor" to s.primaryPaletteKeyColor,
      "secondaryPaletteKeyColor" to s.secondaryPaletteKeyColor,
      "tertiaryPaletteKeyColor" to s.tertiaryPaletteKeyColor,
      "neutralPaletteKeyColor" to s.neutralPaletteKeyColor,
      "neutralVariantPaletteKeyColor" to s.neutralVariantPaletteKeyColor,
      "background" to s.background, "onBackground" to s.onBackground,
      "surface" to s.surface, "surfaceDim" to s.surfaceDim, "surfaceBright" to s.surfaceBright,
      "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
      "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
      "surfaceContainerHighest" to s.surfaceContainerHighest, "onSurface" to s.onSurface,
      "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
      "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
      "outline" to s.outline, "outlineVariant" to s.outlineVariant, "shadow" to s.shadow, "scrim" to s.scrim,
      "surfaceTint" to s.surfaceTint,
      "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer,
      "onPrimaryContainer" to s.onPrimaryContainer, "inversePrimary" to s.inversePrimary,
      "secondary" to s.secondary, "onSecondary" to s.onSecondary, "secondaryContainer" to s.secondaryContainer,
      "onSecondaryContainer" to s.onSecondaryContainer,
      "tertiary" to s.tertiary, "onTertiary" to s.onTertiary, "tertiaryContainer" to s.tertiaryContainer,
      "onTertiaryContainer" to s.onTertiaryContainer,
      "error" to s.error, "onError" to s.onError, "errorContainer" to s.errorContainer,
      "onErrorContainer" to s.onErrorContainer,
      "primaryFixed" to s.primaryFixed, "primaryFixedDim" to s.primaryFixedDim,
      "onPrimaryFixed" to s.onPrimaryFixed, "onPrimaryFixedVariant" to s.onPrimaryFixedVariant,
      "secondaryFixed" to s.secondaryFixed, "secondaryFixedDim" to s.secondaryFixedDim,
      "onSecondaryFixed" to s.onSecondaryFixed, "onSecondaryFixedVariant" to s.onSecondaryFixedVariant,
      "tertiaryFixed" to s.tertiaryFixed, "tertiaryFixedDim" to s.tertiaryFixedDim,
      "onTertiaryFixed" to s.onTertiaryFixed, "onTertiaryFixedVariant" to s.onTertiaryFixedVariant,
    )
    val body = roles.entries.joinToString(",\n") { (k, v) -> "        \"$k\": \"%08X\"".format(v) }
    entries.add("    {\n      \"seed\": \"%08X\",\n      \"isDark\": $dark,\n      \"roles\": {\n$body\n      }\n    }".format(seed.toInt()))
  }
  sb.append(entries.joinToString(",\n")).append("\n  ]\n}\n")
  print(sb)
}
