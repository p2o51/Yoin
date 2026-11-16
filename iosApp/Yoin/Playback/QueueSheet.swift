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

    @Environment(\.scenePhase) private var scenePhase

    /// Spotify's own queue while it plays a song of its own (picked in the Spotify app, here or on a
    /// speaker): App Remote never reports it, so it is read through the Web API, as Android does
    /// (SpotifyMusicSource.playerQueue), when the sheet opens, when Spotify moves to another song, after
    /// an Add to queue and when Yoin comes back to the front. No timed re-read: it would spend the
    /// account's Web API allowance while the sheet sits open (audit 11-15 #37).
    @State private var spotifyQueue: SpotifyQueueRead = .loading
    /// The song [spotifyQueue] was read under: another song starts the list over.
    @State private var spotifyQueueSong: String?
    @State private var spotifyQueueRetry = 0
    /// Bumps when Yoin comes back to the front (a change made in the Spotify app shows then).
    @State private var spotifyQueueForeground = 0

    /// Spotify plays a song that isn't the queue's: the sheet shows Spotify's queue, not Yoin's parked one.
    private var spotifysOwn: PlayableTrack? {
        guard let t = engine.externalTrack, t.provider == SpotifyMapping.provider, model.store.spotify != nil else { return nil }
        return t
    }

    var body: some View {
        if let own = spotifysOwn {
            spotifyList(own)
        } else {
            queueList
        }
    }

    private var queueList: some View {
        let start = (engine.currentIndex ?? -1) + 1
        let upcoming = Array(engine.upcoming.enumerated()).map { (index: start + $0.offset, item: $0.element) }
        let queued = upcoming.filter { $0.item.userQueued }
        let next = upcoming.filter { !$0.item.userQueued }
        return List {
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
                    // Android QueueSheet: Clear confirms.
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
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
        // Songs, albums or playlists dropped on the sheet go after what's queued (the native Mac's rule);
        // on a row, right before it.
        .dropDestination(for: YoinDragItem.self) { items, _ in
            YoinDrag.queue(items, model: model)
            return true
        }
        .modifier(SheetChrome())
    }

    /// The sheet around either list.
    private struct SheetChrome: ViewModifier {
        func body(content: Content) -> some View {
            content
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                // The soft scroll edge drew a grey band along the sheet's bottom edge
                // over the glass; the sheet's own rounded edge already ends the list.
                .scrollEdgeEffectHidden(true, for: .bottom)
                .contentMargins(.bottom, 16, for: .scrollContent)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                .presentationContentInteraction(.scrolls)
        }
    }

    /// Spotify's song, Yoin's parked queue under it when there is one (tap a row to go back to it, audit
    /// 11-15 #13; Android keeps its queue and slots the foreign song in), then what Spotify plays after
    /// it, read-only: Spotify's queue can't be reordered or trimmed from outside, and its rows don't jump
    /// (#37: until a tap can start there without the Web API on this iPhone, they don't look tappable).
    private func spotifyList(_ own: PlayableTrack) -> some View {
        List {
            header
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            sectionLabel(String(localized: "Now playing"))
            spotifyRow(title: own.title, artist: own.artist, cover: own.coverURL, current: true)
            if let parked = engine.currentItem {
                // The parked song and what follows it: a tap plays the queue again from there.
                let start = (engine.currentIndex ?? -1) + 1
                let upcoming = Array(engine.upcoming.enumerated()).map { (index: start + $0.offset, item: $0.element) }
                sectionLabel(String(localized: "Your queue"))
                row(parked, index: engine.currentIndex ?? 0, current: false)
                    .moveDisabled(true)
                part(upcoming)
            }
            switch spotifyQueue {
            case .loading:
                EmptyView()
            case .loaded(let entries) where !entries.isEmpty:
                sectionLabel(String(localized: "Next in Spotify"))
                // A song can be queued twice: a row is its place and its song.
                ForEach(entries, id: \.rowKey) { e in
                    spotifyRow(title: e.title, artist: e.artist, cover: e.coverURL, current: false)
                }
            case .loaded:
                emptyLabel(String(localized: "Nothing up next"))
            case .failed:
                failedLabel
            }
        }
        .modifier(SheetChrome())
        // Read again when Spotify moves to another song, after an Add to queue, on Retry, and every few
        // seconds while the sheet is open.
        .task(id: SpotifyQueueKey(song: own.id, revision: model.spotifyQueueRevision, retry: spotifyQueueRetry,
                                  foreground: spotifyQueueForeground)) {
            if spotifyQueueSong != own.id {
                spotifyQueueSong = own.id
                spotifyQueue = .loading
            }
            await readSpotifyQueue()
        }
        .task(id: own.id) { SpotifyPlayingFrom.shared.observe(own, model: model) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { spotifyQueueForeground += 1 }
        }
    }

    private func readSpotifyQueue() async {
        guard let source = model.store.spotify else { return }
        do {
            let entries = try await source.playerQueue()
            guard !Task.isCancelled else { return }
            spotifyQueue = .loaded(entries)
        } catch {
            PlayTrace.log("SP queue read failed \(SpotifySource.message(for: error))")
            guard !Task.isCancelled else { return }
            spotifyQueue = spotifyQueue.afterFailedRead
        }
    }

    /// The read failed with nothing shown yet: said so, with Retry (not "Nothing up next").
    private var failedLabel: some View {
        HStack(spacing: 8) {
            Text("Couldn't load Spotify's queue")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .lineLimit(2)
            Spacer(minLength: 8)
            Button {
                spotifyQueue = .loading
                spotifyQueueRetry += 1
            } label: {
                Text("Retry")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.primary)
                    .frame(minWidth: 44, minHeight: 36)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain).pointerHighlight()
        }
        .padding(.leading, 24)
        .padding(.trailing, 12)
        .padding(.top, 12)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    private func spotifyRow(title: String, artist: String, cover: URL?, current: Bool) -> some View {
        HStack(spacing: 14) {
            Group {
                if let cover {
                    RemoteCover(ref: CoverRef(url: cover), placeholder: scheme.surfaceContainerHigh)
                } else {
                    Rectangle().fill(scheme.surfaceContainerHigh)
                }
            }
            .frame(width: 48, height: 48)
            .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .yoinRole(Tokens.type.bodyLarge, weight: current ? .semibold : .medium)
                    .foregroundStyle(current ? scheme.primary : scheme.onSurface)
                Text(artist)
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
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets())
        .accessibilityElement(children: .combine)
    }

    private func emptyLabel(_ text: String) -> some View {
        Text(text)
            .yoinRole(Tokens.type.bodyMedium)
            .foregroundStyle(scheme.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 18)
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    /// Where the queue plays from (Android queuePlayingFrom): the album,
    /// playlist or artist page it was started on. Started from a list (Home,
    /// Library, Search): "Queue" / "Next up". The mock: its album.
    private var sourceTitle: String? {
        guard engine.currentTrack != nil else { return model.playback.album.title }
        // Spotify's own song: where Spotify plays it from (audit 11-15 #35), not the parked queue's page.
        if let own = spotifysOwn { return SpotifyPlayingFrom.shared.label(for: own)?.name }
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

/// What the queue sheet holds of Spotify's own queue.
enum SpotifyQueueRead: Equatable {
    case loading
    case loaded([SpotifySource.QueueEntry])
    case failed

    /// A read failed: a list already shown stays (a later read may answer); nothing shown is a failure.
    var afterFailedRead: SpotifyQueueRead {
        if case .loaded = self { return self }
        return .failed
    }
}

/// When the sheet reads Spotify's queue again.
private struct SpotifyQueueKey: Equatable {
    var song: String
    var revision: Int
    var retry: Int
    var foreground: Int
}

extension SpotifySource.QueueEntry {
    /// Its place and its song: the same song queued twice is two rows, and a list that moved on doesn't
    /// keep a row for a song that left it.
    var rowKey: String { "\(id):\(uri)" }
}
