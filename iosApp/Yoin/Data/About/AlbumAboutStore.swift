import Foundation
import Observation
import YoinShared

/// An album's About (Android `data/local/AlbumAboutEntry`, Room `album_about_entries`): the AI's credits and
/// editorial paragraph (`kind == canonical`, keys `credits` / `blurb`) and the questions asked about the album
/// (`kind == ask`, keyed by the normalized question). Kept as one JSON file in Application Support
/// (`Yoin/album_about.json`), the way `SongAboutStore` keeps the song rows.
///
/// Keyed by the album's identity on its service (provider + raw album id), as Android does: an album's credits
/// belong to one release (a deluxe edition, a reissue). Shared across accounts like the song rows: reference
/// material, not the listener's own words.
///
/// Every row carries the "Answer in" language it was written in (iOS keeps a cache per language, like
/// `SongAboutStore`, where Android clears its table on a switch): only the current language's rows are shown
/// and synced. Nothing here is the listener's own: there is no `own` kind and no any-language row.
///
/// The shapes and rules (credits JSON, row keys, the ask key, rows → page content) are :shared's
/// `YoinAlbumAbout`, pinned by shared/src/commonTest/resources/golden/album-about.json.
///
/// Sync (`album_about`, the shared `AlbumAboutSyncAdapter`): `syncUpsert(_:)` takes rows in Android's shape;
/// `all` reads them back. Deletes never sync.
@MainActor
@Observable
final class AlbumAboutStore {
    static let shared = AlbumAboutStore(fileURL: AlbumAboutStore.launchURL)

    struct Entry: Codable, Hashable, Sendable {
        var provider: String
        var albumId: String
        var albumDisplay: String
        var artistDisplay: String
        /// `Kind.canonical` or `Kind.ask`.
        var kind: String
        /// `Canon.credits` / `Canon.blurb`, or the normalized question.
        var entryKey: String
        /// Ask rows: the question as typed.
        var promptText: String?
        /// Ask rows: the AI's short heading.
        var titleText: String?
        var answerText: String
        /// Epoch ms.
        var createdAt: Int64
        var updatedAt: Int64
        /// The "Answer in" language the row was written in.
        var language: String

        var album: AlbumKey { AlbumKey(provider: provider, albumId: albumId) }
        var ref: Ref { Ref(album: album, kind: kind, entryKey: entryKey) }

        /// The row as :shared reads it (`YoinAlbumAbout.content`).
        var sharedRow: AlbumAboutRow {
            AlbumAboutRow(kind: kind, entryKey: entryKey, promptText: promptText, titleText: titleText,
                          answerText: answerText, createdAt: createdAt)
        }
    }

    /// One album on its service (Android `MediaId.provider` + `rawId`).
    struct AlbumKey: Hashable, Sendable {
        var provider: String
        var albumId: String
    }

    /// One row's primary key (Android's `provider, albumId, kind, entryKey`).
    struct Ref: Hashable, Sendable {
        var album: AlbumKey
        var kind: String
        var entryKey: String
    }

    enum Kind {
        static var canonical: String { YoinAlbumAbout.shared.KIND_CANONICAL }
        static var ask: String { YoinAlbumAbout.shared.KIND_ASK }
    }

    enum Canon {
        static var credits: String { YoinAlbumAbout.shared.CANON_CREDITS }
        static var blurb: String { YoinAlbumAbout.shared.CANON_BLURB }
    }

    private(set) var all: [Entry] = []
    @ObservationIgnored private let fileURL: URL
    /// The language rows are shown in (`GeminiSettings.targetLanguage`); a loader with its own settings points it there.
    @ObservationIgnored var language: @MainActor () -> String = {
        GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
    }

