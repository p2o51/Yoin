import SwiftUI

/*
 * The "E" badge (Android ExplicitBadge.kt, PR #343; owner 2026-11-25 / 26): a song or a release its service
 * marks explicit wears a small rounded square with an E cut out of it, right after its title, as Apple Music
 * does. Only explicit ever shows one; a clean or unknown title lays out exactly as before. The square is the
 * title's cap height (Google Sans Flex, at least 9 pt), sits on its baseline like a capital letter, and is drawn
 * in the title's neutral secondary colour (onSurfaceVariant). The E is a knockout, so whatever is behind shows
 * through (covers' colours, the spectrum bar, Now Playing's aurora). Now Playing's big titles take the hero size:
 * the title's x-height, at most 18 pt. The sizes, the gap and whether it fits are :shared's (`ReleaseMarks`).
 *
 * iOS (owner 2026-11-26, option B): there are no backdrop shapes, so the E always follows the title — on Library's
 * cards too — and never scrolls away: a marquee title scrolls in the room the E leaves.
 */

/// The square with its knocked-out E, filling the size it is given.
struct ExplicitBadgeShape: View {
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            let s = min(size.width, size.height)
            guard s > 0 else { return }
            ctx.drawLayer { layer in
                let rect = CGRect(x: (size.width - s) / 2, y: (size.height - s) / 2, width: s, height: s)
                layer.fill(Path(roundedRect: rect, cornerRadius: s * ReleaseMarks.cornerShare), with: .color(color))
                // The E's cap box centred in the square: its cap height is LETTER_SHARE of the side.
                let letterSize = s * ReleaseMarks.letterShare / ReleaseMarks.capHeightEm
                let e = layer.resolve(Text("E").font(.gsf(letterSize, .bold)))
                let measured = e.measure(in: CGSize(width: s * 4, height: s * 4))
                let baseline = e.firstBaseline(in: measured)
                let cap = letterSize * ReleaseMarks.capHeightEm
                layer.blendMode = .destinationOut
                layer.draw(e, at: CGPoint(x: rect.midX, y: rect.minY + (s + cap) / 2),
                           anchor: UnitPoint(x: 0.5, y: measured.height > 0 ? baseline / measured.height : 1))
            }
        }
        .accessibilityHidden(true)
    }
}

/// The badge for a title set at [fontSize] on its own (a row's end, beside a tag): its fixed square, its bottom
/// reported as its baseline so a `.firstTextBaseline` stack sets it on the title's baseline. VoiceOver reads
/// "Explicit" unless [describe] is false (the title around it says it).
struct ExplicitBadge: View {
    var fontSize: CGFloat
    var hero = false
    var color: Color
    var describe = true

    var body: some View {
        let side = ReleaseMarks.badgeSide(fontSize: fontSize, hero: hero)
        ExplicitBadgeShape(color: color)
            .frame(width: side, height: side)
            .alignmentGuide(.firstTextBaseline) { $0.height }
            .alignmentGuide(.lastTextBaseline) { $0.height }
            .accessibilityElement()
            .accessibilityLabel(Text("Explicit"))
            .accessibilityHidden(!describe)
    }
}

/// [title] with the E right after its last glyph when [explicit]; without it, [title] alone, laid out exactly as
/// before. One line: a cut title ends on its ellipsis and the E follows it; a marquee scrolls in the room the E
/// leaves, the E fixed after it. The title wins: where the E would leave it less than half the row (or less than
/// itself, if shorter), the E is left out (`explicitBadgeFits`). VoiceOver reads the title and "Explicit" as one,
/// whether the E is drawn or not.
struct ExplicitTitle<Title: View>: View {
    var text: String
    var explicit: Bool
    /// The title's point size (the badge's side and gap follow it).
    var fontSize: CGFloat
    var hero = false
    var color: Color
    @ViewBuilder var title: () -> Title

    var body: some View {
        if explicit {
            ExplicitTitleLayout(side: ReleaseMarks.badgeSide(fontSize: fontSize, hero: hero),
                                gap: ReleaseMarks.badgeGap(fontSize: fontSize, hero: hero)) {
                title()
                ExplicitBadgeShape(color: color)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(verbatim: text) + Text(verbatim: ", ") + Text("Explicit"))
        } else {
            title()
        }
    }
}

