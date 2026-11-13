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
    /// a bottom sheet, never a dropdown); so does a secondary click. Presented by the root host, so it
    /// rides over Now Playing and pushed pages alike.
    /// [secondaryClick] false: the host attaches `onSecondaryClick` itself, inside its own tap
    /// (a tap outside it would take the click first).
    func trackMenu(for entry: PlaylistEntry, context: TrackMenuContext = .library,
                   secondaryClick: Bool = true) -> some View {
        modifier(TrackMenuTrigger(entry: entry, context: context, secondaryClick: secondaryClick))
    }

    /// A song row of a list (Android SongListItem / AlbumTrackRow combinedClickable): the
    /// whole row plays on a tap and opens the song's sheet on a long press, and wears the
    /// pressed tint while a finger is on it.
    /// [menu]: what the long press does in place of the song's sheet (the tip page's demo phone raises its own).
    func trackRow(for entry: PlaylistEntry, context: TrackMenuContext = .library,
                  play: @escaping () -> Void, menu: (() -> Void)? = nil) -> some View {
        modifier(TrackRowGestures(entry: entry, context: context, play: play, menu: menu))
    }
}

private struct TrackRowGestures: ViewModifier {
    var entry: PlaylistEntry
    var context: TrackMenuContext
    var play: () -> Void
    @Environment(AppModel.self) private var model
    var menu: (() -> Void)?
    @State private var pressing = false
    @State private var hovered = false
    @State private var pressed = 0

    private func openMenu() {
        if let menu { menu() } else { PlaylistActions.shared.openMenu(entry, context: context) }
    }

    func body(content: Content) -> some View {
        let layer = PointerHover.opacity(hovered: hovered || PointerHover.preview, pressed: pressing)
        content
            // Dragged onto the queue or a playlist (a pointer at once; a finger after the long press).
            .yoinDraggable { YoinDrag.song(entry, model: model) }
            .background {
                // The pressed tint, and the same 8 % under a pointer (iPad, the iPad app on a Mac).
                RoundedRectangle(cornerRadius: Tokens.shape.LIST_ROW.cg, style: .continuous)
                    .fill(Color.primary.opacity(layer))
                    .animation(Yoin.effects, value: layer)
            }
            .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
            .onPointerHover { hovered = $0 }
            // A right-click (pointer, the iPad app on a Mac): the same sheet, never a play.
            .onSecondaryClick { openMenu() }
            .onTapGesture(perform: play)
            .onLongPressGesture(minimumDuration: 0.4) {
                pressed += 1
                openMenu()
            } onPressingChanged: { pressing = $0 }
            .sensoryFeedback(.impact(weight: .medium), trigger: pressed)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: "More") { openMenu() }
    }
}

private struct TrackMenuTrigger: ViewModifier {
    var entry: PlaylistEntry
    var context: TrackMenuContext
    var secondaryClick: Bool
    @State private var pressed = 0

    func body(content: Content) -> some View {
        // Fixed per call site, so the branch never changes a view's identity.
        Group {
            if secondaryClick {
                content.onSecondaryClick { PlaylistActions.shared.openMenu(entry, context: context) }
            } else {
                content
            }
        }
        .onLongPressGesture(minimumDuration: 0.4) {
            pressed += 1
            PlaylistActions.shared.openMenu(entry, context: context)
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: pressed)
        .accessibilityAction(named: "More") { PlaylistActions.shared.openMenu(entry, context: context) }
    }
}

