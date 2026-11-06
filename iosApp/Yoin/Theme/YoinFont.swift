import CoreText
import SwiftUI
import YoinShared
#if canImport(UIKit)
import UIKit
typealias YoinPlatformFont = UIFont
#else
import AppKit
typealias YoinPlatformFont = NSFont
#endif

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
    nonisolated(unsafe) private static let axisNames: [String: String] = {
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

    static func uiFont(size: CGFloat, _ axes: Axes) -> YoinPlatformFont {
        guard let base else {
            assertionFailure("Google Sans Flex variable font missing from the bundle")
            return YoinPlatformFont(name: "GoogleSansFlex-Regular", size: size) ?? .systemFont(ofSize: size)
        }
        let values: [String: CGFloat] = [
            "wght": axes.weight,
            "wdth": axes.width,
            "ROND": axes.round,
            "opsz": min(Tokens.type.OPTICAL_SIZE_MAX.cg, max(Tokens.type.OPTICAL_SIZE_MIN.cg, axes.opticalSize ?? size)),
        ]
        var variations: [String: NSNumber] = [:]
        for (tag, value) in values {
            if let name = axisNames[tag] { variations[name] = NSNumber(value: Double(value)) }
        }
        let instance = base.copy(withVariations: variations as CFDictionary) ?? base
        return CTFontCreateWithGraphicsFont(instance, size, nil, nil) as YoinPlatformFont
    }

    #if os(iOS)
    /// Launch self-check: logs which Google Sans Flex faces resolve, and
    /// warns when any falls back (static faces via UIAppFonts, variable via file).
    static func selfCheck() {
        let statics = ["GoogleSansFlex-Regular", "GoogleSansFlex-Medium", "GoogleSansFlex-SemiBold", "GoogleSansFlex-Bold"]
        let missing = statics.filter { UIFont(name: $0, size: 12) == nil }
        let variable = uiFont(size: 17, Axes(weight: 500, round: 100))
        let family = variable.familyName
        let scaled = UIFontMetrics(forTextStyle: .body).scaledFont(for: variable)
        let line = "[YoinFont] statics missing: \(missing.isEmpty ? "none" : missing.joined(separator: ", ")); variable → \(variable.fontName) (\(family)); axes \(axisNames.keys.sorted()); text size \(UIApplication.shared.preferredContentSizeCategory.rawValue); scaledFont(for:) → \(scaled.familyName)"
        print(line)
        NSLog("%@", line)
        assert(missing.isEmpty && family.contains("Google Sans"), line)
    }
    #endif

    /// A SwiftUI font that also scales with Dynamic Type. The size is scaled first and the
    /// instance built at it: `UIFontMetrics.scaledFont(for:)` rebuilds the font from its
    /// descriptor, a lookup by name that this unregistered instance doesn't answer, so on an
    /// iPhone whose text size isn't the default the text fell back to the system font.
    static func font(size: CGFloat, _ axes: Axes, relativeTo style: YoinPlatformFont.TextStyle = .body) -> Font {
        #if os(iOS)
        let scaled = UIFontMetrics(forTextStyle: style).scaledValue(for: size)
        #else
        let scaled = size // the Mac has no Dynamic Type
        #endif
        var a = axes
        // Optical size follows the designed size, as before scaling.
        if a.opticalSize == nil { a.opticalSize = size }
        return Font(uiFont(size: scaled, a))
    }

    /// Android `GoogleSansFlexRounded`: ROND 100 at Medium (500) or SemiBold (600) (YoinTokens.Typography).
    static func rounded(_ size: CGFloat, semibold: Bool = false, relativeTo style: YoinPlatformFont.TextStyle = .body) -> Font {
        let t = semibold ? Tokens.type.roundedSemiBold : Tokens.type.roundedMedium
        return font(size: size, Axes(weight: CGFloat(t.weight), width: t.width.cg, round: t.round.cg), relativeTo: style)
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
