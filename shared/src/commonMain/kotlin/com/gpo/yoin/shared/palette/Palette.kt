/*
 * Copyright 2014 The Android Open Source Project
 *
 * Modified for Yoin: the parts of androidx/palette/graphics/Palette.java (androidx.palette:palette
 * 1.0.0) Yoin uses, and the RGBToHSL of androidx/core/graphics/ColorUtils.java, ported to Kotlin
 * Multiplatform common code and kept close to the Java so the picks are byte-identical to Android's
 * (PaletteParityTest in :app). The Builder's bitmap resize is the caller's (it needs a bitmap
 * type); [Palette.scaleDownPixels] is a nearest-neighbour stand-in for callers without one.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gpo.yoin.shared.palette

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A helper class to extract prominent colors from an image: `Palette.from(bitmap)
 * .maximumColorCount(n).generate()` with the default (or given) filters and the six default
 * targets, run on the bitmap's ARGB pixels.
 */
class Palette internal constructor(
    /** All of the swatches which make up the palette. */
    val swatches: List<Swatch>,
    private val targets: List<Target>,
) {
    /** A filter which can be passed to the builder to filter out colors. */
    fun interface Filter {
        /**
         * Hook to allow clients to be able filter colors from resulting palette.
         *
         * @param rgb the color in RGB888.
         * @param hsl HSL representation of the color.
         * @return true if the color is allowed, false if not.
         */
        fun isAllowed(rgb: Int, hsl: FloatArray): Boolean
    }

    /** Represents a color swatch generated from an image's palette. */
    class Swatch(
        /** This swatch's RGB color value. */
        val rgb: Int,
        /** The number of pixels represented by this swatch. */
        val population: Int,
    ) {
        /**
         * This swatch's HSL values: hsl[0] is Hue [0 .. 360), hsl[1] is Saturation [0...1],
         * hsl[2] is Lightness [0...1].
         */
        val hsl: FloatArray by lazy { rgbToHsl(rgb) }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Swatch) return false
            return population == other.population && rgb == other.rgb
        }

        override fun hashCode(): Int = 31 * rgb + population

        override fun toString(): String = "Swatch(rgb=#${(rgb.toLong() and 0xFFFFFFFFL).toString(16)}, population=$population)"
    }

    private val selectedSwatches = HashMap<Target, Swatch?>()
    private val usedColors = HashSet<Int>()

    /** The dominant swatch, the swatch with the greatest population. */
    val dominantSwatch: Swatch? = findDominantSwatch()

    /** A light and vibrant swatch, if one was found. */
    val lightVibrantSwatch: Swatch? get() = getSwatchForTarget(Target.LIGHT_VIBRANT)

    /** A vibrant swatch, if one was found. */
    val vibrantSwatch: Swatch? get() = getSwatchForTarget(Target.VIBRANT)

    /** A dark and vibrant swatch, if one was found. */
    val darkVibrantSwatch: Swatch? get() = getSwatchForTarget(Target.DARK_VIBRANT)

    /** A light and muted swatch, if one was found. */
    val lightMutedSwatch: Swatch? get() = getSwatchForTarget(Target.LIGHT_MUTED)

    /** A muted swatch, if one was found. */
    val mutedSwatch: Swatch? get() = getSwatchForTarget(Target.MUTED)

    /** A dark and muted swatch, if one was found. */
    val darkMutedSwatch: Swatch? get() = getSwatchForTarget(Target.DARK_MUTED)

    /** The selected swatch for the given target from the palette, or null if one could not be found. */
    fun getSwatchForTarget(target: Target): Swatch? = selectedSwatches[target]

    internal fun generate() {
        // We need to make sure that the scored targets are generated first. This is so that
        // inherited targets have something to inherit from
        for (target in targets) {
            target.normalizeWeights()
            selectedSwatches[target] = generateScoredTarget(target)
        }
        // We now clear out the used colors
        usedColors.clear()
    }

    private fun generateScoredTarget(target: Target): Swatch? {
        val maxScoreSwatch = getMaxScoredSwatchForTarget(target)
        if (maxScoreSwatch != null && target.isExclusive) {
            // If we have a swatch, and the target is exclusive, add the color to the used list
            usedColors.add(maxScoreSwatch.rgb)
        }
        return maxScoreSwatch
    }

    private fun getMaxScoredSwatchForTarget(target: Target): Swatch? {
        var maxScore = 0f
        var maxScoreSwatch: Swatch? = null
        for (swatch in swatches) {
            if (shouldBeScoredForTarget(swatch, target)) {
                val score = generateScore(swatch, target)
                if (maxScoreSwatch == null || score > maxScore) {
                    maxScoreSwatch = swatch
                    maxScore = score
                }
            }
        }
        return maxScoreSwatch
    }

    private fun shouldBeScoredForTarget(swatch: Swatch, target: Target): Boolean {
        // Check whether the HSL values are within the correct ranges, and this color hasn't
        // been used yet.
        val hsl = swatch.hsl
        return hsl[1] >= target.minimumSaturation && hsl[1] <= target.maximumSaturation &&
            hsl[2] >= target.minimumLightness && hsl[2] <= target.maximumLightness &&
            !usedColors.contains(swatch.rgb)
    }

    private fun generateScore(swatch: Swatch, target: Target): Float {
        val hsl = swatch.hsl

        var saturationScore = 0f
        var luminanceScore = 0f
        var populationScore = 0f

        val maxPopulation = dominantSwatch?.population ?: 1

        if (target.saturationWeight > 0) {
            saturationScore = target.saturationWeight * (1f - abs(hsl[1] - target.targetSaturation))
        }
        if (target.lightnessWeight > 0) {
            luminanceScore = target.lightnessWeight * (1f - abs(hsl[2] - target.targetLightness))
        }
        if (target.populationWeight > 0) {
            populationScore = target.populationWeight * (swatch.population / maxPopulation.toFloat())
        }

        return saturationScore + luminanceScore + populationScore
    }

    private fun findDominantSwatch(): Swatch? {
        var maxPop = Int.MIN_VALUE
        var maxSwatch: Swatch? = null
        for (swatch in swatches) {
            if (swatch.population > maxPop) {
                maxSwatch = swatch
                maxPop = swatch.population
            }
        }
        return maxSwatch
    }

    companion object {
        const val DEFAULT_RESIZE_BITMAP_AREA = 112 * 112
        const val DEFAULT_CALCULATE_NUMBER_COLORS = 16

        private const val BLACK_MAX_LIGHTNESS = 0.05f
        private const val WHITE_MIN_LIGHTNESS = 0.95f

        /** The default filter: no near-black, near-white or skin-tone "I line" colors. */
        val DEFAULT_FILTER: Filter = Filter { _, hsl -> !isWhite(hsl) && !isBlack(hsl) && !isNearRedILine(hsl) }

        /** @return true if the color represents a color which is close to black. */
        private fun isBlack(hslColor: FloatArray): Boolean = hslColor[2] <= BLACK_MAX_LIGHTNESS

        /** @return true if the color represents a color which is close to white. */
        private fun isWhite(hslColor: FloatArray): Boolean = hslColor[2] >= WHITE_MIN_LIGHTNESS

        /** @return true if the color lies close to the red side of the I line. */
        private fun isNearRedILine(hslColor: FloatArray): Boolean =
            hslColor[0] >= 10f && hslColor[0] <= 37f && hslColor[1] <= 0.82f

        /**
         * `Palette.from(bitmap).maximumColorCount(maxColors)[.clearFilters()].generate()` on the
         * bitmap's ARGB pixels (Android `Bitmap.getPixels` layout, after the Builder's resize).
         * [pixels] is left untouched.
         *
         * @param filters the Builder's filters; empty = `clearFilters()`.
         */
        fun generate(
            pixels: IntArray,
            maximumColorCount: Int = DEFAULT_CALCULATE_NUMBER_COLORS,
            filters: List<Filter> = listOf(DEFAULT_FILTER),
        ): Palette {
            val quantizer = ColorCutQuantizer(pixels.copyOf(), maximumColorCount, filters)
            val palette = Palette(quantizer.quantizedColors, Target.DEFAULTS)
            palette.generate()
            return palette
        }

        /**
         * The size the Builder scales a [width] × [height] bitmap down to before quantizing (its
         * default resize area, [DEFAULT_RESIZE_BITMAP_AREA]): `[width, height]`, unchanged when the
         * bitmap is small enough.
         */
        fun scaledSize(width: Int, height: Int): IntArray {
            val bitmapArea = width * height
            if (bitmapArea <= DEFAULT_RESIZE_BITMAP_AREA) return intArrayOf(width, height)
            val scaleRatio = sqrt(DEFAULT_RESIZE_BITMAP_AREA / bitmapArea.toDouble())
            return intArrayOf(ceil(width * scaleRatio).toInt(), ceil(height * scaleRatio).toInt())
        }

        /**
         * The Builder's resize for callers without an Android bitmap: [scaledSize], nearest
         * neighbour sampled at pixel centres (`Bitmap.createScaledBitmap(…, filter = false)`).
         * Pixel-exact against Skia for the 200 px cover decode both apps use (200 → 112, checked by
         * PaletteParityTest); at some other sizes Skia's fixed-point stepping picks the neighbouring
         * source pixel where a sample lands exactly on a pixel edge.
         */
        fun scaleDownPixels(pixels: IntArray, width: Int, height: Int): IntArray {
            val size = scaledSize(width, height)
            val dstWidth = size[0]
            val dstHeight = size[1]
            if (dstWidth == width && dstHeight == height) return pixels.copyOf()
            val out = IntArray(dstWidth * dstHeight)
            val sx = width.toDouble() / dstWidth
            val sy = height.toDouble() / dstHeight
            for (y in 0 until dstHeight) {
                val srcY = min(height - 1, floor((y + 0.5) * sy).toInt())
                for (x in 0 until dstWidth) {
                    val srcX = min(width - 1, floor((x + 0.5) * sx).toInt())
                    out[y * dstWidth + x] = pixels[srcY * width + srcX]
                }
            }
            return out
        }

        /** androidx.core ColorUtils.RGBToHSL, Java float math. */
        fun rgbToHsl(rgb: Int): FloatArray {
            val rf = ((rgb shr 16) and 0xFF) / 255f
            val gf = ((rgb shr 8) and 0xFF) / 255f
            val bf = (rgb and 0xFF) / 255f

            val max = max(rf, max(gf, bf))
            val min = min(rf, min(gf, bf))
            val deltaMaxMin = max - min

            var h: Float
            val s: Float
            val l = (max + min) / 2f

            if (max == min) {
                // Monochromatic
                h = 0f
                s = 0f
            } else {
                h = if (max == rf) {
                    ((gf - bf) / deltaMaxMin) % 6f
                } else if (max == gf) {
                    ((bf - rf) / deltaMaxMin) + 2f
                } else {
                    ((rf - gf) / deltaMaxMin) + 4f
                }
                s = deltaMaxMin / (1f - abs(2f * l - 1f))
            }

            h = (h * 60f) % 360f
            if (h < 0) {
                h += 360f
            }

            return floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }
    }
}
