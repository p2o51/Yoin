/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/hct/Cam16.kt.
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

/// CAM16, a color appearance model. Colors are not just defined by their hex code, but rather, a hex
/// code and viewing conditions.
struct Cam16: Sendable {
    let hue: Double
    let chroma: Double
    let j: Double
    let q: Double
    let m: Double
    let s: Double
    let jstar: Double
    let astar: Double
    let bstar: Double

    static let XYZ_TO_CAM16RGB: [[Double]] = [
        [0.401288, 0.650173, -0.051461],
        [-0.250268, 1.204414, 0.045854],
        [-0.002079, 0.048952, 0.953127],
    ]

    static let CAM16RGB_TO_XYZ: [[Double]] = [
        [1.8620678, -1.0112547, 0.14918678],
        [0.38752654, 0.62144744, -0.00897398],
        [-0.0158415, -0.03412294, 1.0499644],
    ]

    /// CAM16 instances also have coordinates in the CAM16-UCS space, called J*, a*, b*.
    func distance(_ other: Cam16) -> Double {
        let dJ = jstar - other.jstar
        let dA = astar - other.astar
        let dB = bstar - other.bstar
        let dEPrime = sqrt(dJ * dJ + dA * dA + dB * dB)
        return 1.41 * pow(dEPrime, 0.63)
    }

    /// ARGB representation of the color. Assumes the color was viewed in default viewing conditions.
    func toInt() -> Int { viewed(ViewingConditions.DEFAULT) }

    func viewed(_ viewingConditions: ViewingConditions) -> Int {
        let xyz = xyzInViewingConditions(viewingConditions)
        return ColorUtils.argbFromXyz(xyz[0], xyz[1], xyz[2])
    }

