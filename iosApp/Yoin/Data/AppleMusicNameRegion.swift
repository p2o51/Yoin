import Foundation
import Observation

/// One curated Apple Music storefront option with its official primary language tag (`l` parameter).
/// Matches Android (`AppleMusicNameRegion.CuratedStorefronts`) and HarmonyOS in exact code order.
struct AppleMusicStorefrontOption: Hashable, Sendable, Identifiable {
    let code: String
    let language: String

    var id: String { code }

    static let us = AppleMusicStorefrontOption(code: "us", language: "en-US")
    static let gb = AppleMusicStorefrontOption(code: "gb", language: "en-GB")
    static let jp = AppleMusicStorefrontOption(code: "jp", language: "ja")
    static let cn = AppleMusicStorefrontOption(code: "cn", language: "zh-Hans-CN")
    static let tw = AppleMusicStorefrontOption(code: "tw", language: "zh-Hant-TW")
    static let hk = AppleMusicStorefrontOption(code: "hk", language: "zh-Hant-HK")
    static let kr = AppleMusicStorefrontOption(code: "kr", language: "ko")
    static let ca = AppleMusicStorefrontOption(code: "ca", language: "en-CA")
    static let au = AppleMusicStorefrontOption(code: "au", language: "en-AU")
    static let de = AppleMusicStorefrontOption(code: "de", language: "de-DE")
    static let fr = AppleMusicStorefrontOption(code: "fr", language: "fr-FR")
    static let es = AppleMusicStorefrontOption(code: "es", language: "es-ES")
    static let it = AppleMusicStorefrontOption(code: "it", language: "it-IT")
    static let br = AppleMusicStorefrontOption(code: "br", language: "pt-BR")
    static let mx = AppleMusicStorefrontOption(code: "mx", language: "es-MX")
    static let `in` = AppleMusicStorefrontOption(code: "in", language: "hi")
    static let th = AppleMusicStorefrontOption(code: "th", language: "th")
    static let ru = AppleMusicStorefrontOption(code: "ru", language: "ru")

    static let curated: [AppleMusicStorefrontOption] = [
        .us, .gb, .jp, .cn, .tw, .hk, .kr, .ca, .au,
        .de, .fr, .es, .it, .br, .mx, .in, .th, .ru,
    ]

    static func option(for code: String) -> AppleMusicStorefrontOption? {
        let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return curated.first { $0.code == normalized }
    }

    func displayName(locale: Locale = .current) -> String {
        Self.displayName(for: code, locale: locale)
    }

    static func displayName(for code: String, locale: Locale = .current) -> String {
        let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let isChinese = locale.language.languageCode?.identifier.lowercased() == "zh"
        if normalized == "cn" { return isChinese ? "中国大陆" : "China mainland" }
        if isChinese {
            if normalized == "hk" { return "香港" }
            if normalized == "tw" { return "台湾" }
        }
        return locale.localizedString(forRegionCode: normalized.uppercased()) ?? normalized.uppercased()
    }
}

/// Which storefront the Apple Music account shows song, album and artist names from, set on the account's
/// page (Android #118 AppleMusicNameRegion). An account's own storefront writes every song in its language:
/// the US storefront calls 晴る by ヨルシカ "Sunny" by "Yorushika", and Japan's calls Kim Petras キム・ペトラス.
/// The catalog serves the same song in every storefront under the same id, so asking another storefront for
/// it, in that storefront's language, brings its names from there.
enum AppleMusicNameRegion: Hashable, Sendable {
    /// The account's own storefront: names as Apple Music shows them.
    case off
    /// Each song from the storefront of the language it was released in (`OriginalNameTarget.detect`).
    case original
    /// Every song from one chosen Apple Music storefront, in that storefront's primary language.
    case storefront(AppleMusicStorefrontOption)

    static let `default`: AppleMusicNameRegion = .off
    static let mainlandChina: AppleMusicNameRegion = .storefront(.cn)
    static let japan: AppleMusicNameRegion = .storefront(.jp)

    /// The stored key (Android's `key`): `"off"`, `"original"`, or storefront code (`"cn"`, `"jp"`, …).
    var key: String {
        switch self {
        case .off: "off"
        case .original: "original"
        case .storefront(let option): option.code
        }
    }

    var storefrontOption: AppleMusicStorefrontOption? {
        if case .storefront(let option) = self { return option }
        return nil
    }

