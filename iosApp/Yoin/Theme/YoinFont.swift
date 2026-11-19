import CoreText
import os
import SwiftUI
import UIKit
import YoinShared

/// Preferred CJK fallback script for Google Sans Flex (a Latin-only typeface).
///
/// Without an explicit `kCTFontCascadeListAttribute`, CoreText falls back through
/// `Locale.preferredLanguages` one character at a time. When a user runs Yoin in
/// Simplified Chinese UI on a device whose primary system language is Japanese
/// (`ja > zh-Hant > zh-Hans`), CoreText slices every Chinese string into alternating
/// runs of Hiragino Sans, PingFang TC, and PingFang SC — producing mixed glyph shapes
/// and weights, and causing SwiftUI text animations to drop runs mid-transition.
enum YoinCJKScript: String, Hashable, Sendable {
    /// Follows the app's resolved UI localization (`Bundle.main.preferredLocalizations`)
    /// before consulting `Locale.preferredLanguages`.
    case appDefault
    case simplifiedChinese
    case traditionalChinese
    case japanese

    /// Resolves `.appDefault` to a concrete CJK script.
    func resolved(
        bundleLocalizations: [String] = Bundle.main.preferredLocalizations,
        preferredLanguages: [String] = Locale.preferredLanguages
    ) -> YoinCJKScript {
        guard self == .appDefault else { return self }
        return Self.resolvedAppDefault(bundleLocalizations: bundleLocalizations,
                                       preferredLanguages: preferredLanguages)
    }

    static func resolvedAppDefault(
        bundleLocalizations: [String] = Bundle.main.preferredLocalizations,
        preferredLanguages: [String] = Locale.preferredLanguages
    ) -> YoinCJKScript {
        if let loc = bundleLocalizations.first, let matched = script(forLanguageTag: loc) {
            return matched
        }
        for lang in preferredLanguages {
            if let matched = script(forLanguageTag: lang) {
                return matched
            }
        }
        return .simplifiedChinese
    }

    static func script(forLanguageTag tag: String) -> YoinCJKScript? {
        let lower = tag.lowercased().replacingOccurrences(of: "_", with: "-")
        if lower.hasPrefix("ja") { return .japanese }
        if lower.hasPrefix("zh-hant") || lower.hasPrefix("zh-tw")
            || lower.hasPrefix("zh-hk") || lower.hasPrefix("zh-mo") {
            return .traditionalChinese
        }
        if lower.hasPrefix("zh") { return .simplifiedChinese }
        return nil
    }

    /// Detects the best CJK script for song lyrics, translations, or metadata.
    static func detect(
        for text: String,
        fallback: YoinCJKScript = .appDefault,
        bundleLocalizations: [String] = Bundle.main.preferredLocalizations,
        preferredLanguages: [String] = Locale.preferredLanguages
    ) -> YoinCJKScript {
        detect(forLines: [text], fallback: fallback,
               bundleLocalizations: bundleLocalizations,
               preferredLanguages: preferredLanguages)
    }

