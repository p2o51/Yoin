/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/utils/MathUtils.kt.
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

/// Utility methods for mathematical operations.
enum MathUtils {
    static func lerp(_ start: Double, _ stop: Double, _ amount: Double) -> Double {
        (1.0 - amount) * start + amount * stop
    }

    static func sanitizeDegreesInt(_ degrees: Int) -> Int {
        var degrees = degrees % 360
        if degrees < 0 { degrees += 360 }
        return degrees
    }

    static func sanitizeDegreesDouble(_ degrees: Double) -> Double {
        // Kotlin's Double % is the truncating remainder (C fmod).
        var degrees = degrees.truncatingRemainder(dividingBy: 360.0)
        if degrees < 0 { degrees += 360.0 }
        return degrees
    }

    static func clampDouble(_ min: Double, _ max: Double, _ input: Double) -> Double {
        if input < min { return min } else if input > max { return max }
        return input
    }

    static func rotationDirection(_ from: Double, _ to: Double) -> Double {
        let increasingDifference = sanitizeDegreesDouble(to - from)
        return increasingDifference <= 180.0 ? 1.0 : -1.0
    }

    static func differenceDegrees(_ a: Double, _ b: Double) -> Double {
        180.0 - abs(abs(a - b) - 180.0)
    }

    static func matrixMultiply(_ row: [Double], _ matrix: [[Double]]) -> [Double] {
        let a = row[0] * matrix[0][0] + row[1] * matrix[0][1] + row[2] * matrix[0][2]
        let b = row[0] * matrix[1][0] + row[1] * matrix[1][1] + row[2] * matrix[1][2]
        let c = row[0] * matrix[2][0] + row[1] * matrix[2][1] + row[2] * matrix[2][2]
        return [a, b, c]
    }
}

// MARK: - JVM semantics the Kotlin source relies on

/// Kotlin `Double.roundToInt()` / Java `Math.round`: round half up (towards +∞).
@inline(__always)
func jvmRoundToInt(_ x: Double) -> Int {
    if x.isNaN { return 0 }
    if x >= 9.2e18 { return Int.max }
    if x <= -9.2e18 { return Int.min }
    let f = x.rounded(.down)
    return Int(x - f >= 0.5 ? f + 1 : f)
}

/// Kotlin `Float.roundToInt()` / Java `Math.round(float)`, evaluated in Float.
@inline(__always)
func jvmRoundToInt(_ x: Float) -> Int {
    if x.isNaN { return 0 }
    if x >= 2.1e9 { return Int(Int32.max) }
    if x <= -2.1e9 { return Int(Int32.min) }
    let f = x.rounded(.down)
    return Int(x - f >= 0.5 ? f + 1 : f)
}

/// Kotlin `sign` / Java `Math.signum`: 0 for ±0.
@inline(__always)
func jvmSignum(_ x: Double) -> Double {
    x > 0 ? 1.0 : (x < 0 ? -1.0 : x)
}

/// Java 9+ `Math.toRadians` (multiplies by a precomputed constant).
@inline(__always)
func jvmToRadians(_ degrees: Double) -> Double { degrees * 0.017453292519943295 }

/// Java 9+ `Math.toDegrees` (multiplies by a precomputed constant).
@inline(__always)
func jvmToDegrees(_ radians: Double) -> Double { radians * 57.29577951308232 }

extension Comparable {
    /// Kotlin `coerceIn`.
    @inline(__always)
    func coerceIn(_ lower: Self, _ upper: Self) -> Self {
        self < lower ? lower : (self > upper ? upper : self)
    }
}
