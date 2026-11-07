import SwiftUI
import WidgetKit

/*
 * Yoin's home screen widgets (Android widget/PlayWidget + MemoryWidget, Figma "Final · Play & Memory"):
 * Play picks up recent listening; Memory shows album memories, the score and how long ago first. They read
 * the app's snapshot (WidgetSnapshot) and nothing else. iOS sizes: Small = Android's 2×2 (the stack / the
 * anchor), Medium and Large = the wide layouts (the shelf / the timeline, two rows when tall). Covers stay
 * rounded squares (4 pt), artists circles; nothing is drawn over Spotify artwork.
 */
@main
struct YoinWidgetsBundle: WidgetBundle {
    var body: some Widget {
        PlayWidget()
        MemoryWidget()
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?
}

/// One entry from the snapshot, until the next midnight ("3 Months Ago" ages); the app reloads on a change.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry { SnapshotEntry(date: .now, snapshot: nil) }

    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(SnapshotEntry(date: .now, snapshot: WidgetStore.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let now = Date()
        let midnight = Calendar.current.nextDate(after: now, matching: DateComponents(hour: 0, minute: 1),
                                                 matchingPolicy: .nextTime) ?? now.addingTimeInterval(86_400)
        completion(Timeline(entries: [SnapshotEntry(date: now, snapshot: WidgetStore.read())], policy: .after(midnight)))
    }
}

struct PlayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetStore.playKind, provider: SnapshotProvider()) { entry in
            PlayWidgetView(snapshot: entry.snapshot)
        }
        .configurationDisplayName(Text("Play"))
        .description(Text("Recent listening"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

struct MemoryWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetStore.memoryKind, provider: SnapshotProvider()) { entry in
            MemoryWidgetView(snapshot: entry.snapshot, now: entry.date)
        }
        .configurationDisplayName(Text("Memory"))
        .description(Text("Your album memories"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .contentMarginsDisabled()
    }
}

// MARK: Pieces

/// The neutral colours for the current appearance.
struct NeutralInk {
    var bg: Color, ink: Color, sub: Color, primary: Color, onPrimary: Color, shelf: Color, outline: Color

    init(_ n: WidgetSnapshot.Neutral, dark: Bool) {
        let c = dark ? n.dark : n.light
        bg = Color(argb: c.background); ink = Color(argb: c.onSurface); sub = Color(argb: c.onSurfaceVariant)
        primary = Color(argb: c.primary); onPrimary = Color(argb: c.onPrimary); shelf = Color(argb: c.shelf)
        outline = Color(argb: c.outline)
    }
}

/// A cover from the snapshot's folder: a rounded square (4 pt), or a circle for an artist.
struct WidgetCover: View {
    var file: String?
    var size: CGFloat
    var circle = false
    var placeholder: Color

    var body: some View {
        let shape = circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        Group {
            if let url = WidgetStore.cover(file), let image = UIImage(contentsOfFile: url.path) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
    }
}

/// The play key: a filled circle with Yoin's play glyph, starting [item] in the app.
struct WidgetPlayButton: View {
    var item: WidgetSnapshot.Play
    var size: CGFloat
    var color: Color
    var ink: Color

    var body: some View {
        Button(intent: PlayFromWidgetIntent(item)) {
            Image("yoin.play_filled")
                .font(.system(size: size * 0.4))
                .foregroundStyle(ink)
                .frame(width: size, height: size)
                .background(color, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("Play \(item.title)"))
    }
}

/// Nothing to show yet: the container and one line; a tap opens the app.
struct WidgetEmptyView: View {
    var text: LocalizedStringKey
    var ink: NeutralInk

    var body: some View {
        Text(text)
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(ink.sub)
            .multilineTextAlignment(.center)
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .widgetURL(WidgetLink.app.url)
    }
}