    /// Detects the best CJK script across multiple lines (e.g. a full lyric sheet).
    static func detect(
        forLines lines: [String],
        fallback: YoinCJKScript = .appDefault,
        bundleLocalizations: [String] = Bundle.main.preferredLocalizations,
        preferredLanguages: [String] = Locale.preferredLanguages
    ) -> YoinCJKScript {
        let resolvedFallback = fallback.resolved(bundleLocalizations: bundleLocalizations,
                                                 preferredLanguages: preferredLanguages)
        var kanaCount = 0
        var hanScalars: [Unicode.Scalar] = []
        hanScalars.reserveCapacity(128)
        let maxHanSample = 128

        outer: for line in lines {
            for scalar in line.unicodeScalars {
                let v = scalar.value
                // Hiragana + Katakana (excluding punctuation such as U+30FB middle dot).
                if (0x3041...0x3096).contains(v)
                    || (0x30A1...0x30FA).contains(v)
                    || v == 0x30FC
                    || (0x31F0...0x31FF).contains(v)
                    || (0xFF66...0xFF9D).contains(v) {
                    kanaCount += 1
                    if kanaCount >= 4 { break outer }
                } else if (0x4E00...0x9FFF).contains(v)
                    || (0x3400...0x4DBF).contains(v)
                    || (0xF900...0xFAFF).contains(v)
                    || (0x20000...0x2EBEF).contains(v) {
                    if hanScalars.count < maxHanSample {
                        hanScalars.append(scalar)
                    }
                }
            }
        }

        let hanCount = hanScalars.count
        if kanaCount > 0, hanCount == 0 || kanaCount >= 3 || kanaCount * 4 >= hanCount {
            return .japanese
        }
        guard hanCount > 0 else { return resolvedFallback }

        let sample = String(String.UnicodeScalarView(hanScalars))
        let toSimplified = sample.applyingTransform(StringTransform("Traditional-Simplified"), reverse: false) ?? sample
        let toTraditional = sample.applyingTransform(StringTransform("Simplified-Traditional"), reverse: false) ?? sample

        var tradOnly = 0
        var simpOnly = 0
        let sampleChars = Array(sample)
        let simpChars = Array(toSimplified)
        let tradChars = Array(toTraditional)
        for i in sampleChars.indices {
            if i < simpChars.count, sampleChars[i] != simpChars[i] { tradOnly += 1 }
            if i < tradChars.count, sampleChars[i] != tradChars[i] { simpOnly += 1 }
        }

        if simpOnly > tradOnly { return .simplifiedChinese }
        if tradOnly > simpOnly { return .traditionalChinese }
        if kanaCount > 0 { return .japanese }

        // Multi-line Hanzi-only lyrics with zero Kana are Chinese even if fallback was Japanese.
        let nonEmptyLines = lines.reduce(0) { $0 + ($1.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0 : 1) }
        if resolvedFallback == .japanese, nonEmptyLines >= 4 {
            for lang in preferredLanguages {
                if let matched = script(forLanguageTag: lang), matched != .japanese {
                    return matched
                }
            }
            return .simplifiedChinese
        }
        return resolvedFallback
    }
}

/// Google Sans Flex through its variable axes, like Android's
/// `GoogleSansFlexRounded` (Type.kt) and `GrooveType` (Memories emblem).
///
/// The variable file shares its PostScript name ("GoogleSansFlex-Regular")
/// with the static 400 file, so it is not in UIAppFonts: it is loaded from its
/// URL and instanced with `kCTFontVariationAttribute`.
enum YoinFont {
    struct Axes: Hashable, Sendable {
        var weight: CGFloat = 400
        var width: CGFloat = 100
        var round: CGFloat = 0
        /// nil = follow the point size (opsz is clamped to the font's 6…144).
        var opticalSize: CGFloat?
    }

    private struct CacheKey: Hashable, Sendable {
        let isStatic: Bool
        let sizeQ: Int
        let weightQ: Int
        let widthQ: Int
        let roundQ: Int
        let opticalSizeQ: Int
        let script: YoinCJKScript
        let textStyleRaw: String
        let preferredContentSizeCategoryRaw: String
    }

    private struct CachedFont: @unchecked Sendable {
        let uiFont: UIFont
        let font: Font
    }

    private struct CacheState: @unchecked Sendable {
        var entries: [CacheKey: CachedFont] = [:]
        var lastCategoryRaw: String = UIContentSizeCategory.large.rawValue
    }

    private static let cache = OSAllocatedUnfairLock<CacheState>(initialState: CacheState())
    private static let maxCacheEntries = 1024

    private static func quantize(_ value: CGFloat) -> Int {
        guard value.isFinite else { return 0 }
        return Int((value * 4).rounded())
    }

    private static func clampedOpticalSize(for size: CGFloat, explicit: CGFloat?) -> CGFloat {
        min(Tokens.type.OPTICAL_SIZE_MAX.cg, max(Tokens.type.OPTICAL_SIZE_MIN.cg, explicit ?? size))
    }

    private static func mainThreadCategoryRaw() -> String? {
        Thread.isMainThread
            ? MainActor.assumeIsolated { UIApplication.shared.preferredContentSizeCategory.rawValue }
            : nil
    }

    private static func uiTextStyle(for style: Font.TextStyle) -> UIFont.TextStyle {
        switch style {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .subheadline: .subheadline
        case .body: .body
        case .callout: .callout
        case .footnote: .footnote
        case .caption: .caption1
        case .caption2: .caption2
        @unknown default: .body
        }
    }

