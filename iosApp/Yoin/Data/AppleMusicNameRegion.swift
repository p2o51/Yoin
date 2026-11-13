import Foundation
import Observation

/// Which storefront the Apple Music account shows song, album and artist names from, set on the account's
/// page (Android #118 AppleMusicNameRegion). An account's own storefront writes every song in its language:
/// the US storefront calls 晴る by ヨルシカ "Sunny" by "Yorushika", and Japan's calls Kim Petras キム・ペトラス.
/// The catalog serves the same song in every storefront under the same id, so asking another storefront for
/// it, in that storefront's language, brings its names from there.
enum AppleMusicNameRegion: String, CaseIterable, Sendable {
    /// The account's own storefront: names as Apple Music shows them.
    case off
    /// Each song from the storefront of the language it was released in (`OriginalNameTarget.detect`).
    case original
    /// Every song from China mainland's storefront, in Simplified Chinese.
    case mainlandChina = "cn"
    /// Every song from Japan's storefront, in Japanese.
    case japan = "jp"

    static let `default`: AppleMusicNameRegion = .off

    /// The stored key (Android's `key`).
    var key: String { rawValue }

    static func from(key: String?) -> AppleMusicNameRegion { key.flatMap(AppleMusicNameRegion.init(rawValue:)) ?? .default }
}

/// A storefront to ask for names, and the language to ask it in (the `l` parameter).
struct OriginalNameTarget: Hashable, Sendable {
    var storefront: String
    var language: String

    static let japan = OriginalNameTarget(storefront: "jp", language: "ja")
    static let korea = OriginalNameTarget(storefront: "kr", language: "ko")
    static let mainlandChina = OriginalNameTarget(storefront: "cn", language: "zh-Hans-CN")
    static let thailand = OriginalNameTarget(storefront: "th", language: "th")
    static let russia = OriginalNameTarget(storefront: "ru", language: "ru")
    static let ukraine = OriginalNameTarget(storefront: "ua", language: "uk")
    static let saudiArabia = OriginalNameTarget(storefront: "sa", language: "ar")
    static let israel = OriginalNameTarget(storefront: "il", language: "he")
    static let india = OriginalNameTarget(storefront: "in", language: "hi")
    static let greece = OriginalNameTarget(storefront: "gr", language: "el")
    static let bulgaria = OriginalNameTarget(storefront: "bg", language: "bg")

    /// The storefront [region] asks for an item's names, or nil when it keeps the shown ones. `.original`
    /// goes by the item's own language ([detect]); the fixed regions ask their storefront for everything.
    static func forRegion(_ region: AppleMusicNameRegion, title: String?, artist: String?, genre: String?,
                          isrc: String?) -> OriginalNameTarget? {
        switch region {
        case .off: nil
        case .original: detect(title: title, artist: artist, genre: genre, isrc: isrc)
        case .mainlandChina: .mainlandChina
        case .japan: .japan
        }
    }

    /// The language a song was released in, as well as its metadata tells: its genre first (J-Pop,
    /// Mandopop…, in English or in Chinese as the CN storefront writes them), then the country its ISRC was
    /// registered in, then the script its shown names are written in. Nil when nothing points at a language
    /// whose storefront writes names differently from English: a song in English keeps the names it has.
    static func detect(title: String?, artist: String?, genre: String?, isrc: String?) -> OriginalNameTarget? {
        forGenre(genre) ?? forIsrc(isrc) ?? forScript("\(title ?? "") \(artist ?? "")")
    }

    static func forGenre(_ genre: String?) -> OriginalNameTarget? {
        let g = genre?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        guard !g.isEmpty else { return nil }
        func has(_ markers: String...) -> Bool { markers.contains { g.contains($0) } }
        if has("j-pop", "japanese", "anime", "日本流行", "日语流行", "日語流行", "动漫", "動漫") { return .japan }
        if has("k-pop", "korean", "韩国流行", "韓國流行", "韩语流行", "韓語流行") { return .korea }
        if has("mandopop", "cantopop", "chinese", "hong kong", "c-pop",
               "国语流行", "國語流行", "华语流行", "華語流行", "中文流行", "粤语流行", "粵語流行") { return .mainlandChina }
        if has("thai") { return .thailand }
        if has("russian") { return .russia }
        if has("ukrain") { return .ukraine }
        if has("arab", "khaleeji") { return .saudiArabia }
        if has("israel", "hebrew") { return .israel }
        if has("bollywood", "indian") { return .india }
        if has("greek") { return .greece }
        if has("bulgar") { return .bulgaria }
        return nil
    }

