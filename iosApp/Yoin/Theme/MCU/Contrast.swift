/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/contrast/Contrast.kt.
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

/// Color science for contrast utilities.
enum Contrast {
    static let RATIO_MIN = 1.0
    static let RATIO_MAX = 21.0
    static let RATIO_30 = 3.0
    static let RATIO_45 = 4.5
    static let RATIO_70 = 7.0
    private static let CONTRAST_RATIO_EPSILON = 0.04
    private static let LUMINANCE_GAMUT_MAP_TOLERANCE = 0.4

    static func ratioOfYs(_ y1: Double, _ y2: Double) -> Double {
        let lighter = max(y1, y2)
        let darker = lighter == y2 ? y1 : y2
        return (lighter + 5.0) / (darker + 5.0)
    }

    static func ratioOfTones(_ t1: Double, _ t2: Double) -> Double {
        ratioOfYs(ColorUtils.yFromLstar(t1), ColorUtils.yFromLstar(t2))
    }

    static func lighter(_ tone: Double, _ ratio: Double) -> Double? {
        if tone < 0.0 || tone > 100.0 { return nil }
        let darkY = ColorUtils.yFromLstar(tone)
        let lightY = ratio * (darkY + 5.0) - 5.0
        if lightY < 0.0 || lightY > 100.0 { return nil }
        let realContrast = ratioOfYs(lightY, darkY)
        let delta = abs(realContrast - ratio)
        if realContrast < ratio && delta > CONTRAST_RATIO_EPSILON { return nil }
        let returnValue = ColorUtils.lstarFromY(lightY) + LUMINANCE_GAMUT_MAP_TOLERANCE
        return (returnValue < 0.0 || returnValue > 100.0) ? nil : returnValue
    }

    static func lighterUnsafe(_ tone: Double, _ ratio: Double) -> Double {
        lighter(tone, ratio) ?? 100.0
    }

    static func darker(_ tone: Double, _ ratio: Double) -> Double? {
        if tone < 0.0 || tone > 100.0 { return nil }
        let lightY = ColorUtils.yFromLstar(tone)
        let darkY = (lightY + 5.0) / ratio - 5.0
        if darkY < 0.0 || darkY > 100.0 { return nil }
        let realContrast = ratioOfYs(lightY, darkY)
        let delta = abs(realContrast - ratio)
        if realContrast < ratio && delta > CONTRAST_RATIO_EPSILON { return nil }
        let returnValue = ColorUtils.lstarFromY(darkY) - LUMINANCE_GAMUT_MAP_TOLERANCE
        return (returnValue < 0.0 || returnValue > 100.0) ? nil : returnValue
    }

    static func darkerUnsafe(_ tone: Double, _ ratio: Double) -> Double {
        darker(tone, ratio) ?? 0.0
    }
}
