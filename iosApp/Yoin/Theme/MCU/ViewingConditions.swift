/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/hct/ViewingConditions.kt.
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

/// In traditional color spaces, a color can be identified solely by the observer's measurement of
/// the color. Color appearance models such as CAM16 also use information about the environment
/// where the color was observed, known as the viewing conditions.
struct ViewingConditions: Sendable {
    let n: Double
    let aw: Double
    let nbb: Double
    let ncb: Double
    let c: Double
    let nc: Double
    let rgbD: [Double]
    let fl: Double
    let flRoot: Double
    let z: Double

    /// sRGB-like viewing conditions.
    static let DEFAULT: ViewingConditions = defaultWithBackgroundLstar(50.0)

    static func make(
        whitePoint: [Double],
        adaptingLuminance: Double,
        backgroundLstar: Double,
        surround: Double,
        discountingIlluminant: Bool
    ) -> ViewingConditions {
        let backgroundLstar = max(0.1, backgroundLstar)
        let matrix = Cam16.XYZ_TO_CAM16RGB
        let xyz = whitePoint
        let rW = xyz[0] * matrix[0][0] + xyz[1] * matrix[0][1] + xyz[2] * matrix[0][2]
        let gW = xyz[0] * matrix[1][0] + xyz[1] * matrix[1][1] + xyz[2] * matrix[1][2]
        let bW = xyz[0] * matrix[2][0] + xyz[1] * matrix[2][1] + xyz[2] * matrix[2][2]
        let f = 0.8 + surround / 10.0
        let c: Double = f >= 0.9
            ? MathUtils.lerp(0.59, 0.69, (f - 0.9) * 10.0)
            : MathUtils.lerp(0.525, 0.59, (f - 0.8) * 10.0)
        var d: Double = discountingIlluminant
            ? 1.0
            : f * (1.0 - 1.0 / 3.6 * exp((-adaptingLuminance - 42.0) / 92.0))
        d = d.coerceIn(0.0, 1.0)
        let nc = f
        let rgbD: [Double] = [
            d * (100.0 / rW) + 1.0 - d,
            d * (100.0 / gW) + 1.0 - d,
            d * (100.0 / bW) + 1.0 - d,
        ]
        let k = 1.0 / (5.0 * adaptingLuminance + 1.0)
        let k4 = k * k * k * k
        let k4F = 1.0 - k4
        let fl = k4 * adaptingLuminance + 0.1 * k4F * k4F * cbrt(5.0 * adaptingLuminance)
        let n = ColorUtils.yFromLstar(backgroundLstar) / whitePoint[1]
        let z = 1.48 + sqrt(n)
        let nbb = 0.725 / pow(n, 0.2)
        let ncb = nbb
        let rgbAFactors: [Double] = [
            pow(fl * rgbD[0] * rW / 100.0, 0.42),
            pow(fl * rgbD[1] * gW / 100.0, 0.42),
            pow(fl * rgbD[2] * bW / 100.0, 0.42),
        ]
        let rgbA: [Double] = [
            400.0 * rgbAFactors[0] / (rgbAFactors[0] + 27.13),
            400.0 * rgbAFactors[1] / (rgbAFactors[1] + 27.13),
            400.0 * rgbAFactors[2] / (rgbAFactors[2] + 27.13),
        ]
        let aw = (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb
        return ViewingConditions(
            n: n, aw: aw, nbb: nbb, ncb: ncb, c: c, nc: nc, rgbD: rgbD,
            fl: fl, flRoot: pow(fl, 0.25), z: z
        )
    }

    static func defaultWithBackgroundLstar(_ lstar: Double) -> ViewingConditions {
        make(
            whitePoint: ColorUtils.whitePointD65(),
            adaptingLuminance: 200.0 / Double.pi * ColorUtils.yFromLstar(50.0) / 100.0,
            backgroundLstar: lstar,
            surround: 2.0,
            discountingIlluminant: false
        )
    }
}
