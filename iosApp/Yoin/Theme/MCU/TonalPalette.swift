/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/palettes/TonalPalette.kt.
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

import Foundation

/// A convenience class for retrieving colors that are constant in hue and chroma, but vary in tone.
///
/// The Kotlin class memoizes `tone(_:)` in a mutable map; this value type
/// recomputes instead (same results, and it stays `Sendable`).
struct TonalPalette: Sendable {
    let hue: Double
    let chroma: Double
    let keyColor: Hct

    private init(hue: Double, chroma: Double, keyColor: Hct) {
        self.hue = hue
        self.chroma = chroma
        self.keyColor = keyColor
    }

    /// Create an ARGB color with HCT hue and chroma of this palette, and the provided HCT tone.
    func tone(_ tone: Int) -> Int {
        if tone == 99 && Hct.isYellow(hue) {
            return averageArgb(self.tone(98), self.tone(100))
        }
        return Hct.from(hue, chroma, Double(tone)).toInt()
    }

    /// Given a tone, use hue and chroma of palette to create a color, and return it as HCT.
    func getHct(_ tone: Double) -> Hct {
        if tone == 99.0 && Hct.isYellow(hue) {
            return Hct.fromInt(self.tone(99))
        }
        return Hct.from(hue, chroma, tone)
    }

    private func averageArgb(_ argb1: Int, _ argb2: Int) -> Int {
        let red1 = (argb1 >> 16) & 0xff
        let green1 = (argb1 >> 8) & 0xff
        let blue1 = argb1 & 0xff
        let red2 = (argb2 >> 16) & 0xff
        let green2 = (argb2 >> 8) & 0xff
        let blue2 = argb2 & 0xff
        let red = jvmRoundToInt(Float(red1 + red2) / 2)
        let green = jvmRoundToInt(Float(green1 + green2) / 2)
        let blue = jvmRoundToInt(Float(blue1 + blue2) / 2)
        return (255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255)
    }

    /// Key color is a color that represents the hue and chroma of a tonal palette.
    private struct KeyColor {
        let hue: Double
        let requestedChroma: Double
        private static let MAX_CHROMA_VALUE = 200.0

        /// Creates a key color from a [hue] and a [chroma]. The key color is the first tone, starting
        /// from T50, matching the given hue and chroma.
        func create() -> Hct {
            var chromaCache: [Int: Double] = [:]
            func maxChroma(_ tone: Int) -> Double {
                if let cached = chromaCache[tone] { return cached }
                let value = Hct.from(hue, KeyColor.MAX_CHROMA_VALUE, Double(tone)).chroma
                chromaCache[tone] = value
                return value
            }
            let pivotTone = 50
            let toneStepSize = 1
            let epsilon = 0.01
            var lowerTone = 0
            var upperTone = 100
            while lowerTone < upperTone {
                let midTone = (lowerTone + upperTone) / 2
                let isAscending = maxChroma(midTone) < maxChroma(midTone + toneStepSize)
                let sufficientChroma = maxChroma(midTone) >= requestedChroma - epsilon
                if sufficientChroma {
                    if abs(Double(lowerTone - pivotTone)) < abs(Double(upperTone - pivotTone)) {
                        upperTone = midTone
                    } else {
                        if lowerTone == midTone {
                            return Hct.from(hue, requestedChroma, Double(lowerTone))
                        }
                        lowerTone = midTone
                    }
                } else {
                    if isAscending {
                        lowerTone = midTone + toneStepSize
                    } else {
                        upperTone = midTone
                    }
                }
            }
            return Hct.from(hue, requestedChroma, Double(lowerTone))
        }
    }

    static func fromInt(_ argb: Int) -> TonalPalette { fromHct(Hct.fromInt(argb)) }

    static func fromHct(_ hct: Hct) -> TonalPalette {
        TonalPalette(hue: hct.hue, chroma: hct.chroma, keyColor: hct)
    }

    static func fromHueAndChroma(_ hue: Double, _ chroma: Double) -> TonalPalette {
        let keyColor = KeyColor(hue: hue, requestedChroma: chroma).create()
        return TonalPalette(hue: hue, chroma: chroma, keyColor: keyColor)
    }
}
