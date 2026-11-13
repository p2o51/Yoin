import SwiftUI

/*
 * The demo phone's library (Android ui/tips/TipDemoData.kt): made-up albums with made-up covers (the three
 * colours of Android's tip_demo_cover_* drawables, as the mock covers iOS draws from a palette), so the real
 * views have something to show. Names are the same as Android's and are not translated there either.
 */
enum TipDemo {
    private static func hex(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    private static func album(_ i: Int, _ title: String, _ artist: String, _ a: UInt32, _ b: UInt32, _ c: UInt32) -> Album {
        Album(id: MediaId(provider: provider, rawId: "tip-album-\(i)"), title: title, artist: artist, year: 2024,
              palette: CoverPalette(a: hex(a), b: hex(b), c: hex(c)), tracks: [])
    }

    /// The made-up library's provider: no account's, so nothing here reaches a real one.
    static let provider = "tip"

    static let albums: [Album] = [
        album(0, "Harbour Lights", "Mira Sol", 0x2F4A7A, 0xF2B04C, 0xE8E1D0),
        album(1, "Paper Moons", "The Lanterns", 0xB8432F, 0xF4C9A8, 0x3C2A4D),
        album(2, "Low Tide", "Ines Varga", 0x5E8C6A, 0xEAD9A0, 0x23372B),
        album(3, "Night Garden", "Okto", 0x6B4FA3, 0xE38BB5, 0x1F1638),
        album(4, "Golden Hour", "Mira Sol", 0xD9A441, 0xF6EFD9, 0x2B5C8A),
        album(5, "Blue Rooms", "Halden", 0x2E6E73, 0xCFE6E2, 0xE4664A),
    ]

    static let trackTitles = [
        "Slow Light", "Northbound", "Salt and Glass", "Weathervane",
        "Quiet Hours", "Lanterns", "Undertow", "Afterglow",
    ]

    // MARK: Now Playing

    /// The playing song's lines and when each begins, in seconds (Android demoLyrics).
    static let lyrics: [(at: Double, text: String)] = [
        (0, "Streetlights hum a slower song"),
        (52, "I keep the window open wide"),
        (60, "The harbour folds into the dark"),
        (68, "And every light is passing by"),
        (76, "Hold the evening, let it stay"),
        (84, "Slow light, slow light"),
        (92, "Carry me the long way home"),
        (100, "Over water, under stone"),
    ]

    /// The playing song: 3:34 long, 0:58 in (Android DemoDurationMs, DemoStartMs).
    static let songSec = 214
    static let songStartSec: Double = 58

    /// The song the demo plays (Android DemoNowPlayingTrack: the first song of the first album, a favorite,
    /// lossless), with a playhead of its own.
    @MainActor
    static func nowPlaying() -> NowPlayingDemo {
        let track = Track(id: MediaId(provider: provider, rawId: "tip-song"), number: 1, title: trackTitles[0],
                          durationSec: songSec, rating: 8.2, starred: true)
        return NowPlayingDemo(album: albums[0], track: track, lyrics: lyrics, startSec: songStartSec,
                              formatTag: "FLAC 24/96")
    }

    // MARK: An album page

    /// How many songs the demo's album page lists, and the one drawn with the note mark (its number).
    static let albumPageTracks = 7
    static let notedTrack = 4

    /// The album the demo's album page shows (Android DemoAlbums[1]): seven songs, the second a favorite.
    static let albumPage: Album = {
        var album = albums[1]
        album.tracks = (0..<albumPageTracks).map { i in
            Track(id: MediaId(provider: provider, rawId: "tip-song-\(i)"), number: i + 1, title: trackTitles[i],
                  durationSec: 180 + i * 23, rating: nil, starred: i == 1)
        }
        return album
    }()

    // MARK: Library

    /// Library's grid on the phone (Android demoLibraryAlbums + DemoLibraryArtists): every album, an artist
    /// after every third from the second.
    static let libraryItems: [LibraryItem] = albums.enumerated().flatMap { i, album -> [LibraryItem] in
        let item = LibraryItem(id: album.id, kind: .album, title: album.title, subtitle: album.artist,
                               palette: album.palette, album: album)
        guard i % 3 == 1 else { return [item] }
        let artist = LibraryItem(id: MediaId(provider: provider, rawId: "tip-artist-\(i)"), kind: .artist,
                                 title: album.artist, subtitle: "", palette: album.palette)
        return [item, artist]
    }

    /// Made-up accounts, one per service, each its own avatar shape (their index).
    static let accounts: [Profile] = [
        Profile(id: "demo-a", service: .subsonic, baseUrl: "https://music.home", username: "Mira", createdAt: 0),
        Profile(id: "demo-b", service: .spotify, createdAt: 1, spotifyUserId: "okto", spotifyName: "Okto"),
        Profile(id: "demo-c", service: .appleMusic, createdAt: 2),
    ]

    static func shape(of profile: Profile) -> Int { accounts.firstIndex { $0.id == profile.id } ?? 0 }

    /// A Home layout store of the demo's own: nothing a demo Home does reaches the account's saved layout.
    @MainActor
    static func layoutStore() -> HomeLayoutStore {
        HomeLayoutStore(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("tip-demo-home-layout.json"))
    }

    // MARK: Lyric Cards

    /// The lines are made up for the demo (a real card shows the song's own); the last is one the listener kept.
    private static let lyricLines: [(text: String, userPick: Bool)] = [
        ("We left the porch light on all winter,\nso the summer would know the way", false),
        ("Some songs wait in a drawer for years,\nthen fit you like a coat", false),
        ("I hum the parts I don't know yet", true),
    ]

    static let lyricCards: [LyricHighlightRow] = lyricLines.enumerated().map { i, line in
        let album = albums[i]
        return LyricHighlightRow(provider: provider, scope: nil, songId: "tip-lyric-\(i)", title: trackTitles[i],
                                 artist: album.artist, album: album.title, albumId: album.id.rawId, coverKey: nil,
                                 durationMs: 200_000, text: line.text, startMs: nil, fingerprint: nil,
                                 model: line.userPick ? LyricHighlightStore.userPick : "demo", attemptedAt: 0,
                                 lastShownAt: nil)
    }

    /// Each lyric card's cover, by the card's id.
    static let lyricPalettes: [String: CoverPalette] = Dictionary(
        uniqueKeysWithValues: lyricCards.enumerated().map { ($1.id, albums[$0].palette) })

    // MARK: All Memories

    /// How many days back the made-up listener kept something: 16 days over the last four months.
    static let ledgerDays = [0, 1, 4, 9, 12, 20, 26, 33, 41, 47, 55, 62, 70, 84, 97, 110]

    /// A listener who writes now and then: a week a cell, a few albums, notes and scores.
    static func ledgerState(today: Date = Date(), calendar: Calendar = .current) -> LedgerState {
        let account = LedgerAccount(provider: provider, scope: "demo")
        let ledgerAlbums = albums.enumerated().map { i, a in
            LedgerAlbum(account: account, albumId: "demo-\(i)", name: a.title, artist: a.artist, coverKey: nil)
        }
        let start = calendar.startOfDay(for: today)
        var entries: [LedgerEntry] = []
        for (i, back) in ledgerDays.enumerated() {
            let day = calendar.date(byAdding: .day, value: -back, to: start) ?? start
            let evening = calendar.date(bySettingHour: 21, minute: 0, second: 0, of: day) ?? day
            // Today's is never ahead of now (a cell in the future is not drawn).
            let at = Int64(min(evening, max(today, day)).timeIntervalSince1970 * 1000)
            let album = ledgerAlbums[i % ledgerAlbums.count]
            let title = trackTitles[i % trackTitles.count]
            let review = i % 3 == 0
            entries.append(LedgerEntry(id: "demo-note-\(i)", kind: review ? .review : .songNote, at: at, account: account,
                                       album: album, text: title, trackTitle: review ? nil : title,
                                       songId: review ? nil : "demo-song-\(i)"))
            if i % 2 == 0 {
                entries.append(LedgerEntry(id: "demo-score-\(i)", kind: .trackRating, at: at - 60_000, account: account,
                                           album: ledgerAlbums[(i + 1) % ledgerAlbums.count],
                                           trackTitle: trackTitles[(i + 3) % trackTitles.count],
                                           songId: "demo-scored-\(i)", score: 7 + Double(i % 4) * 0.5))
            }
        }
        entries.sort(by: MemoryLedger.newestFirst)
        let covers = Dictionary(uniqueKeysWithValues: ledgerAlbums.enumerated().map { ($1.key, albums[$0].palette) })
        return LedgerState(entries: entries, stats: MemoryLedger.stats(entries),
                           grid: MemoryGrid.build(entries, today: today, calendar: calendar, scale: .week),
                           covers: covers,
                           profiles: [LedgerProfile(id: "demo", account: account, name: "Yoin", service: .subsonic, isActive: true)],
                           activeAccount: account, today: today)
    }

    /// The newest week with something in it: the cell the demo picks.
    static func ledgerPick(_ grid: MemoryGrid) -> Int? {
        grid.cells.lastIndex { !$0.isEmpty && !$0.future }
    }

    /// The week wall's columns (MemoryGridView: 13 × 2).
    static let weekColumns = 13

    /// Where week cell [index] sits on the 13 × 2 wall, as fractions of the grid.
    static func weekCellFraction(_ index: Int) -> CGPoint {
        CGPoint(x: (CGFloat(index % weekColumns) + 0.5) / CGFloat(weekColumns), y: (CGFloat(index / weekColumns) + 0.5) / 2)
    }
}
