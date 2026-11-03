/*
 * Copyright 2023 Google LLC
 *
 * Modified for Yoin (Swift port) of app/src/main/java/scheme/SchemeExpressive.kt.
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

/// A Dynamic Color theme that is intentionally detached from the source color.
enum SchemeExpressive {
    static func make(
        sourceColorHct: Hct,
        isDark: Bool,
        contrastLevel: Double,
        specVersion: SpecVersion = DynamicScheme.DEFAULT_SPEC_VERSION,
        platform: DynamicScheme.Platform = DynamicScheme.DEFAULT_PLATFORM
    ) -> DynamicScheme {
        make(sourceColorHctList: [sourceColorHct], isDark: isDark, contrastLevel: contrastLevel,
             specVersion: specVersion, platform: platform)
    }

    static func make(
        sourceColorHctList: [Hct],
        isDark: Bool,
        contrastLevel: Double,
        specVersion: SpecVersion = DynamicScheme.DEFAULT_SPEC_VERSION,
        platform: DynamicScheme.Platform = DynamicScheme.DEFAULT_PLATFORM
    ) -> DynamicScheme {
        let spec = ColorSpecs.get(specVersion)
        let source = sourceColorHctList[0]
        return DynamicScheme(
            sourceColorHctList: sourceColorHctList,
            variant: .EXPRESSIVE,
            isDark: isDark,
            contrastLevel: contrastLevel,
            platform: platform,
            specVersion: specVersion,
            primaryPalette: spec.getPrimaryPalette(.EXPRESSIVE, source, isDark, platform, contrastLevel),
            secondaryPalette: spec.getSecondaryPalette(.EXPRESSIVE, source, isDark, platform, contrastLevel),
            tertiaryPalette: spec.getTertiaryPalette(.EXPRESSIVE, source, isDark, platform, contrastLevel),
            neutralPalette: spec.getNeutralPalette(.EXPRESSIVE, source, isDark, platform, contrastLevel),
            neutralVariantPalette: spec.getNeutralVariantPalette(.EXPRESSIVE, source, isDark, platform, contrastLevel),
            errorPalette: spec.getErrorPalette(.EXPRESSIVE, source, isDark, platform, contrastLevel)
        )
    }
}
