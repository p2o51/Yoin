import Foundation
import Observation

/// What Gemini said about songs: Android `data/local/SongAboutEntry` (Room
/// `song_about_entries`), kept as one JSON file in Application Support
/// (`Yoin/song_about.json`).
///
/// Rows are keyed like Android's: normalized title + artist + album, not the
/// provider's song id, so the same song from another server (or a row synced
/// from Android) hits the same answers. Canonical rows (`kind == "canonical"`)
/// carry one of `Canon.order`; Ask rows (`kind == "ask"`) carry the
/// normalized question and are shown after them, newest first.
///
/// Sync: `upsert(_:)` takes rows in Android's shape; `all` reads them back.
@MainActor
@Observable
final class SongAboutStore {
    static let shared = SongAboutStore(fileURL: SongAboutStore.defaultURL)

    struct Entry: Codable, Hashable, Sendable {
        var titleKey: String
        var artistKey: String
        var albumKey: String
        var titleDisplay: String
        var artistDisplay: String
        var albumDisplay: String
        /// `Kind.canonical` or `Kind.ask`.
        var kind: String
        /// A `Canon` key, or the normalized question.
        var entryKey: String
        /// Ask rows: the question as typed.
        var promptText: String?
        /// Ask rows: Gemini's short heading.
        var titleText: String?
        var answerText: String
        /// Epoch ms.
        var createdAt: Int64
        var updatedAt: Int64
        /// The "Answer in" language the row was written in (iOS-written
        /// canonical rows; nil on rows from Android, which doesn't record it).
        var language: String?

        struct SongKey: Hashable, Sendable {
            var title: String
            var artist: String
            var album: String
        }

        var songKey: SongKey { SongKey(title: titleKey, artist: artistKey, album: albumKey) }
        var id: String { "\(kind)/\(entryKey)" }
    }

    enum Kind {
        static let canonical = "canonical"
        static let ask = "ask"
    }

    enum Canon {
        static let creationTime = "creation_time"
        static let creationLocation = "creation_location"
        static let lyricist = "lyricist"
        static let composer = "composer"
        static let producer = "producer"
        static let review = "review"
        /// Display order (Android `CANONICAL_ORDER`).
        static let order = [creationTime, creationLocation, lyricist, composer, producer, review]
    }

    private(set) var all: [Entry] = []
    @ObservationIgnored private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            all = saved
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("song_about.json")
    }

    /// Android `SongAboutEntry.normalize`: trimmed, lowercased, "(feat. …)"
    /// dropped, runs of space collapsed.
    nonisolated static func normalize(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: #"\s*\(feat\.[^)]*\)"#, with: "", options: [.regularExpression, .caseInsensitive])
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    nonisolated static func key(title: String, artist: String, album: String) -> Entry.SongKey {
        Entry.SongKey(title: normalize(title), artist: normalize(artist), album: normalize(album))
    }

    /// This song's rows as About shows them: canonical in `Canon.order`, then
    /// ask rows newest first.
    func entries(_ key: Entry.SongKey) -> [Entry] {
        let rows = all.filter { $0.songKey == key }
        let canon = Canon.order.compactMap { k in rows.first { $0.kind == Kind.canonical && $0.entryKey == k } }
        let asks = rows.filter { $0.kind == Kind.ask }.sorted { $0.updatedAt > $1.updatedAt }
        return canon + asks
    }

    /// All six canonical rows are stored (blank answers count: Gemini was asked).
    func hasCanonical(_ key: Entry.SongKey) -> Bool {
        let have = Set(all.lazy.filter { $0.songKey == key && $0.kind == Kind.canonical }.map(\.entryKey))
        return Set(Canon.order).isSubset(of: have)
    }

    /// Inserts or replaces rows by (song, kind, entryKey), Android's primary key.
    func upsert(_ rows: [Entry]) {
        guard !rows.isEmpty else { return }
        var next = all
        for row in rows {
            if let i = next.firstIndex(where: { $0.songKey == row.songKey && $0.kind == row.kind && $0.entryKey == row.entryKey }) {
                next[i] = row
            } else {
                next.append(row)
            }
        }
        all = next
        save()
    }

    /// Gemini's canonical answers for a song as six rows (missing ones blank,
    /// as Android stores them), replacing what was there.
    func saveCanonical(title: String, artist: String, album: String, values: [GeminiClient.Value],
                       language: String, now: Date = Date()) {
        let key = Self.key(title: title, artist: artist, album: album)
        let ms = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let byKey = Dictionary(values.map { ($0.key, $0.answer) }, uniquingKeysWith: { a, _ in a })
        upsert(Canon.order.map { k in
            Entry(titleKey: key.title, artistKey: key.artist, albumKey: key.album, titleDisplay: title,
                  artistDisplay: artist, albumDisplay: album, kind: Kind.canonical, entryKey: k, promptText: nil,
                  titleText: nil, answerText: byKey[k] ?? "", createdAt: ms, updatedAt: ms, language: language)
        })
    }

    /// Android `askAboutSong`'s row: keyed by the normalized question, so
    /// asking it again replaces the answer and keeps the first `createdAt`.
    func saveAsk(title: String, artist: String, album: String, question: String,
                 reply: GeminiClient.AskAnswer, now: Date = Date()) {
        let key = Self.key(title: title, artist: artist, album: album)
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let questionKey = Self.normalize(asked)
        let ms = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let existing = all.first { $0.songKey == key && $0.kind == Kind.ask && $0.entryKey == questionKey }
        upsert([Entry(titleKey: key.title, artistKey: key.artist, albumKey: key.album, titleDisplay: title,
                      artistDisplay: artist, albumDisplay: album, kind: Kind.ask, entryKey: questionKey,
                      promptText: asked, titleText: reply.title, answerText: reply.answer,
                      createdAt: existing?.createdAt ?? ms, updatedAt: ms, language: nil)])
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(all).write(to: fileURL, options: .atomic)
        } catch {
            // Best effort: the answers stay in memory for this run.
        }
    }
}
