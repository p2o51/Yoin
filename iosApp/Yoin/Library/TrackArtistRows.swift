import Foundation

/// One credited artist of a song and, where the service names it, that artist's page (Android ArtistCredit).
struct ArtistCredit: Hashable, Sendable {
    var name: String
    /// The artist's raw id (the song's own provider); nil where the service gives none.
    var id: String?
}

/// One "Go to artist" row of a song's sheet.
struct GoToArtistRow: Hashable, Sendable {
    /// Set when the song has several credited artists, so each row names its own.
    var name: String?
    var artistId: String
}

/// Which "Go to artist" rows a song's sheet offers (Android TrackMenu.goToArtistRows): one per credited
/// artist the service names an id for when there are several, else the one row for the song's artist.
enum TrackArtistRows {
    static func rows(credits: [ArtistCredit], fallbackArtistId: String?) -> [GoToArtistRow] {
        let named = credits.compactMap { c in c.id.flatMap { $0.isEmpty ? nil : $0 }.map { (c.name, $0) } }
        if named.count > 1 { return named.map { GoToArtistRow(name: $0.0, artistId: $0.1) } }
        guard let id = fallbackArtistId, !id.isEmpty else { return [] }
        return [GoToArtistRow(name: nil, artistId: id)]
    }
}