    private static func staticPostScriptName(for weight: Font.Weight) -> String {
        if weight == .medium {
            "GoogleSansFlex-Medium"
        } else if weight == .semibold {
            "GoogleSansFlex-SemiBold"
        } else if weight == .bold || weight == .heavy || weight == .black {
            "GoogleSansFlex-Bold"
        } else {
            "GoogleSansFlex-Regular"
        }
    }

    private static func numericWeight(for weight: Font.Weight) -> CGFloat {
        if weight == .medium {
            500
        } else if weight == .semibold {
            600
        } else if weight == .bold || weight == .heavy || weight == .black {
            700
        } else {
            400
        }
    }

    private static func firstAvailableFont(_ candidates: [String]) -> String? {
        for name in candidates where UIFont(name: name, size: 12) != nil {
            return name
        }
        return nil
    }

    private static func cascadeFontNames(for script: YoinCJKScript, weight: CGFloat) -> [String] {
        let sc: [String]
        let tc: [String]
        let jp: [String]
        let kr: [String]
        switch weight {
        case ..<450:
            sc = ["PingFangSC-Regular"]
            tc = ["PingFangTC-Regular"]
            jp = ["HiraginoSans-W3", "HiraginoSans-W4"]
            kr = ["AppleSDGothicNeo-Regular"]
        case ..<550:
            sc = ["PingFangSC-Medium", "PingFangSC-Regular"]
            tc = ["PingFangTC-Medium", "PingFangTC-Regular"]
            jp = ["HiraginoSans-W5", "HiraginoSans-W6", "HiraginoSans-W3"]
            kr = ["AppleSDGothicNeo-Medium", "AppleSDGothicNeo-Regular"]
        case ..<650:
            sc = ["PingFangSC-Semibold", "PingFangSC-Medium", "PingFangSC-Regular"]
            tc = ["PingFangTC-Semibold", "PingFangTC-Medium", "PingFangTC-Regular"]
            jp = ["HiraginoSans-W6", "HiraginoSans-W7", "HiraginoSans-W3"]
            kr = ["AppleSDGothicNeo-SemiBold", "AppleSDGothicNeo-Medium", "AppleSDGothicNeo-Regular"]
        default:
            sc = ["PingFangSC-Semibold", "PingFangSC-Medium", "PingFangSC-Regular"]
            tc = ["PingFangTC-Semibold", "PingFangTC-Medium", "PingFangTC-Regular"]
            jp = ["HiraginoSans-W7", "HiraginoSans-W6", "HiraginoSans-W3"]
            kr = ["AppleSDGothicNeo-Bold", "AppleSDGothicNeo-SemiBold", "AppleSDGothicNeo-Regular"]
        }
        let ordered: [[String]] = switch script.resolved() {
        case .simplifiedChinese, .appDefault: [sc, tc, jp, kr]
        case .traditionalChinese: [tc, sc, jp, kr]
        case .japanese: [jp, sc, tc, kr]
        }
        return ordered.compactMap(firstAvailableFont)
    }

    private static func cascadeDescriptor(for script: YoinCJKScript, weight: CGFloat) -> CTFontDescriptor? {
        let descriptors: [CTFontDescriptor] = cascadeFontNames(for: script, weight: weight).map { name in
            CTFontDescriptorCreateWithNameAndSize(name as CFString, 0)
        }
        guard !descriptors.isEmpty else { return nil }
        return CTFontDescriptorCreateWithAttributes([
            kCTFontCascadeListAttribute: descriptors
        ] as CFDictionary)
    }

    /// The variable file as a CGFont. Not a descriptor from
    /// `CTFontManagerCreateFontDescriptorsFromURL`: a font built from an
    /// unregistered file descriptor resolved on the simulator but fell back to
    /// Helvetica on the iPhone (51, 2026-11-04). A CGFont from the file's own
    /// data with its variations applied needs no lookup by name.
    nonisolated(unsafe) private static let base: CGFont? = {
        guard let url = Bundle.main.url(forResource: "google_sans_flex_variable", withExtension: "ttf"),
              let provider = CGDataProvider(url: url as CFURL)
        else { return nil }
        return CGFont(provider)
    }()