    /// The ISRC's first two letters name the country of its registrant, which mostly is the artist's.
    static func forIsrc(_ isrc: String?) -> OriginalNameTarget? {
        switch isrc?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(2).uppercased() {
        case "JP": .japan
        case "KR": .korea
        case "CN", "HK", "MO", "TW": .mainlandChina
        default: nil
        }
    }

    /// Kana is Japanese and Hangul Korean; Han alone is Chinese. Latin script says nothing.
    static func forScript(_ text: String) -> OriginalNameTarget? {
        let scalars = text.unicodeScalars
        if scalars.contains(where: isKana) { return .japan }
        if scalars.contains(where: isHangul) { return .korea }
        if scalars.contains(where: isHan) { return .mainlandChina }
        return nil
    }

    /// Whether names a storefront answered for [target] are worth showing instead of the shown ones. A fixed
    /// region shows whatever its storefront calls the item. `.original` asks for names in the item's own
    /// script, so it takes only an answer that is: one that differs and carries a letter outside the Latin
    /// script (a storefront without the song in its own language answers in English), and, for Chinese, a
    /// Han character (not a Japanese or Korean name).
    static func accepts(_ region: AppleMusicNameRegion, target: OriginalNameTarget, shown: String?, resolved: String?) -> Bool {
        let answer = resolved?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if answer.isEmpty || answer == shown?.trimmingCharacters(in: .whitespacesAndNewlines) { return false }
        if region != .original { return true }
        guard answer.unicodeScalars.contains(where: isNonLatinLetter) else { return false }
        return target != .mainlandChina || answer.unicodeScalars.contains(where: isHan)
    }

    // MARK: Scripts (Java's Character.UnicodeScript ranges, the parts names use)

    static func isKana(_ s: Unicode.Scalar) -> Bool {
        (0x3040...0x30FF).contains(s.value) || (0x31F0...0x31FF).contains(s.value)
    }

    static func isHangul(_ s: Unicode.Scalar) -> Bool {
        (0xAC00...0xD7AF).contains(s.value) || (0x1100...0x11FF).contains(s.value) || (0x3130...0x318F).contains(s.value)
    }

    private static let hanRanges: [ClosedRange<UInt32>] = [
        0x2E80...0x2E99, 0x2E9B...0x2EF3, 0x2F00...0x2FD5, 0x3005...0x3005, 0x3007...0x3007, 0x3021...0x3029,
        0x3038...0x303B, 0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFA6D, 0xFA70...0xFAD9,
        0x20000...0x2A6DF, 0x2A700...0x2EBEF, 0x2F800...0x2FA1F, 0x30000...0x323AF,
    ]

    static func isHan(_ s: Unicode.Scalar) -> Bool { hanRanges.contains { $0.contains(s.value) } }

    private static let latinRanges: [ClosedRange<UInt32>] = [
        0x0041...0x005A, 0x0061...0x007A, 0x00AA...0x00AA, 0x00BA...0x00BA, 0x00C0...0x00D6, 0x00D8...0x00F6,
        0x00F8...0x02B8, 0x02E0...0x02E4, 0x1D00...0x1D25, 0x1D2C...0x1D5C, 0x1D62...0x1D65, 0x1D6B...0x1D77,
        0x1D79...0x1DBE, 0x1E00...0x1EFF, 0x2071...0x2071, 0x207F...0x207F, 0x2090...0x209C, 0x212A...0x212B,
        0x2132...0x2132, 0x214E...0x214E, 0x2160...0x2188, 0x2C60...0x2C7F, 0xA722...0xA787, 0xA78B...0xA7FF,
        0xAB30...0xAB5A, 0xAB5C...0xAB64, 0xFB00...0xFB06, 0xFF21...0xFF3A, 0xFF41...0xFF5A,
    ]

    /// A letter (Java `Character.isLetter`: a general category L*) whose script isn't Latin.
    static func isNonLatinLetter(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
            return !latinRanges.contains { $0.contains(s.value) }
        default:
            return false
        }
    }
}

/// Profile id → the Apple Music account's name region (Android AppleMusicNameRegionStore). A display choice,
/// nothing secret: UserDefaults. An account with no entry shows names as its storefront does.
@MainActor
@Observable
final class AppleMusicNameRegionStore {
    static let shared = AppleMusicNameRegionStore()
    static let defaultsKey = "appleMusicNameRegions"

