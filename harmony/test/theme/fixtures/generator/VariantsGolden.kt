import dynamiccolor.ColorSpec
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicColor
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct

object VariantsGolden {
  @JvmStatic fun main(args: Array<String>) {
    val spec = ColorSpecs.get(ColorSpec.SpecVersion.SPEC_2025)
    val roles: List<Pair<String, DynamicColor?>> = listOf(
      "surface" to spec.surface, "surfaceDim" to spec.surfaceDim, "surfaceBright" to spec.surfaceBright,
      "surfaceContainerLowest" to spec.surfaceContainerLowest, "surfaceContainerLow" to spec.surfaceContainerLow,
      "surfaceContainer" to spec.surfaceContainer, "surfaceContainerHigh" to spec.surfaceContainerHigh,
      "surfaceContainerHighest" to spec.surfaceContainerHighest, "onSurface" to spec.onSurface,
      "onSurfaceVariant" to spec.onSurfaceVariant, "inverseSurface" to spec.inverseSurface,
      "inverseOnSurface" to spec.inverseOnSurface, "outline" to spec.outline, "outlineVariant" to spec.outlineVariant,
      "onBackground" to spec.onBackground,
      "primary" to spec.primary, "primaryDim" to spec.primaryDim, "onPrimary" to spec.onPrimary,
      "primaryContainer" to spec.primaryContainer, "onPrimaryContainer" to spec.onPrimaryContainer,
      "inversePrimary" to spec.inversePrimary,
      "secondary" to spec.secondary, "secondaryDim" to spec.secondaryDim, "onSecondary" to spec.onSecondary,
      "secondaryContainer" to spec.secondaryContainer, "onSecondaryContainer" to spec.onSecondaryContainer,
      "tertiary" to spec.tertiary, "tertiaryDim" to spec.tertiaryDim, "onTertiary" to spec.onTertiary,
      "tertiaryContainer" to spec.tertiaryContainer, "onTertiaryContainer" to spec.onTertiaryContainer,
      "error" to spec.error, "errorDim" to spec.errorDim, "onError" to spec.onError,
      "errorContainer" to spec.errorContainer, "onErrorContainer" to spec.onErrorContainer,
      "primaryFixed" to spec.primaryFixed, "primaryFixedDim" to spec.primaryFixedDim,
      "onPrimaryFixed" to spec.onPrimaryFixed, "onPrimaryFixedVariant" to spec.onPrimaryFixedVariant,
      "secondaryFixed" to spec.secondaryFixed, "secondaryFixedDim" to spec.secondaryFixedDim,
      "onSecondaryFixed" to spec.onSecondaryFixed, "onSecondaryFixedVariant" to spec.onSecondaryFixedVariant,
      "tertiaryFixed" to spec.tertiaryFixed, "tertiaryFixedDim" to spec.tertiaryFixedDim,
      "onTertiaryFixed" to spec.onTertiaryFixed, "onTertiaryFixedVariant" to spec.onTertiaryFixedVariant,
    )
    val seeds = listOf(0xFF7B3FE4, 0xFFE0B000, 0xFF00B8D4)
    val variants = listOf(Variant.NEUTRAL, Variant.TONAL_SPOT, Variant.VIBRANT, Variant.EXPRESSIVE)
    val contrasts = listOf(-1.0, 0.5, 1.0)
    val out = StringBuilder()
    out.append("{\n  \"spec\": \"SPEC_2025\",\n  \"roles\": [")
    out.append(roles.joinToString(", ") { "\"${it.first}\"" })
    out.append("],\n  \"schemes\": [\n")
    val rows = mutableListOf<String>()
    for (seed in seeds) for (v in variants) for (p in DynamicScheme.Platform.values()) for (dark in listOf(false, true)) for (c in contrasts) {
      val src = Hct.fromInt(seed.toInt())
      val s = DynamicScheme(src, v, dark, c, p, ColorSpec.SpecVersion.SPEC_2025,
        spec.getPrimaryPalette(v, src, dark, p, c), spec.getSecondaryPalette(v, src, dark, p, c),
        spec.getTertiaryPalette(v, src, dark, p, c), spec.getNeutralPalette(v, src, dark, p, c),
        spec.getNeutralVariantPalette(v, src, dark, p, c), spec.getErrorPalette(v, src, dark, p, c))
      val values = roles.joinToString(",") { "\"%08X\"".format(it.second!!.getArgb(s)) }
      rows.add("    {\"seed\": \"%08X\", \"variant\": \"${v.name}\", \"platform\": \"${p.name}\", \"isDark\": $dark, \"contrastLevel\": $c, \"argb\": [$values]}".format(seed.toInt()))
    }
    out.append(rows.joinToString(",\n")).append("\n  ]\n}\n")
    print(out)
  }
}
