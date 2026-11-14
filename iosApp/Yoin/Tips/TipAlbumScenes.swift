import SwiftUI

/*
 * An album page on the demo phone (Android ui/tips/TipLibraryScenes.kt `DemoAlbumPage`): the app's own page
 * (`AlbumDetailView(album:demo:)`) over a made-up album, its track list pulled up, under the detail bar's
 * Play split. A song's hold raises the song sheet (TipSongSheet.swift).
 */

/// The album page with its track list up, and the bar's Play split over its foot.
struct DemoAlbumPage: View {
    var album: Album
    var onLongPress: (Int) -> Void

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AlbumDetailView(album: album, demo: AlbumPageDemo(notedTrack: TipDemo.notedTrack, onLongPress: onLongPress))
                .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
            DemoPlaySplit(album: album)
                .padding(.leading, 14)
                .padding(.bottom, DemoHomeMetrics.barBottom)
        }
    }
}

/// The detail bar's Play split (ShellBar.playSplit: 156 wide, 2 apart, inner corners 4, the cover's primary in
/// both halves' glass), as it floats over an album page with nothing playing. Looked at, not pressed.
struct DemoPlaySplit: View {
    var album: Album
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let s = CoverScheme(album: album, dark: colorScheme == .dark)
        let tint = s.primary.opacity(0.85)
        let inner = Tokens.bar.SPLIT_INNER_CORNER.cg
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: Tokens.bar.SPLIT_GAP.cg) {
                HStack(spacing: 8) {
                    YoinIcon("play_filled", size: 20)
                    Text("Play").font(.gsf(16, .medium))
                }
                .foregroundStyle(s.onPrimary)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity)
                .frame(height: 60)
                .yGlassEffect(.regular.tint(tint), solid: tint,
                             in: UnevenRoundedRectangle(topLeadingRadius: 30, bottomLeadingRadius: 30,
                                                        bottomTrailingRadius: inner, topTrailingRadius: inner))
                YoinIcon("chevron_down", size: 22)
                    .foregroundStyle(s.onPrimary)
                    .padding(.horizontal, 14)
                    .frame(height: 60)
                    .yGlassEffect(.regular.tint(tint), solid: tint,
                                 in: UnevenRoundedRectangle(topLeadingRadius: inner, bottomLeadingRadius: inner,
                                                            bottomTrailingRadius: 30, topTrailingRadius: 30))
            }
        }
        .frame(width: Tokens.bar.SPLIT_WIDTH.cg, height: 60)
        .allowsHitTesting(false)
    }
}

// MARK: - Song quick actions

/// Long-press a song on an album: its sheet (Play next, Add to queue, Favorite, Share on top). A tap above
/// the sheet sends it down.
struct SongMenuDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var open = false
    @State private var row = SongMenuDemo.heldRow

    /// The row the demo holds.
    static let heldRow = 2
    private let album = TipDemo.albumPage

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            open = false
            try await DemoTiming.delay(300)
            try await finger.moveTo("row\(Self.heldRow)", fx: 0.45)
            try await finger.hold {
                row = Self.heldRow
                open = true
            }
            try await finger.lift()
            try await DemoTiming.delay(1_800)
            // On the page above the sheet.
            try await finger.moveTo("row0", fx: 0.5)
            try await finger.tap { open = false }
            try await DemoTiming.delay(500)
        }) {
            DemoSheetStage(open: open, onDismiss: { open = false }) {
                DemoAlbumPage(album: album) { i in
                    row = i
                    open = true
                }
            } sheet: {
                DemoSongSheet(track: PlaylistEntry(album: album, track: album.tracks[min(row, album.tracks.count - 1)]),
                              scheme: scheme) { open = false }
            }
        }
    }
}

// MARK: - Offline downloads

/// A Subsonic song's Download in its sheet: long-press a song, tap Download, and the next time the sheet
/// says Remove download (the sheet's own row and words, OfflineDownloads.rowLabel).
struct DownloadsDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var open = false
    @State private var downloaded = false
    @State private var row = SongMenuDemo.heldRow

    private let album = TipDemo.albumPage
    private var status: OfflineDownloads.Status { downloaded ? .downloaded : .none }

    /// The sheet is up and laid out before the finger looks for a row in it.
    private func sheetUp() async throws { try await DemoTiming.delay(DemoTiming.springMs) }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            open = false
            downloaded = false
            try await DemoTiming.delay(300)
            try await finger.moveTo("row\(SongMenuDemo.heldRow)", fx: 0.45)
            try await finger.hold {
                row = SongMenuDemo.heldRow
                open = true
            }
            try await sheetUp()
            try await finger.moveTo(DemoSongSheetRows.offlineTarget(.none), fx: 0.4)
            try await DemoTiming.delay(250)
            try await finger.tap {}
            // The row answers first; the sheet goes down once the dot has left it.
            withAnimation(Yoin.effects) { downloaded = true }
            try await finger.lift()
            open = false
            try await DemoTiming.delay(900)
            try await finger.moveTo("row\(SongMenuDemo.heldRow)", fx: 0.45)
            try await finger.hold { open = true }
            try await sheetUp()
            try await finger.moveTo(DemoSongSheetRows.offlineTarget(.downloaded), fx: 0.4)
            try await DemoTiming.delay(300)
            try await finger.lift()
            try await DemoTiming.delay(1_800)
            // Close on the page above the sheet, not on the sheet's song.
            try await finger.moveTo(x: 0.5, y: 0.12)
            try await finger.tap { open = false }
            finger.hide()
            try await DemoTiming.delay(500)
        }) {
            DemoSheetStage(open: open, onDismiss: { open = false }) {
                DemoAlbumPage(album: album) { i in
                    row = i
                    open = true
                }
            } sheet: {
                DemoSongSheet(track: PlaylistEntry(album: album, track: album.tracks[min(row, album.tracks.count - 1)]),
                              scheme: scheme, offline: status,
                              onOffline: { withAnimation(Yoin.effects) { downloaded.toggle() } }) { open = false }
            }
        }
    }
}
