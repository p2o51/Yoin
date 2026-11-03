import SwiftUI
import YoinShared

// Android's M3 type roles (YoinTokens.Typography) in Google Sans Flex.
// Compose sets every line `lineHeight` tall; iOS sets GSF at its own line,
// Tokens.type.GSF_NATURAL_LINE_HEIGHT × size, so these helpers make up the
// difference.

extension Font.Weight {
    /// A YoinTokens.Typography weight (400/500/600/700) as a GSF static cut.
    init(gsf w: Int32) {
        switch w {
        case ..<450: self = .regular
        case ..<550: self = .medium
        case ..<650: self = .semibold
        default: self = .bold
        }
    }
}

/// Line boxes for wrapping text. SwiftUI's `lineSpacing` adds to the font's
/// own line, so a role's line height minus GSF's natural line is the extra.
enum YoinType {
    /// GSF's natural line, × size (hhea ascent 1932 + descent 572) / 2000 em.
    static var naturalLine: CGFloat { Tokens.type.GSF_NATURAL_LINE_HEIGHT.cg }

    static func lineSpacing(size: CGFloat, lineHeight: CGFloat) -> CGFloat {
        max(0, lineHeight - size * naturalLine)
    }

    static func lineSpacing(_ role: YoinTokens.TypographyStyle) -> CGFloat {
        lineSpacing(size: role.size.cg, lineHeight: role.lineHeight.cg)
    }
}

extension View {
    /// One YoinTypography role from the shared tokens (Type.kt): GSF at the
    /// role's size and static weight, its tracking (0, or 0.1 on
    /// labelMedium/Small) and its line box, so rows measure as on Android.
    /// One-line text: the line box is a minimum height, the glyphs centred.
    func yoinRole(_ s: YoinTokens.TypographyStyle, weight: Font.Weight? = nil) -> some View {
        font(.gsf(s.size.cg, weight ?? Font.Weight(gsf: s.weight)))
            .tracking(s.letterSpacing.cg)
            .frame(minHeight: s.lineHeight.cg)
    }

    /// Pads each line out to Android's line box (leading split above/below,
    /// like Compose's proportional line-height span), so wrapping text gets
    /// the role's line height on every line.
    func homeLine(_ size: CGFloat, _ lineHeight: CGFloat) -> some View {
        let extra = YoinType.lineSpacing(size: size, lineHeight: lineHeight)
        return lineSpacing(extra).padding(.vertical, extra / 2)
    }

    /// A role as Home sets it: `yoinRole`'s size, weight and tracking, with
    /// the `.copy(...)` overrides Home makes (size, line height, tabular
    /// digits) and `homeLine`'s per-line box, since Home's cards wrap.
    func homeType(_ style: YoinTokens.TypographyStyle, _ weight: Font.Weight? = nil,
                  size: CGFloat? = nil, lineHeight: CGFloat? = nil, digits: Bool = false) -> some View {
        let sz = size ?? style.size.cg
        let f = Font.gsf(sz, weight ?? Font.Weight(gsf: style.weight))
        return font(digits ? f.monospacedDigit() : f)
            .tracking(style.letterSpacing.cg)
            .homeLine(sz, lineHeight ?? style.lineHeight.cg)
    }
}
