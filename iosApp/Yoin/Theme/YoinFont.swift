import CoreText
import SwiftUI
import UIKit
import YoinShared

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

    nonisolated(unsafe) private static let base: CTFontDescriptor? = {
        guard let url = Bundle.main.url(forResource: "google_sans_flex_variable", withExtension: "ttf"),
              let list = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor]
        else { return nil }
        return list.first
    }()

    private static func tag(_ s: String) -> NSNumber {
        NSNumber(value: s.utf8.reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
    }

    static func uiFont(size: CGFloat, _ axes: Axes) -> UIFont {
        guard let base else { return .systemFont(ofSize: size) }
        let variation: [NSNumber: NSNumber] = [
            tag("wght"): NSNumber(value: Double(axes.weight)),
            tag("wdth"): NSNumber(value: Double(axes.width)),
            tag("ROND"): NSNumber(value: Double(axes.round)),
            tag("opsz"): NSNumber(value: Double(min(Tokens.type.OPTICAL_SIZE_MAX.cg,
                                                    max(Tokens.type.OPTICAL_SIZE_MIN.cg, axes.opticalSize ?? size)))),
        ]
        let desc = CTFontDescriptorCreateCopyWithAttributes(
            base, [kCTFontVariationAttribute: variation] as CFDictionary)
        return CTFontCreateWithFontDescriptor(desc, size, nil) as UIFont
    }

    /// A SwiftUI font that also scales with Dynamic Type.
    static func font(size: CGFloat, _ axes: Axes, relativeTo style: UIFont.TextStyle = .body) -> Font {
        Font(UIFontMetrics(forTextStyle: style).scaledFont(for: uiFont(size: size, axes)))
    }

    /// Android `GoogleSansFlexRounded`: ROND 100 at Medium (500) or SemiBold (600) (YoinTokens.Typography).
    static func rounded(_ size: CGFloat, semibold: Bool = false, relativeTo style: UIFont.TextStyle = .body) -> Font {
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