    private(set) var regions: [String: AppleMusicNameRegion]
    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.dictionary(forKey: Self.defaultsKey) as? [String: String] ?? [:]
        regions = stored.compactMapValues { AppleMusicNameRegion(rawValue: $0) }.filter { $0.value != .default }
    }

    func region(_ profileId: String) -> AppleMusicNameRegion { regions[profileId] ?? .default }

    func set(_ region: AppleMusicNameRegion, for profileId: String) {
        guard self.region(profileId) != region else { return }
        if region == .default { regions[profileId] = nil } else { regions[profileId] = region }
        defaults.set(regions.mapValues(\.key), forKey: Self.defaultsKey)
    }

    func remove(_ profileId: String) { set(.default, for: profileId) }
}

/// Renames songs and albums after another storefront's names, as the account's region asks (Android
/// AppleMusicNameResolver). Items are grouped by the storefront they ask (`OriginalNameTarget.forRegion`);
/// each group is one request per batch of catalog ids (`GET /v1/catalog/{storefront}/songs?ids=…&l=…`), and
/// songs that storefront doesn't know by their id are asked for once more by ISRC. Answers, misses included,
/// are kept in memory, so a list read again costs nothing. Names are a nicety: a batch that fails or takes
/// longer than [batchTimeout] keeps the names it had, and a storefront the account is in already is never asked.
@MainActor
final class AppleMusicNameResolver {
    /// One item's names in one storefront. A nil field: the storefront named it nothing there.
    struct Names: Hashable, Sendable {
        var title: String?
        var artist: String?
        var album: String?
    }

    /// One catalog read: `/v1/catalog/{storefront}/{kind}?ids=…&l=…`, answered with its `data` resources'
    /// names by id.
    typealias Catalog = @MainActor (_ storefront: String, _ kind: String, _ ids: [String], _ language: String)
        async throws -> [String: Names]
    /// Catalog songs by ISRC in a storefront and language: ISRC → the first song's names.
    typealias ByIsrc = @MainActor (_ storefront: String, _ isrcs: [String], _ language: String) async throws -> [String: Names]

    enum Kind: String, Sendable {
        case songs, albums
        /// Apple's most ids per request for the type.
        var batch: Int { self == .songs ? 300 : 100 }
    }

    /// Items whose names are kept: a large playlist's worth.
    static let cacheSize = 4000
    /// How long a list waits on one batch of names before it opens under the names it has.
    static let batchTimeout: Duration = .seconds(4)

    private let catalog: Catalog
    private let byIsrc: ByIsrc
    private let timeout: Duration
    private var cache: [String: Names?] = [:]
    private var order: [String] = []

    init(timeout: Duration = AppleMusicNameResolver.batchTimeout, catalog: @escaping Catalog, byIsrc: @escaping ByIsrc) {
        self.catalog = catalog
        self.byIsrc = byIsrc
        self.timeout = timeout
    }

    private struct Request: Sendable {
        var id: String
        var target: OriginalNameTarget
        var isrc: String?
    }

    func songs(_ region: AppleMusicNameRegion, accountStorefront: String?, _ songs: [AMSong]) async -> [AMSong] {
        guard region != .off, !songs.isEmpty else { return songs }
        var requests: [String: Request] = [:]
        for s in songs {
            guard let id = s.catalogId,
                  let target = OriginalNameTarget.forRegion(region, title: s.title, artist: s.artist, genre: s.genre, isrc: s.isrc),
                  target.storefront != accountStorefront else { continue }
            requests[id] = Request(id: id, target: target, isrc: s.isrc)
        }
        guard !requests.isEmpty else { return songs }
        let names = await resolve(.songs, Array(requests.values))
        return songs.map { s in
            guard let id = s.catalogId, let request = requests[id], let found = names[id] else { return s }
            var out = s
            out.title = Self.pick(region, request.target, s.title, found.title)
            out.artist = Self.pick(region, request.target, s.artist, found.artist)
            out.album = Self.pick(region, request.target, s.album, found.album)
            return out
        }
    }

    func albums(_ region: AppleMusicNameRegion, accountStorefront: String?, _ albums: [AMAlbum]) async -> [AMAlbum] {
        guard region != .off, !albums.isEmpty else { return albums }
        var requests: [String: Request] = [:]
        for a in albums {
            // An album's genre says its language as a song's does; its tracks' ISRCs aren't in a list's answer.
            guard let id = a.catalogId,
                  let target = OriginalNameTarget.forRegion(region, title: a.title, artist: a.artist, genre: a.genre, isrc: nil),
                  target.storefront != accountStorefront else { continue }
            requests[id] = Request(id: id, target: target, isrc: nil)
        }
        guard !requests.isEmpty else { return albums }
        let names = await resolve(.albums, Array(requests.values))
        return albums.map { a in
            guard let id = a.catalogId, let request = requests[id], let found = names[id] else { return a }
            var out = a
            out.title = Self.pick(region, request.target, a.title, found.title)
            out.artist = Self.pick(region, request.target, a.artist, found.artist)
            return out
        }
    }