    static func from(key: String?) -> AppleMusicNameRegion {
        guard let normalized = key?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !normalized.isEmpty else { return .default }
        switch normalized {
        case "off": return .off
        case "original": return .original
        default:
            if let option = AppleMusicStorefrontOption.option(for: normalized) {
                return .storefront(option)
            }
            return .default
        }
    }
}

/// A storefront to ask for names, and the language to ask it in (the `l` parameter).
struct OriginalNameTarget: Hashable, Sendable {
    var storefront: String
    var language: String

    static let japan = OriginalNameTarget(storefront: "jp", language: "ja")
    static let korea = OriginalNameTarget(storefront: "kr", language: "ko")
    static let mainlandChina = OriginalNameTarget(storefront: "cn", language: "zh-Hans-CN")
    static let taiwan = OriginalNameTarget(storefront: "tw", language: "zh-Hant-TW")
    static let hongKong = OriginalNameTarget(storefront: "hk", language: "zh-Hant-HK")
    static let unitedStates = OriginalNameTarget(storefront: "us", language: "en-US")
    static let thailand = OriginalNameTarget(storefront: "th", language: "th")
    static let russia = OriginalNameTarget(storefront: "ru", language: "ru")
    static let ukraine = OriginalNameTarget(storefront: "ua", language: "uk")
    static let saudiArabia = OriginalNameTarget(storefront: "sa", language: "ar")
    static let israel = OriginalNameTarget(storefront: "il", language: "he")
    static let india = OriginalNameTarget(storefront: "in", language: "hi")
    static let greece = OriginalNameTarget(storefront: "gr", language: "el")
    static let bulgaria = OriginalNameTarget(storefront: "bg", language: "bg")

    private static let latinIsrcCountries: Set<String> = [
        "US", "QM", "QZ", "GB", "UK", "CA", "AU", "NZ", "IE",
        "SE", "NO", "DK", "FI", "IS", "NL", "BE", "DE", "AT", "CH",
        "FR", "IT", "ES", "PT", "BR", "MX", "AR", "CO", "CL", "PL", "CZ", "ZA",
    ]

    /// The storefront [region] asks for an item's names, or nil when it keeps the shown ones. `.original`
    /// goes by the item's own language ([detect]); a fixed `.storefront` asks its storefront for everything.
    static func forRegion(_ region: AppleMusicNameRegion, title: String?, artist: String?, genre: String?,
                          isrc: String?) -> OriginalNameTarget? {
        switch region {
        case .off: nil
        case .original: detect(title: title, artist: artist, genre: genre, isrc: isrc)
        case .storefront(let option): OriginalNameTarget(storefront: option.code, language: option.language)
        }
    }

    /// The language a song was released in, as well as its metadata tells: its genre first (J-Pop,
    /// Mandopop…, in English, Chinese, or Japanese as the storefront writes them), then the country its ISRC
    /// was registered in (including Western/Latin countries when the shown text was transliterated into
    /// Katakana/Hangul/Han), then Katakana transliteration of Western names, then the script its shown names
    /// are written in.
    static func detect(title: String?, artist: String?, genre: String?, isrc: String?) -> OriginalNameTarget? {
        let shown = "\(title ?? "") \(artist ?? "")"
        let byGenre = forGenre(genre)
        let byIsrc = forIsrc(isrc)
        if let byGenre {
            let resolved = (isChinese(byGenre) && byIsrc.map(isChinese) == true) ? byIsrc! : byGenre
            return (isChinese(resolved) && !needsChineseRecovery(title: title, artist: artist)) ? nil : resolved
        }
        if let byIsrc {
            if byIsrc == .unitedStates {
                return shown.unicodeScalars.contains(where: isNonLatinLetter) ? .unitedStates : nil
            }
            if isChinese(byIsrc) && !needsChineseRecovery(title: title, artist: artist) { return nil }
            return byIsrc
        }
        if looksLikeKatakanaTransliteration(shown) { return .unitedStates }
        return forScript(shown)
    }

