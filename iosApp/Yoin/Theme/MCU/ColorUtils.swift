/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/utils/ColorUtils.kt.
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

/// Color science utilities. ARGB colors are carried as `Int` holding the
/// unsigned 32-bit value (0xAARRGGBB), where Kotlin uses a signed `Int`.
enum ColorUtils {
    static let SRGB_TO_XYZ: [[Double]] = [
        [0.41233895, 0.35762064, 0.18051042],
        [0.2126, 0.7152, 0.0722],
        [0.01932141, 0.11916382, 0.95034478],
    ]

    static let XYZ_TO_SRGB: [[Double]] = [
        [3.2413774792388685, -1.5376652402851851, -0.49885366846268053],
        [-0.9691452513005321, 1.8758853451067872, 0.04156585616912061],
        [0.05562093689691305, -0.20395524564742123, 1.0571799111220335],
    ]

    static let WHITE_POINT_D65: [Double] = [95.047, 100.0, 108.883]

    static func argbFromRgb(_ red: Int, _ green: Int, _ blue: Int) -> Int {
        (255 << 24) | ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255)
    }

    static func argbFromLinrgb(_ linrgb: [Double]) -> Int {
        argbFromRgb(delinearized(linrgb[0]), delinearized(linrgb[1]), delinearized(linrgb[2]))
    }

    static func alphaFromArgb(_ argb: Int) -> Int { (argb >> 24) & 255 }
    static func redFromArgb(_ argb: Int) -> Int { (argb >> 16) & 255 }
    static func greenFromArgb(_ argb: Int) -> Int { (argb >> 8) & 255 }
    static func blueFromArgb(_ argb: Int) -> Int { argb & 255 }
    static func isOpaque(_ argb: Int) -> Bool { alphaFromArgb(argb) >= 255 }

    static func argbFromXyz(_ x: Double, _ y: Double, _ z: Double) -> Int {
        let matrix = XYZ_TO_SRGB
        let linearR = matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z
        let linearG = matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z
        let linearB = matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z
        return argbFromRgb(delinearized(linearR), delinearized(linearG), delinearized(linearB))
    }

    static func xyzFromArgb(_ argb: Int) -> [Double] {
        let r = linearized(redFromArgb(argb))
        let g = linearized(greenFromArgb(argb))
        let b = linearized(blueFromArgb(argb))
        return MathUtils.matrixMultiply([r, g, b], SRGB_TO_XYZ)
    }

    static func argbFromLab(_ l: Double, _ a: Double, _ b: Double) -> Int {
        let whitePoint = WHITE_POINT_D65
        let fy = (l + 16.0) / 116.0
        let fx = a / 500.0 + fy
        let fz = fy - b / 200.0
        let x = labInvf(fx) * whitePoint[0]
        let y = labInvf(fy) * whitePoint[1]
        let z = labInvf(fz) * whitePoint[2]
        return argbFromXyz(x, y, z)
    }

    static func labFromArgb(_ argb: Int) -> [Double] {
        let linearR = linearized(redFromArgb(argb))
        let linearG = linearized(greenFromArgb(argb))
        let linearB = linearized(blueFromArgb(argb))
        let matrix = SRGB_TO_XYZ
        let x = matrix[0][0] * linearR + matrix[0][1] * linearG + matrix[0][2] * linearB
        let y = matrix[1][0] * linearR + matrix[1][1] * linearG + matrix[1][2] * linearB
        let z = matrix[2][0] * linearR + matrix[2][1] * linearG + matrix[2][2] * linearB
        let whitePoint = WHITE_POINT_D65
        let fx = labF(x / whitePoint[0])
        let fy = labF(y / whitePoint[1])
        let fz = labF(z / whitePoint[2])
        return [116.0 * fy - 16, 500.0 * (fx - fy), 200.0 * (fy - fz)]
    }

    static func argbFromLstar(_ lstar: Double) -> Int {
        let component = delinearized(yFromLstar(lstar))
        return argbFromRgb(component, component, component)
    }

    static func lstarFromArgb(_ argb: Int) -> Double {
        let y = xyzFromArgb(argb)[1]
        return 116.0 * labF(y / 100.0) - 16.0
    }

    static func yFromLstar(_ lstar: Double) -> Double {
        100.0 * labInvf((lstar + 16.0) / 116.0)
    }

    static func lstarFromY(_ y: Double) -> Double {
        labF(y / 100.0) * 116.0 - 16.0
    }

    static func linearized(_ rgbComponent: Int) -> Double {
        let normalized = Double(rgbComponent) / 255.0
        if normalized <= 0.040449936 {
            return normalized / 12.92 * 100.0
        } else {
            return pow((normalized + 0.055) / 1.055, 2.4) * 100.0
        }
    }

    static func delinearized(_ rgbComponent: Double) -> Int {
        let normalized = rgbComponent / 100.0
        let delinearized: Double
        if normalized <= 0.0031308 {
            delinearized = normalized * 12.92
        } else {
            delinearized = 1.055 * pow(normalized, 1.0 / 2.4) - 0.055
        }
        return jvmRoundToInt(delinearized * 255.0).coerceIn(0, 255)
    }

    static func whitePointD65() -> [Double] { WHITE_POINT_D65 }

    static func labF(_ t: Double) -> Double {
        let e = 216.0 / 24389.0
        let kappa = 24389.0 / 27.0
        if t > e {
            return pow(t, 1.0 / 3.0)
        } else {
            return (kappa * t + 16) / 116
        }
    }

    static func labInvf(_ ft: Double) -> Double {
        let e = 216.0 / 24389.0
        let kappa = 24389.0 / 27.0
        let ft3 = ft * ft * ft
        if ft3 > e {
            return ft3
        } else {
            return (116 * ft - 16) / kappa
        }
    }
}
