/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/dynamiccolor/{Variant,ContrastCurve,ToneDeltaPair}.kt.
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

/// Set of themes supported by Dynamic Color.
enum Variant: Sendable {
    case MONOCHROME
    case NEUTRAL
    case TONAL_SPOT
    case VIBRANT
    case EXPRESSIVE
    case FIDELITY
    case CONTENT
    case RAINBOW
    case FRUIT_SALAD
    case CMF
}

/// Kotlin `ColorSpec.SpecVersion`.
enum SpecVersion: Int, Sendable, Comparable {
    case SPEC_2021
    case SPEC_2025
    case SPEC_2026

    static func < (lhs: SpecVersion, rhs: SpecVersion) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A class containing a value that changes with the contrast level.
struct ContrastCurve: Sendable {
    let low: Double
    let normal: Double
    let medium: Double
    let high: Double

    init(_ low: Double, _ normal: Double, _ medium: Double, _ high: Double) {
        self.low = low
        self.normal = normal
        self.medium = medium
        self.high = high
    }

    func get(_ contrastLevel: Double) -> Double {
        if contrastLevel <= -1.0 {
            return low
        } else if contrastLevel < 0.0 {
            return MathUtils.lerp(low, normal, contrastLevel + 1.0)
        } else if contrastLevel < 0.5 {
            return MathUtils.lerp(normal, medium, contrastLevel / 0.5)
        } else if contrastLevel < 1.0 {
            return MathUtils.lerp(medium, high, (contrastLevel - 0.5) / 0.5)
        } else {
            return high
        }
    }
}

/// Documents a constraint between two DynamicColors, in which their tones must have a certain
/// distance from each other.
struct ToneDeltaPair: Sendable {
    enum DeltaConstraint: Sendable { case EXACT, NEARER, FARTHER }
    enum TonePolarity: Sendable { case DARKER, LIGHTER, RELATIVE_DARKER, RELATIVE_LIGHTER }

    let roleA: DynamicColor
    let roleB: DynamicColor
    let delta: Double
    let polarity: TonePolarity
    let stayTogether: Bool
    let constraint: DeltaConstraint

    init(
        roleA: DynamicColor,
        roleB: DynamicColor,
        delta: Double,
        polarity: TonePolarity,
        stayTogether: Bool = true,
        constraint: DeltaConstraint = .EXACT
    ) {
        self.roleA = roleA
        self.roleB = roleB
        self.delta = delta
        self.polarity = polarity
        self.stayTogether = stayTogether
        self.constraint = constraint
    }
}