    static func forGenre(_ genre: String?) -> OriginalNameTarget? {
        let g = genre?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        guard !g.isEmpty else { return nil }
        func has(_ markers: String...) -> Bool { markers.contains { g.contains($0) } }
        if has("j-pop", "j-rock", "japanese", "anime", "enka", "kayokyoku",
               "日本流行", "日语流行", "日語流行", "动漫", "動漫",
               "アニメ", "邦楽", "日本のポップ", "演歌", "歌謡曲") { return .japan }
        if has("k-pop", "korean", "韩国流行", "韓國流行", "韩语流行", "韓語流行", "韓国") { return .korea }
        if has("cantopop", "hong kong", "粤语流行", "粵語流行", "カントポップ", "広東ポップ") { return .hongKong }
        if has("mandopop", "chinese", "c-pop",
               "国语流行", "國語流行", "华语流行", "華語流行", "中文流行", "マンドポップ") { return .mainlandChina }
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
        guard let prefix = isrc?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(2).uppercased(),
              prefix.count == 2 else { return nil }
        switch prefix {
        case "JP": return .japan
        case "KR": return .korea
        case "CN": return .mainlandChina
        case "TW": return .taiwan
        case "HK", "MO": return .hongKong
        default: return latinIsrcCountries.contains(prefix) ? .unitedStates : nil
        }
    }

    /// Kana is Japanese and Hangul Korean. Han alone is shared across Japanese Kanji ("藤井 風"),
    /// Traditional Chinese ("張懸"), and Simplified Chinese, so Han without Chinese genre/ISRC stays put.
    static func forScript(_ text: String) -> OriginalNameTarget? {
        let scalars = text.unicodeScalars
        if scalars.contains(where: isKana) { return .japan }
        if scalars.contains(where: isHangul) { return .korea }
        return nil
    }

    /// Whether names a storefront answered for [target] are worth showing instead of the shown ones. A fixed
    /// region shows whatever its storefront calls the item. `.original` asks for names in the item's own
    /// script: for `.unitedStates`, accept a Latin name that drops Katakana/Hangul/Han transliteration; for
    /// `.korea` artist names, keep official international names (NewJeans, ROSÉ, IVE, ILLIT); for Chinese
    /// targets, only replace text without existing Han characters (protecting Japanese Kanji and Traditional
    /// Chinese from being simplified).
    static func accepts(_ region: AppleMusicNameRegion, target: OriginalNameTarget, shown: String?, resolved: String?,
                        isArtist: Bool = false) -> Bool {
        let answer = resolved?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if answer.isEmpty || answer == shown?.trimmingCharacters(in: .whitespacesAndNewlines) { return false }
        if region != .original { return true }
        if target == .unitedStates {
            let shownText = shown?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let answerNonLatin = answer.unicodeScalars.filter(isNonLatinLetter).count
            let shownNonLatin = shownText.unicodeScalars.filter(isNonLatinLetter).count
            return answer.unicodeScalars.contains(where: isLatinLetter) && answerNonLatin < shownNonLatin
        }
        if isArtist && target == .korea { return false }
        guard answer.unicodeScalars.contains(where: isNonLatinLetter) else { return false }
        if isChinese(target) {
            let shownText = shown?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return answer.unicodeScalars.contains(where: isHan) && !shownText.unicodeScalars.contains(where: isHan)
        }
        return true
    }

    private static func isChinese(_ target: OriginalNameTarget) -> Bool {
        target == .mainlandChina || target == .taiwan || target == .hongKong
    }

    private static func needsChineseRecovery(title: String?, artist: String?) -> Bool {
        let parts = [title, artist].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        return parts.isEmpty || parts.contains { !$0.unicodeScalars.contains(where: isHan) }
    }

    // MARK: Scripts (Java's Character.UnicodeScript ranges, the parts names use)

    static func isHiragana(_ s: Unicode.Scalar) -> Bool { (0x3040...0x309F).contains(s.value) }

    static func isKatakana(_ s: Unicode.Scalar) -> Bool {
        (0x30A1...0x30FA).contains(s.value) || (0x31F0...0x31FF).contains(s.value)
    }

    static func isKana(_ s: Unicode.Scalar) -> Bool {
        (0x3040...0x30FF).contains(s.value) || (0x31F0...0x31FF).contains(s.value)
    }

    static func looksLikeKatakanaTransliteration(_ text: String) -> Bool {
        let scalars = text.unicodeScalars
        if scalars.contains(where: isHiragana) || scalars.contains(where: isHan) || scalars.contains(where: isHangul) {
            return false
        }
        return scalars.contains(where: isKatakana)
    }

