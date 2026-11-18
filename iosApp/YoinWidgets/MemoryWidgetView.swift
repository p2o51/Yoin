import SwiftUI
import WidgetKit

/// Memory (Android MemoryWidget): album memories, the score and how long since you heard it first.
/// Small = the anchor in the album's own colours (a big cover, the Cookie12 badge at its top right);
/// Medium / Large = the timeline on the neutral colours, every cover the same size, a score chip only when
/// the album is rated; one row, two when tall.
struct MemoryWidgetView: View {
    var snapshot: WidgetSnapshot?
    var now: Date
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let dark = colorScheme == .dark
        let ink = NeutralInk(snapshot?.neutral ?? .fallback, dark: dark)
        if let items = snapshot?.memories, let first = items.first {
            switch family {
            case .systemSmall:
                let tones = MemoryInk(first.palette, dark: dark)
                MemoryAnchor(item: first, lastHeard: WidgetCopy.lastHeard(first.lastHeardAt, now: now), tones: tones)
                    .containerBackground(tones.bg, for: .widget)
            default:
                MemoryTimeline(items: items, now: now, ink: ink, rows: family == .systemLarge ? 2 : 1)
                    .containerBackground(ink.bg, for: .widget)
            }
        } else {
            WidgetEmptyView(text: "No memories yet", ink: ink)
                .containerBackground(ink.bg, for: .widget)
        }
    }
}

/// One memory's colours (Android WidgetPalette's bg / ink / accent).
struct MemoryInk {
    var bg: Color, ink: Color, accent: Color, deep: Color, badge: Color

    init(_ p: WidgetSnapshot.Palette, dark: Bool) {
        bg = dark ? Self.blend(p.deep, 0xFF00_0000, 0.38) : Color(argb: p.soft)
        ink = Color(argb: dark ? p.soft : p.deep)
        accent = dark ? Color(argb: p.soft) : Color(argb: p.base)
        deep = Color(argb: p.deep)
        badge = Color(argb: p.accent)
    }

    static func blend(_ a: UInt32, _ b: UInt32, _ t: Double) -> Color {
        func ch(_ v: UInt32, _ s: UInt32) -> Double { Double((v >> s) & 0xFF) }
        func mix(_ s: UInt32) -> UInt32 { UInt32(ch(a, s) * (1 - t) + ch(b, s) * t) }
        return Color(argb: 0xFF00_0000 | mix(16) << 16 | mix(8) << 8 | mix(0))
    }
}

/// Small: a big cover at the bottom left, the Cookie12 badge at the top right — over the cover's corner,
/// or (Spotify artwork) clear of it, the cover a little smaller.
private struct MemoryAnchor: View {
    var item: WidgetSnapshot.Memory
    var lastHeard: String?
    var tones: MemoryInk

    var body: some View {
        GeometryReader { geo in
            let s = min(geo.size.width, geo.size.height) - 24
            let u = s / 184
            let cover: (size: CGFloat, y: CGFloat) = item.spotify ? (108 * u, 76 * u) : (120 * u, 64 * u)
            let badge: (size: CGFloat, x: CGFloat) = item.spotify ? (76 * u, 108 * u) : (110 * u, 74 * u)
            ZStack(alignment: .topLeading) {
                WidgetCover(file: item.cover, size: cover.size, placeholder: tones.badge)
                    .offset(y: cover.y)
                ScoreBadge(item: item, lastHeard: lastHeard, tones: tones, size: badge.size, u: u)
                    .offset(x: badge.x)
            }
            .frame(width: s, height: s, alignment: .topLeading)
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .widgetURL(WidgetLink.memory(provider: item.provider, albumId: item.albumId).url)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text([item.score.map(WidgetCopy.score), lastHeard, item.title ?? item.albumName]
            .compactMap { $0 }.joined(separator: ", ")))
    }
}

/// The Cookie12 badge: the score in the middle (else when you heard it), and around the edge when you
/// heard it and the memory's title, in small capitals (Android cookieBadge).
private struct ScoreBadge: View {
    var item: WidgetSnapshot.Memory
    var lastHeard: String?
    var tones: MemoryInk
    var size: CGFloat
    var u: CGFloat

