import SwiftUI

/// Now Playing's Queue pill, laid out as Android's `component/QueueSheet.kt`:
/// "Playing from" over the source, then Now playing, Next in queue (songs the
/// user queued, with Clear) and Next from: the source. Tap a row to play it,
/// long-press and drag to reorder within its part, swipe to remove (the
/// current song stays, Android rule).
///
/// iOS: the sheet keeps the system's iOS 26 glass (no presentation
/// background of our own), the same material as the Devices (AirPlay) sheet.
/// Text is the cover scheme's on-surface roles, which read over it in both modes.
struct QueueSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    private var engine: PlayerEngine { model.player }
    private var scheme: CoverScheme { CoverScheme(album: model.playback.album, dark: colorScheme == .dark) }

    /// Android QueueRowHeight.
    private static let rowHeight: CGFloat = 64

    var body: some View {
        let start = (engine.currentIndex ?? -1) + 1
        let upcoming = Array(engine.upcoming.enumerated()).map { (index: start + $0.offset, item: $0.element) }
        let queued = upcoming.filter { $0.item.userQueued }
        let next = upcoming.filter { !$0.item.userQueued }
        List {
            header
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            if let current = engine.currentItem {
                sectionLabel("Now playing")
                row(current, index: engine.currentIndex ?? 0, current: true)
            }
            if !queued.isEmpty {
                sectionLabel("Next in queue") {
                    withAnimation(Yoin.spatial) { for q in queued { engine.remove(id: q.item.id) } }
                }
                part(queued)
            }
            if !next.isEmpty {
                sectionLabel(sourceTitle.map { "Next from: \($0)" } ?? "Next up")
                part(next)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .contentMargins(.bottom, 16, for: .scrollContent)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationContentInteraction(.scrolls)
    }

    /// The album the queue plays from, when it is one album (iOS has no
    /// playlist context yet: a mixed queue reads "Queue" / "Next up").
    private var sourceTitle: String? {
        guard let t = engine.currentTrack else { return model.playback.album.title }
        let albums = Set(engine.queue.map { $0.track.albumId ?? $0.track.album })
        return albums.count == 1 && !t.album.isEmpty ? t.album : nil
    }

    /// Android QueueHeader: labelMedium "Playing from" over the source in titleLarge.
    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let source = sourceTitle {
                Text("Playing from")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                Text(source)
                    .yoinRole(Tokens.type.titleLarge)
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
            } else {
                Text("Queue")
                    .yoinRole(Tokens.type.titleLarge)
                    .foregroundStyle(scheme.onSurface)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 4)
    }

    /// Android QueueSectionLabel: titleSmall SemiBold, 18 above, Clear at the end.
    private func sectionLabel(_ text: String, clear: (() -> Void)? = nil) -> some View {
        HStack(spacing: 0) {
            Text(text)
                .yoinRole(Tokens.type.titleSmall, weight: .semibold)
                .foregroundStyle(scheme.onSurface)
                .lineLimit(1)
            Spacer(minLength: 8)
            if let clear {
                Button(action: clear) {
                    Text("Clear")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.primary)
                        .frame(minWidth: 44, minHeight: 36)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, 24)
        .padding(.trailing, 12)
        .padding(.top, 18)
        .padding(.bottom, 2)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .moveDisabled(true)
        .deleteDisabled(true)
    }

    /// One part of the upcoming songs: reorders within itself, swipes away.
    private func part(_ rows: [(index: Int, item: QueueItem)]) -> some View {
        ForEach(rows, id: \.item.id) { entry in
            row(entry.item, index: entry.index, current: false)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        withAnimation(Yoin.spatial) { engine.remove(id: entry.item.id) }
                    } label: {
                        Label { Text("Remove") } icon: { YoinIcon("close", size: 20) }
                    }
                }
        }
        .onMove { offsets, destination in
            // The part's songs are one run of the queue: map its offsets onto it.
            let indices = rows.map(\.index)
            guard let first = indices.first, let last = indices.last else { return }
            let target = destination < indices.count ? indices[destination] : last + 1
            engine.move(fromOffsets: IndexSet(offsets.map { indices[$0] }), toOffset: max(first, target))
        }
    }

    /// Android QueueTrackRow: 48 cover, 14 apart, title (bodyLarge Medium,
    /// SemiBold primary when current) over the artist (bodyMedium), the
    /// equalizer at the end of the current song.
    private func row(_ item: QueueItem, index: Int, current: Bool) -> some View {
        let album = AppModel.album(for: item.track, library: model.library)
        return Button {
            if !current { engine.jump(to: index) }
        } label: {
            HStack(spacing: 14) {
                CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                    .frame(width: 48, height: 48)
                    .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    Text(item.track.title)
                        .yoinRole(Tokens.type.bodyLarge, weight: current ? .semibold : .medium)
                        .foregroundStyle(current ? scheme.primary : scheme.onSurface)
                    Text(item.track.artist)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
                if current {
                    YoinIcon("equalizer", size: 22)
                        .foregroundStyle(scheme.primary)
                }
            }
            .padding(.horizontal, 24)
            .frame(height: Self.rowHeight)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets())
        .moveDisabled(current)
        .accessibilityHint(current ? (engine.isPlaying ? "Playing" : "Paused") : "Plays this song")
    }
}
