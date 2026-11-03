/*
 * Copyright 2014 The Android Open Source Project
 *
 * Modified for Yoin (Swift port) of androidx/palette/graphics/Palette.java and the
 * RGBToHSL of androidx/core/graphics/ColorUtils.java (androidx.palette:palette, Apache 2.0), kept close to the Java source so
 * vibrant / dominant / muted picks match Android's.
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

import CoreGraphics
import Foundation

/// A helper class to extract prominent colors from an image.
///
/// Ports the parts of AndroidX Palette Yoin uses: `Palette.from(bitmap)
/// .maximumColorCount(n).generate()` with the default resize area
/// (112 × 112), the default filter and the six default targets.
struct Palette: Sendable {
    /// A filter which can be passed to the builder to filter out colors.
    struct Filter: Sendable {
        let predicate: @Sendable (_ rgb: Int, _ hsl: [Float]) -> Bool

        init(_ predicate: @escaping @Sendable (_ rgb: Int, _ hsl: [Float]) -> Bool) {
            self.predicate = predicate
        }

        /// Hook to allow clients to be able filter colors from resulting palette.
        func isAllowed(_ rgb: Int, _ hsl: [Float]) -> Bool { predicate(rgb, hsl) }
    }

    /// Represents a color swatch generated from an image's palette.
    struct Swatch: Sendable, Hashable {
        let rgb: Int
        let population: Int
        let hsl: [Float]

        init(rgb: Int, population: Int) {
            self.rgb = rgb
            self.population = population
            self.hsl = Palette.rgbToHsl(rgb)
        }
    }

    static let DEFAULT_RESIZE_BITMAP_AREA = 112 * 112
    static let DEFAULT_CALCULATE_NUMBER_COLORS = 16

    /// The default filter: no near-black, near-white or skin-tone "I line" colors.
    static let DEFAULT_FILTER = Filter { _, hsl in
        let BLACK_MAX_LIGHTNESS: Float = 0.05
        let WHITE_MIN_LIGHTNESS: Float = 0.95
        func isBlack(_ hslColor: [Float]) -> Bool { hslColor[2] <= BLACK_MAX_LIGHTNESS }
        func isWhite(_ hslColor: [Float]) -> Bool { hslColor[2] >= WHITE_MIN_LIGHTNESS }
        func isNearRedILine(_ hslColor: [Float]) -> Bool {
            hslColor[0] >= 10 && hslColor[0] <= 37 && hslColor[1] <= 0.82
        }
        return !isWhite(hsl) && !isBlack(hsl) && !isNearRedILine(hsl)
    }

    let swatches: [Swatch]
    let targets: [Target]
    private(set) var selectedSwatches: [Target: Swatch] = [:]
    let dominantSwatch: Swatch?

    init(swatches: [Swatch], targets: [Target]) {
        self.swatches = swatches
        self.targets = targets
        dominantSwatch = Palette.findDominantSwatch(swatches)
    }

    var lightVibrantSwatch: Swatch? { selectedSwatches[Target.LIGHT_VIBRANT] }
    var vibrantSwatch: Swatch? { selectedSwatches[Target.VIBRANT] }
    var darkVibrantSwatch: Swatch? { selectedSwatches[Target.DARK_VIBRANT] }
    var lightMutedSwatch: Swatch? { selectedSwatches[Target.LIGHT_MUTED] }
    var mutedSwatch: Swatch? { selectedSwatches[Target.MUTED] }
    var darkMutedSwatch: Swatch? { selectedSwatches[Target.DARK_MUTED] }

    // MARK: Builder

    /// `Palette.from(bitmap).maximumColorCount(maxColors).generate()` on an
    /// ARGB pixel buffer (Android `Bitmap.getPixels` layout).
    static func generate(
        pixels: [UInt32],
        width: Int,
        height: Int,
        maximumColorCount maxColors: Int = DEFAULT_CALCULATE_NUMBER_COLORS,
        filters: [Filter] = [DEFAULT_FILTER]
    ) -> Palette {
        let scaled = scaleBitmapDown(pixels: pixels, width: width, height: height)
        let quantizer = ColorCutQuantizer(pixels: scaled, maxColors: maxColors, filters: filters)
        var p = Palette(
            swatches: quantizer.quantizedColors,
            targets: [.LIGHT_VIBRANT, .VIBRANT, .DARK_VIBRANT, .LIGHT_MUTED, .MUTED, .DARK_MUTED]
        )
        p.generate()
        return p
    }

    /// Builder.scaleBitmapDown with the default resize area, then
    /// `Bitmap.createScaledBitmap(..., filter = false)` (nearest neighbour,
    /// sampled at pixel centres).
    static func scaleBitmapDown(pixels: [UInt32], width: Int, height: Int) -> [UInt32] {
        var scaleRatio = -1.0
        let bitmapArea = width * height
        if bitmapArea > DEFAULT_RESIZE_BITMAP_AREA {
            scaleRatio = (Double(DEFAULT_RESIZE_BITMAP_AREA) / Double(bitmapArea)).squareRoot()
        }
        if scaleRatio <= 0 { return pixels }
        let dstWidth = Int((Double(width) * scaleRatio).rounded(.up))
        let dstHeight = Int((Double(height) * scaleRatio).rounded(.up))
        var out = [UInt32](repeating: 0, count: dstWidth * dstHeight)
        let sx = Double(width) / Double(dstWidth)
        let sy = Double(height) / Double(dstHeight)
        for y in 0..<dstHeight {
            let srcY = min(height - 1, Int(((Double(y) + 0.5) * sy).rounded(.down)))
            for x in 0..<dstWidth {
                let srcX = min(width - 1, Int(((Double(x) + 0.5) * sx).rounded(.down)))
                out[y * dstWidth + x] = pixels[srcY * width + srcX]
            }
        }
        return out
    }

    // MARK: Generation

    private mutating func generate() {
        // We need to make sure that the scored targets are generated first. This is so that
        // inherited targets have something to inherit from
        var usedColors = Set<Int>()
        for var target in targets {
            let key = target
            target.normalizeWeights()
            selectedSwatches[key] = generateScoredTarget(target, &usedColors)
        }
    }

    private func generateScoredTarget(_ target: Target, _ usedColors: inout Set<Int>) -> Swatch? {
        let maxScoreSwatch = getMaxScoredSwatchForTarget(target, usedColors)
        if let maxScoreSwatch, target.isExclusive {
            // If we have a swatch, and the target is exclusive, add the color to the used list
            usedColors.insert(maxScoreSwatch.rgb)
        }
        return maxScoreSwatch
    }

    private func getMaxScoredSwatchForTarget(_ target: Target, _ usedColors: Set<Int>) -> Swatch? {
        var maxScore: Float = 0
        var maxScoreSwatch: Swatch?
        for swatch in swatches where shouldBeScoredForTarget(swatch, target, usedColors) {
            let score = generateScore(swatch, target)
            if maxScoreSwatch == nil || score > maxScore {
                maxScoreSwatch = swatch
                maxScore = score
            }
        }
        return maxScoreSwatch
    }

    private func shouldBeScoredForTarget(_ swatch: Swatch, _ target: Target, _ usedColors: Set<Int>) -> Bool {
        // Check whether the HSL values are within the correct ranges, and this color hasn't
        // been used yet.
        let hsl = swatch.hsl
        return hsl[1] >= target.minimumSaturation && hsl[1] <= target.maximumSaturation
            && hsl[2] >= target.minimumLightness && hsl[2] <= target.maximumLightness
            && !usedColors.contains(swatch.rgb)
    }

    private func generateScore(_ swatch: Swatch, _ target: Target) -> Float {
        let hsl = swatch.hsl
        var saturationScore: Float = 0
        var luminanceScore: Float = 0
        var populationScore: Float = 0
        let maxPopulation = dominantSwatch?.population ?? 1
        if target.saturationWeight > 0 {
            saturationScore = target.saturationWeight * (1 - abs(hsl[1] - target.targetSaturation))
        }
        if target.lightnessWeight > 0 {
            luminanceScore = target.lightnessWeight * (1 - abs(hsl[2] - target.targetLightness))
        }
        if target.populationWeight > 0 {
            populationScore = target.populationWeight * (Float(swatch.population) / Float(maxPopulation))
        }
        return saturationScore + luminanceScore + populationScore
    }

    private static func findDominantSwatch(_ swatches: [Swatch]) -> Swatch? {
        var maxPop = Int(Int32.min)
        var maxSwatch: Swatch?
        for swatch in swatches where swatch.population > maxPop {
            maxSwatch = swatch
            maxPop = swatch.population
        }
        return maxSwatch
    }

    // MARK: androidx.core ColorUtils.RGBToHSL (Java float math)

    static func rgbToHsl(_ rgb: Int) -> [Float] {
        let rf = Float((rgb >> 16) & 0xff) / 255
        let gf = Float((rgb >> 8) & 0xff) / 255
        let bf = Float(rgb & 0xff) / 255
        let maxV = max(rf, max(gf, bf))
        let minV = min(rf, min(gf, bf))
        let deltaMaxMin = maxV - minV
        var h: Float
        let s: Float
        let l = (maxV + minV) / 2
        if maxV == minV {
            // Monochromatic
            h = 0
            s = 0
        } else {
            if maxV == rf {
                h = ((gf - bf) / deltaMaxMin).truncatingRemainder(dividingBy: 6)
            } else if maxV == gf {
                h = ((bf - rf) / deltaMaxMin) + 2
            } else {
                h = ((rf - gf) / deltaMaxMin) + 4
            }
            s = deltaMaxMin / (1 - abs(2 * l - 1))
        }
        h = (h * 60).truncatingRemainder(dividingBy: 360)
        if h < 0 {
            h += 360
        }
        return [h.coerceIn(0, 360), s.coerceIn(0, 1), l.coerceIn(0, 1)]
    }
}
