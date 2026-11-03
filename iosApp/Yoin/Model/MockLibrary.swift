import SwiftUI
import UIKit

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
                rating: i < ratings.count ? ratings[i] : nil,
                // The mock's hearts: the tracks rated 9 or more.
                starred: (i < ratings.count ? ratings[i] ?? 0 : 0) >= 9
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

    /// Mina Asahi's other releases, for the artist page's discography.
    static let extraReleases: [Album] = {
        var single = album(13, "Ninety Days", "Mina Asahi", 2025, 0xB44FD6, 0xF7B2D9, 0x2A1E5A, tracks: ["Ninety Days"])
        single.kind = .single
        var ep = album(14, "Window Seat", "Mina Asahi", 2023, 0x5A4FD6, 0xA8C8FF, 0x1E2440,
                       tracks: ["Window Seat", "Platform 4", "Tunnel Light", "Arrival"])
        ep.kind = .ep
        let lp = album(15, "Small Hours", "Mina Asahi", 2021, 0x3F5BD6, 0xF2C1A1, 0x1A1F3A,
                       tracks: ["Small Hours", "Kettle", "Streetlamp", "Lull", "Morning Bus", "Static Snow", "Postcard", "Fold"])
        var comp = album(16, "Early Lights", "Mina Asahi", 2019, 0x8A6FD6, 0xE0E0F0, 0x2E2550,
                         tracks: ["Early Lights", "First Room", "Demo 3", "Paper Moon (Demo)"])
        comp.kind = .compilation
        return [single, ep, lp, comp]
    }()

    func playlist(_ rawId: String) -> PlaylistInfo {
        let a = Self.allAlbums
        let picks: [(Int, Int)] = [(6, 2), (0, 1), (9, 0), (1, 0), (3, 1), (11, 2), (4, 4), (8, 3), (7, 0), (0, 10)]
        return PlaylistInfo(id: MediaId(provider: "mock", rawId: rawId), title: "Night Walks", owner: "51",
                            isPublic: false, description: "Long way home after the last train. Streetlights, slow tempo, nothing too loud.",
                            palette: a[6].palette,
                            entries: picks.map { PlaylistEntry(album: a[$0.0], track: a[$0.0].tracks[$0.1]) })
    }

    func releases(by artist: String) -> [Album] {
        (Self.allAlbums + Self.extraReleases).filter { $0.artist == artist }.sorted { $0.year > $1.year }
    }

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

    /// Home as the screenshots show it (hand-placed shelf).
    func homeFeed() -> HomeFeed {
        let a = Self.allAlbums
        let grid: [(Int, Int)] = [(9, 0), (6, 1), (2, 1), (8, 2)]
        return HomeFeed(
            layout: .mock,
            activities: [
                ActivityEntry(id: "hero", eyebrow: "12m ago", album: a[0]),
                ActivityEntry(id: "artist", eyebrow: "2h ago", album: a[3]),
                ActivityEntry(id: "wide", eyebrow: "1d ago", album: a[1]),
                ActivityEntry(id: "strip", eyebrow: "3d ago", album: a[11]),
            ],
            jumpBackIn: jumpBackIn(),
            recentTracks: grid.map { PlaylistEntry(album: a[$0.0], track: a[$0.0].tracks[$0.1]) },
            recentAlbums: [10, 4, 7, 5, 2, 8].map { a[$0] },
            rediscover: [
                RediscoverEntry(album: a[6], reason: "Reviewed", away: "7 months away", score: 8.8,
                                footnoteDate: "2020.11", footnotePlays: "14"),
                RediscoverEntry(album: a[8], reason: "3 notes", away: "1 year away", score: nil,
                                footnoteDate: "2019.04", footnotePlays: "9"),
            ])
    }

    /// The artist page for a mock artist (newest release first).
    func artistPage(_ name: String) -> ArtistPage {
        let releases = releases(by: name)
        let hero = releases.first ?? Self.allAlbums[0]
        let picks = releases.flatMap { a in a.tracks.prefix(2).map { PlaylistEntry(album: a, track: $0) } }.prefix(5)
        let plays = picks.enumerated().map { i, e in
            var e = e
            e.track.playCount = 30 - i * 5
            return e
        }
        return ArtistPage(id: MediaId(provider: "mock", rawId: name), name: name, starred: true,
                          palette: hero.palette, releases: releases, mostPlayed: plays)
    }

    func jumpBackIn() -> [Album] {
        let a = Self.allAlbums
        return [a[11], a[2], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[1], a[3], a[0]]
    }

    /// Generated filler albums so Library is long enough for its fast scroller
    /// (Android shows it only past three screens of rows).
    static let fillerAlbums: [Album] = {
        let first = ["Amber", "Blue", "Cedar", "Distant", "Echo", "Falling", "Golden", "Hollow", "Iris", "Juniper",
                     "Kindred", "Lunar", "Marble", "Northern", "Opal", "Paper", "Quiet", "River", "Silver", "Tidal",
                     "Umber", "Velvet", "Winter", "Yellow", "Zephyr", "After", "Bright", "Coral", "Dusk", "Ember"]
        let second = ["Rooms", "Hours", "Letters", "Signals", "Gardens", "Lines", "Tapes", "Weather", "Coast", "Fields"]
        let artists = ["Hana Ueda", "The Lanterns", "Oro", "Fieldwork", "Sora Kim", "Low Tide Club", "Mei", "Northbound"]
        return first.enumerated().map { i, f in
            let hue = Double(i) / Double(first.count)
            func c(_ h: Double, _ s: Double, _ b: Double) -> UInt32 {
                let col = UIColor(hue: h.truncatingRemainder(dividingBy: 1), saturation: s, brightness: b, alpha: 1)
                var r: CGFloat = 0, g: CGFloat = 0, bl: CGFloat = 0, a: CGFloat = 0
                col.getRed(&r, green: &g, blue: &bl, alpha: &a)
                return UInt32(r * 255) << 16 | UInt32(g * 255) << 8 | UInt32(bl * 255)
            }
            return album(100 + i, "\(f) \(second[i % second.count])", artists[i % artists.count], 2010 + i % 15,
                         c(hue, 0.6, 0.75), c(hue + 0.08, 0.35, 0.95), c(hue - 0.05, 0.7, 0.3),
                         tracks: ["One", "Two", "Three", "Four", "Five"])
        }
    }()

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
        for al in Self.fillerAlbums {
            items.append(LibraryItem(id: al.id, kind: .album, title: al.title,
                                     subtitle: "Album · \(al.artist)", palette: al.palette, album: al))
        }
        return items
    }

    func current() -> PlaybackSnapshot {
        let al = Self.allAlbums[0]
        return PlaybackSnapshot(album: al, track: al.tracks[2], positionSec: 74, isPlaying: true,
                                lyricLine: "The echo stays after the song is gone",
                                nextLyricLine: "and I keep the light on in the hall",
                                lyrics: [
                                    "Paper moon above the station",
                                    "Every train already left",
                                    "The echo stays after the song is gone",
                                    "and I keep the light on in the hall",
                                    "in case the quiet wants to talk",
                                    "Hold the last note a little longer",
                                    "let it ring until the morning",
                                    "余韻だけが 部屋に残る",
                                ],
                                activeLyric: 2)
    }
}
