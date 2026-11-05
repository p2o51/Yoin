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
/// normalized question; Own rows (`kind == "own"`) are the listener's own
/// entries, written in the album's scrapbook (heading in `titleText`, key the
/// normalized heading). Ask and Own rows follow the canonical ones, newest
/// first by when they were first written, so an edit leaves a row in place.
///
/// A blank answer is a removed row (Android `editAboutEntry`): About deletes
/// never sync, a blank answer does, and every reader hides it.
///
/// Sync: `upsert(_:)` takes rows in Android's shape; `all` reads them back.
@MainActor
@Observable
final class SongAboutStore {
    static let shared = SongAboutStore(fileURL: SongAboutStore.launchURL)

    struct Entry: Codable, Hashable, Sendable {
        var titleKey: String
        var artistKey: String
        var albumKey: String
        var titleDisplay: String
        var artistDisplay: String
        var albumDisplay: String
        /// `Kind.canonical`, `Kind.ask` or `Kind.own`.
        var kind: String
        /// A `Canon` key, the normalized question, or an own entry's normalized heading.
        var entryKey: String
        /// Ask rows: the question as typed.
        var promptText: String?
        /// Ask rows: Gemini's short heading. Own rows: the listener's heading.
        var titleText: String?
        var answerText: String
        /// Epoch ms.
        var createdAt: Int64
        var updatedAt: Int64
        /// The "Answer in" language the row was written in. Every row written
        /// now records it (generated ones with the request's language, synced
        /// ones with the record's); nil only on rows from before, which are not
        /// shown or synced since their language is unknown (Codex C17). Own
        /// rows are the listener's words in no Gemini language: `anyLanguage`.
        var language: String?

        struct SongKey: Hashable, Sendable {
            var title: String
            var artist: String
            var album: String
        }

        /// One row's primary key (Android `AlbumScrapbookAboutRef`).
        struct Ref: Hashable, Sendable {
            var song: SongKey
            var kind: String
            var entryKey: String
        }

        var songKey: SongKey { SongKey(title: titleKey, artist: artistKey, album: albumKey) }
        var ref: Ref { Ref(song: songKey, kind: kind, entryKey: entryKey) }
        var id: String { "\(kind)/\(entryKey)" }
        /// Removed in the scrapbook (or never answered).
        var isBlank: Bool { answerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        /// The heading About shows: a canonical row's label is the reader's; an
        /// ask row's is Gemini's title (the question before titles existed);
        /// an own row's is the listener's.
        var heading: String? {
            guard kind != Kind.canonical else { return nil }
            return titleText.flatMap { $0.isEmpty ? nil : $0 } ?? promptText
        }
    }

    enum Kind {
        static let canonical = "canonical"
        static let ask = "ask"
        static let own = "own"
    }

    /// The language an own row is filed under (Android SongAboutSyncAdapter
    /// `ANY_LANGUAGE`): every language shows it, and a language switch keeps it.
    static let anyLanguage = "*"

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
    /// The language rows are shown in (`GeminiSettings.targetLanguage`); a loader with its own settings
    /// points it there.
    @ObservationIgnored var language: @MainActor () -> String = {
        GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
    }

