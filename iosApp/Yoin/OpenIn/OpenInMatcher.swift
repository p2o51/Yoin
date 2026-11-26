import Foundation
import YoinShared

/// A found link's results on the account in use, best first. [exact]: the best is the same item by the
/// playlist-transfer rules (an ISRC match, or TrackMatcher's confident match): the page still asks, but
/// doesn't say it couldn't confirm one (Android OpenInRanking).
struct OpenInRanking<T> {
    var items: [T]
    var exact: Bool
}

/// Ranks a found link's search results with the shared playlist-transfer `TrackMatcher`
/// (docs/playlist-transfer.md §4; Android OpenInMatcher): ISRC first where the link's service named one,
/// then title, artist and duration.
enum OpenInMatcher {
    /// At most this many rows on the results page.
    static let maxResults = 20
    private static let nameExact = 0.9
    private static let artistExact = 0.8

    /// The link's track as the matcher reads it.
    static func sourceTrack(_ m: MusicLinkMetadata) -> MatchTrack {
        MatchTrack(id: "openin:link", title: m.title, artist: m.artist, album: m.album,
                   durationSec: m.durationSec.map { KotlinInt(int: Int32($0)) }, isrc: m.isrc)
    }

    static func matchTrack(_ e: PlaylistEntry) -> MatchTrack {
        let t = e.track
        return MatchTrack(id: "\(t.id.provider):\(t.id.rawId)", title: t.title, artist: t.artist ?? e.album.artist,
                          album: e.album.title, durationSec: t.durationSec > 0 ? KotlinInt(int: Int32(t.durationSec)) : nil,
                          isrc: nil)
    }

    /// The search after which the title alone is tried (TrackMatcher.searchQuery, else the title).
    static func searchQuery(_ m: MusicLinkMetadata) -> String? {
        TrackMatcher.shared.searchQuery(track: sourceTrack(m)) ?? m.title
    }

    static func titleQuery(_ m: MusicLinkMetadata) -> String? { TrackMatcher.shared.titleQuery(track: sourceTrack(m)) }

    /// [byIsrc]: the account's songs sharing the link's ISRC; [searched]: its title search. ISRC matches lead,
    /// then the search's songs TrackMatcher scores, best first, then the rest of the search as it came.
    static func rankTracks(_ source: MatchTrack, byIsrc: [PlaylistEntry], searched: [PlaylistEntry]) -> OpenInRanking<PlaylistEntry> {
        let matcher = TrackMatcher.shared
        var byId: [String: PlaylistEntry] = [:]
        for e in byIsrc + searched { byId[matchTrack(e).id] = byId[matchTrack(e).id] ?? e }
        let isrcBest = byIsrc.isEmpty ? nil
            : (matcher.chooseByIsrc(source: source, candidates: byIsrc.map(matchTrack)) as? MatchDecisionMatched)?.track
        let candidates = searched.map(matchTrack)
        let decision = matcher.choose(source: source, candidates: candidates)
        let scored = candidates.map { ($0, matcher.score(source: source, candidate: $0)) }
            .enumerated()
            .filter { $0.element.1 > 0 }
            .sorted { $0.element.1 != $1.element.1 ? $0.element.1 > $1.element.1 : $0.offset < $1.offset }
            .map(\.element.0)
        var seen = Set<String>()
        var ordered: [PlaylistEntry] = []
        let ids = (isrcBest.map { [$0.id] } ?? []) + byIsrc.map { matchTrack($0).id } + scored.map(\.id) + candidates.map(\.id)
        for id in ids where seen.insert(id).inserted {
            if let e = byId[id] { ordered.append(e) }
            if ordered.count == maxResults { break }
        }
        return OpenInRanking(items: ordered, exact: isrcBest != nil || decision is MatchDecisionMatched)
    }

    static func rankAlbums(name: String?, artist: String?, _ albums: [Album]) -> OpenInRanking<Album> {
        rankByName(albums, name: name, artist: artist, nameOf: \.title, artistOf: { $0.artist })
    }

    static func rankArtists(name: String?, _ artists: [LibraryItem]) -> OpenInRanking<LibraryItem> {
        rankByName(artists, name: name, artist: nil, nameOf: \.title, artistOf: { _ in nil })
    }

    static func rankPlaylists(name: String?, owner: String?, _ playlists: [LibraryItem]) -> OpenInRanking<LibraryItem> {
        rankByName(playlists, name: name, artist: owner, nameOf: \.title, artistOf: { $0.owner ?? $0.subtitle })
    }

    /// Name first, the artist (or owner) after; items scoring nothing keep the search's order after the rest.
    private static func rankByName<T>(_ items: [T], name: String?, artist: String?, nameOf: (T) -> String,
                                      artistOf: (T) -> String?) -> OpenInRanking<T> {
        guard let name, !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            return OpenInRanking(items: Array(items.prefix(maxResults)), exact: false)
        }
        let matcher = TrackMatcher.shared
        let hasArtist = !(artist ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        let scored = items.enumerated().map { i, item in
            let n = max(0, matcher.titleSimilarity(a: name, b: nameOf(item)))
            let a = hasArtist ? max(0, matcher.artistSimilarity(a: artist, b: artistOf(item))) : 1.0
            return (item: item, index: i, name: n, artist: a)
        }
        let ordered = scored.sorted {
            let l = $0.name * 0.65 + $0.artist * 0.35, r = $1.name * 0.65 + $1.artist * 0.35
            return l != r ? l > r : $0.index < $1.index
        }
        let exact = ordered.first.map { $0.name >= nameExact && $0.artist >= artistExact } ?? false
        return OpenInRanking(items: ordered.prefix(maxResults).map(\.item), exact: exact)
    }
}
