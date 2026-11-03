/*
 * Copyright 2022 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/dynamiccolor/DynamicColor.kt.
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

typealias SchemeFn<T> = @Sendable (DynamicScheme) -> T

/// A color that adjusts itself based on UI state provided by DynamicScheme.
///
/// Port note: Kotlin's `extendSpecVersion` layering (2021 → 2025 → 2026) is
/// flattened in this port. Only SPEC_2025 schemes are supported, so each
/// color carries its 2025 definition directly; see ColorSpec2025.swift.
struct DynamicColor: Sendable {
    let name: String
    let palette: SchemeFn<TonalPalette>
    let isBackground: Bool
    let chromaMultiplier: SchemeFn<Double>?
    let background: SchemeFn<DynamicColor?>?
    let tone: SchemeFn<Double>
    let secondBackground: SchemeFn<DynamicColor?>?
    let contrastCurve: SchemeFn<ContrastCurve?>?
    let toneDeltaPair: SchemeFn<ToneDeltaPair?>?
    let opacity: SchemeFn<Double?>?

    init(
        name: String,
        palette: @escaping SchemeFn<TonalPalette>,
        isBackground: Bool = false,
        chromaMultiplier: SchemeFn<Double>? = nil,
        background: SchemeFn<DynamicColor?>? = nil,
        tone: SchemeFn<Double>? = nil,
        secondBackground: SchemeFn<DynamicColor?>? = nil,
        contrastCurve: SchemeFn<ContrastCurve?>? = nil,
        toneDeltaPair: SchemeFn<ToneDeltaPair?>? = nil,
        opacity: SchemeFn<Double?>? = nil
    ) {
        precondition(!(background == nil && secondBackground != nil),
                     "Color \(name) has secondBackground defined, but background is not defined.")
        precondition(!(background == nil && contrastCurve != nil),
                     "Color \(name) has contrastCurve defined, but background is not defined.")
        precondition(!(background != nil && contrastCurve == nil),
                     "Color \(name) has background defined, but contrastCurve is not defined.")
        self.name = name
        self.palette = palette
        self.isBackground = isBackground
        self.chromaMultiplier = chromaMultiplier
        self.background = background
        self.tone = tone ?? DynamicColor.getInitialToneFromBackground(background)
        self.secondBackground = secondBackground
        self.contrastCurve = contrastCurve
        self.toneDeltaPair = toneDeltaPair
        self.opacity = opacity
    }

    /// Kotlin data-class `copy(name = ..., tone = ...)`.
    func copy(name: String, tone: SchemeFn<Double>? = nil) -> DynamicColor {
        DynamicColor(
            name: name, palette: palette, isBackground: isBackground,
            chromaMultiplier: chromaMultiplier, background: background, tone: tone ?? self.tone,
            secondBackground: secondBackground, contrastCurve: contrastCurve,
            toneDeltaPair: toneDeltaPair, opacity: opacity
        )
    }

    /// Returns an ARGB integer (unsigned 0xAARRGGBB in an `Int`).
    func getArgb(_ scheme: DynamicScheme) -> Int {
        let argb = getHct(scheme).toInt()
        guard let opacityPercentage = opacity?(scheme) else { return argb }
        let alpha = jvmRoundToInt(opacityPercentage * 255).coerceIn(0, 255)
        return (argb & 0x00ffffff) | (alpha << 24)
    }

    func getHct(_ scheme: DynamicScheme) -> Hct {
        ColorSpecs.get(scheme.specVersion).getHct(scheme, self)
    }

    func getTone(_ scheme: DynamicScheme) -> Double {
        ColorSpecs.get(scheme.specVersion).getTone(scheme, self)
    }

    static func fromArgb(_ name: String, _ argb: Int) -> DynamicColor {
        let hct = Hct.fromInt(argb)
        let palette = TonalPalette.fromInt(argb)
        return DynamicColor(name: name, palette: { _ in palette }, tone: { _ in hct.tone })
    }

    /// Given a background tone, finds a foreground tone, while ensuring they reach a contrast ratio
    /// that is as close to [ratio] as possible.
    static func foregroundTone(_ bgTone: Double, _ ratio: Double) -> Double {
        let lighterTone = Contrast.lighterUnsafe(bgTone, ratio)
        let darkerTone = Contrast.darkerUnsafe(bgTone, ratio)
        let lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone)
        let darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone)
        let preferLighter = tonePrefersLightForeground(bgTone)
        if preferLighter {
            let negligibleDifference =
                abs(lighterRatio - darkerRatio) < 0.1 && lighterRatio < ratio && darkerRatio < ratio
            return (lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference)
                ? lighterTone : darkerTone
        } else {
            return (darkerRatio >= ratio || darkerRatio >= lighterRatio) ? darkerTone : lighterTone
        }
    }

    static func enableLightForeground(_ tone: Double) -> Double {
        (tonePrefersLightForeground(tone) && !toneAllowsLightForeground(tone)) ? 49.0 : tone
    }

    static func tonePrefersLightForeground(_ tone: Double) -> Bool { jvmRoundToInt(tone) < 60 }

    static func toneAllowsLightForeground(_ tone: Double) -> Bool { jvmRoundToInt(tone) <= 49 }

    static func getInitialToneFromBackground(
        _ background: SchemeFn<DynamicColor?>?
    ) -> SchemeFn<Double> {
        guard let background else { return { _ in 50.0 } }
        return { scheme in background(scheme)?.getTone(scheme) ?? 50.0 }
    }
}