    /// Axis tags by name, from the font itself (CGFont variations are keyed
    /// by axis name: "Weight", "Width", "Roundness", "Optical size").
    private static let axisNames: [String: String] = {
        guard let axes = base?.variationAxes as? [[String: Any]] else { return [:] }
        var out: [String: String] = [:]
        for a in axes {
            guard let name = a[CGFont.variationAxisName as String] as? String else { continue }
            switch name.lowercased() {
            case let n where n.contains("weight"): out["wght"] = name
            case let n where n.contains("width"): out["wdth"] = name
            case let n where n.contains("round"): out["ROND"] = name
            case let n where n.contains("optical"): out["opsz"] = name
            default: break
            }
        }
        return out
    }()

    static func uiFont(size: CGFloat, _ axes: Axes, script: YoinCJKScript = .appDefault) -> UIFont {
        let resolvedScript = script.resolved()
        let opsz = clampedOpticalSize(for: size, explicit: axes.opticalSize)
        let key = CacheKey(
            isStatic: false,
            sizeQ: quantize(size),
            weightQ: quantize(axes.weight),
            widthQ: quantize(axes.width),
            roundQ: quantize(axes.round),
            opticalSizeQ: quantize(opsz),
            script: resolvedScript,
            textStyleRaw: "",
            preferredContentSizeCategoryRaw: ""
        )
        if let hit = cache.withLock({ $0.entries[key] }) {
            return hit.uiFont
        }
        let built = makeUIFont(size: size, axes: axes, opticalSize: opsz, script: resolvedScript)
        let pair = CachedFont(uiFont: built, font: Font(built))
        cache.withLock { state in
            if state.entries.count >= maxCacheEntries { state.entries.removeAll(keepingCapacity: true) }
            state.entries[key] = pair
        }
        return built
    }

    private static func makeUIFont(size: CGFloat, axes: Axes, opticalSize: CGFloat, script: YoinCJKScript) -> UIFont {
        guard let base else {
            assertionFailure("Google Sans Flex variable font missing from the bundle")
            return staticUIFont(size: size, weight: .regular, script: script)
        }
        let values: [String: CGFloat] = [
            "wght": axes.weight,
            "wdth": axes.width,
            "ROND": axes.round,
            "opsz": opticalSize,
        ]
        var variations: [String: NSNumber] = [:]
        for (tag, value) in values {
            if let name = axisNames[tag] { variations[name] = NSNumber(value: Double(value)) }
        }
        let instance = base.copy(withVariations: variations as CFDictionary) ?? base
        let descriptor = cascadeDescriptor(for: script, weight: axes.weight)
        return CTFontCreateWithGraphicsFont(instance, size, nil, descriptor) as UIFont
    }

    /// Static Google Sans Flex face with an explicit CJK cascade list attached.
    static func staticUIFont(size: CGFloat, weight: Font.Weight = .regular,
                             script: YoinCJKScript = .appDefault) -> UIFont {
        let resolvedScript = script.resolved()
        let numeric = numericWeight(for: weight)
        let key = CacheKey(
            isStatic: true,
            sizeQ: quantize(size),
            weightQ: quantize(numeric),
            widthQ: 0,
            roundQ: 0,
            opticalSizeQ: 0,
            script: resolvedScript,
            textStyleRaw: "",
            preferredContentSizeCategoryRaw: ""
        )
        if let hit = cache.withLock({ $0.entries[key] }) {
            return hit.uiFont
        }
        let psName = staticPostScriptName(for: weight)
        let baseFont = CTFontCreateWithName(psName as CFString, size, nil)
        let built: UIFont
        if let descriptor = cascadeDescriptor(for: resolvedScript, weight: numeric) {
            built = CTFontCreateCopyWithAttributes(baseFont, size, nil, descriptor) as UIFont
        } else {
            built = baseFont as UIFont
        }
        let pair = CachedFont(uiFont: built, font: Font(built))
        cache.withLock { state in
            if state.entries.count >= maxCacheEntries { state.entries.removeAll(keepingCapacity: true) }
            state.entries[key] = pair
        }
        return built
    }

