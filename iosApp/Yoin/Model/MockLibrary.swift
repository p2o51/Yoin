import SwiftUI

/// Mock content. Everything here is invented; no network.
struct MockLibrary: MusicLibrary, PlaybackSource {
    static let shared = MockLibrary()

    private static func hex(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    private static func album(
        _ n: Int, _ title: String, _ artist: String, _ year: Int,
        _ a: UInt32, _ b: UInt32, _ c: UInt32,
        tracks: [String], ratings: [Double?] = [], rating: Double? = nil
    ) -> Album {
        let id = MediaId(provider: "mock", rawId: "al\(n)")
        let list = tracks.enumerated().map { i, t in
            Track(
                id: MediaId(provider: "mock", rawId: "al\(n)-t\(i + 1)"),
                number: i + 1, title: t,
                durationSec: 150 + (i * 37 + n * 11) % 140,
                rating: i < ratings.count ? ratings[i] : nil
            )
        }
        return Album(id: id, title: title, artist: artist, year: year,
                     palette: CoverPalette(a: hex(a), b: hex(b), c: hex(c)),
                     tracks: list, rating: rating)
    }

    static let allAlbums: [Album] = [
        album(1, "Afterglow Letters", "Mina Asahi", 2024, 0x7A4FD6, 0xF2A1C8, 0x1E2A5A,
              tracks: ["Paper Moon", "Late Bus", "余韻", "Window Seat", "Soft Static", "Kites in July",
                       "Hallway Light", "Ninety Days", "Glass Harbor", "Small Hours", "Undertow", "Goodnight, Again"],
              ratings: [8.4, 9.1, 9.6, 7.8, nil, 8.0, 8.8, nil, 9.0], rating: 8.6),
        album(2, "Tidal Rooms", "North Pavilion", 2023, 0x1F7A8C, 0x9BE3D2, 0x0D2B36,
              tracks: ["Low Tide", "Saltwater Hymn", "Lantern", "Breakwater", "Drift", "Foghorn", "Return"],
              ratings: [7.5, 8.2, nil, 8.9]),
        album(3, "Copper Season", "Juniper & Vale", 2022, 0xD9733B, 0xF6D365, 0x5B2333,
              tracks: ["Rust", "Harvest Radio", "Cinnamon", "Long Shadows", "Amber Line", "Porch Light"]),
        album(4, "Neon Garden", "Kaito Mori", 2025, 0xE0457B, 0x46E0C8, 0x241047,
              tracks: ["Bloom Signal", "Night Market", "Petal Drive", "Afterhours", "Arcade Rain", "Wilt", "Seedling", "Overgrowth"],
              ratings: [9.2, 8.7, 8.1], rating: 9.0),
        album(5, "Quiet Engines", "Lowfield", 2021, 0x55606E, 0xC9D6DF, 0x1B1F24,
              tracks: ["Idle", "Carriage", "Pistons", "Steam", "Quiet", "Rails"]),
        album(6, "Sunroom", "Ama Okafor", 2024, 0xF2B035, 0xFFE8A3, 0xB3472D,
              tracks: ["Morning Pages", "Citrus", "Sunroom", "Linen", "Honey", "Dusk Chorus"],
              ratings: [8.0, 8.5]),
        album(7, "Blue Hour Atlas", "Selene Park", 2020, 0x3459D6, 0x8FB8FF, 0x0F1640,
              tracks: ["Atlas", "Cartography", "Blue Hour", "Meridian", "Polaris", "Compass Rose", "Terminus"]),
        album(8, "Moss & Mineral", "Fernhill", 2023, 0x4E8A3E, 0xC4E6A1, 0x22331C,
              tracks: ["Lichen", "Granite", "Spring Water", "Under Fern", "Quartz", "Canopy"],
              ratings: [7.2, nil, 8.4]),
        album(9, "Velvet Static", "The Halcyons", 2019, 0x8C2F5A, 0xF07FA6, 0x2C0E1F,
              tracks: ["Velvet", "Static", "Receiver", "Channel 9", "Snowfall TV", "Off Air"]),
        album(10, "Paper Planets", "Yuna Ito", 2025, 0x6C8CFF, 0xFFC1E3, 0x2B2D6E,
              tracks: ["Origami", "Orbit", "Paper Planets", "Gravity Well", "Comet Tail"]),
        album(11, "Ember Archive", "Cold Lantern", 2022, 0xC2412D, 0xFF9E7A, 0x2E0F0B,
              tracks: ["Archive", "Cinders", "Bellows", "Ember", "Ash Garden"]),
        album(12, "Lumen Field", "Aster Bloom", 2024, 0x2EA37A, 0xF9F871, 0x0E3B3A,
              tracks: ["Lumen", "Field Notes", "Meadowlark", "Photosynthesis", "Goldenrod", "Daybreak"],
              ratings: [9.4, 9.0, 8.8, 9.1], rating: 9.3),
    ]

    func albums() -> [Album] { Self.allAlbums }

    func activities() -> [ActivityEntry] {
        let a = Self.allAlbums
        return [
            ActivityEntry(id: "act1", eyebrow: "Played 12 min ago", album: a[0]),
            ActivityEntry(id: "act2", eyebrow: "Rated 9.0", album: a[3]),
            ActivityEntry(id: "act3", eyebrow: "Visited yesterday", album: a[1]),
            ActivityEntry(id: "act4", eyebrow: "2 new notes", album: a[11]),
        ]
    }

    func jumpBackIn() -> [Album] {
        let a = Self.allAlbums
        return [a[11], a[2], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[1], a[3], a[0]]
    }

    func libraryItems() -> [LibraryItem] {
        let a = Self.allAlbums
        var items: [LibraryItem] = []
        let playlist = LibraryItem(id: MediaId(provider: "mock", rawId: "pl1"), kind: .playlist,
                                   title: "Night Walks", subtitle: "Playlist · 48 songs", palette: a[6].palette)
        let artist1 = LibraryItem(id: MediaId(provider: "mock", rawId: "ar1"), kind: .artist,
                                  title: "Mina Asahi", subtitle: "Artist", palette: a[0].palette)
        let artist2 = LibraryItem(id: MediaId(provider: "mock", rawId: "ar2"), kind: .artist,
                                  title: "Kaito Mori", subtitle: "Artist", palette: a[3].palette)
        items.append(playlist)
        for (i, al) in a.enumerated() {
            items.append(LibraryItem(id: al.id, kind: .album, title: al.title,
                                     subtitle: "Album · \(al.artist)", palette: al.palette, album: al))
            if i == 1 { items.append(artist1) }
            if i == 4 { items.append(artist2) }
        }
        return items
    }

    func current() -> PlaybackSnapshot {
        let al = Self.allAlbums[0]
        return PlaybackSnapshot(album: al, track: al.tracks[2], positionSec: 74, isPlaying: true,
                                lyricLine: "The echo stays after the song is gone",
                                nextLyricLine: "and I keep the light on in the hall")
    }
}
