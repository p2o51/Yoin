import SwiftUI

/// Now Playing's Queue pill, laid out as Android's `component/QueueSheet.kt`:
/// "Playing from" over the source, then Now playing, Next in queue (songs the
/// user queued, with Clear) and Next from: the source. Tap a row to play it,
/// long-press and drag to reorder within its part, swipe to remove (the
/// current song stays, Android rule). Songs, albums and playlists dragged in
/// from elsewhere (YoinDrag) join the queue, before the row they land on.
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
                sectionLabel(String(localized: "Now playing"))
                row(current, index: engine.currentIndex ?? 0, current: true)
            }
            if !queued.isEmpty {
                sectionLabel(String(localized: "Next in queue")) {
                    withAnimation(Yoin.spatial) { for q in queued { engine.remove(id: q.item.id) } }
                }
                part(queued)
            }
            if !next.isEmpty {
                sectionLabel(sourceTitle.map { String(localized: "Next from: \($0)") } ?? String(localized: "Next up"))
                part(next)
            }
            if upcoming.isEmpty {
                // Empty: a label, not a blank sheet under the header.
                Text(engine.currentItem == nil ? String(localized: "Queue is empty") : String(localized: "Nothing up next"))
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 24)
                    .padding(.top, 18)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        // Songs, albums or playlists dropped on the sheet go after what's queued (the native Mac's rule);
        // on a row, right before it.
        .dropDestination(for: YoinDragItem.self) { items, _ in
            YoinDrag.queue(items, model: model)
            return true
        }
        // The soft scroll edge drew a grey band along the sheet's bottom edge
        // over the glass; the sheet's own rounded edge already ends the list.
        .scrollEdgeEffectHidden(true, for: .bottom)
        .contentMargins(.bottom, 16, for: .scrollContent)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationContentInteraction(.scrolls)
    }

    /// Where the queue plays from (Android queuePlayingFrom): the album,
    /// playlist or artist page it was started on. Started from a list (Home,
    /// Library, Search): "Queue" / "Next up". The mock: its album.
    private var sourceTitle: String? {
        guard engine.currentTrack != nil else { return model.playback.album.title }
        guard let from = model.playingFrom, !from.title.isEmpty else { return nil }
        return from.title
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
                .buttonStyle(.plain).pointerHighlight()
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
            // Android: select, then the sheet goes away.
            guard !current else { return }
            engine.jump(to: index)
            model.queueSheetOpen = false
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
        .buttonStyle(.plain).pointerHighlight()
        .yoinDropTarget(in: Rectangle(), ink: scheme.primary) { YoinDrag.queue($0, before: current ? nil : item.id, model: model) }
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets())
        .moveDisabled(current)
        .accessibilityHint(current ? (engine.isPlaying ? String(localized: "Playing") : String(localized: "Paused")) : String(localized: "Plays this song"))
    }
}
