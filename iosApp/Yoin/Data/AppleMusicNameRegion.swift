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
               "国语流行", "國語流行", "华语流行", "華語流行", "中文流行", "マンドポップ") { return .taiwan }
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
        let shownText = shown?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if answer.isEmpty || answer == shownText { return false }
        if region != .original { return true }
        if isArtist && (isCompilationArtist(shownText) || isCompilationArtist(answer)) {
            let answerNonLatin = answer.unicodeScalars.filter(isNonLatinLetter).count
            let shownNonLatin = shownText.unicodeScalars.filter(isNonLatinLetter).count
            return isCompilationArtist(shownText) && isCompilationArtist(answer) &&
                answer.unicodeScalars.contains(where: isLatinLetter) && answerNonLatin < shownNonLatin
        }
        if target == .unitedStates {
            let answerNonLatin = answer.unicodeScalars.filter(isNonLatinLetter).count
            let shownNonLatin = shownText.unicodeScalars.filter(isNonLatinLetter).count
            return answer.unicodeScalars.contains(where: isLatinLetter) && answerNonLatin < shownNonLatin
        }
        if isArtist && target == .korea { return false }
        guard answer.unicodeScalars.contains(where: isNonLatinLetter) else { return false }
        if isChinese(target) {
            return answer.unicodeScalars.contains(where: isHan) && !shownText.unicodeScalars.contains(where: isHan)
        }
        return true
    }

    static func isCompilationArtist(_ name: String?) -> Bool {
        let normalized = name?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        return !normalized.isEmpty && compilationArtists.contains(normalized)
    }

    private static let compilationArtists: Set<String> = [
        "various artists", "various", "ヴァリアス・アーティスト", "群星", "群星/合辑", "合辑", "合輯",
    ]

    static func isKatakanaSpeculative(title: String?, artist: String?, genre: String?, isrc: String?) -> Bool {
        if forGenre(genre) != nil || forIsrc(isrc) != nil { return false }
        return looksLikeKatakanaTransliteration("\(title ?? "") \(artist ?? "")")
    }

    static func isMandopopGeneric(genre: String?, isrc: String?) -> Bool {
        if let byIsrc = forIsrc(isrc), isChinese(byIsrc) { return false }
        return forGenre(genre) == .taiwan
    }

    static func hasJapaneseScript(_ text: String) -> Bool {
        text.unicodeScalars.contains { isHiragana($0) || isHan($0) }
    }

    static func isChinese(_ target: OriginalNameTarget) -> Bool {
        target == .mainlandChina || target == .taiwan || target == .hongKong
    }

    static func hasNonLatin(_ text: String) -> Bool {
        text.unicodeScalars.contains(where: isNonLatinLetter)
    }

    static func hasHanText(_ text: String) -> Bool {
        text.unicodeScalars.contains(where: isHan)
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
        if scalars.contains(where: isHan) || scalars.contains(where: isHangul) {
            return false
        }
        var katakana = 0
        var hiragana = 0
        for s in scalars {
            if isKatakana(s) { katakana += 1 }
            else if isHiragana(s) { hiragana += 1 }
        }
        if katakana == 0 { return false }
        if hiragana == 0 { return true }
        return hiragana == 1 && katakana >= 2 && text.contains("・")
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

    /// Sendable projection of one Apple Music catalog resource (`attributes` + `relationships`/`views` metadata)
    /// used to verify Japanese origin on speculative Katakana lookups and recover missed regional-edition albums.
    struct CatalogResourceInfo: Sendable {
        var id: String
        var names: Names
        var genre: String?
        var artistIds: [String]
        var nestedTrackIsrcs: [String]
        var nestedTrackGenres: [String]
        var nestedTrackNames: [String]
    }

    /// One catalog read: `/v1/catalog/{storefront}/{kind}?ids=…&l=…`, answered with its `data` resources'
    /// names by id.
    typealias Catalog = @MainActor (_ storefront: String, _ kind: String, _ ids: [String], _ language: String)
        async throws -> [String: Names]
    /// Raw catalog read with arbitrary query parameters (`views=top-songs`, `include=artists,tracks`),
    /// returning Sendable resource projections.
    typealias CatalogResources = @MainActor (_ storefront: String, _ kind: String, _ query: [String: String])
        async throws -> [CatalogResourceInfo]
    /// Catalog songs by ISRC in a storefront and language: ISRC → the first song's names.
    typealias ByIsrc = @MainActor (_ storefront: String, _ isrcs: [String], _ language: String) async throws -> [String: Names]

    private struct SourceAlbumFallback: Sendable {
        var artistIds: [String]
        var firstTrackIsrc: String?
        var preferredChineseTarget: OriginalNameTarget?
        var hasJapaneseScript: Bool
        var genre: String?
        var tracksInspected: Bool
    }

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
        /// Smaller batch size when embedding `tracks` / `top-songs`.
        var inspectBatch: Int {
            switch self {
            case .songs: 300
            case .albums: 15
            case .artists: 10
            }
        }
    }

    /// Items whose names are kept: a large playlist's worth.
    static let cacheSize = 4000
    /// How long a list waits on one batch of names before it opens under the names it has.
    static let batchTimeout: Duration = .seconds(6)
    /// Bounded parallelism for multi-batch region lookups.
    static let maxConcurrentBatches = 4
    private static let artistsOnlyAlbumsBatch = 50

    private let catalog: Catalog
    private let catalogResources: CatalogResources?
    private let byIsrc: ByIsrc
    private let timeout: Duration
    private var cache: [String: Names?] = [:]
    private var order: [String] = []
    private var sourceAlbumCache: [String: SourceAlbumFallback?] = [:]
    private var artistNameCache: [String: String] = [:]

    init(timeout: Duration = AppleMusicNameResolver.batchTimeout,
         catalog: @escaping Catalog,
         byIsrc: @escaping ByIsrc,
         catalogResources: CatalogResources? = nil) {
        self.catalog = catalog
        self.catalogResources = catalogResources
        self.byIsrc = byIsrc
        self.timeout = timeout
    }

    private struct Request: Sendable {
        var id: String
        var target: OriginalNameTarget
        var isrc: String?
        var verifyNotJapanese: Bool = false
        var checkChineseRegion: Bool = false
        var needsInspection: Bool { verifyNotJapanese || checkChineseRegion }
    }

    private struct ResolvedBatch: Sendable {
        var names: [String: Names]
        var targets: [String: OriginalNameTarget]
    }

    private struct ReadOutcome: Sendable {
        var found: [String: Names]
        var reclassified: [String: OriginalNameTarget]
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
        let names = await resolveWithTargets(.songs, Array(requests.values)).names
        return songs.map { s in
            guard let id = s.catalogId, let request = requests[id], let found = names[id] else { return s }
            var out = s
            out.title = Self.pick(region, request.target, s.title, found.title)
            out.artist = Self.pick(region, request.target, s.artist, found.artist, isArtist: true)
            out.album = Self.pick(region, request.target, s.album, found.album)
            rememberArtistName(region, shown: s.artist, resolved: out.artist)
            return out
        }
    }

    func albums(_ region: AppleMusicNameRegion, accountStorefront: String?, _ albums: [AMAlbum],
                isrcByAlbumId: [String: String] = [:]) async -> [AMAlbum] {
        guard region != .off, !albums.isEmpty else { return albums }
        var requests: [String: Request] = [:]
        for a in albums {
            guard let id = a.catalogId else { continue }
            let isrc = isrcByAlbumId[id]
            guard let target = OriginalNameTarget.forRegion(
                      region, title: a.title, artist: a.artist, genre: a.genre, isrc: isrc
                  ),
                  target.storefront != accountStorefront else { continue }
            let speculative = region == .original && target == .unitedStates &&
                OriginalNameTarget.isKatakanaSpeculative(title: a.title, artist: a.artist, genre: a.genre, isrc: isrc)
            let checkChinese = region == .original && target == .taiwan &&
                OriginalNameTarget.isMandopopGeneric(genre: a.genre, isrc: isrc)
            requests[id] = Request(
                id: id,
                target: target,
                isrc: nil,
                verifyNotJapanese: speculative,
                checkChineseRegion: checkChinese
            )
        }
        guard !requests.isEmpty else { return albums }
        let resolved = await resolveWithTargets(.albums, Array(requests.values))
        var names = resolved.names
        var effectiveTarget = resolved.targets
        let missed = requests.values.filter { names[$0.id] == nil }
        if !missed.isEmpty, let accountStorefront, !accountStorefront.isEmpty, catalogResources != nil {
            let albumsById = Dictionary(albums.compactMap { a in a.catalogId.map { ($0, a) } }, uniquingKeysWith: { a, _ in a })
            let recovered = await resolveMissedAlbumsViaSourceStorefront(
                region: region, accountStorefront: accountStorefront, missed: missed, albumsById: albumsById
            )
            for (id, pair) in recovered {
                names[id] = pair.0
                effectiveTarget[id] = pair.1
            }
        }
        for a in albums {
            if let id = a.catalogId, let target = effectiveTarget[id], let found = names[id] {
                let picked = Self.pick(region, target, a.artist, found.artist, isArtist: true)
                rememberArtistName(region, shown: a.artist, resolved: picked)
            }
        }
        return albums.map { a in
            if let id = a.catalogId, let target = effectiveTarget[id], let found = names[id] {
                var out = a
                out.title = Self.pick(region, target, a.title, found.title)
                let picked = Self.pick(region, target, a.artist, found.artist, isArtist: true)
                out.artist = (picked != a.artist) ? picked : (cachedArtistName(region, shown: a.artist) ?? picked)
                return out
            }
            if let cached = cachedArtistName(region, shown: a.artist) {
                var out = a
                out.artist = cached
                return out
            }
            return a
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
            let speculative = region == .original && target == .unitedStates &&
                OriginalNameTarget.isKatakanaSpeculative(title: nil, artist: artist.name, genre: artist.genre, isrc: nil)
            let checkChinese = region == .original && target == .taiwan &&
                OriginalNameTarget.isMandopopGeneric(genre: artist.genre, isrc: nil)
            requests[id] = Request(
                id: id,
                target: target,
                isrc: nil,
                verifyNotJapanese: speculative,
                checkChineseRegion: checkChinese
            )
        }
        guard !requests.isEmpty else { return artists }
        let resolved = await resolveWithTargets(.artists, Array(requests.values))
        return artists.map { artist in
            guard let id = artist.catalogId, let request = requests[id], let found = resolved.names[id] else { return artist }
            let target = resolved.targets[id] ?? request.target
            var out = artist
            out.name = Self.pick(region, target, artist.name, found.title, isArtist: true)
            rememberArtistName(region, shown: artist.name, resolved: out.name)
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

    private func rememberArtistName(_ region: AppleMusicNameRegion, shown: String, resolved: String) {
        let s = shown.trimmingCharacters(in: .whitespacesAndNewlines)
        let r = resolved.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty, !r.isEmpty, s != r else { return }
        guard !OriginalNameTarget.isCompilationArtist(s), !OriginalNameTarget.isCompilationArtist(r) else { return }
        artistNameCache["\(region.key)|\(s)"] = r
    }

    private func cachedArtistName(_ region: AppleMusicNameRegion, shown: String) -> String? {
        let s = shown.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty, !OriginalNameTarget.isCompilationArtist(s) else { return nil }
        return artistNameCache["\(region.key)|\(s)"]
    }

    private func resolveWithTargets(_ kind: Kind, _ requests: [Request]) async -> ResolvedBatch {
        var seenIds = Set<String>()
        let distinctRequests = requests.filter { seenIds.insert($0.id).inserted }
        let groups = Dictionary(grouping: distinctRequests, by: \.target)
        var finalNames: [String: Names] = [:]
        var finalTargets: [String: OriginalNameTarget] = Dictionary(
            distinctRequests.map { ($0.id, $0.target) },
            uniquingKeysWith: { existing, _ in existing }
        )
        var reclassified: [Request] = []

        let tasks = groups.map { target, share in
            Task { @MainActor [self] in await self.resolveGroup(kind, target, share) }
        }
        let groupResults: [(OriginalNameTarget, [String: Names], [Request])] = await withTaskCancellationHandler {
            var out: [(OriginalNameTarget, [String: Names], [Request])] = []
            for t in tasks { out.append(await t.value) }
            return out
        } onCancel: {
            for t in tasks { t.cancel() }
        }
        for (_, found, reclass) in groupResults {
            finalNames.merge(found) { _, new in new }
            reclassified.append(contentsOf: reclass)
        }

        if !reclassified.isEmpty {
            let reclassGroups = Dictionary(grouping: reclassified, by: \.target)
            let reTasks = reclassGroups.map { target, share in
                Task { @MainActor [self] in await self.resolveGroup(kind, target, share) }
            }
            for t in reTasks {
                let (target, found, _) = await t.value
                for (id, names) in found {
                    finalNames[id] = names
                    finalTargets[id] = target
                }
            }
        }
        return ResolvedBatch(names: finalNames, targets: finalTargets)
    }

    private func resolveGroup(_ kind: Kind, _ target: OriginalNameTarget, _ requests: [Request]) async -> (OriginalNameTarget, [String: Names], [Request]) {
        var known: [String: Names] = [:]
        var unreadPlain: [Request] = []
        var unreadInspected: [Request] = []
        for request in requests {
            let key = Self.key(kind, target, request.id)
            if let hit = cache[key] {
                if let names = hit { known[request.id] = names }
            } else if request.needsInspection && kind != .songs {
                unreadInspected.append(request)
            } else {
                unreadPlain.append(request)
            }
        }
        let plainBatches = stride(from: 0, to: unreadPlain.count, by: kind.batch).map { start in
            (Array(unreadPlain[start..<min(start + kind.batch, unreadPlain.count)]), false)
        }
        let inspectedBatches = stride(from: 0, to: unreadInspected.count, by: kind.inspectBatch).map { start in
            (Array(unreadInspected[start..<min(start + kind.inspectBatch, unreadInspected.count)]), true)
        }
        let allBatches = plainBatches + inspectedBatches
        var reclassified: [Request] = []

        for waveStart in stride(from: 0, to: allBatches.count, by: Self.maxConcurrentBatches) {
            guard !Task.isCancelled else { break }
            let wave = allBatches[waveStart..<min(waveStart + Self.maxConcurrentBatches, allBatches.count)]
            let tasks = wave.map { batch, inspect in
                (batch, Task { @MainActor [self] in await self.readBatch(kind, target, batch, inspect: inspect) })
            }
            let waveResults: [([Request], ReadOutcome?)] = await withTaskCancellationHandler {
                var results: [([Request], ReadOutcome?)] = []
                for (batch, task) in tasks {
                    results.append((batch, await task.value))
                }
                return results
            } onCancel: {
                for (_, task) in tasks { task.cancel() }
            }
            for (batch, outcome) in waveResults {
                guard let outcome else { continue }
                for request in batch where outcome.reclassified[request.id] == nil {
                    store(Self.key(kind, target, request.id), outcome.found[request.id])
                }
                known.merge(outcome.found) { _, new in new }
                for (id, newTarget) in outcome.reclassified {
                    reclassified.append(Request(id: id, target: newTarget, isrc: nil))
                }
            }
        }

        if kind == .songs {
            let missing = unreadPlain.filter { known[$0.id] == nil && $0.isrc != nil }
            if !missing.isEmpty {
                let songs = await withTimeout({ [self] in
                    try await self.byIsrc(target.storefront, missing.compactMap(\.isrc), target.language)
                }) ?? [:]
                for request in missing {
                    if let isrc = request.isrc, let names = songs[isrc] {
                        known[request.id] = names
                        store(Self.key(kind, target, request.id), names)
                    }
                }
            }
        }
        return (target, known, reclassified)
    }

    private func readBatch(_ kind: Kind, _ target: OriginalNameTarget, _ requests: [Request], inspect: Bool) async -> ReadOutcome? {
        let verifyIds = inspect ? Set(requests.filter(\.verifyNotJapanese).map(\.id)) : []
        let checkChineseIds = inspect ? Set(requests.filter(\.checkChineseRegion).map(\.id)) : []
        if (!verifyIds.isEmpty || !checkChineseIds.isEmpty), let catalogResources, kind == .artists || kind == .albums {
            var query: [String: String] = ["ids": requests.map(\.id).joined(separator: ","), "l": target.language]
            if kind == .artists { query["views"] = "top-songs" }
            if kind == .albums { query["include"] = "tracks" }
            guard let resources = await withTimeoutResources({
                try await catalogResources(target.storefront, kind.rawValue, query)
            }) else { return nil }
            return Self.extractOutcome(
                in: resources,
                target: target,
                verifyNonWesternIds: verifyIds,
                checkChineseIds: checkChineseIds
            )
        } else {
            guard let map = await withTimeout({ [self] in
                try await self.catalog(target.storefront, kind.rawValue, requests.map(\.id), target.language)
            }) else { return nil }
            return ReadOutcome(found: map, reclassified: [:])
        }
    }

    private func resolveMissedAlbumsViaSourceStorefront(
        region: AppleMusicNameRegion,
        accountStorefront: String,
        missed: [Request],
        albumsById: [String: AMAlbum]
    ) async -> [String: (Names, OriginalNameTarget)] {
        var withTracksIds: [String] = []
        var artistsOnlyIds: [String] = []
        if region == .original {
            for req in missed {
                let title = albumsById[req.id]?.title ?? ""
                let titleNeedsRecovery =
                    (req.target == .unitedStates && OriginalNameTarget.hasNonLatin(title)) ||
                    (OriginalNameTarget.isChinese(req.target) && !OriginalNameTarget.hasHanText(title))
                if req.verifyNotJapanese || req.checkChineseRegion || titleNeedsRecovery {
                    withTracksIds.append(req.id)
                } else {
                    artistsOnlyIds.append(req.id)
                }
            }
        } else {
            artistsOnlyIds = missed.map(\.id)
        }
        let withTracks = withTracksIds.isEmpty ? [:] : await fetchSourceAlbumFallbacks(
            accountStorefront: accountStorefront, albumIds: withTracksIds, includeTracks: true
        )
        let artistsOnly = artistsOnlyIds.isEmpty ? [:] : await fetchSourceAlbumFallbacks(
            accountStorefront: accountStorefront, albumIds: artistsOnlyIds, includeTracks: false
        )
        let sourceInfo = withTracks.merging(artistsOnly) { a, _ in a }
        guard !sourceInfo.isEmpty else { return [:] }

        struct Plan {
            var albumId: String
            var target: OriginalNameTarget
            var artistIds: [String]
            var isrc: String?
            var needsTitleByIsrc: Bool
            var verifyArtistNotJapanese: Bool
            var checkChineseRegion: Bool
        }

        var plans: [Plan] = []
        for req in missed {
            guard let info = sourceInfo[req.id] else { continue }
            let album = albumsById[req.id]
            let effectiveTarget: OriginalNameTarget
            if region == .original {
                let byIsrc = OriginalNameTarget.forIsrc(info.firstTrackIsrc)
                let byGenre = OriginalNameTarget.forGenre(info.genre)
                if let preferred = info.preferredChineseTarget {
                    effectiveTarget = preferred
                } else if let byIsrc, byIsrc.storefront == accountStorefront {
                    continue
                } else if byIsrc == .unitedStates {
                    effectiveTarget = .unitedStates
                } else if let byIsrc {
                    effectiveTarget = byIsrc
                } else if let byGenre, byGenre.storefront == accountStorefront {
                    continue
                } else if req.verifyNotJapanese && info.hasJapaneseScript {
                    continue
                } else {
                    effectiveTarget = req.target
                }
            } else {
                effectiveTarget = req.target
            }
            guard effectiveTarget.storefront != accountStorefront else { continue }
            if region == .original && effectiveTarget == .korea { continue }

            let title = album?.title ?? ""
            let needsTitle = region == .original && (
                (effectiveTarget == .unitedStates && OriginalNameTarget.hasNonLatin(title)) ||
                (OriginalNameTarget.isChinese(effectiveTarget) && !OriginalNameTarget.hasHanText(title))
            )
            let confirmedNonJp = info.firstTrackIsrc != nil && OriginalNameTarget.forIsrc(info.firstTrackIsrc) != nil
            let artistIds = info.artistIds.filter { !$0.isEmpty && $0.allSatisfy(\.isNumber) }
            plans.append(Plan(
                albumId: req.id,
                target: effectiveTarget,
                artistIds: artistIds,
                isrc: info.firstTrackIsrc,
                needsTitleByIsrc: needsTitle && !(info.firstTrackIsrc?.isEmpty ?? true),
                verifyArtistNotJapanese: req.verifyNotJapanese && !confirmedNonJp,
                checkChineseRegion: req.checkChineseRegion && info.preferredChineseTarget == nil
            ))
        }
        guard !plans.isEmpty else { return [:] }

        var seenArtistIds = Set<String>()
        let artistRequests = plans.flatMap { p in
            p.artistIds.compactMap { artistId -> Request? in
                guard seenArtistIds.insert(artistId).inserted else { return nil }
                return Request(
                    id: artistId,
                    target: p.target,
                    isrc: nil,
                    verifyNotJapanese: p.verifyArtistNotJapanese,
                    checkChineseRegion: p.checkChineseRegion
                )
            }
        }
        let resolvedArtists: ResolvedBatch = artistRequests.isEmpty
            ? ResolvedBatch(names: [:], targets: [:])
            : await resolveWithTargets(.artists, artistRequests)

        var resolvedIsrcs: [OriginalNameTarget: [String: Names]] = [:]
        let isrcGroups = Dictionary(grouping: plans.filter { $0.needsTitleByIsrc && $0.isrc != nil }, by: \.target)
        for (target, group) in isrcGroups {
            let codes = Array(Set(group.compactMap(\.isrc)))
            resolvedIsrcs[target] = await withTimeout({ [self] in
                try await self.byIsrc(target.storefront, codes, target.language)
            }) ?? [:]
        }

        var out: [String: (Names, OriginalNameTarget)] = [:]
        for p in plans {
            let artistParts = p.artistIds.compactMap { resolvedArtists.names[$0]?.title }.filter { !$0.isEmpty }
            let artistName = artistParts.isEmpty ? nil : artistParts.joined(separator: " & ")
            let effectiveTarget = p.artistIds.compactMap { resolvedArtists.targets[$0] }.first ?? p.target
            let songMatch = p.isrc.flatMap { resolvedIsrcs[p.target]?[$0] }
            let title = songMatch?.album
            let artist = artistName ?? songMatch?.artist
            if title != nil || artist != nil {
                let resolved = Names(title: title, artist: artist, album: nil)
                out[p.albumId] = (resolved, effectiveTarget)
                store(Self.key(.albums, effectiveTarget, p.albumId), resolved)
            }
        }
        return out
    }

    private func fetchSourceAlbumFallbacks(
        accountStorefront: String,
        albumIds: [String],
        includeTracks: Bool
    ) async -> [String: SourceAlbumFallback] {
        guard let catalogResources else { return [:] }
        var known: [String: SourceAlbumFallback] = [:]
        var unread: [String] = []
        for id in Array(Set(albumIds)) {
            let k = "\(accountStorefront)|\(id)"
            if let hit = sourceAlbumCache[k] {
                if hit == nil || hit!.tracksInspected || !includeTracks {
                    if let hit { known[id] = hit }
                } else {
                    unread.append(id)
                }
            } else {
                unread.append(id)
            }
        }
        guard !unread.isEmpty else { return known }
        let batchSize = includeTracks ? Kind.albums.inspectBatch : Self.artistsOnlyAlbumsBatch
        let includeValue = includeTracks ? "artists,tracks" : "artists"
        for start in stride(from: 0, to: unread.count, by: batchSize) {
            let batch = Array(unread[start..<min(start + batchSize, unread.count)])
            guard let resources = await withTimeoutResources({
                try await catalogResources(accountStorefront, Kind.albums.rawValue, [
                    "ids": batch.joined(separator: ","),
                    "include": includeValue,
                ])
            }) else { continue }
            var parsed: [String: SourceAlbumFallback] = [:]
            for r in resources {
                parsed[r.id] = SourceAlbumFallback(
                    artistIds: r.artistIds,
                    firstTrackIsrc: r.nestedTrackIsrcs.first,
                    preferredChineseTarget: includeTracks ? Self.preferredChineseTarget(in: r.nestedTrackIsrcs) : nil,
                    hasJapaneseScript: r.nestedTrackNames.contains { OriginalNameTarget.hasJapaneseScript($0) },
                    genre: r.genre,
                    tracksInspected: includeTracks
                )
            }
            for id in batch { sourceAlbumCache["\(accountStorefront)|\(id)"] = parsed[id] }
            known.merge(parsed) { _, new in new }
        }
        return known
    }

    private func withTimeoutResources(_ body: @escaping @Sendable @MainActor () async throws -> [CatalogResourceInfo]) async -> [CatalogResourceInfo]? {
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

    nonisolated static func parseResources(in resources: [AppleMusicJSON.Object]) -> [CatalogResourceInfo] {
        var out: [CatalogResourceInfo] = []
        for r in resources {
            guard let id = r["id"] as? String, let a = r["attributes"] as? AppleMusicJSON.Object else { continue }
            let names = Names(
                title: AppleMusicJSON.string(a, "name"),
                artist: AppleMusicJSON.string(a, "artistName"),
                album: AppleMusicJSON.string(a, "albumName")
            )
            let rels = r["relationships"] as? AppleMusicJSON.Object
            let views = r["views"] as? AppleMusicJSON.Object
            let artistsData = nestedData(rels?["artists"])
            let artistIds = artistsData.compactMap { $0["id"] as? String }
            let tracksData = nestedData(rels?["tracks"]) + nestedData(views?["top-songs"])
            var isrcs: [String] = []
            var trackGenres: [String] = []
            var trackNames: [String] = []
            for track in tracksData {
                guard let tAttrs = track["attributes"] as? AppleMusicJSON.Object else { continue }
                if let code = AppleMusicJSON.string(tAttrs, "isrc") { isrcs.append(code) }
                if let g = genreString(tAttrs) { trackGenres.append(g) }
                if let n = AppleMusicJSON.string(tAttrs, "name") { trackNames.append(n) }
            }
            out.append(CatalogResourceInfo(
                id: id,
                names: names,
                genre: genreString(a),
                artistIds: artistIds,
                nestedTrackIsrcs: isrcs,
                nestedTrackGenres: trackGenres,
                nestedTrackNames: trackNames
            ))
        }
        return out
    }

    private nonisolated static func extractOutcome(
        in resources: [CatalogResourceInfo],
        target: OriginalNameTarget,
        verifyNonWesternIds: Set<String>,
        checkChineseIds: Set<String>
    ) -> ReadOutcome {
        var found: [String: Names] = [:]
        var reclassified: [String: OriginalNameTarget] = [:]
        for r in resources {
            if verifyNonWesternIds.contains(r.id) && isConfirmedNonWesternOrigin(r) {
                continue
            }
            if checkChineseIds.contains(r.id),
               let preferred = preferredChineseTarget(in: r.nestedTrackIsrcs),
               preferred != target {
                reclassified[r.id] = preferred
            }
            found[r.id] = r.names
        }
        return ReadOutcome(found: found, reclassified: reclassified)
    }

    private nonisolated static func preferredChineseTarget(in isrcs: [String]) -> OriginalNameTarget? {
        guard !isrcs.isEmpty else { return nil }
        var cn = 0
        var tw = 0
        var hk = 0
        for code in isrcs {
            switch OriginalNameTarget.forIsrc(code) {
            case .mainlandChina: cn += 1
            case .taiwan: tw += 1
            case .hongKong: hk += 1
            default: break
            }
        }
        if cn > 0 && cn >= tw + hk { return .mainlandChina }
        if hk > 0 && hk > tw && cn == 0 { return .hongKong }
        if tw > 0 { return .taiwan }
        return nil
    }

    private nonisolated static func isConfirmedNonWesternOrigin(_ r: CatalogResourceInfo) -> Bool {
        if let g = OriginalNameTarget.forGenre(r.genre), g != .unitedStates { return true }
        for isrc in r.nestedTrackIsrcs {
            if let t = OriginalNameTarget.forIsrc(isrc), t != .unitedStates { return true }
        }
        for genre in r.nestedTrackGenres {
            if let t = OriginalNameTarget.forGenre(genre), t != .unitedStates { return true }
        }
        for name in r.nestedTrackNames {
            if OriginalNameTarget.hasJapaneseScript(name) { return true }
        }
        return false
    }

    private nonisolated static func nestedData(_ container: Any?) -> [AppleMusicJSON.Object] {
        guard let dict = container as? AppleMusicJSON.Object,
              let list = dict["data"] as? [AppleMusicJSON.Object] else { return [] }
        return list
    }

    private nonisolated static func genreString(_ attrs: AppleMusicJSON.Object?) -> String? {
        guard let list = attrs?["genreNames"] as? [String], !list.isEmpty else { return nil }
        return list.joined(separator: ", ")
    }
}
