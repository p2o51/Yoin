import SwiftUI
import UIKit

/// Where a song's sheet was opened: decides the rows it leaves out (Android
/// TrackMenuHost: an album row has no Go to album, it is that album).
struct TrackMenuContext {
    enum Kind { case library, album, playlist, nowPlaying }
    var kind: Kind
    /// Playlist rows: the playlist and the song's position, for Remove.
    var playlistId: String? = nil
    var index: Int? = nil
    /// The album page's own page 2 (Android onViewInScrapbook).
    var viewInScrapbook: (() -> Void)? = nil

    static var library: TrackMenuContext { TrackMenuContext(kind: .library) }
    static var nowPlaying: TrackMenuContext { TrackMenuContext(kind: .nowPlaying) }
    static func album(viewInScrapbook: (() -> Void)? = nil) -> TrackMenuContext {
        TrackMenuContext(kind: .album, viewInScrapbook: viewInScrapbook)
    }
    static func playlist(_ id: String, index: Int) -> TrackMenuContext {
        TrackMenuContext(kind: .playlist, playlistId: id, index: index)
    }
}

extension View {
    /// Long-press opens the song's sheet from the bottom (owner 2026-10-17:
    /// a bottom sheet, never a dropdown). Presented by the root host, so it
    /// rides over Now Playing and pushed pages alike.
    func trackMenu(for entry: PlaylistEntry, context: TrackMenuContext = .library) -> some View {
        modifier(TrackMenuTrigger(entry: entry, context: context))
    }
}

private struct TrackMenuTrigger: ViewModifier {
    var entry: PlaylistEntry
    var context: TrackMenuContext
    @State private var pressed = 0

    func body(content: Content) -> some View {
        content
            .onLongPressGesture(minimumDuration: 0.4) {
                pressed += 1
                PlaylistActions.shared.openMenu(entry, context: context)
            }
            .sensoryFeedback(.impact(weight: .medium), trigger: pressed)
            .accessibilityAction(named: "More") { PlaylistActions.shared.openMenu(entry, context: context) }
    }
}

/// A song's sheet (Android TrackMenuSheet): the song (56 Thumb cover, title
/// over artist), then Play next / Add to queue / Add to playlist, then Go to
/// album / View in Scrapbook / Go to artist / Share, then Remove from
/// playlist on a playlist the user owns, then Keep screen on from Now
/// Playing. A row the song or provider can't do is left out, and so is an
/// empty group. Rows are Settings segments (20 outer / 4 inner, 2 apart,
/// groups 12 apart) on the system glass sheet.
///
/// Present it with `.trackMenu(for:)` (or `PlaylistActions.shared.openMenu`).
/// Hosting it in a sheet of your own works too: call
/// `PlaylistActions.shared.flushPendingAdd()` from that sheet's onDismiss.
struct TrackMenuSheet: View {
    var track: PlaylistEntry
    var context: TrackMenuContext

    init(track: PlaylistEntry, context: TrackMenuContext = .library) {
        self.track = track
        self.context = context
    }

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @AppStorage("keepScreenOn") private var keepScreenOn = false
    @State private var height: CGFloat = 420
    @State private var tapped = 0

    private var actions: PlaylistActions { .shared }
    private var store: LibraryStore { model.store }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    private struct Row: Identifiable {
        var id: String { label }
        var label: String
        var icon: String
        var share: String? = nil
        var action: () -> Void = {}
    }

    private var hasAlbum: Bool { !track.album.id.rawId.hasPrefix("song:") && !track.album.id.rawId.isEmpty }
    private var artist: String { track.track.artist ?? track.album.artist }

    private var groups: [[Row]] {
        var queue: [Row] = [
            Row(label: "Play next", icon: "skip_next") { [model, track] in
                model.enqueue([track], next: true)
                PlaylistActions.shared.show("1 song will play next")
            },
            Row(label: "Add to queue", icon: "queue") { [model, track] in
                model.enqueue([track], next: false)
                PlaylistActions.shared.show("Added 1 song to the queue")
            },
        ]
        if actions.canAddToPlaylist(store) {
            queue.append(Row(label: "Add to playlist", icon: "playlist") { [track] in
                PlaylistActions.shared.addAfterDismiss([track])
            })
        }
        var go: [Row] = []
        if context.kind != .album && hasAlbum {
            go.append(Row(label: "Go to album", icon: "album") { [model, track] in
                Self.leaveOverlays(model)
                model.open(track.album)
            })
        }
        if let view = context.viewInScrapbook {
            go.append(Row(label: "View in Scrapbook", icon: "note", action: view))
        }
        if let artistId = track.album.artistId, !artist.isEmpty {
            go.append(Row(label: "Go to artist", icon: "artist") { [model, artist] in
                Self.leaveOverlays(model)
                model.openArtist(artist, id: artistId)
            })
        } else if track.album.id.provider == "mock" {
            go.append(Row(label: "Go to artist", icon: "artist") { [model, artist] in
                Self.leaveOverlays(model)
                model.openArtist(artist)
            })
        }
        go.append(Row(label: "Share", icon: "share",
                      share: [track.track.title, artist].filter { !$0.isEmpty }.joined(separator: " — ")))
        var edit: [Row] = []
        if context.kind == .playlist, let id = context.playlistId, let index = context.index,
           actions.canEdit(model.playlistInfo(id), store: store) {
            edit.append(Row(label: "Remove from playlist", icon: "delete") { [store] in
                PlaylistActions.shared.remove(at: index, from: id, store: store)
            })
        }
        return [queue, go, edit].filter { !$0.isEmpty }
    }

