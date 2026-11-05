import SwiftUI

/// A long-pressed About row's sheet (Android AboutEntrySheet, owner-approved
/// prototype v3, 2026-11-02): the row's heading and the start of its text,
/// then Manage in Scrapbook (when the song has an album page; the album opens
/// on its scrapbook with this row's editor) and Regenerate with Gemini (not
/// for the listener's own entry; undoable from the toast). Rows in the song
/// menu's style (TrackMenuSheet); present it with `.reportsSheetRise()`.
struct AboutEntrySheet: View {
    struct Target: Identifiable, Equatable {
        var entry: SongAboutStore.Entry
        /// The heading as the row showed it (a canonical row's label is the page's).
        var label: String
        var id: String { entry.id }
    }

    var target: Target
    var scheme: CoverScheme

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var tapped = 0
    /// The content's measured height (until the first layout, `estimatedHeight`).
    @State private var height: CGFloat?

    private struct Row: Identifiable {
        var id: String { label }
        var label: String
        var icon: String
        var action: () -> Void
    }

    /// The song on show as the engine plays it (nil: the mock snapshot).
    private var playing: (key: SongAboutStore.Entry.SongKey, songId: MediaId, album: Album)? {
        let p = model.playback
        let album = p.album
        // A lone song's stand-in has no album page (NowPlayingView.albumRoute).
        guard !album.id.rawId.isEmpty, !album.id.rawId.hasPrefix("song:") else { return nil }
        if let t = model.player.currentTrack {
            return (AboutSong(t).key, MediaId(provider: t.provider, rawId: t.id), album)
        }
        return (SongAboutStore.key(title: p.track.title, artist: p.track.artist ?? album.artist, album: album.title),
                p.track.id, album)
    }

    private var rows: [Row] {
        var out: [Row] = []
        if playing != nil {
            out.append(Row(label: "Manage in Scrapbook", icon: "note") { [model, target] in
                Self.manage(target.entry, model: model)
            })
        }
        if target.entry.kind != SongAboutStore.Kind.own {
            out.append(Row(label: "Regenerate with Gemini", icon: "sparkle") { [target] in
                SongAboutLoader.shared.regenerateAndReport(target.entry.ref)
            })
        }
        return out
    }

    /// Android NowPlayingOverlayHost onManage: only the playing song's own row
    /// (one picked just before the song moved on stays put); Now Playing
    /// collapses and the album opens on page 2 at the row's editor.
    private static func manage(_ entry: SongAboutStore.Entry, model: AppModel) {
        let p = model.playback
        let album = p.album
        let key: SongAboutStore.Entry.SongKey
        let songId: MediaId
        if let t = model.player.currentTrack {
            key = AboutSong(t).key
            songId = MediaId(provider: t.provider, rawId: t.id)
        } else {
            key = SongAboutStore.key(title: p.track.title, artist: p.track.artist ?? album.artist, album: album.title)
            songId = p.track.id
        }
        guard entry.songKey == key else { return }
        ScrapbookFocusRequest.shared.request(songId: songId, edit: entry.ref)
        if model.npExpanded { model.setNowPlaying(expanded: false) }
        if model.npPanel { withAnimation(Yoin.spatial) { model.npPanel = false } }
        // The album already open behind Now Playing takes the request itself.
        if case .album(let open)? = model.path.last, open.id == album.id, !model.isPad { return }
        if model.isPad, model.padSelection?.id == album.id { return }
        model.open(album)
    }

    var body: some View {
        VStack(spacing: 12) {
            header
            if !rows.isEmpty {
                VStack(spacing: 2) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        Button {
                            tapped += 1
                            row.action()
                            dismiss()
                        } label: { rowContent(row) }
                            .buttonStyle(PressScale())
                            .background(rowFill, in: TrackMenuSheet.segment(i, rows.count))
                            .contentShape(TrackMenuSheet.segment(i, rows.count))
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { new in
            if abs(new - (height ?? estimatedHeight)) > 0.5 { height = new }
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .presentationDetents([.height((height ?? estimatedHeight) + 8)])
        .presentationDragIndicator(.visible)
        .sensoryFeedback(.selection, trigger: tapped)
    }

    /// Padding 24 + 16, the header (titleMedium 24 + 2 + two bodyMedium lines
    /// 40 + 4), groups 12 apart, rows 56 two apart.
    private var estimatedHeight: CGFloat {
        let n = CGFloat(rows.count)
        return 24 + 70 + (n > 0 ? 12 + n * 56 + (n - 1) * 2 : 0) + 16
    }

    /// Android: label in titleMedium primary (one line), the text in
    /// bodyMedium onSurfaceVariant (two lines), 8 in from the rows.
    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(target.label)
                .yoinRole(Tokens.type.titleMedium)
                .foregroundStyle(scheme.primary)
                .lineLimit(1)
            Text(NowPlayingAboutView.markdown(target.entry.answerText))
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    /// TrackMenuRowContent: 56 tall, 20 in, icon onSurfaceVariant, 16, bodyLarge.
    private func rowContent(_ row: Row) -> some View {
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

    /// The song menu's row fill: surfaceContainerHigh, thinned so the sheet's glass reads through.
    private var rowFill: Color { scheme.surfaceContainerHigh.opacity(colorScheme == .dark ? 0.55 : 0.7) }
}

/// Android ThinkingDots: three 6 pt dots, 5 apart, breathing one after
/// another while Gemini writes (still at 60 % with Reduce Motion).
struct ThinkingDots: View {
    var color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathing = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .fill(color)
                    .frame(width: 6, height: 6)
                    .opacity(reduceMotion ? 0.6 : (breathing ? 0.25 : 1))
                    .animation(reduceMotion ? nil : Yoin.effects.repeatForever(autoreverses: true)
                        .delay(Double(i) * 0.15), value: breathing)
            }
        }
        .onAppear { breathing = true }
        .accessibilityElement()
        .accessibilityLabel("Gemini is thinking")
    }
}
