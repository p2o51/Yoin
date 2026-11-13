import SwiftUI

/*
 * A song's sheet on the demo phone (Android ui/tips/TipNowPlayingScenes.kt `DemoSongSheet`): the app's own
 * sheet body (Library/TrackMenuSheet.swift `TrackMenuBody`) over a made-up song, in a sheet of the phone's
 * own. The app's is the system's, which belongs to the window; this one rises inside the phone the same way,
 * on the same glass, and the page behind it steps back by the same numbers (docs/glass-layers.md L4).
 */

/// A page of the demo phone and the sheet over it: the sheet slides up from the phone's foot, the page behind
/// steps back, and a tap above the sheet sends it down.
struct DemoSheetStage<Page: View, Sheet: View>: View {
    var open: Bool
    var onDismiss: () -> Void
    @ViewBuilder var page: () -> Page
    @ViewBuilder var sheet: () -> Sheet

    var body: some View {
        let lab = GlassLab.shared
        ZStack(alignment: .bottom) {
            page()
                .scaleEffect(open ? 1 - lab.extra("sheetRecedeScale") : 1)
                .blur(radius: open ? lab.extra("sheetRecedeBlur") : 0)
                .overlay {
                    Color.black.opacity(open ? max(lab.extra("sheetRecedeDim"), DemoSheetMetrics.scrim) : 0)
                        .allowsHitTesting(false)
                }
            if open {
                Color.clear
                    .contentShape(.rect)
                    .onTapGesture(perform: onDismiss)
                    .transition(.identity)
                sheet()
                    .transition(.move(edge: .bottom))
                    .zIndex(1)
            }
        }
        .animation(Yoin.spatial, value: open)
    }
}

enum DemoSheetMetrics {
    /// The system sheet floats this far inside the screen's edges, on this corner.
    static let inset: CGFloat = 8
    static let corner: CGFloat = 38
    /// The grabber: 36 × 5, 5 under the sheet's top.
    static let grabber = CGSize(width: 36, height: 5)
    static let grabberTop: CGFloat = 5
    /// The least the page behind dims (the system's own dimming under a sheet).
    static let scrim: Double = 0.1
    /// How long a queue button shows its check (TrackMenuSheet.flash).
    static let flashMs = 1_400
}

/// The song's sheet: the top row and rows the app's sheet lists for a song of an album or playlist, or for
/// the playing one ([nowPlaying]: Favorite / Add to playlist / Share on top, Go to album below). [offline]:
/// a Subsonic song's Download row, whose tap is told. Nothing here plays, queues, shares or opens a page:
/// a row or Add to playlist sends the sheet down, as in the app.
struct DemoSongSheet<Player: View>: View {
    var track: PlaylistEntry
    var scheme: CoverScheme
    var nowPlaying = false
    /// The Download row's state (nil: no such row) and its tap.
    var offline: OfflineDownloads.Status? = nil
    var onOffline: () -> Void = {}
    var onDismiss: () -> Void
    @ViewBuilder var player: () -> Player

    @State private var favorite = false
    /// Queue buttons just tapped: their icon turns into a check for a moment.
    @State private var done: Set<String> = []

    private func flash(_ id: String) {
        withAnimation(Yoin.effects) { _ = done.insert(id) }
        Task {
            try? await DemoTiming.delay(DemoSheetMetrics.flashMs)
            withAnimation(Yoin.effects) { _ = done.remove(id) }
        }
    }

    private var quick: [TrackMenuQuick] {
        DemoSongSheetRows.quick(nowPlaying: nowPlaying, favorite: favorite, done: done,
                                onQueue: { flash($0) }, onFavorite: { withAnimation(Yoin.spatialFast) { favorite.toggle() } })
    }

