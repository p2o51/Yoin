import SwiftUI
import WidgetKit

/// Play (Android PlayWidget): recent listening on the neutral colours. Small = the stack (the latest in
/// front, play in the clear corner); Medium / Large = the latest with its play key over a shelf of the next
/// ones, two segments meeting at a 16 pt seam.
struct PlayWidgetView: View {
    var snapshot: WidgetSnapshot?
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ink = NeutralInk(snapshot?.neutral ?? .fallback, dark: colorScheme == .dark)
        Group {
            if let items = snapshot?.play, let front = items.first {
                switch family {
                case .systemSmall: PlayStack(items: items, front: front, ink: ink)
                default: PlayShelf(items: items, front: front, ink: ink)
                }
            } else {
                WidgetEmptyView(text: "Nothing played yet", ink: ink)
            }
        }
        .containerBackground(ink.bg, for: .widget)
    }
}

/// Small: three covers on a 184 grid that never overlap one another (no cover over a Spotify cover), the
/// latest largest at the bottom left, the play key in the corner it leaves clear.
private struct PlayStack: View {
    var items: [WidgetSnapshot.Play]
    var front: WidgetSnapshot.Play
    var ink: NeutralInk

    var body: some View {
        GeometryReader { geo in
            let s = min(geo.size.width, geo.size.height)
            let u = s / 184
            ZStack(alignment: .topLeading) {
                if items.count > 2 { cover(items[2], 58 * u).offset(x: 52 * u, y: 10 * u) }
                if items.count > 1 { cover(items[1], 58 * u).offset(x: 116 * u, y: 10 * u) }
                cover(front, 104 * u).offset(x: 10 * u, y: 72 * u)
                WidgetPlayButton(item: front, size: 52 * u, color: ink.primary, ink: ink.onPrimary)
                    .offset(x: 122 * u, y: 122 * u)
            }
            .frame(width: s, height: s, alignment: .topLeading)
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .widgetURL(WidgetLink.open(entity: front.entity, provider: front.provider, id: front.id).url)
    }

    private func cover(_ item: WidgetSnapshot.Play, _ size: CGFloat) -> some View {
        WidgetCover(file: item.cover, size: size, circle: item.entity == .artist, placeholder: ink.shelf)
            .accessibilityLabel(item.title)
    }
}

/// Medium / Large: the latest (cover, title, play) over a shelf of the next ones.
private struct PlayShelf: View {
    var items: [WidgetSnapshot.Play]
    var front: WidgetSnapshot.Play
    var ink: NeutralInk

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let heroH = h * 0.54
            let heroCover = min(max(heroH - 32, 56), 96)
            let shelfCover = min(max(h - heroH - 28, 44), 80)
            let count = min(max(Int((w - 32 + 12) / (shelfCover + 12)), 3), 7)
            let shelf = Array(items.dropFirst().prefix(count))
            VStack(spacing: 0) {
                Link(destination: WidgetLink.open(entity: front.entity, provider: front.provider, id: front.id).url) {
                    HStack(spacing: 12) {
                        WidgetCover(file: front.cover, size: heroCover, circle: front.entity == .artist, placeholder: ink.shelf)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(front.title)
                                .font(.system(size: 18, weight: .medium))
                                .foregroundStyle(ink.ink)
                                .lineLimit(2)
                            if !front.subtitle.isEmpty {
                                Text(front.subtitle)
                                    .font(.system(size: 13))
                                    .foregroundStyle(ink.sub)
                                    .lineLimit(1)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        WidgetPlayButton(item: front, size: min(heroCover, 72), color: ink.primary, ink: ink.onPrimary)
                    }
                    .padding(16)
                    .frame(height: heroH)
                }
                HStack(spacing: 0) {
                    ForEach(Array(shelf.enumerated()), id: \.element.key) { i, item in
                        if i > 0 { Spacer(minLength: 12) }
                        Link(destination: WidgetLink.open(entity: item.entity, provider: item.provider, id: item.id).url) {
                            WidgetCover(file: item.cover, size: shelfCover, circle: item.entity == .artist,
                                        placeholder: ink.bg)
                                .accessibilityLabel(item.title)
                        }
                    }
                    if shelf.count < count { Spacer(minLength: 0) }
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(ink.shelf, in: UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16,
                                                                   style: .continuous))
            }
        }
    }
}