    /// Going somewhere closes Search and Now Playing first (Search's own rule).
    private static func leaveOverlays(_ model: AppModel) {
        withAnimation(Yoin.spatial) { model.searchActive = false }
        if model.npExpanded { model.setNowPlaying(expanded: false) }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                header
                ForEach(Array(groups.enumerated()), id: \.offset) { _, rows in
                    VStack(spacing: 2) {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                            rowView(row, shape: Self.segment(i, rows.count))
                        }
                    }
                }
                if context.kind == .nowPlaying { keepScreenOnRow }
            }
            .padding(.horizontal, 16)
            .padding(.top, 24)
            .padding(.bottom, 16)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.height(min(height + 8, 640))])
        .presentationDragIndicator(.visible)
        .sensoryFeedback(.selection, trigger: tapped)
    }

    /// TrackMenuHeader: 56 Thumb, 16, titleMedium SemiBold over bodyMedium.
    private var header: some View {
        HStack(spacing: 16) {
            CoverArt(palette: track.album.palette, cornerRadius: 0, seed: track.album.seed)
                .frame(width: 56, height: 56)
                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(track.track.title).lineLimit(1)
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(app.onSurface)
                if !artist.isEmpty {
                    Text(artist).lineLimit(1)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private func rowView(_ row: Row, shape: UnevenRoundedRectangle) -> some View {
        if let text = row.share {
            ShareLink(item: text) { rowContent(row) }
                .buttonStyle(.plain)
                .background(rowFill, in: shape)
                .contentShape(shape)
        } else {
            Button {
                tapped += 1
                row.action()
                dismiss()
            } label: { rowContent(row) }
                .buttonStyle(PressScale())
                .background(rowFill, in: shape)
                .contentShape(shape)
        }
    }

    /// TrackMenuRowContent: 56 tall, 20 in, icon onSurfaceVariant, 16, bodyLarge.
    private func rowContent(_ row: Row) -> some View {
        HStack(spacing: 16) {
            YoinIcon(row.icon, size: 24).foregroundStyle(app.onSurfaceVariant)
            Text(row.label)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(app.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 56)
        .contentShape(.rect)
    }

    /// Now Playing's player section: Keep screen on (the whole row toggles).
    private var keepScreenOnRow: some View {
        HStack(spacing: 16) {
            YoinIcon("light_mode", size: 24).foregroundStyle(app.onSurfaceVariant)
            Toggle(isOn: Binding(get: { keepScreenOn }, set: { on in
                keepScreenOn = on
                UIApplication.shared.isIdleTimerDisabled = on && model.npExpanded
                tapped += 1
            })) {
                Text("Keep screen on")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
            }
            .tint(app.primary)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 56)
        .background(rowFill, in: Self.segment(0, 1))
    }

    /// surfaceContainerHigh, thinned so the sheet's glass still reads through.
    private var rowFill: Color { app.surfaceContainerHigh.opacity(colorScheme == .dark ? 0.55 : 0.7) }

    /// Settings segments: 20 outer, 4 inner.
    static func segment(_ i: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = i == 0 ? 20 : 4
        let bottom: CGFloat = i == count - 1 ? 20 : 4
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .circular)
    }
}

// MARK: Root host

extension View {
    /// The one place the song sheet, Add to playlist, the playlist dialogs and
    /// the confirmations are presented from (RootView).
    func playlistActionsHost() -> some View { modifier(PlaylistActionsHost()) }
}

private struct PlaylistActionsHost: ViewModifier {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    /// Android NowPlayingKeepScreenOn: the screen stays on while Now Playing is up.
    @AppStorage("keepScreenOn") private var keepScreenOn = false

    func body(content: Content) -> some View {
        @Bindable var actions = PlaylistActions.shared
        content
            .sheet(item: $actions.menu, onDismiss: { PlaylistActions.shared.flushPendingAdd() }) { t in
                TrackMenuSheet(track: t.entry, context: t.context).environment(model)
            }
            .sheet(item: $actions.adding) { t in
                AddToPlaylistSheet(entries: t.entries).environment(model)
            }
            .alert("Rename playlist", isPresented: Binding(get: { actions.renaming != nil },
                                                           set: { if !$0 { actions.renaming = nil } }),
                   presenting: actions.renaming) { p in
                TextField("Name", text: $name)
                Button("Cancel", role: .cancel) {}
                Button("Rename") { PlaylistActions.shared.rename(p, to: name, store: model.store) }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .alert("Delete playlist?", isPresented: Binding(get: { actions.deleting != nil },
                                                            set: { if !$0 { actions.deleting = nil } }),
                   presenting: actions.deleting) { p in
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) { PlaylistActions.shared.delete(p, model: model) }
            } message: { p in
                Text("\"\(p.title)\" will be removed from your library.")
            }
            .onChange(of: actions.renaming?.id) { _, _ in name = actions.renaming?.title ?? "" }
            .overlay(alignment: .bottom) {
                if let toast = actions.toast {
                    Text(toast.text)
                        .yoinRole(Tokens.type.labelLarge)
                        .lineLimit(2)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 14)
                        .glassEffect(.regular, in: .capsule)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 96)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .id(toast.id)
                        .allowsHitTesting(false)
                }
            }
            .sensoryFeedback(.success, trigger: actions.toast?.id)
            #if DEBUG
            .task { await PlaylistActions.shared.runDemo(model) }
            #endif
            .onChange(of: model.npExpanded, initial: true) { _, open in
                UIApplication.shared.isIdleTimerDisabled = keepScreenOn && open
            }
    }
}
