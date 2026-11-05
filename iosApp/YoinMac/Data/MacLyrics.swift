import Foundation
import Observation
import YoinShared

/// The current song's lyrics: the server's first (OpenSubsonic structured,
/// then plain `getLyrics`), else the shared automatic chain (QQ → NetEase →
/// LRCLIB, YoinShared `LyricsRegistry`).
@MainActor
@Observable
final class MacLyrics {
    struct Line: Identifiable, Hashable, Sendable {
        var id: Int
        var timeSec: Double
        var text: String
    }

    enum State: Equatable {
        case idle, loading, none
        case loaded(synced: Bool, lines: [Line], source: String)
    }

    private(set) var state: State = .idle
    private(set) var songId: String?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private let registry = LyricsRegistry()
    @ObservationIgnored private var cache: [String: State] = [:]

    /// Another account: its songs share ids with nothing here.
    func reset() {
        task?.cancel()
        cache = [:]
        songId = nil
        state = .idle
    }

    func load(_ track: PlayableTrack?, client: SubsonicClient?) {
        guard track?.id != songId else { return }
        task?.cancel()
        songId = track?.id
        guard let track else { state = .idle; return }
        if let hit = cache[track.id] { state = hit; return }
        state = .loading
        task = Task {
            let result = await Self.fetch(track, client: client, registry: registry)
            guard !Task.isCancelled, self.songId == track.id else { return }
            self.cache[track.id] = result
            self.state = result
        }
    }

    private static func fetch(_ t: PlayableTrack, client: SubsonicClient?, registry: LyricsRegistry) async -> State {
        if let client, let l = try? await client.lyricsFor(songId: t.id, artist: t.artist, title: t.title),
           !l.lines.isEmpty, l.lines.contains(where: { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
            return lines(l, source: "Server")
        }
        let ms = t.durationSec > 0 ? KotlinLong(value: Int64(t.durationSec * 1000)) : nil
        if let r = try? await registry.autoFetch(title: t.title, artist: t.artist, durationMs: ms), !r.lyrics.lines.isEmpty {
            return lines(r.lyrics, source: r.providerName)
        }
        return .none
    }

    private static func lines(_ l: Lyrics, source: String) -> State {
        .loaded(synced: l.synced,
                lines: l.lines.enumerated().map { Line(id: $0, timeSec: Double($1.timeMs) / 1000, text: $1.text) },
                source: source)
    }

    /// The line playing at [sec] (−1 before the first or unsynced).
    static func index(in lines: [Line], at sec: Double) -> Int {
        var lo = 0, hi = lines.count - 1, found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if lines[mid].timeSec <= sec + 0.15 { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return found
    }
}
