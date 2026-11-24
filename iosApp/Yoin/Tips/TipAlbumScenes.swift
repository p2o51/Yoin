import SwiftUI
import YoinShared

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

// MARK: - Album notes

/// Album notes (Android AlbumStickiesDemo): a tap on the scrapbook's bare paper pins a new sticky and opens it
/// as its editor; the words go in and it stays on the page beside the one already there. Built from the
/// scrapbook's own pieces (dot paper, cover, review clipping, ScrapStickyCard / ScrapStickyEditor /
/// DashedSticky, the shared tilts). The demo's editor never takes the focus, so no keyboard comes up over the
/// tip; a sticky the user writes in the phone stays for the visit.
struct AlbumStickiesDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme
    @State private var notes: [AlbumNote] = [AlbumStickiesDemo.note(0, AlbumStickiesDemo.first)]
    @State private var open = false
    @State private var draft = ""

    /// Android's demo words (TipScrapbookScenes.kt), note text rather than UI copy.
    static let review = "Late summer, windows down."
    static let first = "Bought the vinyl at the harbour market"
    static let second = "Side B is for 2 a.m."

    private let album = TipDemo.albums[0]

    private static func note(_ i: Int, _ words: String) -> AlbumNote {
        AlbumNote(id: "tip-an-\(i)", provider: TipDemo.provider, albumId: TipDemo.albums[0].id.rawId, content: words,
                  createdAt: Int64(i + 1), updatedAt: Int64(i + 1), albumName: TipDemo.albums[0].title,
                  artist: TipDemo.albums[0].artist)
    }

    private func tilt(_ key: String) -> Double {
        Double(ScrapbookTilt.shared.tilt(seed: album.id.rawId, key: key, set: ScrapbookTilt.shared.PAPER))
    }

    private func save() {
        let words = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        withAnimation(Yoin.spatial) {
            if !words.isEmpty { notes.append(Self.note(notes.count, words)) }
            open = false
        }
    }

    private func startNew() {
        guard !open else { return }
        draft = ""
        withAnimation(Yoin.spatial) { open = true }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            open = false
            draft = ""
            notes = [Self.note(0, Self.first)]
            try await DemoTiming.delay(900)
            // Bare paper under the page's pieces.
            try await finger.moveTo("scrapPaper", fx: 0.5, fy: 0.88)
            try await finger.tap { startNew() }
            try await DemoTiming.delay(700)
            try await finger.lift()
            for c in Self.second {
                draft.append(c)
                try await DemoTiming.delay(55)
            }
            try await DemoTiming.delay(900)
            save()
            try await DemoTiming.delay(2_400)
        }) {
            GeometryReader { g in
                page(width: g.size.width)
            }
        }
    }

    private func page(width: CGFloat) -> some View {
        let dark = colorScheme == .dark
        let s = CoverScheme(album: album, dark: dark)
        let cover = Tokens.detail.SCRAPBOOK_COVER.cg
        let reviewTilt = tilt("review")
        let stickies = ScrapStickyRules.stickies(notes, reviewTilt: reviewTilt) { tilt($0) }
        return VStack(alignment: .leading, spacing: 4) {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: cover, height: cover)
                .clipShape(.rect(cornerRadius: Tokens.shape.HERO.cg, style: .continuous))
                .rotationEffect(.degrees(Double(ScrapbookTilt.shared.tilt(seed: album.id.rawId, key: "cover",
                                                                           set: ScrapbookTilt.shared.COVER))))
                .padding(.bottom, 12)
            NoteMarkupText(text: Self.review, size: 15, mentionColor: s.onPrimaryContainer, glyphColor: s.primary)
                .foregroundStyle(s.onPrimaryContainer)
                .padding(.horizontal, 16).padding(.vertical, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(s.primaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
                .rotationEffect(.degrees(reviewTilt))
            VStack(alignment: .leading, spacing: 10) {
                ForEach(stickies) { sticky in
                    ScrapStickyCard(text: sticky.text, scheme: s)
                        .rotationEffect(.degrees(sticky.tilt))
                }
                if open {
                    ScrapStickyEditor(text: $draft, scheme: s, autoFocus: false, onSave: save,
                                      onCancel: { withAnimation(Yoin.spatial) { open = false } })
                        .rotationEffect(.degrees(0.6 * 0.4))
                } else {
                    DashedSticky(scheme: s, action: startNew)
                }
            }
            .frame(width: min(0.72 * max(0, width - 32), Tokens.detail.SCRAPBOOK_STICKY_MAX_WIDTH.cg), alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.top, -Tokens.detail.SCRAPBOOK_STICKY_TUCK.cg)
        }
        .padding(.horizontal, 16)
        .padding(.top, DemoPhoneMetrics.statusBar + 16)
        .frame(width: width, alignment: .topLeading)
        .frame(maxHeight: .infinity, alignment: .top)
        .background {
            DotPaper(color: s.outlineVariant.opacity(dark ? 0.35 : 0.45))
                .contentShape(.rect)
                .onTapGesture { startNew() }
        }
        .background(s.surface)
        .demoTarget("scrapPaper")
    }
}