    /// Dynamic-Type-scaled static Google Sans Flex face with CJK cascade list.
    static func staticFont(size: CGFloat, weight: Font.Weight = .regular,
                           relativeTo style: Font.TextStyle = .body,
                           script: YoinCJKScript = .appDefault) -> Font {
        let resolvedScript = script.resolved()
        let numeric = numericWeight(for: weight)
        let uiStyle = uiTextStyle(for: style)
        let mainRaw = mainThreadCategoryRaw()
        let sizeQ = quantize(size)
        let weightQ = quantize(numeric)
        let lookup: (hit: CachedFont?, key: CacheKey) = cache.withLock { state in
            let categoryRaw = mainRaw ?? state.lastCategoryRaw
            if let mainRaw, state.lastCategoryRaw != mainRaw { state.lastCategoryRaw = mainRaw }
            let key = CacheKey(
                isStatic: true,
                sizeQ: sizeQ,
                weightQ: weightQ,
                widthQ: 0,
                roundQ: 0,
                opticalSizeQ: 0,
                script: resolvedScript,
                textStyleRaw: uiStyle.rawValue,
                preferredContentSizeCategoryRaw: categoryRaw
            )
            return (state.entries[key], key)
        }
        if let hit = lookup.hit { return hit.font }

        let scaled = UIFontMetrics(forTextStyle: uiStyle).scaledValue(for: size)
        let ui = staticUIFont(size: scaled, weight: weight, script: resolvedScript)
        let font = Font(ui)
        let pair = CachedFont(uiFont: ui, font: font)
        cache.withLock { state in
            if state.entries.count >= maxCacheEntries { state.entries.removeAll(keepingCapacity: true) }
            state.entries[lookup.key] = pair
        }
        return font
    }

    /// Launch self-check: logs which Google Sans Flex faces resolve, and
    /// warns when any falls back (static faces via UIAppFonts, variable via file).
    static func selfCheck() {
        let statics = ["GoogleSansFlex-Regular", "GoogleSansFlex-Medium", "GoogleSansFlex-SemiBold", "GoogleSansFlex-Bold"]
        let missing = statics.filter { UIFont(name: $0, size: 12) == nil }
        let variable = uiFont(size: 17, Axes(weight: 500, round: 100))
        let family = variable.familyName
        let scaled = UIFontMetrics(forTextStyle: .body).scaledFont(for: variable)
        let textSize = mainThreadCategoryRaw() ?? cache.withLock { $0.lastCategoryRaw }
        let line = "[YoinFont] statics missing: \(missing.isEmpty ? "none" : missing.joined(separator: ", ")); variable → \(variable.fontName) (\(family)); axes \(axisNames.keys.sorted()); text size \(textSize); scaledFont(for:) → \(scaled.familyName)"
        print(line)
        NSLog("%@", line)
        assert(missing.isEmpty && family.contains("Google Sans"), line)
    }

    /// A SwiftUI font that also scales with Dynamic Type. The size is scaled first and the
    /// instance built at it: `UIFontMetrics.scaledFont(for:)` rebuilds the font from its
    /// descriptor, a lookup by name that this unregistered instance doesn't answer, so on an
    /// iPhone whose text size isn't the default the text fell back to the system font.
    static func font(size: CGFloat, _ axes: Axes, relativeTo style: UIFont.TextStyle = .body,
                     script: YoinCJKScript = .appDefault) -> Font {
        let resolvedScript = script.resolved()
        let mainRaw = mainThreadCategoryRaw()
        let opsz = clampedOpticalSize(for: size, explicit: axes.opticalSize)
        let sizeQ = quantize(size)
        let weightQ = quantize(axes.weight)
        let widthQ = quantize(axes.width)
        let roundQ = quantize(axes.round)
        let opszQ = quantize(opsz)
        let lookup: (hit: CachedFont?, key: CacheKey) = cache.withLock { state in
            let categoryRaw = mainRaw ?? state.lastCategoryRaw
            if let mainRaw, state.lastCategoryRaw != mainRaw { state.lastCategoryRaw = mainRaw }
            let key = CacheKey(
                isStatic: false,
                sizeQ: sizeQ,
                weightQ: weightQ,
                widthQ: widthQ,
                roundQ: roundQ,
                opticalSizeQ: opszQ,
                script: resolvedScript,
                textStyleRaw: style.rawValue,
                preferredContentSizeCategoryRaw: categoryRaw
            )
            return (state.entries[key], key)
        }
        if let hit = lookup.hit { return hit.font }

        let scaled = UIFontMetrics(forTextStyle: style).scaledValue(for: size)
        var a = axes
        // Optical size follows the designed size, as before scaling.
        if a.opticalSize == nil { a.opticalSize = size }
        let ui = uiFont(size: scaled, a, script: resolvedScript)
        let font = Font(ui)
        let pair = CachedFont(uiFont: ui, font: font)
        cache.withLock { state in
            if state.entries.count >= maxCacheEntries { state.entries.removeAll(keepingCapacity: true) }
            state.entries[lookup.key] = pair
        }
        return font
    }