    /// An answer Gemini just gave on this device (not a row from sync): Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            all = saved
        }
    }

    /// DEBUG `-aboutDemo`: a scratch file, so demo rows never reach the real cache.
    private static var launchURL: URL {
        #if DEBUG
        if SongAboutDemo.mode != nil {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("song_about_demo.json")
            try? FileManager.default.removeItem(at: url)
            return url
        }
        #endif
        return defaultURL
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

    /// Shown in this language: its own rows, and the listener's whatever the language.
    private static func shown(_ e: Entry, _ language: String) -> Bool {
        e.kind == Kind.own || e.language == language
    }

    /// This song's rows as About shows them: canonical in `Canon.order`, then
    /// ask and own rows newest first by `createdAt` (Android `observeAbout`:
    /// an edit or a regenerate leaves a row where it was). Removed (blank)
    /// ask and own rows are left out; blank canonical rows stay (Gemini was asked).
    func entries(_ key: Entry.SongKey) -> [Entry] {
        let language = language()
        let rows = all.filter { $0.songKey == key && Self.shown($0, language) }
        let canon = Canon.order.compactMap { k in rows.first { $0.kind == Kind.canonical && $0.entryKey == k } }
        let rest = rows.filter { $0.kind != Kind.canonical && !$0.isBlank }
            .sorted { ($0.createdAt, $0.updatedAt) > ($1.createdAt, $1.updatedAt) }
        return canon + rest
    }

    /// The row behind `ref`, as this language shows it.
    func entry(_ ref: Entry.Ref) -> Entry? {
        let language = language()
        return all.first { $0.ref == ref && Self.shown($0, language) }
    }

    /// All six canonical rows are stored (blank answers count: Gemini was asked).
    func hasCanonical(_ key: Entry.SongKey) -> Bool {
        let language = language()
        let have = Set(all.lazy.filter { $0.songKey == key && $0.kind == Kind.canonical && $0.language == language }
            .map(\.entryKey))
        return Set(Canon.order).isSubset(of: have)
    }

    /// Inserts or replaces rows by (song, kind, entryKey), Android's primary key.
    /// False when the file couldn't be written; memory then keeps the rows for this run (an answer just
    /// paid for still shows), and sync, which checks, retries.
    @discardableResult
    func upsert(_ rows: [Entry]) -> Bool {
        guard !rows.isEmpty else { return true }
        var next = all
        for row in rows {
            if let i = next.firstIndex(where: { $0.songKey == row.songKey && $0.kind == row.kind && $0.entryKey == row.entryKey }) {
                next[i] = row
            } else {
                next.append(row)
            }
        }
        all = next
        return save()
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
        Self.onLocalChange?()
    }

    /// Android `askAboutSong`'s row: keyed by the normalized question, so
    /// asking it again replaces the answer and keeps the first `createdAt`.
    /// [language] is the one the question was asked in (nil: the store's current one).
    func saveAsk(title: String, artist: String, album: String, question: String,
                 reply: GeminiClient.AskAnswer, language: String? = nil, now: Date = Date()) {
        let language = language ?? self.language()
        let key = Self.key(title: title, artist: artist, album: album)
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let questionKey = Self.normalize(asked)
        let ms = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let existing = all.first { $0.songKey == key && $0.kind == Kind.ask && $0.entryKey == questionKey }
        upsert([Entry(titleKey: key.title, artistKey: key.artist, albumKey: key.album, titleDisplay: title,
                      artistDisplay: artist, albumDisplay: album, kind: Kind.ask, entryKey: questionKey,
                      promptText: asked, titleText: reply.title, answerText: reply.answer,
                      createdAt: existing?.createdAt ?? ms, updatedAt: ms, language: language)])
        Self.onLocalChange?()
    }

    /// Android `editAboutEntry`: the listener's version of one row from the
    /// scrapbook. `answer` (and, on an ask or own row, `title`) replace what
    /// was there. A blank answer removes the row from every view; it stays as
    /// a blank row rather than being deleted, because About deletes never sync
    /// and the row would come back from Drive. An own row always keeps a heading.
    func edit(_ ref: Entry.Ref, title: String?, answer: String, now: Date = Date()) {
        guard var row = entry(ref) else { return }
        let text = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        let heading = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        if row.kind != Kind.canonical, !text.isEmpty {
            if row.kind == Kind.own {
                if let heading, !heading.isEmpty { row.titleText = heading }
            } else {
                row.titleText = heading.flatMap { $0.isEmpty ? nil : $0 }
            }
        }
        row.answerText = text
        row.updatedAt = Self.ms(now)
        upsert([row])
        Self.onLocalChange?()
    }

    /// Android `writeOwnAboutEntry`: the listener's own entry for a song (the
    /// scrapbook's Write, beside Ask Gemini), filed under the song like
    /// Gemini's rows so Now Playing's About shows it too. The same heading
    /// again replaces the text; blank input writes nothing. A key is fixed
    /// when first written while the heading can be renamed later, so a heading
    /// is matched against the live entries' headings, not their keys.
    func writeOwn(title: String, artist: String, album: String, heading: String, text: String, now: Date = Date()) {
        let head = heading.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !head.isEmpty, !body.isEmpty else { return }
        let key = Self.key(title: title, artist: artist, album: album)
        let headKey = Self.normalize(head)
        let ms = Self.ms(now)
        let live = all.filter { $0.songKey == key && $0.kind == Kind.own && !$0.isBlank }
        let existing = live.first { Self.normalize($0.titleText ?? "") == headKey }
        // A removed entry's key is free again; a live entry's key stays its own even after a rename.
        let entryKey = existing?.entryKey ?? (1...).lazy
            .map { n in n == 1 ? headKey : "\(headKey) #\(n)" }
            .first { k in !live.contains { $0.entryKey == k } }!
        upsert([Entry(titleKey: key.title, artistKey: key.artist, albumKey: key.album, titleDisplay: title,
                      artistDisplay: artist, albumDisplay: album, kind: Kind.own, entryKey: entryKey,
                      promptText: nil, titleText: head, answerText: body, createdAt: existing?.createdAt ?? ms,
                      updatedAt: ms, language: Self.anyLanguage)])
        Self.onLocalChange?()
    }

    private static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    private func save() -> Bool {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(all).write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    /// A row from another device: on disk before memory, false = nothing changed (sync retries it).
    func syncUpsert(_ row: Entry) -> Bool {
        let before = all
        guard upsert([row]) else {
            all = before
            return false
        }
        return true
    }
}