    var body: some View {
        TrackMenuBody(track: track, scheme: scheme, quick: quick,
                      groups: DemoSongSheetRows.groups(nowPlaying: nowPlaying, offline: offline, onOffline: onOffline),
                      onQuick: { q in
                          q.action()
                          if q.closes { onDismiss() }
                      },
                      onRow: { row in
                          row.action()
                          onDismiss()
                      },
                      player: player)
        // The grabber sits in the sheet's 24 above the song, as the system's does.
        .overlay(alignment: .top) {
            Capsule()
                .fill(scheme.onSurfaceVariant.opacity(0.4))
                .frame(width: DemoSheetMetrics.grabber.width, height: DemoSheetMetrics.grabber.height)
                .padding(.top, DemoSheetMetrics.grabberTop)
        }
        .glassEffect(.regular, in: .rect(cornerRadius: DemoSheetMetrics.corner, style: .continuous))
        .padding(.horizontal, DemoSheetMetrics.inset)
        .padding(.bottom, DemoSheetMetrics.inset)
        .onChange(of: track) { favorite = false }
    }
}

extension DemoSongSheet where Player == EmptyView {
    init(track: PlaylistEntry, scheme: CoverScheme, nowPlaying: Bool = false, offline: OfflineDownloads.Status? = nil,
         onOffline: @escaping () -> Void = {}, onDismiss: @escaping () -> Void) {
        self.init(track: track, scheme: scheme, nowPlaying: nowPlaying, offline: offline, onOffline: onOffline,
                  onDismiss: onDismiss) { EmptyView() }
    }
}

/// What the app's sheet lists (TrackMenuSheet `quick` / `groups`), without the account: the labels and
/// symbols are the sheet's own strings. Pure (TipDemoTests).
enum DemoSongSheetRows {
    /// The Download row's symbol, for the demo touch to find it.
    @MainActor
    static func offlineTarget(_ status: OfflineDownloads.Status) -> String { "menuRow.\(OfflineDownloads.rowIcon(status))" }

    @MainActor
    static func quick(nowPlaying: Bool, favorite: Bool, done: Set<String> = [], onQueue: @escaping (String) -> Void = { _ in },
                      onFavorite: @escaping () -> Void = {}) -> [TrackMenuQuick] {
        let heart = TrackMenuQuick(id: "favorite",
                                   label: favorite ? String(localized: "Favorited") : String(localized: "Favorite"),
                                   icon: favorite ? "favorite_filled" : "favorite", checked: favorite, action: onFavorite)
        let share = TrackMenuQuick(id: "share", label: String(localized: "Share"), icon: "share")
        if nowPlaying {
            return [heart,
                    TrackMenuQuick(id: "playlist", label: String(localized: "Add to playlist"), icon: "playlist", closes: true),
                    share]
        }
        func queue(_ id: String, _ label: String, _ icon: String) -> TrackMenuQuick {
            TrackMenuQuick(id: id, label: label, icon: done.contains(id) ? "check" : icon) { onQueue(id) }
        }
        return [queue("next", String(localized: "Play next"), "skip_next"),
                queue("queue", String(localized: "Add to queue"), "queue"),
                heart, share]
    }

    @MainActor
    static func groups(nowPlaying: Bool, offline: OfflineDownloads.Status? = nil,
                       onOffline: @escaping () -> Void = {}) -> [[TrackMenuRow]] {
        var queue: [TrackMenuRow] = []
        if !nowPlaying { queue.append(TrackMenuRow(label: String(localized: "Add to playlist"), icon: "playlist")) }
        if let offline {
            queue.append(TrackMenuRow(label: OfflineDownloads.rowLabel(offline), icon: OfflineDownloads.rowIcon(offline),
                                      action: onOffline))
        }
        var go: [TrackMenuRow] = []
        // An album's own row has no Go to album (it is that album).
        if nowPlaying { go.append(TrackMenuRow(label: String(localized: "Go to album"), icon: "album")) }
        go.append(TrackMenuRow(label: String(localized: "View in Scrapbook"), icon: "note"))
        go.append(TrackMenuRow(label: String(localized: "Go to artist"), icon: "artist"))
        return [queue, go].filter { !$0.isEmpty }
    }
}
