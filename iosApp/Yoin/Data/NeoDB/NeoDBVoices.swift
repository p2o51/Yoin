import Foundation

/// What other listeners on NeoDB make of an album: the page "别人怎么说" (Android `NeoDbVoices.kt`, owner
/// 2026-11-09). A reference, not a feature of the album page: the score spread, what people wrote, and ways
/// out (NeoDB, Douban, RYM, AOTY). No comparison with the user's own score.
struct NeoDBAlbumVoices: Equatable, Sendable {
    var itemUuid: String
    /// The item's page on the instance.
    var itemUrl: String
    var title: String
    var rating: Double?
    var ratingCount: Int
    /// Five buckets (1 to 5 stars), percent. Empty when NeoDB gave none.
    var distribution: [Int]
    var doubanUrl: String?
    var discogsUrl: String?
    var musicBrainzUrl: String?
}

/// One listener's words on the album: a short comment, or a review's title and opening.
struct NeoDBVoice: Equatable, Identifiable, Sendable {
    var id: String
    var author: String
    /// user@instance: the instance says where the listener lives (NeoDB is federated).
    var handle: String
    var avatarUrl: String?
    /// 1–10 (NeoDB's grade), or nil when they left words only.
    var rating: Int?
    var text: String
    /// Non-nil for a long review: its title; [text] is then its opening.
    var reviewTitle: String?
    /// The writer folded it (sensitive or a content warning): shown only on a tap.
    var folded: Bool
    var foldNote: String?
    var url: String?
    var createdAtMillis: Int64?

    /// Long words, a review: the whole width; short ones sit two by two (Android `isLong`, 40 characters).
    var isLong: Bool { reviewTitle != nil || text.count > NeoDBVoicesParsing.longVoiceChars }

    /// The writer's name; their instance only when it isn't neodb.social (Android `byline`, its "·" dropped:
    /// the host is a second, quieter label).
    var host: String? {
        guard let at = handle.firstIndex(of: "@") else { return nil }
        let h = String(handle[handle.index(after: at)...])
        return h.isEmpty || h == "neodb.social" ? nil : h
    }
}

struct NeoDBVoicePage: Equatable, Sendable {
    var voices: [NeoDBVoice]
    var page: Int
    var pages: Int
    var count: Int
}

/// A score from another catalogue, shown beside NeoDB's spread for reference.
struct NeoDBReferenceScore: Equatable, Sendable {
    var source: String
    var value: Double
    var scale: Int
    var votes: Int?
    var url: String
}

/// Android `NeoDbVoicesParsing`, line for line.
enum NeoDBVoicesParsing {
    static let searchWindow = 8
    static let reviewOpeningChars = 180
    static let longVoiceChars = 40