    func xyzInViewingConditions(_ viewingConditions: ViewingConditions) -> [Double] {
        let alpha = (chroma == 0.0 || j == 0.0) ? 0.0 : chroma / sqrt(j / 100.0)
        let t = pow(alpha / pow(1.64 - pow(0.29, viewingConditions.n), 0.73), 1.0 / 0.9)
        let hRad = jvmToRadians(hue)
        let eHue = 0.25 * (cos(hRad + 2.0) + 3.8)
        let ac = viewingConditions.aw * pow(j / 100.0, 1.0 / viewingConditions.c / viewingConditions.z)
        let p1 = eHue * (50000.0 / 13.0) * viewingConditions.nc * viewingConditions.ncb
        let p2 = ac / viewingConditions.nbb
        let hSin = sin(hRad)
        let hCos = cos(hRad)
        let gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin)
        let a = gamma * hCos
        let b = gamma * hSin
        let rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
        let gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
        let bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0
        let rCBase = max(0.0, 27.13 * abs(rA) / (400.0 - abs(rA)))
        let rC = jvmSignum(rA) * (100.0 / viewingConditions.fl) * pow(rCBase, 1.0 / 0.42)
        let gCBase = max(0.0, 27.13 * abs(gA) / (400.0 - abs(gA)))
        let gC = jvmSignum(gA) * (100.0 / viewingConditions.fl) * pow(gCBase, 1.0 / 0.42)
        let bCBase = max(0.0, 27.13 * abs(bA) / (400.0 - abs(bA)))
        let bC = jvmSignum(bA) * (100.0 / viewingConditions.fl) * pow(bCBase, 1.0 / 0.42)
        let rF = rC / viewingConditions.rgbD[0]
        let gF = gC / viewingConditions.rgbD[1]
        let bF = bC / viewingConditions.rgbD[2]
        let matrix = Cam16.CAM16RGB_TO_XYZ
        let x = rF * matrix[0][0] + gF * matrix[0][1] + bF * matrix[0][2]
        let y = rF * matrix[1][0] + gF * matrix[1][1] + bF * matrix[1][2]
        let z = rF * matrix[2][0] + gF * matrix[2][1] + bF * matrix[2][2]
        return [x, y, z]
    }

    static func fromInt(_ argb: Int) -> Cam16 {
        fromIntInViewingConditions(argb, ViewingConditions.DEFAULT)
    }

    static func fromIntInViewingConditions(_ argb: Int, _ viewingConditions: ViewingConditions) -> Cam16 {
        let red = (argb & 0x00ff0000) >> 16
        let green = (argb & 0x0000ff00) >> 8
        let blue = argb & 0x000000ff
        let redL = ColorUtils.linearized(red)
        let greenL = ColorUtils.linearized(green)
        let blueL = ColorUtils.linearized(blue)
        let x = 0.41233895 * redL + 0.35762064 * greenL + 0.18051042 * blueL
        let y = 0.2126 * redL + 0.7152 * greenL + 0.0722 * blueL
        let z = 0.01932141 * redL + 0.11916382 * greenL + 0.95034478 * blueL
        return fromXyzInViewingConditions(x, y, z, viewingConditions)
    }

    static func fromXyzInViewingConditions(
        _ x: Double, _ y: Double, _ z: Double, _ viewingConditions: ViewingConditions
    ) -> Cam16 {
        let matrix = XYZ_TO_CAM16RGB
        let rT = x * matrix[0][0] + y * matrix[0][1] + z * matrix[0][2]
        let gT = x * matrix[1][0] + y * matrix[1][1] + z * matrix[1][2]
        let bT = x * matrix[2][0] + y * matrix[2][1] + z * matrix[2][2]
        let rD = viewingConditions.rgbD[0] * rT
        let gD = viewingConditions.rgbD[1] * gT
        let bD = viewingConditions.rgbD[2] * bT
        let rAF = pow(viewingConditions.fl * abs(rD) / 100.0, 0.42)
        let gAF = pow(viewingConditions.fl * abs(gD) / 100.0, 0.42)
        let bAF = pow(viewingConditions.fl * abs(bD) / 100.0, 0.42)
        let rA = jvmSignum(rD) * 400.0 * rAF / (rAF + 27.13)
        let gA = jvmSignum(gD) * 400.0 * gAF / (gAF + 27.13)
        let bA = jvmSignum(bD) * 400.0 * bAF / (bAF + 27.13)
        let a = (11.0 * rA + -12.0 * gA + bA) / 11.0
        let b = (rA + gA - 2.0 * bA) / 9.0
        let u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
        let p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0
        let atanDegrees = jvmToDegrees(atan2(b, a))
        let hue = MathUtils.sanitizeDegreesDouble(atanDegrees)
        let hueRadians = jvmToRadians(hue)
        let ac = p2 * viewingConditions.nbb
        let j = 100.0 * pow(ac / viewingConditions.aw, viewingConditions.c * viewingConditions.z)
        let q = 4.0 / viewingConditions.c * sqrt(j / 100.0) * (viewingConditions.aw + 4.0) * viewingConditions.flRoot
        let huePrime = hue < 20.14 ? hue + 360 : hue
        let eHue = 0.25 * (cos(jvmToRadians(huePrime) + 2.0) + 3.8)
        let p1 = 50000.0 / 13.0 * eHue * viewingConditions.nc * viewingConditions.ncb
        let t = p1 * hypot(a, b) / (u + 0.305)
        let alpha = pow(1.64 - pow(0.29, viewingConditions.n), 0.73) * pow(t, 0.9)
        let c = alpha * sqrt(j / 100.0)
        let m = c * viewingConditions.flRoot
        let s = 50.0 * sqrt(alpha * viewingConditions.c / (viewingConditions.aw + 4.0))
        let jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j)
        let mstar = 1.0 / 0.0228 * log1p(0.0228 * m)
        let astar = mstar * cos(hueRadians)
        let bstar = mstar * sin(hueRadians)
        return Cam16(hue: hue, chroma: c, j: j, q: q, m: m, s: s, jstar: jstar, astar: astar, bstar: bstar)
    }

    static func fromJch(_ j: Double, _ c: Double, _ h: Double) -> Cam16 {
        fromJchInViewingConditions(j, c, h, ViewingConditions.DEFAULT)
    }

    private static func fromJchInViewingConditions(
        _ j: Double, _ c: Double, _ h: Double, _ viewingConditions: ViewingConditions
    ) -> Cam16 {
        let q = 4.0 / viewingConditions.c * sqrt(j / 100.0) * (viewingConditions.aw + 4.0) * viewingConditions.flRoot
        let m = c * viewingConditions.flRoot
        let alpha = c / sqrt(j / 100.0)
        let s = 50.0 * sqrt(alpha * viewingConditions.c / (viewingConditions.aw + 4.0))
        let hueRadians = jvmToRadians(h)
        let jstar = (1.0 + 100.0 * 0.007) * j / (1.0 + 0.007 * j)
        let mstar = 1.0 / 0.0228 * log1p(0.0228 * m)
        let astar = mstar * cos(hueRadians)
        let bstar = mstar * sin(hueRadians)
        return Cam16(hue: h, chroma: c, j: j, q: q, m: m, s: s, jstar: jstar, astar: astar, bstar: bstar)
    }

    static func fromUcs(_ jstar: Double, _ astar: Double, _ bstar: Double) -> Cam16 {
        fromUcsInViewingConditions(jstar, astar, bstar, ViewingConditions.DEFAULT)
    }

    static func fromUcsInViewingConditions(
        _ jstar: Double, _ astar: Double, _ bstar: Double, _ viewingConditions: ViewingConditions
    ) -> Cam16 {
        let m = hypot(astar, bstar)
        let m2 = expm1(m * 0.0228) / 0.0228
        let c = m2 / viewingConditions.flRoot
        var h = atan2(bstar, astar) * (180.0 / Double.pi)
        if h < 0.0 { h += 360.0 }
        let j = jstar / (1.0 - (jstar - 100.0) * 0.007)
        return fromJchInViewingConditions(j, c, h, viewingConditions)
    }
}
