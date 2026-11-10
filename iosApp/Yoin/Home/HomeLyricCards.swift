import SwiftUI
import YoinShared

/*
 * Home's Lyric Cards (Android ui/home/HomeLyricHighlight.kt, #180 #189): passages the on-device model picked
 * from the listener's songs, in a pager that keeps them all composed. A tap plays the song from that line.
 * The first time the section can show, an intro card offers the beta (owner 2026-11-08: the model runs only
 * after the opt-in); the listener's own shared or saved lines show whether or not it's on. No title on the
 * feed (#189).
 */
struct HomeLyricCardsSection: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.homeMargin) private var margin
    var cards: [LyricHighlightRow]
    private let store = LyricHighlightStore.shared
    private let processor = LyricHighlightProcessor.shared

    var body: some View {
        let s = scheme ?? .brand(dark: false)
        VStack(alignment: .leading, spacing: 12) {
            if !store.introSeen { intro(s) }
            if !cards.isEmpty {
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 12) {
                        ForEach(cards) { card($0, s).containerRelativeFrame(.horizontal) { w, _ in min(w * 0.86, 420) } }
                    }
                    .scrollTargetLayout()
                    .padding(.horizontal, margin)
                }
                .scrollTargetBehavior(.viewAligned)
                .scrollIndicators(.hidden)
                .padding(.horizontal, -margin)
                .shelfEdgeFade()
            }
        }
        .onAppear {
            let now = LyricHighlightProcessor.nowMs
            if let first = cards.first { store.markShown(first.id, at: now) }
        }
    }

    private func card(_ c: LyricHighlightRow, _ s: CoverScheme) -> some View {
        Button { homeTap(edit) { model.playFromLine(c) } } label: {
            VStack(alignment: .leading, spacing: 14) {
                Text(c.text ?? "")
                    .homeType(Tokens.type.titleMedium, .medium, size: 19, lineHeight: 27)
                    .foregroundStyle(s.onSurface)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                HStack(spacing: 10) {
                    CoverArt(palette: palette(c), cornerRadius: 4).frame(width: 32, height: 32)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(c.title).homeType(Tokens.type.labelLarge, .semibold).foregroundStyle(s.onSurface).lineLimit(1)
                        Text(c.artist).homeType(Tokens.type.labelMedium).foregroundStyle(s.onSurfaceVariant).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if c.isUserPick {
                        Text("Your pick").homeType(Tokens.type.labelSmall).foregroundStyle(s.onSecondaryContainer)
                            .padding(.horizontal, 8).frame(height: 22)
                            .background(s.secondaryContainer, in: .capsule)
                    }
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, minHeight: 170, alignment: .topLeading)
            .background(s.surfaceContainerLow.mix(with: BackdropColors(palette(c)).base, by: 0.14, in: .perceptual),
                        in: .rect(cornerRadius: 28))
            .contentShape(.rect(cornerRadius: 28))
        }
        .buttonStyle(PressScale())
        .accessibilityHint(Text("Play from this line"))
    }

    private func palette(_ c: LyricHighlightRow) -> CoverPalette {
        if c.provider == AccountScope.subsonicProvider, let client = model.store.client {
            return SubsonicMapping(client: client).palette(coverId: c.coverKey ?? c.albumId)
        }
        return AppleMusicMapping.palette(c.coverKey.flatMap(URL.init(string:)), key: c.id)
    }

    // MARK: Intro

    /// The beta's first-use offer: compact, phone-wide on a tablet, the model's state folded into one line.
    private func intro(_ s: CoverScheme) -> some View {
        let status = processor.status ?? processor.checkNow()
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text("Beta").homeType(Tokens.type.labelSmall, .semibold).foregroundStyle(s.onPrimaryContainer)
                    .padding(.horizontal, 7).frame(height: 18).background(s.primaryContainer, in: .capsule)
                Text("You found a beta feature").homeType(Tokens.type.titleSmall, .semibold).foregroundStyle(s.onSurface)
            }
            Text(LabsCopy.introBody + " " + LabsCopy.introStatus(status))
                .homeType(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                Spacer()
                if status == .unsupported {
                    Button("Got it") { withAnimation(Yoin.spatial) { store.introSeen = true } }
                        .buttonStyle(.glass).controlSize(.small)
                } else {
                    Button("Not now") { withAnimation(Yoin.spatial) { store.introSeen = true } }
                        .buttonStyle(.glass).controlSize(.small)
                    Button("Turn on") { withAnimation(Yoin.spatial) { processor.setOptedIn(true) } }
                        .buttonStyle(.glassProminent).controlSize(.small)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: 560, alignment: .leading)
        .background(s.surfaceContainerLow, in: .rect(cornerRadius: 24))
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }
}

extension AppModel {
    /// Plays a card's song from its line (Android "Play from this line").
    func playFromLine(_ c: LyricHighlightRow) {
        let palette: CoverPalette = store.client.map { SubsonicMapping(client: $0).palette(coverId: c.coverKey ?? c.albumId) }
            ?? AppleMusicMapping.palette(c.coverKey.flatMap(URL.init(string:)), key: c.id)
        let album = store.albumDetails[c.albumId]
            ?? Album(id: MediaId(provider: c.provider, rawId: c.albumId.isEmpty ? "song:\(c.songId)" : c.albumId),
                     title: c.album, artist: c.artist, year: 0, palette: palette, tracks: [])
        let track = album.tracks.first { $0.id.rawId == c.songId }
            ?? Track(id: MediaId(provider: c.provider, rawId: c.songId), number: 1, title: c.title,
                     durationSec: Int(c.durationMs / 1000), rating: nil)
        play([PlaylistEntry(album: album, track: track)])
        guard let ms = c.startMs, ms > 0 else { return }
        let player = player
        Task { @MainActor in
            // The song loads first; then the seek lands on the line (a little before it, so it's heard whole).
            for _ in 0..<40 {
                try? await Task.sleep(for: .milliseconds(150))
                if player.currentTrack?.id == c.songId, !player.isBuffering { break }
            }
            guard player.currentTrack?.id == c.songId else { return }
            player.seek(to: max(0, Double(ms) / 1000 - 0.3))
        }
    }
}