    var body: some View {
        let title = item.title ?? item.albumName
        let ring = ([item.score != nil ? lastHeard : nil, title].compactMap { $0 })
            .joined(separator: "  •  ").uppercased() + "  •  "
        ZStack {
            WidgetCookie().fill(tones.badge)
            if let score = item.score {
                Text(WidgetCopy.score(score))
                    .font(.system(size: 30 * u * size / (110 * u), weight: .bold))
                    .foregroundStyle(tones.deep)
            } else {
                Text((lastHeard ?? "").uppercased())
                    .font(.system(size: 12 * u, weight: .bold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(tones.deep)
                    .frame(width: size * 0.5)
            }
            // The ring needs room: a small (Spotify) badge keeps the middle only.
            if size >= 90 * u {
                RingText(text: ring, radius: size * 0.37, fontSize: 8 * u, color: tones.deep)
            }
        }
        .frame(width: size, height: size)
    }
}

/// Characters around a circle, clockwise from the top, as many as fit once round.
private struct RingText: View {
    var text: String
    var radius: CGFloat
    var fontSize: CGFloat
    var color: Color

    var body: some View {
        let chars = Array(text)
        let widths = chars.map { c -> CGFloat in
            c.unicodeScalars.contains { $0.value >= 0x2E80 } ? fontSize * 1.05 : (c == " " ? fontSize * 0.32 : fontSize * 0.66)
        }
        let full = 2 * CGFloat.pi * radius
        var shown: [(Character, CGFloat)] = []
        var at: CGFloat = 0
        for (c, w) in zip(chars, widths) where at + w <= full {
            shown.append((c, at + w / 2))
            at += w
        }
        return ZStack {
            ForEach(Array(shown.enumerated()), id: \.offset) { _, g in
                let angle = Double(g.1 / radius)
                Text(String(g.0))
                    .font(.system(size: fontSize, weight: .semibold))
                    .foregroundStyle(color)
                    .offset(y: -radius)
                    .rotationEffect(.radians(angle))
            }
        }
    }
}

/// Cookie12Sided (the app's CookieShape).
struct WidgetCookie: Shape {
    var lobes = 12
    var depth: CGFloat = 0.075

    func path(in rect: CGRect) -> Path {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        for i in 0...360 {
            let t = Double(i) / 360 * 2 * .pi
            let r = radius * (1 - depth / 2 + depth / 2 * CGFloat(cos(Double(lobes) * t)))
            let p = CGPoint(x: c.x + r * CGFloat(cos(t - .pi / 2)), y: c.y + r * CGFloat(sin(t - .pi / 2)))
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }
}

/// Medium / Large: the memories on a time axis, newest first, left to right; a second row when tall.
private struct MemoryTimeline: View {
    var items: [WidgetSnapshot.Memory]
    var now: Date
    var ink: NeutralInk
    var rows: Int

    private let cover: CGFloat = 64
    private var itemW: CGFloat { cover + 14 }

    var body: some View {
        GeometryReader { geo in
            let perRow = min(max(Int((geo.size.width - 32 + 12) / (itemW + 12)), 2), 7)
            VStack(spacing: 20) {
                ForEach(0..<rows, id: \.self) { r in
                    let slice = Array(items.dropFirst(r * perRow).prefix(perRow))
                    if !slice.isEmpty {
                        ZStack(alignment: .topLeading) {
                            // The axis, through the dots.
                            Rectangle().fill(ink.outline).frame(height: 2).offset(y: cover + 12 + 8 + 3)
                            HStack(alignment: .top, spacing: 0) {
                                ForEach(Array(slice.enumerated()), id: \.element.id) { i, item in
                                    if i > 0 { Spacer(minLength: 12) }
                                    entry(item)
                                }
                                if slice.count < perRow { Spacer(minLength: 0) }
                            }
                        }
                    }
                }
            }
            .padding(16)
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private func entry(_ item: WidgetSnapshot.Memory) -> some View {
        let tones = MemoryInk(item.palette, dark: false)
        let chip: CGFloat = 34
        return Link(destination: WidgetLink.memory(provider: item.provider, albumId: item.albumId).url) {
            VStack(alignment: .leading, spacing: 8) {
                ZStack(alignment: .topLeading) {
                    WidgetCover(file: item.cover, size: cover, placeholder: ink.shelf).offset(y: 12)
                    // The chip bites the cover's corner; never over Spotify artwork (its score is a line below).
                    if let score = item.score, !item.spotify {
                        ZStack {
                            WidgetCookie().fill(tones.badge)
                            Text(WidgetCopy.score(score)).font(.system(size: 13, weight: .bold)).foregroundStyle(tones.deep)
                        }
                        .frame(width: chip, height: chip)
                        .offset(x: itemW - chip)
                    }
                }
                .frame(width: itemW, height: cover + 12, alignment: .topLeading)
                Circle().fill(ink.ink).frame(width: 8, height: 8)
                VStack(alignment: .leading, spacing: 0) {
                    if let score = item.score, item.spotify {
                        Text(WidgetCopy.score(score)).font(.system(size: 13, weight: .bold)).foregroundStyle(ink.primary)
                    }
                    Text(WidgetCopy.lastHeard(item.lastHeardAt, now: now) ?? "")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(ink.sub)
                        .lineLimit(2)
                }
            }
            .frame(width: itemW, alignment: .leading)
        }
        .accessibilityLabel(Text([item.albumName, item.score.map(WidgetCopy.score),
                                  WidgetCopy.lastHeard(item.lastHeardAt, now: now)].compactMap { $0 }.joined(separator: ", ")))
    }
}
