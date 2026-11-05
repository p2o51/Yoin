import Foundation
import YoinShared

// The data side of the expanded Lyrics page's four tools (Android
// LyricsActionBar: Search, Translate, Select, Recenter) and select mode's
// Copy / Share / Edit: LRCLIB search, the user's own lyrics, the Gemini
// translation cache and the pure rules Android tests (LyricsSelection.kt).

// MARK: - LRCLIB (Android data/lyrics/LrclibLyricsProvider)

/// LRCLIB (https://lrclib.net): FOSS, no key. Android's lyrics search fans out
/// over QQ / NetEase / Huawei / LRCLIB; iOS carries LRCLIB only.
struct LrclibClient: Sendable {
    static let providerName = "lrclib"
    static let providerLabel = "LRCLIB"

    var baseURL = URL(string: "https://lrclib.net")!
    var session: URLSession = .shared

    struct Match: Equatable, Sendable, Identifiable {
        var id: String
        var title: String
        var artist: String
    }

    /// Android `searchLyricsProviderSections`: the query as the title, no
    /// artist, three per provider.
    func search(_ query: String, limit: Int = 3) async throws -> [Match] {
        var c = URLComponents(url: baseURL.appendingPathComponent("api/search"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "track_name", value: query), URLQueryItem(name: "artist_name", value: "")]
        let (data, response) = try await session.data(for: Self.request(c.url!))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }
        return Array(Self.parseSearch(data, fallbackTitle: query).prefix(limit))
    }

    /// `/api/get/{id}`: the synced LRC, else the plain lyrics.
    func fetch(id: String) async throws -> String? {
        let (data, response) = try await session.data(for: Self.request(baseURL.appendingPathComponent("api/get/\(id)")))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return Self.parseLyric(data)
    }

    private static func request(_ url: URL) -> URLRequest {
        var r = URLRequest(url: url)
        r.timeoutInterval = 20
        // LRCLIB asks for an identifiable User-Agent.
        r.setValue("Yoin (https://github.com/p2o51/Yoin)", forHTTPHeaderField: "User-Agent")
        return r
    }

    static func parseSearch(_ data: Data, fallbackTitle: String) -> [Match] {
        guard let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return array.compactMap { obj in
            let id: String
            if let n = obj["id"] as? NSNumber { id = n.stringValue } else if let s = obj["id"] as? String { id = s } else { return nil }
            return Match(id: id, title: obj["trackName"] as? String ?? fallbackTitle,
                         artist: obj["artistName"] as? String ?? "")
        }
    }

    static func parseLyric(_ data: Data) -> String? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let synced = (obj["syncedLyrics"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let synced, !synced.isEmpty { return synced }
        let plain = (obj["plainLyrics"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return plain?.isEmpty == false ? plain : nil
    }
}

// MARK: - The user's lyrics and translations, on disk

/// Where a song's lyrics came from: a lyrics provider ("qq", "netease",
/// "huawei", "lrclib") and its song id, or the server ("subsonic").
struct LyricsSource: Codable, Equatable, Hashable, Sendable {
    var provider: String
    var songId: String

    static let server = "subsonic"

    /// Android `toLyricsProviderLabel`.
    static func label(_ provider: String) -> String {
        switch provider {
        case "qq": "QQ Music"
        case "netease": "NetEase"
        case "huawei": "Huawei Music"
        case "lrclib": "LRCLIB"
        default: provider
        }
    }
}

/// Lyrics the user chose (search result or the editor) win over the server's
/// on every later load (Android `userChosen` lyrics_cache rows), and Gemini
/// translations are kept as Android's `lyrics_translation_cache` rows (same
/// identity, so cloud sync carries them both ways). A provider's own hit is
/// kept 30 days.
@MainActor
final class LyricsUserStore {
    static let shared = LyricsUserStore(fileURL: LyricsUserStore.defaultURL)

    /// A change made on this device (a new translation), for cloud sync; sync's own writes don't call it.
    static var onLocalChange: (@MainActor () -> Void)?

    /// One Android `lyrics_translation_cache` row. `translationsJson` is kept as the exact text it was
    /// written or received as (sync hashes it).
    struct TranslationRow: Codable, Equatable {
        var trackProvider: String
        var trackRawId: String
        /// `LyricsTranslationIdentity.sourceHash` of title, artist and the source lines.
        var sourceHash: String
        var targetLanguage: String
        /// Gemini's model name (Android `GeminiService.MODEL`), or "provider:<name>" for a provider's own.
        var model: String
        var translationsJson: String
        /// Epoch ms.
        var cachedAt: Int64

        var key: String { LyricsUserStore.rowKey(trackProvider, trackRawId, sourceHash, targetLanguage, model) }
    }

    struct Snapshot: Codable, Equatable {
        /// Track key → raw LRC.
        var chosen: [String: String] = [:]
        /// Before Android's identity (read only, moved to `rows` when next used):
        /// "trackKey|language|fnvHash" → one translation per source line ("" = none).
        var translations: [String: [String]] = [:]
        /// Android `lyrics_translation_cache`, by `TranslationRow.key`.
        var rows: [String: TranslationRow]? = nil
        /// Track key → where the chosen lyrics came from (nil for the editor's).
        var chosenSource: [String: LyricsSource]? = nil
        /// Lyrics a provider found on its own (Android's lyrics cache, not user-chosen):
        /// used for `autoDays`, then looked up again.
        var auto: [String: AutoLyrics]? = nil
    }

    struct AutoLyrics: Codable, Equatable {
        var lrc: String
        var source: LyricsSource
        var savedAt: Date
    }

    /// Android LyricsCachePolicy: an automatic hit is kept 30 days.
    static let autoDays: TimeInterval = 30 * 24 * 3600

    private(set) var snapshot = Snapshot()
    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL), let s = try? JSONDecoder().decode(Snapshot.self, from: data) {
            snapshot = s
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("lyrics-user.json")
    }

    func chosen(_ key: String) -> String? { snapshot.chosen[key] }
    func chosenSource(_ key: String) -> LyricsSource? { snapshot.chosenSource?[key] }

    /// The provider's own hit for [key], while it is fresh.
    func auto(_ key: String, now: Date = .now) -> AutoLyrics? {
        guard let a = snapshot.auto?[key], now.timeIntervalSince(a.savedAt) < Self.autoDays else { return nil }
        return a
    }

    func saveAuto(_ lrc: String, source: LyricsSource, for key: String, now: Date = .now) {
        var all = snapshot.auto ?? [:]
        all[key] = AutoLyrics(lrc: lrc, source: source, savedAt: now)
        snapshot.auto = all
        persist()
    }

    func choose(_ lrc: String, for key: String, source: LyricsSource? = nil) {
        snapshot.chosen[key] = lrc
        var sources = snapshot.chosenSource ?? [:]
        sources[key] = source
        snapshot.chosenSource = sources
        // A new text makes the old translations stale.
        snapshot.translations = snapshot.translations.filter { !$0.key.hasPrefix(key + "|") }
        persist()
    }

    /// The pre-Android cache's translations (kept readable; `geminiTranslations` moves them over).
    func translations(_ key: String, language: String, lines: [String]) -> [String]? {
        snapshot.translations[Self.translationKey(key, language: language, lines: lines)]
    }

    func saveTranslations(_ t: [String], key: String, language: String, lines: [String]) {
        snapshot.translations[Self.translationKey(key, language: language, lines: lines)] = t
        persist()
    }

    // MARK: Android lyrics_translation_cache

    /// Android's composite key (U+001F between the five fields).
    nonisolated static func rowKey(_ parts: String...) -> String { parts.joined(separator: "\u{1F}") }

    func translation(trackProvider: String, trackRawId: String, sourceHash: String, targetLanguage: String,
                     model: String) -> TranslationRow? {
        snapshot.rows?[Self.rowKey(trackProvider, trackRawId, sourceHash, targetLanguage, model)]
    }

    /// Gemini's rows (Android `paidTranslations`: model not "provider:…").
    var paidTranslations: [TranslationRow] {
        (snapshot.rows ?? [:]).values.filter { !LyricsTranslationIdentity.shared.isProviderModel(model: $0.model) }
    }

    /// Android `translateLyrics`' cache read for Gemini: the row for this song's exact source lines,
    /// language and model, its list as long as the lines (Android `toTranslations`). A translation from
    /// the pre-Android cache moves to its Android row here, the first time its lines are seen again
    /// (the old key can't be turned into Android's: it kept only a hash of the lines).
    func geminiTranslations(provider: String, rawId: String, title: String, artist: String, language: String,
                            model: String, lines: [String], now: Date = .now) -> [String]? {
        let hash = LyricsTranslationIdentity.shared.geminiSourceHash(title: title, artist: artist, lines: lines)
        if let row = translation(trackProvider: provider, trackRawId: rawId, sourceHash: hash,
                                 targetLanguage: language, model: model) {
            return LyricsTranslationIdentity.shared.decodeTranslations(translationsJson: row.translationsJson)
                .flatMap { $0.count == lines.count ? $0 : nil }
        }
        let legacyKey = Self.translationKey(LocalRatings.key(provider: provider, songId: rawId), language: language,
                                            lines: lines)
        guard let legacy = snapshot.translations[legacyKey], legacy.count == lines.count else { return nil }
        let row = TranslationRow(trackProvider: provider, trackRawId: rawId, sourceHash: hash,
                                 targetLanguage: language, model: model,
                                 translationsJson: LyricsTranslationIdentity.shared.encodeTranslations(translations: legacy),
                                 cachedAt: Self.ms(now))
        var rows = snapshot.rows ?? [:]
        rows[row.key] = row
        snapshot.rows = rows
        snapshot.translations[legacyKey] = nil
        persist()
        Self.onLocalChange?()
        return legacy
    }

    /// Android `translateLyrics`' upsert after Gemini answered: one entry per source line ("" = none).
    func saveGeminiTranslations(_ t: [String], provider: String, rawId: String, title: String, artist: String,
                                language: String, model: String, lines: [String], now: Date = .now) {
        let row = TranslationRow(
            trackProvider: provider, trackRawId: rawId,
            sourceHash: LyricsTranslationIdentity.shared.geminiSourceHash(title: title, artist: artist, lines: lines),
            targetLanguage: language, model: model,
            translationsJson: LyricsTranslationIdentity.shared.encodeTranslations(translations: t),
            cachedAt: Self.ms(now))
        var rows = snapshot.rows ?? [:]
        rows[row.key] = row
        snapshot.rows = rows
        persist()
        Self.onLocalChange?()
    }

    /// Sync: adds a row from another device unless one with its key is here. On disk before memory.
    func syncInsertIfAbsent(_ row: TranslationRow) -> Bool {
        guard snapshot.rows?[row.key] == nil else { return true }
        var next = snapshot
        var rows = next.rows ?? [:]
        rows[row.key] = row
        next.rows = rows
        return commit(next)
    }

    func syncRemoveTranslation(key: String) -> Bool {
        guard snapshot.rows?[key] != nil else { return true }
        var next = snapshot
        next.rows?[key] = nil
        return commit(next)
    }

    // MARK: Android lyrics_cache, for sync's lyrics snapshot

    /// The lyrics a track loads, as Android's `lyrics_cache` row would hold them: the user's (typed =
    /// "manual"; a provider's they chose = song id marked "user|"), else the provider's own hit (any age).
    struct CachedLyrics: Equatable {
        var lyricsProvider: String
        var lyricsProviderSongId: String?
        var lrc: String
        /// Epoch ms the automatic hit was saved; 0 for the user's.
        var cachedAt: Int64
    }

    nonisolated static let manualProvider = "manual"
    nonisolated static let userChosenPrefix = "user|"

    func cachedLyrics(_ key: String) -> CachedLyrics? {
        if let lrc = snapshot.chosen[key] {
            guard let source = snapshot.chosenSource?[key] else {
                return CachedLyrics(lyricsProvider: Self.manualProvider, lyricsProviderSongId: nil, lrc: lrc, cachedAt: 0)
            }
            return CachedLyrics(lyricsProvider: source.provider, lyricsProviderSongId: Self.userChosenPrefix + source.songId,
                                lrc: lrc, cachedAt: 0)
        }
        guard let a = snapshot.auto?[key] else { return nil }
        return CachedLyrics(lyricsProvider: a.source.provider, lyricsProviderSongId: a.source.songId, lrc: a.lrc,
                            cachedAt: Self.ms(a.savedAt))
    }

    /// Sync's lyrics seed (the shared adapter decided it may replace what is here). On disk before memory.
    func syncWriteLyrics(_ c: CachedLyrics, for key: String) -> Bool {
        var next = snapshot
        let songId = c.lyricsProviderSongId ?? ""
        if c.lyricsProvider == Self.manualProvider {
            next.chosen[key] = c.lrc
            next.chosenSource?[key] = nil
        } else if songId.hasPrefix(Self.userChosenPrefix) {
            next.chosen[key] = c.lrc
            var sources = next.chosenSource ?? [:]
            sources[key] = LyricsSource(provider: c.lyricsProvider, songId: String(songId.dropFirst(Self.userChosenPrefix.count)))
            next.chosenSource = sources
        } else {
            next.chosen[key] = nil
            next.chosenSource?[key] = nil
            var all = next.auto ?? [:]
            all[key] = AutoLyrics(lrc: c.lrc, source: LyricsSource(provider: c.lyricsProvider, songId: songId),
                                  savedAt: Date(timeIntervalSince1970: TimeInterval(c.cachedAt) / 1000))
            next.auto = all
        }
        return commit(next)
    }

    private func commit(_ next: Snapshot) -> Bool {
        guard write(next) else { return false }
        snapshot = next
        return true
    }

    private static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    static func translationKey(_ key: String, language: String, lines: [String]) -> String {
        // FNV-1a over the source text: stable across launches (Swift's Hasher isn't).
        var h: UInt64 = 0xcbf29ce484222325
        for b in lines.joined(separator: "\n").utf8 {
            h ^= UInt64(b)
            h = h &* 0x100000001b3
        }
        return "\(key)|\(language)|\(String(h, radix: 16))"
    }

    private func persist() { _ = write(snapshot) }

    private func write(_ s: Snapshot) -> Bool {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(s).write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}

// MARK: - Pure rules (Android LyricsSelection.kt / LyricsActionBar.kt / YoinRepository)

enum LyricsRules {
    /// spotoolfy's poster cap (Android `MaxSelectedLyricLines`).
    static let maxSelectedLines = 15

    /// Android `nextLyricSelection`: a picked line lets go, any other joins —
    /// refused once the pick is full.
    static func nextSelection(_ current: Set<Int>, tapped: Int,
                              maxLines: Int = maxSelectedLines) -> (selected: Set<Int>, hitLimit: Bool) {
        if current.contains(tapped) { return (current.subtracting([tapped]), false) }
        if current.count >= maxLines { return (current, true) }
        return (current.union([tapped]), false)
    }

    /// Android `lyricsSelectionLabel`.
    static func selectionLabel(_ count: Int, limitNudge: Bool = false, maxLines: Int = maxSelectedLines) -> String {
        if limitNudge || count >= maxLines { return "\(maxLines) / \(maxLines)" }
        return count == 0 ? "Select lines" : "\(count) / \(maxLines)"
    }

    /// Positions in the sorted pick that skip part of the song (Android `lyricPassageStarts`).
    static func passageStarts(_ selected: Set<Int>, lineCount: Int) -> Set<Int> {
        let picked = selected.filter { $0 >= 0 && $0 < lineCount }.sorted()
        return Set(picked.indices.filter { $0 > 0 && picked[$0] != picked[$0 - 1] + 1 })
    }

    /// Android `lyricsClipboardText`: one lyric per line (its translation
    /// under it when shown), a blank line before each new passage, then
    /// "— Title · Artist".
    static func clipboardText(lines: [String], translations: [String?], passageStarts: Set<Int>,
                              title: String?, artist: String?) -> String {
        let body = lines.enumerated().map { i, line -> String in
            let t = i < translations.count ? translations[i] : nil
            let text = t.map { $0.trimmingCharacters(in: .whitespaces).isEmpty ? line : "\(line)\n\($0)" } ?? line
            return passageStarts.contains(i) ? "\n\(text)" : text
        }.joined(separator: "\n")
        guard let credit = credit(title: title, artist: artist) else { return body }
        return "\(body)\n\n— \(credit)"
    }

    /// Android `lyricsCredit`: "Title · Artist", the title alone, or nil.
    static func credit(title: String?, artist: String?) -> String? {
        let t = title?.trimmingCharacters(in: .whitespaces) ?? ""
        guard !t.isEmpty else { return nil }
        let a = artist?.trimmingCharacters(in: .whitespaces) ?? ""
        return a.isEmpty ? t : "\(t) · \(a)"
    }

    /// Android `lyricsKeyWidths`: the pressed key gains 15 % of its width
    /// from its neighbours (both halves for a middle key); the row's total
    /// stays put and no width goes below zero.
    static func keyWidths(count: Int, key: CGFloat, press: (Int) -> CGFloat) -> [CGFloat] {
        var w = Array(repeating: key, count: count)
        if count > 1 {
            for i in 0..<count {
                let growth = press(i) * 0.15 * key
                if growth == 0 { continue }
                w[i] += growth
                if i == 0 { w[1] -= growth } else if i == count - 1 { w[i - 1] -= growth } else {
                    w[i - 1] -= growth / 2
                    w[i + 1] -= growth / 2
                }
            }
        }
        return w.map { max(0, $0) }
    }

    /// Android `toEditableLyricsText`: LRC with [mm:ss.xx] stamps when synced.
    static func editableText(_ lyrics: Lyrics) -> String {
        lyrics.lines.map { line in
            guard lyrics.synced else { return line.text }
            let ms = max(0, line.timeMs)
            return String(format: "[%02lld:%02lld.%02lld]", ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10) + line.text
        }.joined(separator: "\n")
    }

    /// Android `appearToAlreadyBeTargetLanguage`: a Chinese target and lyrics
    /// that are mostly Han already.
    static func alreadyTargetLanguage(_ lines: [String], target: String) -> Bool {
        guard target.lowercased().contains("chinese") else { return false }
        var han = 0, kana = 0, hangul = 0, latin = 0
        for line in lines {
            for u in line.unicodeScalars {
                switch u.value {
                case 0x4E00...0x9FFF, 0x3400...0x4DBF, 0x20000...0x323AF, 0xF900...0xFAFF, 0x2F800...0x2FA1F: han += 1
                case 0x3040...0x30FF, 0x31F0...0x31FF: kana += 1
                case 0xAC00...0xD7AF, 0x1100...0x11FF, 0x3130...0x318F: hangul += 1
                case 0x41...0x5A, 0x61...0x7A, 0xC0...0x24F: latin += 1
                default: break
                }
            }
        }
        let meaningful = han + kana + hangul + latin
        if han < 8 || meaningful < 12 || kana > 0 || hangul > 0 { return false }
        let ratio = Double(han) / Double(meaningful)
        return ratio >= 0.72 || (han >= 20 && ratio >= 0.60)
    }
}