    /// An album page: the album and its tracklist, renamed together.
    func album(_ region: AppleMusicNameRegion, accountStorefront: String?, _ album: AMAlbum) async -> AMAlbum {
        guard region != .off else { return album }
        var out = await albums(region, accountStorefront: accountStorefront, [album])[0]
        out.tracks = await songs(region, accountStorefront: accountStorefront, album.tracks)
        return out
    }

    private static func pick(_ region: AppleMusicNameRegion, _ target: OriginalNameTarget, _ shown: String, _ found: String?) -> String {
        guard OriginalNameTarget.accepts(region, target: target, shown: shown, resolved: found), let found else { return shown }
        return found.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Catalog id → its names, for every request that has an answer (cached or read now).
    private func resolve(_ kind: Kind, _ requests: [Request]) async -> [String: Names] {
        var known: [String: Names] = [:]
        let groups = Dictionary(grouping: requests, by: \.target)
        for target in groups.keys.sorted(by: { $0.storefront < $1.storefront }) {
            var unread: [Request] = []
            for request in groups[target] ?? [] {
                let key = Self.key(kind, target, request.id)
                if let hit = cache[key] {
                    if let names = hit { known[request.id] = names }
                } else {
                    unread.append(request)
                }
            }
            // Batch by batch, each kept as it lands: a long list that runs out of time keeps what it read.
            for start in stride(from: 0, to: unread.count, by: kind.batch) {
                let batch = Array(unread[start..<min(start + kind.batch, unread.count)])
                guard !Task.isCancelled, let read = await withTimeout({ [self] in try await self.read(kind, target, batch) })
                else { break }
                for request in batch { store(Self.key(kind, target, request.id), read[request.id]) }
                known.merge(read) { _, new in new }
            }
        }
        return known
    }

    /// One batch's names: by id, then the songs missing by ISRC.
    private func read(_ kind: Kind, _ target: OriginalNameTarget, _ requests: [Request]) async throws -> [String: Names] {
        var found = try await catalog(target.storefront, kind.rawValue, requests.map(\.id), target.language)
        // A storefront may carry a song under an id of its own; the recording's ISRC still finds it.
        if kind == .songs {
            let missing = requests.filter { found[$0.id] == nil && $0.isrc != nil }
            if !missing.isEmpty {
                // The ids' answer stands even when this second read fails.
                let songs = (try? await byIsrc(target.storefront, missing.compactMap(\.isrc), target.language)) ?? [:]
                for request in missing {
                    if let isrc = request.isrc, let names = songs[isrc] { found[request.id] = names }
                }
            }
        }
        return found
    }

    /// [body]'s answer, or nil when it fails or [timeout] passes first (then it is cancelled).
    private func withTimeout(_ body: @escaping @Sendable @MainActor () async throws -> [String: Names]) async -> [String: Names]? {
        let work = Task { @MainActor in try await body() }
        let timeout = timeout
        let timer = Task {
            try await Task.sleep(for: timeout)
            work.cancel()
        }
        defer { timer.cancel() }
        return await withTaskCancellationHandler {
            try? await work.value
        } onCancel: {
            work.cancel()
        }
    }

    private func store(_ key: String, _ names: Names?) {
        if cache.updateValue(names, forKey: key) == nil { order.append(key) }
        if order.count > Self.cacheSize {
            let drop = order.count - Self.cacheSize
            for k in order.prefix(drop) { cache[k] = nil }
            order.removeFirst(drop)
        }
    }

    private static func key(_ kind: Kind, _ target: OriginalNameTarget, _ id: String) -> String {
        "\(kind.rawValue)|\(target.storefront)|\(target.language)|\(id)"
    }

    /// The names in a catalog response's `data` resources, by id.
    nonisolated static func names(in resources: [AppleMusicJSON.Object]) -> [String: Names] {
        var out: [String: Names] = [:]
        for r in resources {
            guard let id = r["id"] as? String, let a = r["attributes"] as? AppleMusicJSON.Object else { continue }
            out[id] = Names(title: AppleMusicJSON.string(a, "name"), artist: AppleMusicJSON.string(a, "artistName"),
                            album: AppleMusicJSON.string(a, "albumName"))
        }
        return out
    }
}
