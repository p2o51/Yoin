package com.gpo.yoin.debug

import android.graphics.PathMeasure
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import com.gpo.yoin.ui.theme.ContinuousSmoothing
import com.gpo.yoin.ui.theme.ExpressiveColorSchemeFactory
import com.gpo.yoin.ui.theme.YoinDarkColorScheme
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import com.gpo.yoin.ui.theme.YoinTypography
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject

/** Isolated branch only: serialises actual runtime design values for Figma comparison. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class DesignTokenExportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val schemes = JSONObject()
            .put("fallbackLight", colors(YoinLightColorScheme))
            .put("fallbackDark", colors(YoinDarkColorScheme))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            schemes.put("dynamicLight", colors(dynamicLightColorScheme(this)))
            schemes.put("dynamicDark", colors(dynamicDarkColorScheme(this)))
        }
        listOf(0xFF3B2D8F.toInt(), 0xFF639922.toInt(), 0xFF378ADD.toInt()).forEach { seed ->
            val name = hex(Color(seed))
            schemes.put("expressive-$name-light", colors(ExpressiveColorSchemeFactory.fromSeed(seed, false)))
            schemes.put("expressive-$name-dark", colors(ExpressiveColorSchemeFactory.fromSeed(seed, true)))
        }
        val shapes = JSONObject()
        linkedMapOf(
            "Cookie12Sided" to MaterialShapes.Cookie12Sided,
            "Cookie9Sided" to MaterialShapes.Cookie9Sided,
            "Cookie6Sided" to MaterialShapes.Cookie6Sided,
            "Bun" to MaterialShapes.Bun,
            "Ghostish" to MaterialShapes.Ghostish,
            "Circle" to MaterialShapes.Circle,
            "Oval" to MaterialShapes.Oval,
            "Clover4Leaf" to MaterialShapes.Clover4Leaf,
            "Heart" to MaterialShapes.Heart,
            "Pentagon" to MaterialShapes.Pentagon,
            "Triangle" to MaterialShapes.Triangle,
            "Arch" to MaterialShapes.Arch,
            "Gem" to MaterialShapes.Gem,
            "Slanted" to MaterialShapes.Slanted,
            "Sunny" to MaterialShapes.Sunny,
        ).forEach { (name, polygon) -> shapes.put(name, exportPolygon(polygon)) }
        for (radius in listOf(4f, 8f, 12f, 16f, 20f, 28f, 32f)) {
            shapes.put(
                "Continuous100x100-r${radius.toInt()}",
                exportPolygon(RoundedPolygon.rectangle(
                    width = 100f,
                    height = 100f,
                    rounding = CornerRounding(radius, ContinuousSmoothing),
                    centerX = 50f,
                    centerY = 50f,
                )).put("referenceWidth", 100).put("referenceHeight", 100).put("radius", radius),
            )
        }
        val typography = JSONObject()
        linkedMapOf(
            "displayLarge" to YoinTypography.displayLarge,
            "displayMedium" to YoinTypography.displayMedium,
            "displaySmall" to YoinTypography.displaySmall,
            "headlineLarge" to YoinTypography.headlineLarge,
            "headlineMedium" to YoinTypography.headlineMedium,
            "headlineSmall" to YoinTypography.headlineSmall,
            "titleLarge" to YoinTypography.titleLarge,
            "titleMedium" to YoinTypography.titleMedium,
            "titleSmall" to YoinTypography.titleSmall,
            "bodyLarge" to YoinTypography.bodyLarge,
            "bodyMedium" to YoinTypography.bodyMedium,
            "bodySmall" to YoinTypography.bodySmall,
            "labelLarge" to YoinTypography.labelLarge,
            "labelMedium" to YoinTypography.labelMedium,
            "labelSmall" to YoinTypography.labelSmall,
        ).forEach { (name, style) -> typography.put(name, textStyle(style)) }
        val root = JSONObject()
            .put("api", Build.VERSION.SDK_INT)
            .put("density", resources.displayMetrics.density)
            .put("fontScale", resources.configuration.fontScale)
            .put("colorSchemes", schemes)
            .put("materialShapes", shapes)
            .put("typography", typography)
            .put("continuousCornerSmoothing", ContinuousSmoothing)
        val file = File(filesDir, "figma-design-tokens.json")
        file.writeText(root.toString(2))
        Log.i("YoinDesignExport", "Wrote ${file.name}; exact M3 cubic paths, runtime schemes and typography.")
        finish()
    }

    private fun hex(color: Color) = String.format(Locale.ROOT, "#%08X", color.toArgb())

    private fun colors(scheme: ColorScheme): JSONObject = JSONObject().apply {
        linkedMapOf(
            "primary" to scheme.primary,
            "onPrimary" to scheme.onPrimary,
            "primaryContainer" to scheme.primaryContainer,
            "onPrimaryContainer" to scheme.onPrimaryContainer,
            "inversePrimary" to scheme.inversePrimary,
            "secondary" to scheme.secondary,
            "onSecondary" to scheme.onSecondary,
            "secondaryContainer" to scheme.secondaryContainer,
            "onSecondaryContainer" to scheme.onSecondaryContainer,
            "tertiary" to scheme.tertiary,
            "onTertiary" to scheme.onTertiary,
            "tertiaryContainer" to scheme.tertiaryContainer,
            "onTertiaryContainer" to scheme.onTertiaryContainer,
            "background" to scheme.background,
            "onBackground" to scheme.onBackground,
            "surface" to scheme.surface,
            "onSurface" to scheme.onSurface,
            "surfaceVariant" to scheme.surfaceVariant,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "surfaceTint" to scheme.surfaceTint,
            "inverseSurface" to scheme.inverseSurface,
            "inverseOnSurface" to scheme.inverseOnSurface,
            "error" to scheme.error,
            "onError" to scheme.onError,
            "errorContainer" to scheme.errorContainer,
            "onErrorContainer" to scheme.onErrorContainer,
            "outline" to scheme.outline,
            "outlineVariant" to scheme.outlineVariant,
            "scrim" to scheme.scrim,
            "surfaceBright" to scheme.surfaceBright,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        ).forEach { (name, color) -> put(name, hex(color)) }
    }

    private fun textStyle(style: TextStyle) = JSONObject()
        .put("fontSizeSp", style.fontSize.value)
        .put("lineHeightSp", style.lineHeight.value)
        .put("letterSpacingSp", style.letterSpacing.value)
        .put("fontWeight", style.fontWeight?.weight)
        .put("fontFamily", "Google Sans Flex")

    private fun exportPolygon(polygon: RoundedPolygon): JSONObject {
        val cubics = JSONArray()
        val pathData = StringBuilder()
        polygon.cubics.forEachIndexed { index, cubic ->
            if (index == 0) pathData.append("M${cubic.anchor0X},${cubic.anchor0Y}")
            pathData.append(" C${cubic.control0X},${cubic.control0Y} ${cubic.control1X},${cubic.control1Y} ${cubic.anchor1X},${cubic.anchor1Y}")
            cubics.put(JSONArray(listOf(
                cubic.anchor0X, cubic.anchor0Y, cubic.control0X, cubic.control0Y,
                cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y,
            )))
        }
        pathData.append(" Z")
        val measure = PathMeasure(polygon.toPath(), false)
        val samples = List(720) { index ->
            val point = FloatArray(2)
            measure.getPosTan(measure.length * index / 720, point, null)
            point
        }
        val minX = samples.minOf { it[0] }
        val maxX = samples.maxOf { it[0] }
        val minY = samples.minOf { it[1] }
        val maxY = samples.maxOf { it[1] }
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        val outerRadius = samples.maxOf { hypot(it[0] - centerX, it[1] - centerY) }
        return JSONObject()
            .put("cubics", cubics)
            .put("svgPath", pathData.toString())
            .put("measuredBounds720", JSONArray(listOf(minX, minY, maxX, maxY)))
            .put("center720", JSONArray(listOf(centerX, centerY)))
            .put("outerRadius720", outerRadius)
    }
}
