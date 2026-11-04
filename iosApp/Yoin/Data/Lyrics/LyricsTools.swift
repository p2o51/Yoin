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

/// Lyrics the user chose (search result or the editor) win over the server's
/// on every later load (Android `userChosen` lyrics_cache rows), and Gemini
/// translations are kept per song, language and source text (Android
/// `lyrics_translation_cache`).
@MainActor
final class LyricsUserStore {
    static let shared = LyricsUserStore(fileURL: LyricsUserStore.defaultURL)

    struct Snapshot: Codable, Equatable {
        /// Track key → raw LRC.
        var chosen: [String: String] = [:]
        /// "trackKey|language|hash" → one translation per source line ("" = none).
        var translations: [String: [String]] = [:]
    }

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

    func choose(_ lrc: String, for key: String) {
        snapshot.chosen[key] = lrc
        // A new text makes the old translations stale.
        snapshot.translations = snapshot.translations.filter { !$0.key.hasPrefix(key + "|") }
        persist()
    }

    func translations(_ key: String, language: String, lines: [String]) -> [String]? {
        snapshot.translations[Self.translationKey(key, language: language, lines: lines)]
    }

    func saveTranslations(_ t: [String], key: String, language: String, lines: [String]) {
        snapshot.translations[Self.translationKey(key, language: language, lines: lines)] = t
        persist()
    }

    static func translationKey(_ key: String, language: String, lines: [String]) -> String {
        // FNV-1a over the source text: stable across launches (Swift's Hasher isn't).
        var h: UInt64 = 0xcbf29ce484222325
        for b in lines.joined(separator: "\n").utf8 {
            h ^= UInt64(b)
            h = h &* 0x100000001b3
        }
        return "\(key)|\(language)|\(String(h, radix: 16))"
    }

    private func persist() {
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: fileURL, options: .atomic) }
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
