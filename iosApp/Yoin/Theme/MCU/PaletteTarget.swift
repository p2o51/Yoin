/*
 * Copyright 2017 The Android Open Source Project
 *
 * Modified for Yoin (Swift port) of androidx/palette/graphics/Target.java
 * (androidx.palette:palette, Apache 2.0), kept close to the Java source so
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

import Foundation

extension Palette {
    /// A class which allows custom selection of colors in a [Palette]'s generation. Instances can be
    /// created via the [Builder] class. All values are Java `float`s, so this keeps `Float`.
    struct Target: Sendable, Hashable {
        static let TARGET_DARK_LUMA: Float = 0.26
        static let MAX_DARK_LUMA: Float = 0.45
        static let MIN_LIGHT_LUMA: Float = 0.55
        static let TARGET_LIGHT_LUMA: Float = 0.74
        static let MIN_NORMAL_LUMA: Float = 0.3
        static let TARGET_NORMAL_LUMA: Float = 0.5
        static let MAX_NORMAL_LUMA: Float = 0.7
        static let TARGET_MUTED_SATURATION: Float = 0.3
        static let MAX_MUTED_SATURATION: Float = 0.4
        static let TARGET_VIBRANT_SATURATION: Float = 1
        static let MIN_VIBRANT_SATURATION: Float = 0.35
        static let WEIGHT_SATURATION: Float = 0.24
        static let WEIGHT_LUMA: Float = 0.52
        static let WEIGHT_POPULATION: Float = 0.24

        static let INDEX_MIN = 0
        static let INDEX_TARGET = 1
        static let INDEX_MAX = 2
        static let INDEX_WEIGHT_SAT = 0
        static let INDEX_WEIGHT_LUMA = 1
        static let INDEX_WEIGHT_POP = 2

        /// Identity, standing in for Java's reference equality in the selected-swatch map.
        let name: String
        var saturationTargets: [Float] = [0, 0.5, 1]
        var lightnessTargets: [Float] = [0, 0.5, 1]
        var weights: [Float] = [WEIGHT_SATURATION, WEIGHT_LUMA, WEIGHT_POPULATION]
        var isExclusive = true

        static let LIGHT_VIBRANT: Target = {
            var t = Target(name: "LIGHT_VIBRANT"); t.setDefaultLightLightnessValues(); t.setDefaultVibrantSaturationValues(); return t
        }()
        static let VIBRANT: Target = {
            var t = Target(name: "VIBRANT"); t.setDefaultNormalLightnessValues(); t.setDefaultVibrantSaturationValues(); return t
        }()
        static let DARK_VIBRANT: Target = {
            var t = Target(name: "DARK_VIBRANT"); t.setDefaultDarkLightnessValues(); t.setDefaultVibrantSaturationValues(); return t
        }()
        static let LIGHT_MUTED: Target = {
            var t = Target(name: "LIGHT_MUTED"); t.setDefaultLightLightnessValues(); t.setDefaultMutedSaturationValues(); return t
        }()
        static let MUTED: Target = {
            var t = Target(name: "MUTED"); t.setDefaultNormalLightnessValues(); t.setDefaultMutedSaturationValues(); return t
        }()
        static let DARK_MUTED: Target = {
            var t = Target(name: "DARK_MUTED"); t.setDefaultDarkLightnessValues(); t.setDefaultMutedSaturationValues(); return t
        }()

        var minimumSaturation: Float { saturationTargets[Target.INDEX_MIN] }
        var targetSaturation: Float { saturationTargets[Target.INDEX_TARGET] }
        var maximumSaturation: Float { saturationTargets[Target.INDEX_MAX] }
        var minimumLightness: Float { lightnessTargets[Target.INDEX_MIN] }
        var targetLightness: Float { lightnessTargets[Target.INDEX_TARGET] }
        var maximumLightness: Float { lightnessTargets[Target.INDEX_MAX] }
        var saturationWeight: Float { weights[Target.INDEX_WEIGHT_SAT] }
        var lightnessWeight: Float { weights[Target.INDEX_WEIGHT_LUMA] }
        var populationWeight: Float { weights[Target.INDEX_WEIGHT_POP] }

        /// The weights are normalized so that they add up to 1 (Java mutates in place in `generate()`).
        mutating func normalizeWeights() {
            var sum: Float = 0
            for weight in weights where weight > 0 {
                sum += weight
            }
            if sum != 0 {
                for i in 0..<weights.count where weights[i] > 0 {
                    weights[i] /= sum
                }
            }
        }

        private mutating func setDefaultDarkLightnessValues() {
            lightnessTargets[Target.INDEX_TARGET] = Target.TARGET_DARK_LUMA
            lightnessTargets[Target.INDEX_MAX] = Target.MAX_DARK_LUMA
        }

        private mutating func setDefaultNormalLightnessValues() {
            lightnessTargets[Target.INDEX_MIN] = Target.MIN_NORMAL_LUMA
            lightnessTargets[Target.INDEX_TARGET] = Target.TARGET_NORMAL_LUMA
            lightnessTargets[Target.INDEX_MAX] = Target.MAX_NORMAL_LUMA
        }

        private mutating func setDefaultLightLightnessValues() {
            lightnessTargets[Target.INDEX_MIN] = Target.MIN_LIGHT_LUMA
            lightnessTargets[Target.INDEX_TARGET] = Target.TARGET_LIGHT_LUMA
        }

        private mutating func setDefaultVibrantSaturationValues() {
            saturationTargets[Target.INDEX_MIN] = Target.MIN_VIBRANT_SATURATION
            saturationTargets[Target.INDEX_TARGET] = Target.TARGET_VIBRANT_SATURATION
        }

        private mutating func setDefaultMutedSaturationValues() {
            saturationTargets[Target.INDEX_TARGET] = Target.TARGET_MUTED_SATURATION
            saturationTargets[Target.INDEX_MAX] = Target.MAX_MUTED_SATURATION
        }
    }
}