    /// Android `GoogleSansFlexRounded`: ROND 100 at Medium (500) or SemiBold (600) (YoinTokens.Typography).
    static func rounded(_ size: CGFloat, semibold: Bool = false, relativeTo style: UIFont.TextStyle = .body,
                        script: YoinCJKScript = .appDefault) -> Font {
        let t = semibold ? Tokens.type.roundedSemiBold : Tokens.type.roundedMedium
        return font(size: size, Axes(weight: CGFloat(t.weight), width: t.width.cg, round: t.round.cg),
                    relativeTo: style, script: script)
    }

    /// Android `GrooveType.score`: wght 690 (album rating) / 640 (average),
    /// ROND 50, wdth 76 for four characters ("10.0") else 92, opsz = size.
    static func score(_ text: String, size: CGFloat, albumRating: Bool = true) -> Font {
        let t = Tokens.type
        return font(size: size,
                    Axes(weight: CGFloat(albumRating ? t.SCORE_WEIGHT_ALBUM : t.SCORE_WEIGHT_AVERAGE),
                         width: (text.count >= 4 ? t.SCORE_WIDTH_FOUR_CHARACTERS : t.SCORE_WIDTH).cg,
                         round: t.SCORE_ROUND.cg),
                    relativeTo: .title1)
    }
}

/// Animates variable axes with springs: SwiftUI interpolates `animatableData`
/// and rebuilds the CTFont each frame (one descriptor copy + font per frame;
/// cheap for a line or two, not for whole lists).
struct VariableFontModifier: ViewModifier, @preconcurrency Animatable {
    var size: CGFloat
    var axes: YoinFont.Axes

    var animatableData: AnimatablePair<CGFloat, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(axes.width, AnimatablePair(axes.round, axes.weight)) }
        set {
            axes.width = newValue.first
            axes.round = newValue.second.first
            axes.weight = newValue.second.second
        }
    }

    func body(content: Content) -> some View {
        content.font(YoinFont.font(size: size, axes))
    }
}

extension View {
    func variableFont(size: CGFloat, _ axes: YoinFont.Axes) -> some View {
        modifier(VariableFontModifier(size: size, axes: axes))
    }
}

/// Debug specimen (`-screen type`): proves the axes reach CoreText.
struct TypeSpecimenView: View {
    @State private var wide = false
    private let sample = "Yoin 余韻 9.6"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text("Google Sans Flex · variable axes")
                    .font(.gsf(15, .medium))
                    .foregroundStyle(Yoin.inkVariant)
                row("ROND 0 · wght 600", YoinFont.Axes(weight: 600, round: 0))
                row("ROND 100 · wght 600", YoinFont.Axes(weight: 600, round: 100))
                row("wdth 75", YoinFont.Axes(weight: 500, width: 75))
                row("wdth 100", YoinFont.Axes(weight: 500, width: 100))
                row("wdth 110", YoinFont.Axes(weight: 500, width: 110))
                VStack(alignment: .leading, spacing: 4) {
                    Text("GrooveType score: 8.6 (wdth 92) · 10.0 (wdth 76), ROND 50, wght 690")
                        .font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                    HStack(spacing: 24) {
                        Text("8.6").font(YoinFont.score("8.6", size: 56))
                        Text("10.0").font(YoinFont.score("10.0", size: 56))
                        Text("7.4").font(YoinFont.score("7.4", size: 56, albumRating: false))
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Spring-animated wdth 75 ⇄ 110")
                        .font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                    Text("Afterglow")
                        .variableFont(size: 44, YoinFont.Axes(weight: 600, width: wide ? 110 : 75, round: 100))
                        .onAppear {
                            withAnimation(.spring(duration: 1.2, bounce: 0.3).repeatForever()) { wide = true }
                        }
                }
            }
            .foregroundStyle(Yoin.ink)
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Yoin.surface)
    }

    private func row(_ label: String, _ axes: YoinFont.Axes) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
            Text(sample).font(YoinFont.font(size: 40, axes))
        }
    }
}