/// Lays out [title, badge]: the title at its own width, no wider than the row less the badge; the badge after it,
/// its bottom on the title's first baseline. A badge that doesn't fit is given no room (and draws nothing).
struct ExplicitTitleLayout: Layout {
    var side: CGFloat
    var gap: CGFloat

    private struct Plan {
        var title: CGSize
        var fits: Bool
    }

    private func plan(_ proposal: ProposedViewSize, _ subviews: Subviews) -> Plan {
        let natural = subviews[0].sizeThatFits(.unspecified)
        guard let width = proposal.width, width.isFinite else {
            return Plan(title: natural, fits: true)
        }
        let room = width - side - gap
        let fits = ReleaseMarks.badgeFits(maxWidth: width, titleRoom: room, naturalTitle: natural.width)
        let titleWidth = min(natural.width, max(0, fits ? room : width))
        return Plan(title: subviews[0].sizeThatFits(ProposedViewSize(width: titleWidth, height: proposal.height)),
                    fits: fits)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let p = plan(proposal, subviews)
        let width = p.title.width + (p.fits ? gap + side : 0)
        return CGSize(width: width, height: max(p.title.height, p.fits ? side : 0))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let p = plan(ProposedViewSize(width: bounds.width, height: bounds.height), subviews)
        let titleProposal = ProposedViewSize(p.title)
        subviews[0].place(at: CGPoint(x: bounds.minX, y: bounds.minY), proposal: titleProposal)
        let baseline = subviews[0].dimensions(in: titleProposal)[VerticalAlignment.firstTextBaseline]
        let y = bounds.minY + max(0, baseline - side)
        let size = p.fits ? side : 0
        subviews[1].place(at: CGPoint(x: bounds.minX + p.title.width + gap, y: y),
                          proposal: ProposedViewSize(width: size, height: size))
    }

    func explicitAlignment(of guide: VerticalAlignment, in bounds: CGRect, proposal: ProposedViewSize,
                           subviews: Subviews, cache: inout ()) -> CGFloat? {
        guard guide == .firstTextBaseline || guide == .lastTextBaseline else { return nil }
        let p = plan(ProposedViewSize(width: bounds.width, height: bounds.height), subviews)
        return bounds.minY + subviews[0].dimensions(in: ProposedViewSize(p.title))[guide]
    }
}

/// The badge as a piece of text, for a title that wraps (Now Playing's two-line title): the gap, then the square,
/// standing on the baseline after the last word. Rendered once per size and colour.
@MainActor
enum ExplicitBadgeText {
    private static var images: [String: UIImage] = [:]

    static func text(fontSize: CGFloat, hero: Bool, color: Color) -> Text {
        let side = ReleaseMarks.badgeSide(fontSize: fontSize, hero: hero)
        let gap = ReleaseMarks.badgeGap(fontSize: fontSize, hero: hero)
        let key = "\(side)/\(gap)/\(color.description)"
        let image: UIImage
        if let hit = images[key] {
            image = hit
        } else {
            let renderer = ImageRenderer(content: ExplicitBadgeShape(color: color)
                .frame(width: side, height: side)
                .padding(.leading, gap))
            renderer.scale = 3
            image = renderer.uiImage ?? UIImage()
            images[key] = image
        }
        return Text(Image(uiImage: image))
    }
}

/// Small tags after a name, as `AlbumVersionTag` draws them: the release's kind worth a tag (EP / Single) and its
/// version ("Deluxe", "全景声"). iOS's option B (owner 2026-11-26) puts them on Now Playing's artist line.
enum ReleaseTags {
    /// The tags to show for [album]: its kind when an EP or a single, then its version.
    static func tags(_ album: Album) -> [String] {
        var tags: [String] = []
        if let kind = album.releaseType, ReleaseMarks.showsKindTag(kind) { tags.append(kind.label) }
        if let version = album.versionTag { tags.append(version) }
        return tags
    }
}