/// A song's sheet (Android TrackMenuSheet): the song (56 Thumb cover, title
/// over artist, then the file's format tags where the server names them),
/// then a top row of one-tap actions on the song — Play next / Add to queue /
/// Favorite / Share (owner 2026-11-03, after Apple Music's row) — then Add
/// to playlist, then Go to album / View in Scrapbook / Go to artist, then
/// Remove from playlist on a playlist the user owns, then Keep screen on
/// from Now Playing. A row the song or provider can't do is left out, and so
/// is an empty group. Rows are Settings segments (20 outer / 4 inner, 2
/// apart, groups 12 apart) on the system glass sheet.
///
/// Now Playing context (the mini player's and the cover's long-press): the
/// song is the one playing, so queueing it again makes no sense — the top
/// row is Favorite / Add to playlist / Share. A top-row tap keeps the sheet
/// up (Add to playlist alone leaves: it opens its own sheet).
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
    @Environment(\.openURL) private var openURL
    @AppStorage("keepScreenOn") private var keepScreenOn = false
    /// The content's measured height; until the first layout, `estimatedHeight`.
    /// (A fixed 420 guess presented the sheet at one height and then re-sized
    /// it mid-rise: the opening looked like two moves.)
    @State private var height: CGFloat?
    @State private var tapped = 0
    /// The song's public link (Spotify, Apple Music), read as the sheet opens.
    @State private var link: URL?
    /// Queue buttons just tapped: their icon turns into a check for a moment.
    @State private var doneQuick: Set<String> = []

    private var actions: PlaylistActions { .shared }
    private var store: LibraryStore { model.store }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    private typealias Row = TrackMenuRow
    private typealias Quick = TrackMenuQuick

    private var hasAlbum: Bool { !track.album.id.rawId.hasPrefix("song:") && !track.album.id.rawId.isEmpty }
    private var artist: String { track.track.artist ?? track.album.artist }

    /// The song's title and artist, then its public link on the next line where it has one (Android
    /// detailShareText).
    private var shareText: String {
        WebLinks.shareText(title: [track.track.title, artist].filter { !$0.isEmpty }.joined(separator: " — "), url: link)
    }

    /// The song as the engine's queue entry, for the heart Now Playing uses
    /// (NowPlayingData): the playing one itself in Now Playing context, else
    /// the store's entry; signed out, a local stand-in (nothing to write to).
    private var favoriteTrack: PlayableTrack? {
        if context.kind == .nowPlaying, let t = model.player.currentTrack, t.id == track.track.id.rawId { return t }
        if let t = store.playable(track) { return t }
        guard !store.isReal, let blank = URL(string: "about:blank") else { return nil }
        var t = PlayableTrack(id: track.track.id.rawId, provider: track.track.id.provider, title: track.track.title,
                              artist: artist, album: track.album.title, albumId: nil,
                              durationSec: Double(track.track.durationSec), streamURL: blank, coverURL: nil)
        t.starred = track.track.starred
        return t
    }

    /// Favorite, or Apple Music's add to library (Now Playing's + / ✓ button).
    private var favoriteQuick: Quick? {
        guard let t = favoriteTrack else { return nil }
        let data = NowPlayingData.shared
        let on = data.isFavorite(t)
        let library = t.provider == AppleMusicMapping.provider
        return Quick(id: "favorite",
                     label: library ? (on ? String(localized: "Added") : String(localized: "Add")) : (on ? String(localized: "Favorited") : String(localized: "Favorite")),
                     icon: library ? (on ? "check" : "add") : (on ? "favorite_filled" : "favorite"),
                     checked: on) { [model] in
            data.toggleFavorite(t, client: model.session.client, apple: model.store.apple)
        }
    }

    private func queueQuick(next: Bool) -> Quick {
        let id = next ? "next" : "queue"
        return Quick(id: id, label: next ? String(localized: "Play next") : String(localized: "Add to queue"),
                     icon: doneQuick.contains(id) ? "check" : (next ? "skip_next" : "queue")) { [model, track] in
            model.enqueue([track], next: next) { ok in
                guard ok else { return PlaylistActions.shared.show(String(localized: "Couldn't add to queue")) }
                PlaylistActions.shared.show(next ? String(localized: "1 song will play next") : String(localized: "Added 1 song to the queue"))
                flash(id)
            }
        }
    }

    private var quick: [Quick] {
        var share = Quick(id: "share", label: String(localized: "Share"), icon: "share", share: shareText)
        // A server's song has no public page: Share asks the server for a share link (Android #175).
        if link == nil, SubsonicShareLinks.canShare(.track, track.track.id), let client = store.client {
            let target = DetailLinkTarget(kind: .track, id: track.track.id,
                                          title: [track.track.title, artist].filter { !$0.isEmpty }.joined(separator: " — "))
            share = Quick(id: "share", label: String(localized: "Share"), icon: "share", closes: true) {
                SubsonicShareLinks.shared.share(target, client: client)
            }
        }
        if context.kind == .nowPlaying {
            var row = [favoriteQuick].compactMap { $0 }
            if actions.canAddToPlaylist(store) {
                row.append(Quick(id: "playlist", label: String(localized: "Add to playlist"), icon: "playlist", closes: true) { [track] in
                    PlaylistActions.shared.addAfterDismiss([track])
                })
            }
            return row + [share]
        }
        // Spotify plays its own song: only its queue takes songs, and only at the end (no Play next).
        // A song Apple Music won't stream can't be queued (Android TrackMenu queueAvailable).
        let queue = track.track.notStreamed ? []
            : model.player.externalTrack != nil ? [queueQuick(next: false)]
            : [queueQuick(next: true), queueQuick(next: false)]
        return queue + [favoriteQuick].compactMap { $0 } + [share]
    }

    /// The song's public link: Spotify's is its id, Apple Music's needs the storefront.
    private func loadLink() async {
        var shop: String?
        if track.track.id.provider == AppleMusicMapping.provider { shop = await store.apple?.webStorefront() }
        guard !Task.isCancelled else { return }
        link = WebLinks.url(kind: .track, id: track.track.id, appleStorefront: shop)
    }

    private func flash(_ id: String) {
        withAnimation(Yoin.effects) { _ = doneQuick.insert(id) }
        Task {
            try? await Task.sleep(for: .seconds(1.4))
            withAnimation(Yoin.effects) { _ = doneQuick.remove(id) }
        }
    }

    private var groups: [[Row]] {
        var queue: [Row] = []
        if context.kind != .nowPlaying && actions.canAddToPlaylist(store) {
            queue.append(Row(label: String(localized: "Add to playlist"), icon: "playlist") { [track] in
                PlaylistActions.shared.addAfterDismiss([track])
            })
        }
        // Android #131: the song alone, with the rows under the quick row.
        if store.isReal, store.client != nil, track.track.id.provider == AccountScope.subsonicProvider {
            let offline = OfflineDownloads.shared
            let key = OfflineDownloads.songKey(track.track.id.rawId)
            let status = offline.status(key)
            queue.append(Row(label: OfflineDownloads.rowLabel(status), icon: OfflineDownloads.rowIcon(status)) {
                [model, track] in
                offline.rowTapped(status, key: key) {
                    model.download(.init(kind: .song, id: track.track.id.rawId, title: track.track.title,
                                         subtitle: track.track.artist ?? track.album.artist,
                                         coverId: OfflineDownloads.coverId(track.album.palette.cover)), [track])
                }
            })
        }
        var go: [Row] = []
        if context.kind != .album && hasAlbum {
            go.append(Row(label: String(localized: "Go to album"), icon: "album") { [model, track] in
                Self.leaveOverlays(model)
                model.open(track.album)
            })
        }
        if let view = context.viewInScrapbook {
            go.append(Row(label: String(localized: "View in Scrapbook"), icon: "note", action: view))
        } else if hasAlbum, track.album.id.provider != "mock" {
            // Anywhere else: the album opens at page 2 on the song's piece (Android TrackMenu).
            go.append(Row(label: String(localized: "View in Scrapbook"), icon: "note") { [model, track] in
                ScrapbookFocusRequest.shared.request(songId: track.track.id, openSheet: false)
                Self.leaveOverlays(model)
                model.open(track.album)
            })
        }
        let artistRows = TrackArtistRows.rows(credits: track.track.credits, fallbackArtistId: track.album.artistId)
        if !artistRows.isEmpty, !artist.isEmpty {
            for row in artistRows {
                let label = row.name.map { String(localized: "Go to \($0)") } ?? String(localized: "Go to artist")
                go.append(Row(label: label, icon: "artist") { [model, artist] in
                    Self.leaveOverlays(model)
                    model.openArtist(row.name ?? artist, id: row.artistId)
                })
            }
        } else if track.album.id.provider == "mock" {
            go.append(Row(label: String(localized: "Go to artist"), icon: "artist") { [model, artist] in
                Self.leaveOverlays(model)
                model.openArtist(artist)
            })
        }
        if let link, let label = WebLinks.openInLabel(link) {
            go.append(Row(label: label, icon: "music_note") { [openURL, link] in openURL(link) })
        }
        var edit: [Row] = []
        if context.kind == .playlist, let id = context.playlistId, let index = context.index,
           actions.canEditSongs(model.playlistInfo(id), store: store) {
            edit.append(Row(label: String(localized: "Remove from playlist"), icon: "delete") { [store, track] in
                PlaylistActions.shared.remove(at: index, from: id, store: store, expecting: track)
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
            TrackMenuBody(track: track, scheme: app, quick: quick, groups: groups,
                          onQuick: { q in
                              tapped += 1
                              q.action()
                              if q.closes { dismiss() }
                          },
                          onShare: { tapped += 1 },
                          onRow: { row in
                              tapped += 1
                              row.action()
                              dismiss()
                          }) {
                if context.kind == .nowPlaying { keepScreenOnRow }
            }
            .task(id: track.track.id) { await loadLink() }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { new in
                // Sub-point differences from the estimate don't re-size the sheet.
                if abs(new - (height ?? estimatedHeight)) > 0.5 { height = new }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        // No soft scroll edge: it laid a grey band along the sheet's bottom.
        .scrollEdgeEffectHidden(true, for: .bottom)
        .presentationDetents([.height(min((height ?? estimatedHeight) + 8, 640))])
        .presentationDragIndicator(.visible)
        .sensoryFeedback(.selection, trigger: tapped)
    }

    /// The content's height from the layout's own numbers (padding 24 + 16,
    /// header 56 + 4, groups 12 apart, the top row 76, rows 56 two apart,
    /// Keep screen on 56), so the sheet presents at its final detent.
    private var estimatedHeight: CGFloat {
        var h: CGFloat = 24 + headerHeight + 4 + 12 + TrackMenuBody<EmptyView>.quickHeight
        for rows in groups { h += 12 + CGFloat(rows.count) * 56 + CGFloat(max(rows.count - 1, 0)) * 2 }
        if context.kind == .nowPlaying { h += 12 + 56 }
        return h + 16
    }

    /// The header's height: the 56 cover, or the text beside it where the format tags make it the taller
    /// (title 24, artist 20, then 4 and the tags' 18).
    private var headerHeight: CGFloat {
        var text = Tokens.type.titleMedium.lineHeight.cg
        if !artist.isEmpty { text += Tokens.type.bodyMedium.lineHeight.cg }
        if !track.track.formatTags.isEmpty { text += 4 + FormatTagRow.height }
        return max(56, text)
    }

    /// Now Playing's player section: Keep screen on (the whole row toggles).
    private var keepScreenOnRow: some View {
        TrackMenuToggleRow(label: String(localized: "Keep screen on"), icon: "light_mode", scheme: app,
                           isOn: Binding(get: { keepScreenOn }, set: { on in
                               keepScreenOn = on
                               UIApplication.shared.isIdleTimerDisabled = on && model.npExpanded
                               tapped += 1
                           }))
    }

    /// ButtonGroupDefaults.connected*ButtonShapes: ends full outside, 8 inside
    /// (4 pressed); ToggleButtonDefaults.checkedShape: full all round.
    static func quickShape(_ i: Int, _ count: Int, pressed: Bool, checked: Bool) -> UnevenRoundedRectangle {
        let full = TrackMenuBody<EmptyView>.quickHeight / 2
        let inner: CGFloat = checked ? full : (pressed ? 4 : 8)
        let leading = i == 0 ? full : inner
        let trailing = i == count - 1 ? full : inner
        return UnevenRoundedRectangle(topLeadingRadius: leading, bottomLeadingRadius: leading,
                                      bottomTrailingRadius: trailing, topTrailingRadius: trailing,
                                      style: .continuous)
    }

    /// Settings segments: 20 outer, 4 inner.
    static func segment(_ i: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = i == 0 ? 20 : 4
        let bottom: CGFloat = i == count - 1 ? 20 : 4
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .circular)
    }
}

/// One button of the song sheet's top row (Android TrackMenuQuickAction).
struct TrackMenuQuick: Identifiable {
    var id: String
    var label: String
    var icon: String
    /// Drawn selected (Favorite on): the tonal fill, rounded to a pill.
    var checked = false
    var share: String? = nil
    /// Leaves the sheet (Add to playlist opens a sheet of its own).
    var closes = false
    var action: () -> Void = {}
}

/// One list row of the song sheet (Android TrackMenuRow).
struct TrackMenuRow: Identifiable {
    var id: String { label }
    var label: String
    var icon: String
    var share: String? = nil
    var action: () -> Void = {}
}

/// The song sheet as drawn (Android TrackMenuSheetBody): the song, the top row of one-tap actions, the row
/// groups, then [player] (Now Playing's Keep screen on). TrackMenuSheet fills it from the account in use;
/// the tip page's demo phone fills it with a made-up song (Tips/TipSongSheet.swift).
struct TrackMenuBody<Player: View>: View {
    var track: PlaylistEntry
    var scheme: CoverScheme
    var quick: [TrackMenuQuick]
    var groups: [[TrackMenuRow]]
    var onQuick: (TrackMenuQuick) -> Void
    /// The Share button was tapped (the system's share sheet is its own).
    var onShare: () -> Void = {}
    var onRow: (TrackMenuRow) -> Void
    @ViewBuilder var player: () -> Player

    @Environment(\.colorScheme) private var colorScheme
    /// The top-row button under the finger (it widens, its corners squeeze).
    @State private var pressedQuick: String?

    var body: some View {
        VStack(spacing: 12) {
            header
            quickRow
            ForEach(Array(groups.enumerated()), id: \.offset) { _, rows in
                VStack(spacing: 2) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        rowView(row, shape: TrackMenuSheet.segment(i, rows.count))
                            // For the tip page's demo touch (nothing outside a demo phone).
                            .demoTarget("menuRow.\(row.icon)")
                    }
                }
            }
            player()
        }
        .padding(.horizontal, 16)
        .padding(.top, 24)
        .padding(.bottom, 16)
    }

    /// TrackMenuHeader: 56 Thumb, 16, titleMedium SemiBold over bodyMedium, then the file's format tags.
    private var header: some View {
        let artist = track.track.artist ?? track.album.artist
        let tags = track.track.formatTags
        return HStack(spacing: 16) {
            CoverArt(palette: track.album.palette, cornerRadius: 0, seed: track.album.seed)
                .frame(width: 56, height: 56)
                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(track.track.title).lineLimit(1)
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                if !artist.isEmpty {
                    Text(artist).lineLimit(1)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                // The file's own format (Subsonic); what is playing shows on Now Playing.
                if !tags.isEmpty {
                    FormatTagRow(tags: tags, container: scheme.secondaryContainer, content: scheme.onSecondaryContainer)
                        .padding(.top, 4)
                }
                // The file's own format (Subsonic); what is playing shows on Now Playing.
                if !tags.isEmpty {
                    FormatTagRow(tags: tags, container: app.secondaryContainer, content: app.onSecondaryContainer)
                        .padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    // MARK: Top row

    /// Android TrackMenuQuickRow, an MD3 Expressive connected button group:
    /// 2 apart, outer corners full, inner 8; a press squeezes the inner
    /// corners to 4 and widens the button while its neighbours give way; a
    /// checked button rounds into a pill in primaryContainer.
    private var quickRow: some View {
        let row = quick
        return ConnectedRow(spacing: 2, pressed: row.firstIndex { $0.id == pressedQuick }) {
            ForEach(Array(row.enumerated()), id: \.element.id) { i, q in
                quickButton(q, index: i, count: row.count)
            }
        }
        .frame(height: Self.quickHeight)
        .animation(Yoin.spatialFast, value: pressedQuick)
    }

    static var quickHeight: CGFloat { 76 }

    @ViewBuilder
    private func quickButton(_ q: TrackMenuQuick, index: Int, count: Int) -> some View {
        let pressed = pressedQuick == q.id
        let shape = TrackMenuSheet.quickShape(index, count, pressed: pressed, checked: q.checked)
        let style = QuickPress { down in
            if down { pressedQuick = q.id } else if pressedQuick == q.id { pressedQuick = nil }
        }
        let label = quickContent(q)
            .background(q.checked ? scheme.primaryContainer : rowFill, in: shape)
            .contentShape(shape)
            .animation(Yoin.spatialFast, value: pressed)
            .animation(Yoin.spatialFast, value: q.checked)
        if let text = q.share {
            ShareLink(item: text) { label }
                .buttonStyle(style)
                .simultaneousGesture(TapGesture().onEnded { onShare() })
        } else {
            Button { onQuick(q) } label: { label }
                .buttonStyle(style)
                .accessibilityAddTraits(q.checked ? .isSelected : [])
        }
    }

    private func quickContent(_ q: TrackMenuQuick) -> some View {
        VStack(spacing: 6) {
            YoinIcon(q.icon, size: 24)
                .contentTransition(.symbolEffect(.replace))
            Text(q.label)
                .yoinRole(Tokens.type.labelMedium)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
        }
        .foregroundStyle(q.checked ? scheme.onPrimaryContainer : scheme.onSurface)
        .padding(.horizontal, 4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(Yoin.effects, value: q.checked)
    }

    @ViewBuilder
    private func rowView(_ row: TrackMenuRow, shape: UnevenRoundedRectangle) -> some View {
        if let text = row.share {
            ShareLink(item: text) { rowContent(row) }
                .buttonStyle(.plain)
                .background(rowFill, in: shape)
                .contentShape(shape)
        } else {
            Button { onRow(row) } label: { rowContent(row) }
                .buttonStyle(PressScale())
                .background(rowFill, in: shape)
                .contentShape(shape)
        }
    }

    /// TrackMenuRowContent: 56 tall, 20 in, icon onSurfaceVariant, 16, bodyLarge.
    private func rowContent(_ row: TrackMenuRow) -> some View {
        HStack(spacing: 16) {
            YoinIcon(row.icon, size: 24).foregroundStyle(scheme.onSurfaceVariant)
            Text(row.label)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(scheme.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 56)
        .contentShape(.rect)
    }

    /// surfaceContainerHigh, thinned so the sheet's glass still reads through.
    private var rowFill: Color { Self.rowFill(scheme, dark: colorScheme == .dark) }

    static func rowFill(_ scheme: CoverScheme, dark: Bool) -> Color {
        scheme.surfaceContainerHigh.opacity(dark ? 0.55 : 0.7)
    }
}

/// A switch row of the song sheet (Android TrackMenuToggleRow): Now Playing's Keep screen on.
struct TrackMenuToggleRow: View {
    var label: String
    var icon: String
    var scheme: CoverScheme
    @Binding var isOn: Bool
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 16) {
            YoinIcon(icon, size: 24).foregroundStyle(scheme.onSurfaceVariant)
            Toggle(isOn: $isOn) {
                Text(label)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(scheme.onSurface)
            }
            .tint(scheme.primary)
        }
        .padding(.horizontal, 20)
        .frame(minHeight: 56)
        .background(TrackMenuBody<EmptyView>.rowFill(scheme, dark: colorScheme == .dark), in: TrackMenuSheet.segment(0, 1))
    }
}

/// Reports the press, leaving the look to the label (the shape morph).
private struct QuickPress: ButtonStyle {
    var onPress: (Bool) -> Void

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .onChange(of: configuration.isPressed) { _, down in onPress(down) }
    }
}

/// The top row's layout: equal widths, the pressed one 15 % wider and its
/// neighbours narrower (Android ButtonGroup animateWidth), so a press
/// animates as a spring on the frames.
private struct ConnectedRow: Layout {
    var spacing: CGFloat
    var pressed: Int?
    var grow: CGFloat = 0.15

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        CGSize(width: proposal.width ?? 360, height: proposal.height ?? 76)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard !subviews.isEmpty else { return }
        let weights = subviews.indices.map { $0 == pressed ? 1 + grow : 1 }
        let total = weights.reduce(0, +)
        let room = bounds.width - spacing * CGFloat(subviews.count - 1)
        var x = bounds.minX
        for (i, v) in subviews.enumerated() {
            let w = room * weights[i] / total
            v.place(at: CGPoint(x: x, y: bounds.minY), proposal: ProposedViewSize(width: w, height: bounds.height))
            x += w + spacing
        }
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
                TrackMenuSheet(track: t.entry, context: t.context).environment(model).reportsSheetRise()
            }
            .sheet(item: $actions.adding) { t in
                AddToPlaylistSheet(entries: t.entries).environment(model).reportsSheetRise()
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
                    // Over the phone bar's failure banner, not on it (a held failure shows both at once).
                    let lifted = !model.isPad && model.player.failure != nil
                    ToastPill(toast: toast)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 96 + (lifted ? PlaybackFailureBanner.toastLift : 0))
                        .animation(Yoin.spatial, value: lifted)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .id(toast.id)
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

/// The shell's one-line confirmation on glass; with an action (Undo), its
/// button sits after the text and only it takes touches.
struct ToastPill: View {
    var toast: PlaylistActions.Toast

    var body: some View {
        HStack(spacing: 16) {
            Text(toast.text)
                .yoinRole(Tokens.type.labelLarge)
                .lineLimit(2)
                .allowsHitTesting(false)
            if let action = toast.action {
                Button(action.label) { PlaylistActions.shared.runToastAction() }
                    .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                    .buttonStyle(PressScale())
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .glassEffect(toast.action == nil ? .regular : .regular.interactive(), in: .capsule)
        .allowsHitTesting(toast.action != nil)
    }
}
