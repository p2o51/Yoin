/*
 * Copyright 2025 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/hct/Hct.kt.
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

/// A color system built using CAM16 hue and chroma, and L* from L*a*b*.
///
/// Kotlin's `Hct` is a mutable class; here it is an immutable value
/// (`setHue`/`setChroma`/`setTone` return a new value), which is all the
/// scheme code needs.
struct Hct: Sendable, Hashable {
    let hue: Double
    let chroma: Double
    let tone: Double
    private let argb: Int

    private init(argb: Int) {
        self.argb = argb
        let cam = Cam16.fromInt(argb)
        hue = cam.hue
        chroma = cam.chroma
        tone = ColorUtils.lstarFromArgb(argb)
    }

    func toInt() -> Int { argb }

    func withHue(_ newHue: Double) -> Hct { Hct(argb: HctSolver.solveToInt(newHue, chroma, tone)) }
    func withChroma(_ newChroma: Double) -> Hct { Hct(argb: HctSolver.solveToInt(hue, newChroma, tone)) }
    func withTone(_ newTone: Double) -> Hct { Hct(argb: HctSolver.solveToInt(hue, chroma, newTone)) }

    var description: String {
        "HCT(\(jvmRoundToInt(hue)), \(jvmRoundToInt(chroma)), \(jvmRoundToInt(tone)))"
    }

    /// Translate a color into different ViewingConditions.
    func inViewingConditions(_ vc: ViewingConditions) -> Hct {
        let cam16 = Cam16.fromInt(toInt())
        let viewedInVc = cam16.xyzInViewingConditions(vc)
        let recastInVc = Cam16.fromXyzInViewingConditions(
            viewedInVc[0], viewedInVc[1], viewedInVc[2], ViewingConditions.DEFAULT
        )
        return Hct.from(recastInVc.hue, recastInVc.chroma, ColorUtils.lstarFromY(viewedInVc[1]))
    }

    static func from(_ hue: Double, _ chroma: Double, _ tone: Double) -> Hct {
        Hct(argb: HctSolver.solveToInt(hue, chroma, tone))
    }

    static func fromInt(_ argb: Int) -> Hct { Hct(argb: argb) }

    static func isBlue(_ hue: Double) -> Bool { hue >= 250 && hue < 270 }
    static func isYellow(_ hue: Double) -> Bool { hue >= 105 && hue < 125 }
    static func isCyan(_ hue: Double) -> Bool { hue >= 170 && hue < 207 }
}