    /// An answer the AI just gave on this device (not a row from sync): Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            all = saved
        }
    }

    /// DEBUG `-albumAboutDemo`: a scratch file, so demo rows never reach the real cache.
    private static var launchURL: URL {
        #if DEBUG
        if AlbumAboutDemo.mode != nil {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("album_about_demo.json")
            try? FileManager.default.removeItem(at: url)
            return url
        }
        #endif
        return defaultURL
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("album_about.json")
    }

    static func key(_ album: Album) -> AlbumKey { AlbumKey(provider: album.id.provider, albumId: album.id.rawId) }

    // MARK: Reading

    /// One album's rows in this language: canonical first, then questions newest first (Android `observe`).
    func entries(_ album: AlbumKey) -> [Entry] {
        let language = language()
        let rows = all.filter { $0.album == album && $0.language == language }
        let canon = rows.filter { $0.kind == Kind.canonical }
        let asks = rows.filter { $0.kind != Kind.canonical }.sorted { $0.createdAt > $1.createdAt }
        return canon + asks
    }

    /// What the page reads (`YoinAlbumAbout.content`): credits, paragraph, questions, and whether the AI was asked.
    func content(_ album: AlbumKey) -> AlbumAboutContent {
        YoinAlbumAbout.shared.content(rows: entries(album).map(\.sharedRow))
    }

    /// The canonical rows in this language (Android `getCanonical`): non-empty once the AI has been asked.
    func canonical(_ album: AlbumKey) -> [Entry] {
        entries(album).filter { $0.kind == Kind.canonical }
    }

    /// The questions asked about the album in this language (the receipt's "Asked" counts them).
    func askCount(_ album: AlbumKey) -> Int {
        Int(content(album).asks.count)
    }

    // MARK: Writing

    /// Inserts or replaces rows by (provider, albumId, kind, entryKey, language). False when the file couldn't be
    /// written; memory then keeps the rows for this run, and sync, which checks, retries.
    @discardableResult
    func upsert(_ rows: [Entry]) -> Bool {
        guard !rows.isEmpty else { return true }
        var next = all
        for row in rows {
            if let i = next.firstIndex(where: { $0.ref == row.ref && $0.language == row.language }) {
                next[i] = row
            } else {
                next.append(row)
            }
        }
        all = next
        return save()
    }

    /// Android `writeAlbumAbout`: the credits and the paragraph as the two canonical rows (an empty paragraph
    /// when the AI wrote none: it was asked), replacing what was there. [createdAt] keeps a regenerated row's
    /// first date.
    func saveCanonical(album: Album, credits: AlbumAiCredits, blurb: String?, language: String,
                       createdAt: Int64? = nil, now: Date = Date()) {
        let ms = Self.ms(now)
        func row(_ key: String, _ answer: String) -> Entry {
            Entry(provider: album.id.provider, albumId: album.id.rawId, albumDisplay: album.title,
                  artistDisplay: album.artist, kind: Kind.canonical, entryKey: key, promptText: nil, titleText: nil,
                  answerText: answer, createdAt: createdAt ?? ms, updatedAt: ms, language: language)
        }
        upsert([row(Canon.credits, YoinAlbumAbout.shared.encodeCredits(credits: credits)),
                row(Canon.blurb, blurb ?? "")])
        Self.onLocalChange?()
    }

    /// Android `restoreAlbumAbout` (Undo of Regenerate): the rows as they were, written back now so they sync
    /// as the newest.
    func restore(_ rows: [Entry], now: Date = Date()) {
        guard !rows.isEmpty else { return }
        let ms = Self.ms(now)
        upsert(rows.map { var r = $0; r.updatedAt = ms; return r })
        Self.onLocalChange?()
    }

    /// Android `askAboutAlbum`'s row: keyed by the normalized question (`YoinAlbumAbout.questionKey`), so asking
    /// it again replaces the answer and keeps its first `createdAt` (and its place).
    func saveAsk(album: Album, question: String, reply: GeminiClient.AskAnswer, language: String,
                 now: Date = Date()) {
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = YoinAlbumAbout.shared.questionKey(question: asked)
        let ms = Self.ms(now)
        let ref = Ref(album: Self.key(album), kind: Kind.ask, entryKey: key)
        let existing = all.first { $0.ref == ref && $0.language == language }
        upsert([Entry(provider: album.id.provider, albumId: album.id.rawId, albumDisplay: album.title,
                      artistDisplay: album.artist, kind: Kind.ask, entryKey: key, promptText: asked,
                      titleText: reply.title, answerText: reply.answer, createdAt: existing?.createdAt ?? ms,
                      updatedAt: ms, language: language)])
        Self.onLocalChange?()
    }

    /// A row from another device, filed under this device's language (the shared adapter applies a record only
    /// in its own language): on disk before memory, false = nothing changed (sync retries it).
    func syncUpsert(_ row: Entry) -> Bool {
        let before = all
        guard upsert([row]) else {
            all = before
            return false
        }
        return true
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
}
