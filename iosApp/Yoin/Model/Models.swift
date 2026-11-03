import SwiftUI

// Neutral domain models, shaped after the Android `data/model` layer so a
// shared KMP module can replace them later. UI code only talks to these
// structs and to the `MusicLibrary` protocol, never to mock internals.

struct MediaId: Hashable, Sendable {
    var provider: String
    var rawId: String
}

/// Three colors per cover; every derived tint comes from these.
struct CoverPalette: Hashable, Sendable {
    var a: Color
    var b: Color
    var c: Color

    /// Accent used for tinted glass and primary actions.
    var tint: Color { a }
    /// Solid card background, the cover color washed into the page surface.
    var container: Color { Yoin.surface.mix(with: a, by: 0.24, in: .perceptual) }
    var containerStrong: Color { Yoin.surface.mix(with: a, by: 0.42, in: .perceptual) }
    /// Text/ink that sits on `container`.
    var onContainer: Color { a.mix(with: Yoin.ink, by: 0.55, in: .perceptual) }
    /// Deep version of the cover for the flat Now Playing base.
    var deep: Color { a.mix(with: .black, by: 0.45, in: .perceptual) }
}

struct Track: Identifiable, Hashable, Sendable {
    var id: MediaId
    var number: Int
    var title: String
    var durationSec: Int
    var rating: Double?
}

struct Album: Identifiable, Hashable, Sendable {
    var id: MediaId
    var title: String
    var artist: String
    var year: Int
    var palette: CoverPalette
    var tracks: [Track]
    var rating: Double?

    var ratedCount: Int { tracks.filter { $0.rating != nil }.count }
    var averageRating: Double? {
        let rated = tracks.compactMap(\.rating)
        guard !rated.isEmpty else { return nil }
        return rated.reduce(0, +) / Double(rated.count)
    }
}

enum LibraryKind: String, CaseIterable, Sendable {
    case playlists = "Playlists"
    case artists = "Artists"
    case albums = "Albums"
    case songs = "Songs"
    case favorites = "Favorites"
}

struct LibraryItem: Identifiable, Hashable, Sendable {
    enum Kind: Hashable, Sendable { case album, artist, playlist }
    var id: MediaId
    var kind: Kind
    var title: String
    var subtitle: String
    var palette: CoverPalette
    var album: Album?
}

struct ActivityEntry: Identifiable, Hashable, Sendable {
    var id: String
    var eyebrow: String
    var album: Album
}

struct PlaybackSnapshot: Sendable {
    var album: Album
    var track: Track
    var positionSec: Int
    var isPlaying: Bool
    var lyricLine: String
    var nextLyricLine: String
}

// MARK: - Source protocols (the KMP module will implement these)

protocol MusicLibrary: Sendable {
    func albums() -> [Album]
    func libraryItems() -> [LibraryItem]
    func activities() -> [ActivityEntry]
    func jumpBackIn() -> [Album]
}

protocol PlaybackSource: Sendable {
    func current() -> PlaybackSnapshot
}