    static func isLatinLetter(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
            return latinRanges.contains { $0.contains(s.value) }
        default:
            return false
        }
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
        regions = stored.compactMapValues { key -> AppleMusicNameRegion? in
            let parsed = AppleMusicNameRegion.from(key: key)
            return parsed == .default ? nil : parsed
        }
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
/// the storefronts are asked at once, each group one request per batch of catalog ids in turn
/// (`GET /v1/catalog/{storefront}/songs?ids=…&l=…`), and
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
        case songs, albums, artists
        /// Apple's most ids per request for the type.
        var batch: Int {
            switch self {
            case .songs: 300
            case .albums: 100
            case .artists: 25
            }
        }
    }

    /// Items whose names are kept: a large playlist's worth.
    static let cacheSize = 4000
    /// How long a list waits on one batch of names before it opens under the names it has.
    static let batchTimeout: Duration = .seconds(4)
    /// Bounded parallelism for multi-batch region lookups.
    static let maxConcurrentBatches = 4

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
            out.artist = Self.pick(region, request.target, s.artist, found.artist, isArtist: true)
            out.album = Self.pick(region, request.target, s.album, found.album)
            return out
        }
    }

    func albums(_ region: AppleMusicNameRegion, accountStorefront: String?, _ albums: [AMAlbum],
                isrcByAlbumId: [String: String] = [:]) async -> [AMAlbum] {
        guard region != .off, !albums.isEmpty else { return albums }
        var requests: [String: Request] = [:]
        for a in albums {
            // An album's genre says its language as a song's does; album pages also pass a unanimous track ISRC.
            guard let id = a.catalogId,
                  let target = OriginalNameTarget.forRegion(
                      region, title: a.title, artist: a.artist, genre: a.genre, isrc: isrcByAlbumId[id]
                  ),
                  target.storefront != accountStorefront else { continue }
            requests[id] = Request(id: id, target: target, isrc: nil)
        }
        guard !requests.isEmpty else { return albums }
        let names = await resolve(.albums, Array(requests.values))
        return albums.map { a in
            guard let id = a.catalogId, let request = requests[id], let found = names[id] else { return a }
            var out = a
            out.title = Self.pick(region, request.target, a.title, found.title)
            out.artist = Self.pick(region, request.target, a.artist, found.artist, isArtist: true)
            return out
        }
    }

    func artists(_ region: AppleMusicNameRegion, accountStorefront: String?, _ artists: [AMArtist]) async -> [AMArtist] {
        guard region != .off, !artists.isEmpty else { return artists }
        var requests: [String: Request] = [:]
        for artist in artists {
            guard let id = artist.catalogId,
                  !id.isEmpty, id.allSatisfy(\.isNumber),
                  let target = OriginalNameTarget.forRegion(region, title: nil, artist: artist.name, genre: artist.genre, isrc: nil),
                  target.storefront != accountStorefront else { continue }
            if region == .original && target == .korea { continue }
            requests[id] = Request(id: id, target: target, isrc: nil)
        }
        guard !requests.isEmpty else { return artists }
        let names = await resolve(.artists, Array(requests.values))
        return artists.map { artist in
            guard let id = artist.catalogId, let request = requests[id], let found = names[id] else { return artist }
            var out = artist
            out.name = Self.pick(region, request.target, artist.name, found.title, isArtist: true)
            return out
        }
    }

    /// An album page: the album and its tracklist, renamed together with consistent header/track artist names.
    func album(_ region: AppleMusicNameRegion, accountStorefront: String?, _ album: AMAlbum) async -> AMAlbum {
        guard region != .off else { return album }
        var isrcMap: [String: String] = [:]
        if let albumId = album.catalogId, let unanimous = Self.unanimousTrackIsrc(album.tracks) {
            isrcMap[albumId] = unanimous
        }
        async let tracks = songs(region, accountStorefront: accountStorefront, album.tracks)
        var out = await albums(region, accountStorefront: accountStorefront, [album], isrcByAlbumId: isrcMap)[0]
        let renamedTracks = await tracks
        out.tracks = renamedTracks
        if out.artist == album.artist, !album.artist.isEmpty,
           let consensus = Self.consensusTrackArtist(originalArtist: album.artist, before: album.tracks, after: renamedTracks) {
            out.artist = consensus
        }
        return out
    }

    private static func unanimousTrackIsrc(_ tracks: [AMSong]) -> String? {
        let isrcs = tracks.compactMap(\.isrc).filter { !$0.isEmpty }
        guard !isrcs.isEmpty else { return nil }
        let targets = Set(isrcs.compactMap(OriginalNameTarget.forIsrc))
        return targets.count == 1 ? isrcs.first : nil
    }

    private static func consensusTrackArtist(originalArtist: String, before: [AMSong], after: [AMSong]) -> String? {
        let renamed = Set(zip(before, after).compactMap { prev, next -> String? in
            guard prev.artist == originalArtist, !next.artist.isEmpty, next.artist != originalArtist else { return nil }
            return next.artist
        })
        return renamed.count == 1 ? renamed.first : nil
    }

    private static func pick(_ region: AppleMusicNameRegion, _ target: OriginalNameTarget, _ shown: String, _ found: String?,
                             isArtist: Bool = false) -> String {
        guard OriginalNameTarget.accepts(region, target: target, shown: shown, resolved: found, isArtist: isArtist),
              let found else { return shown }
        return found.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Catalog id → its names, for every request that has an answer (cached or read now). The storefronts
    /// are asked at once (Android: one async per target, awaited together), so a mixed list waits for its
    /// slowest storefront rather than for all of them one after another.
    private func resolve(_ kind: Kind, _ requests: [Request]) async -> [String: Names] {
        let groups = Dictionary(grouping: requests, by: \.target)
        // One task a storefront, all started before any is awaited; the caller's cancellation reaches them all.
        let tasks = groups.map { target, share in
            Task { @MainActor [self] in await self.resolve(kind, target, share) }
        }
        return await withTaskCancellationHandler {
            var known: [String: Names] = [:]
            for t in tasks {
                let found = await t.value
                known.merge(found) { _, new in new }
            }
            return known
        } onCancel: {
            for t in tasks { t.cancel() }
        }
    }

    /// One storefront's share of [resolve]: cached answers, then the rest in bounded concurrent waves,
    /// caching each finished batch without letting one timed-out batch abort the rest.
    private func resolve(_ kind: Kind, _ target: OriginalNameTarget, _ requests: [Request]) async -> [String: Names] {
        var known: [String: Names] = [:]
        var unread: [Request] = []
        for request in requests {
            let key = Self.key(kind, target, request.id)
            if let hit = cache[key] {
                if let names = hit { known[request.id] = names }
            } else {
                unread.append(request)
            }
        }
        guard !unread.isEmpty else { return known }
        let batches = stride(from: 0, to: unread.count, by: kind.batch).map { start in
            Array(unread[start..<min(start + kind.batch, unread.count)])
        }
        for waveStart in stride(from: 0, to: batches.count, by: Self.maxConcurrentBatches) {
            guard !Task.isCancelled else { break }
            let wave = batches[waveStart..<min(waveStart + Self.maxConcurrentBatches, batches.count)]
            let tasks = wave.map { batch in
                (batch, Task { @MainActor [self] in await self.readBatch(kind, target, batch) })
            }
            let waveResults: [([Request], [String: Names]?)] = await withTaskCancellationHandler {
                var results: [([Request], [String: Names]?)] = []
                for (batch, task) in tasks {
                    results.append((batch, await task.value))
                }
                return results
            } onCancel: {
                for (_, task) in tasks { task.cancel() }
            }
            for (batch, read) in waveResults {
                guard let read else { continue }
                for request in batch { store(Self.key(kind, target, request.id), read[request.id]) }
                known.merge(read) { _, new in new }
            }
        }
        return known
    }

    /// One batch's names: by id, then the songs missing by ISRC (timed out separately so id answers stand).
    private func readBatch(_ kind: Kind, _ target: OriginalNameTarget, _ requests: [Request]) async -> [String: Names]? {
        guard var found = await withTimeout({ [self] in
            try await self.catalog(target.storefront, kind.rawValue, requests.map(\.id), target.language)
        }) else { return nil }
        // A storefront may carry a song under an id of its own; the recording's ISRC still finds it.
        if kind == .songs {
            let missing = requests.filter { found[$0.id] == nil && $0.isrc != nil }
            if !missing.isEmpty {
                // The ids' answer stands even when this second read fails or times out.
                let songs = await withTimeout({ [self] in
                    try await self.byIsrc(target.storefront, missing.compactMap(\.isrc), target.language)
                }) ?? [:]
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