    /// Which NeoDB item stands for the album, or nil when NeoDB doesn't seem to have it. NeoDB's search is
    /// loose (for "Museum" by OWIS, which it doesn't hold, it answers with a jazz "Museum" and a dozen other
    /// museums), so a hit must carry the album's title, and the artist must agree whenever both sides name
    /// one; a title that is the other's plus an edition's words needs the artist to agree outright. The same
    /// release often has several items, so among the hits the one most people rated wins (ties: search order).
    static func pickAlbumItem(albumName: String, artistName: String?, items: [NeoDBItem]) -> NeoDBItem? {
        let key = normalizeTitle(albumName)
        guard !key.isEmpty else { return nil }
        let keys = artistKeys(artistName)
        let candidates = items.prefix(searchWindow).filter { !($0.uuid ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
        var exact: [NeoDBItem] = []
        var loose: [NeoDBItem] = []
        for item in candidates {
            let title = normalizeTitle(item.title ?? item.displayTitle ?? "")
            if title.isEmpty { continue }
            let artist = artistAgreement(keys, item.artist)
            if title == key && artist != .differs {
                exact.append(item)
            } else if artist == .same && isEdition(title, of: key) {
                loose.append(item)
            }
        }
        return mostRated(exact) ?? mostRated(loose)
    }

    /// Kotlin `maxByOrNull`: the first of equal counts.
    private static func mostRated(_ items: [NeoDBItem]) -> NeoDBItem? {
        var best: NeoDBItem?
        for item in items where best == nil || (item.ratingCount ?? 0) > (best?.ratingCount ?? 0) { best = item }
        return best
    }

    /// One normalized title is the other followed by an edition's words and nothing else. A title that only
    /// contains the other is another release ("范特西plus", "依然范特西"; "Luv Is Rage 2").
    private static func isEdition(_ a: String, of b: String) -> Bool {
        let (shorter, longer) = a.count <= b.count ? (a, b) : (b, a)
        guard longer.hasPrefix(shorter) else { return false }
        let added = String(longer.dropFirst(shorter.count))
        // A year or an ordinal alone ("… 2019", "… 25th") says too little: it needs a word beside it.
        return fullMatch(editionAdded, added) && contains(editionWord, added)
    }

    /// What an edition adds outside brackets, as normalizeTitle leaves it (no spaces, no punctuation).
    private static let editionWordPattern = "deluxe|super|expanded|extended|special|limited|collectors?|anniversary|" +
        "edition|version|remaster(?:ed)?|reissue|bonus|tracks?|mono|stereo|vinyl|" +
        "豪华|豪華|特别|特別|限定|限量|纪念|紀念|周年|週年|重制|重製|复刻|復刻|经典|經典|专辑|專輯|黑胶|黑膠|版|盤|盘"
    private static let editionWord = regex(editionWordPattern)
    /// Edition words, with a year ("2011 Remaster") or a count ("20th Anniversary", "25周年紀念版") among them.
    private static let editionAdded =
        regex("(?:\(editionWordPattern)|(?:19|20)[0-9]{2}|[0-9]{1,3}(?:st|nd|rd|th|周年|週年))+")

    private enum ArtistAgreement { case same, differs, unknown }

    /// "A, B & C feat. D" → each name, normalized like a title.
    static func artistKeys(_ artistName: String?) -> [String] {
        split(artistSeparator, artistName ?? "").map(normalizeName).filter { !$0.isEmpty }
    }

    private static let artistSeparator =
        regex(#"\s*(?:,|&|/|、|;|＆|，|\bfeat\.?|\bft\.|\bwith\b|\bx\b)\s*"#, caseInsensitive: true)

    private static func artistAgreement(_ keys: [String], _ itemArtists: [String]) -> ArtistAgreement {
        let names = itemArtists.map(normalizeName).filter { !$0.isEmpty }
        if keys.isEmpty || names.isEmpty { return .unknown }
        let same = keys.contains { key in names.contains { $0 == key || $0.contains(key) || key.contains($0) } }
        return same ? .same : .differs
    }

    private static func normalizeName(_ name: String) -> String { lettersAndDigits(name.lowercased()) }

    /// Case, punctuation, spaces and a trailing "(Remastered)" / "[Deluxe]" don't make a different album. A
    /// title made of signs alone ("?", "( )", "÷") keeps them: they are all there is to compare.
    static func normalizeTitle(_ title: String) -> String {
        var t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        while true {
            let stripped = trailingBracket.stringByReplacingMatches(
                in: t, range: NSRange(t.startIndex..., in: t), withTemplate: "")
            if stripped == t || stripped.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { break }
            t = stripped
        }
        let lowered = t.lowercased()
        let kept = lettersAndDigits(lowered)
        return kept.isEmpty ? String(lowered.unicodeScalars.filter { !$0.properties.isWhitespace }.map(Character.init)) : kept
    }

    private static let trailingBracket = regex(#"\s*[(\[（【][^()\[\]（）【】]*[)\]）】]\s*$"#)

    /// Kotlin `filter { it.isLetterOrDigit() }`: letters and decimal digits.
    private static func lettersAndDigits(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for scalar in s.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
                out.append(scalar)
            default:
                break
            }
        }
        return String(out)
    }

    static func albumVoices(instance: String, item: NeoDBItem) -> NeoDBAlbumVoices? {
        guard let uuid = item.uuid, !uuid.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let links = (item.externalResources ?? []).map(\.url)
        return NeoDBAlbumVoices(
            itemUuid: uuid,
            itemUrl: itemUrl(instance: instance, path: item.url, uuid: uuid),
            title: item.title ?? item.displayTitle ?? "",
            rating: item.rating,
            ratingCount: item.ratingCount ?? 0,
            distribution: item.ratingDistribution.flatMap { $0.count == 5 ? $0 : nil } ?? [],
            doubanUrl: links.first { $0.contains("douban.com/") },
            discogsUrl: links.first { $0.contains("discogs.com/master/") } ?? links.first { $0.contains("discogs.com/release/") },
            musicBrainzUrl: links.first { $0.contains("musicbrainz.org/release-group/") }
                ?? links.first { $0.contains("musicbrainz.org/release/") })
    }

    private static func itemUrl(instance: String, path: String?, uuid: String) -> String {
        let base = trimmedBase(instance)
        guard let path, !path.trimmingCharacters(in: .whitespaces).isEmpty else { return "\(base)/album/\(uuid)" }
        if path.hasPrefix("http") { return path }
        return base + (path.hasPrefix("/") ? path : "/" + path)
    }

    static func page(_ page: Int, _ body: NeoDBPostPage) -> NeoDBVoicePage {
        // One voice per id: the list keys its cards by id.
        var seen = Set<String>()
        let voices = body.data.compactMap(voice).filter { seen.insert($0.id).inserted }
        return NeoDBVoicePage(voices: voices, page: page, pages: body.pages, count: body.count)
    }

    /// A post becomes a voice only when it carries words: a Comment's text or a Review.
    static func voice(_ post: NeoDBPost) -> NeoDBVoice? {
        let related = post.relatedWith
        let comment = related.first { $0["type"]?.string == "Comment" }
        let review = related.first { $0["type"]?.string == "Review" }
        let rating = related.first { $0["type"]?.string == "Rating" }?["value"]?.double
            .map { Int($0) }
            .flatMap { (1...10).contains($0) ? $0 : nil }
        let reviewTitle = review?["name"]?.string?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        let text: String = if let review {
            reviewOpening(review["content"]?.string ?? "")
        } else {
            (comment?["content"]?.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if text.isEmpty && reviewTitle == nil { return nil }
        let account = post.account
        let handle = account?.acct ?? ""
        let spoiler = post.spoilerText?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        let id = post.id.isEmpty ? (review?["id"]?.string ?? comment?["id"]?.string ?? "") : post.id
        let author = account?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            ?? account?.username
            ?? String(handle.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        return NeoDBVoice(
            id: id,
            author: author,
            handle: handle,
            avatarUrl: account?.avatar,
            rating: rating,
            text: text,
            reviewTitle: reviewTitle,
            folded: post.sensitive || spoiler != nil,
            foldNote: spoiler,
            url: review?["href"]?.string ?? post.url,
            createdAtMillis: post.createdAt.flatMap(parseInstant))
    }

    private static func parseInstant(_ text: String) -> Int64? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: text) { return Int64((d.timeIntervalSince1970 * 1000).rounded()) }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: text).map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
    }

    /// A review is Markdown: its first lines as plain text, cut at a sentence when it can be.
    static func reviewOpening(_ markdown: String) -> String {
        var s = replace(regex(#"!\[[^\]]*]\([^)]*\)"#), in: markdown, with: "")
        s = replace(regex(#"\[([^\]]*)]\([^)]*\)"#), in: s, with: "$1")
        s = replace(regex("[*_#>`~]+"), in: s, with: "")
        let plain = s.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        if plain.count <= reviewOpeningChars { return plain }
        let cut = String(plain.prefix(reviewOpeningChars))
        if let end = cut.lastIndex(where: { "。！？.!?".contains($0) }),
           cut.distance(from: cut.startIndex, to: end) >= reviewOpeningChars / 2 {
            return String(cut[...end])
        }
        return cut + "…"
    }

    static func rymSearchUrl(albumName: String, artist: String?) -> String {
        "https://rateyourmusic.com/search?searchtype=l&searchterm=" + NeoDBAPI.formEncode(query(albumName: albumName, artist: artist))
    }

    static func aotySearchUrl(albumName: String, artist: String?) -> String {
        "https://www.albumoftheyear.org/search/albums/?q=" + NeoDBAPI.formEncode(query(albumName: albumName, artist: artist))
    }

    static func neoDBSearchUrl(instance: String, albumName: String, artist: String?) -> String {
        trimmedBase(instance) + "/search?c=music&q=" + NeoDBAPI.formEncode(query(albumName: albumName, artist: artist))
    }

    static func query(albumName: String, artist: String?) -> String {
        [albumName.trimmingCharacters(in: .whitespaces), artist?.trimmingCharacters(in: .whitespaces).nonEmpty]
            .compactMap { $0 }
            .joined(separator: " ")
    }

    /// A link the page may hand to the browser. Post and catalogue links come from NeoDB and, through
    /// federation, from any server its users follow: only a web address is opened, never another scheme.
    static func isWebLink(_ url: String) -> Bool {
        let lower = url.lowercased()
        return lower.hasPrefix("https://") || lower.hasPrefix("http://")
    }

    /// `https://www.discogs.com/master/21491-Radiohead-OK-Computer` → ("master", 21491).
    static func discogsId(_ url: String) -> (kind: String, id: Int64)? {
        guard let g = firstGroups(regex(#"discogs\.com/(?:[a-z]{2}/)?(master|release)/([0-9]+)"#), url),
              let id = Int64(g[1]) else { return nil }
        return (g[0], id)
    }

    /// `https://musicbrainz.org/release/<mbid>` → ("release", mbid).
    static func musicBrainzId(_ url: String) -> (kind: String, id: String)? {
        guard let g = firstGroups(regex(#"musicbrainz\.org/(release-group|release)/([0-9a-fA-F-]{36})"#), url) else { return nil }
        return (g[0], g[1])
    }

    // MARK: Regex helpers (Java's regex, through ICU)

    private static func trimmedBase(_ instance: String) -> String {
        var base = instance
        while base.hasSuffix("/") { base.removeLast() }
        return base
    }

    private static func regex(_ pattern: String, caseInsensitive: Bool = false) -> NSRegularExpression {
        // Patterns are literals: a bad one is a programming error.
        try! NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }

    private static func range(_ s: String) -> NSRange { NSRange(s.startIndex..., in: s) }

    private static func fullMatch(_ re: NSRegularExpression, _ s: String) -> Bool {
        guard let m = re.firstMatch(in: s, options: [.anchored], range: range(s)) else { return false }
        return m.range.length == (s as NSString).length
    }

    private static func contains(_ re: NSRegularExpression, _ s: String) -> Bool {
        re.firstMatch(in: s, range: range(s)) != nil
    }

    private static func replace(_ re: NSRegularExpression, in s: String, with template: String) -> String {
        re.stringByReplacingMatches(in: s, range: range(s), withTemplate: template)
    }

    /// Kotlin `String.split(Regex)`: the pieces between matches, empty ones kept.
    private static func split(_ re: NSRegularExpression, _ s: String) -> [String] {
        let ns = s as NSString
        var out: [String] = []
        var start = 0
        for m in re.matches(in: s, range: range(s)) where m.range.length > 0 {
            out.append(ns.substring(with: NSRange(location: start, length: m.range.location - start)))
            start = m.range.location + m.range.length
        }
        out.append(ns.substring(from: start))
        return out
    }

    private static func firstGroups(_ re: NSRegularExpression, _ s: String) -> [String]? {
        guard let m = re.firstMatch(in: s, range: range(s)), m.numberOfRanges >= 3 else { return nil }
        let ns = s as NSString
        return (1..<m.numberOfRanges).map { ns.substring(with: m.range(at: $0)) }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
