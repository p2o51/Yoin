import SwiftUI
import XCTest
@testable import Yoin

/// Activities keep each log row's own entity and open its real target
/// (Android selectHomeActivities / buildActivityEntries).
@MainActor
final class HomeActivityEntitiesTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000

    private func record(_ entity: ActivityEntity, _ id: String, title: String = "", subtitle: String = "",
                        at: Int64, album: String? = nil, artist: String? = nil) -> ActivityRecord {
        ActivityRecord(id: UUID().uuidString, scope: "s", provider: "subsonic", entity: entity, action: .visited,
                       entityId: id, title: title.isEmpty ? id : title, subtitle: subtitle, coverKey: id,
                       songId: entity == .song ? id : nil, albumId: album, artistId: artist, timestamp: at)
    }

    private func resolve(_ id: String, _ snap: PlayEvent?) -> Album? {
        guard let snap else { return nil }
        return Album(id: MediaId(provider: "subsonic", rawId: id), title: snap.album.isEmpty ? snap.title : snap.album,
                     artist: snap.artist, year: 0, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
    }

    func test_should_keepEveryEntityButSongs_when_pickingActivities() {
        let records = [
            record(.album, "al1", title: "Harbor", subtitle: "Mira", at: now - 50),
            record(.song, "s1", at: now - 40, album: "al9"),
            record(.artist, "ar1", title: "Mira", at: now - 30, artist: "ar1"),
            record(.playlist, "pl1", title: "Night Drive", subtitle: "me", at: now - 20),
        ]
        let picks = HomeHistory.activityPicks(records, resolve: resolve)
        XCTAssertEqual(picks.map(\.entity), [.playlist, .artist, .album])
        XCTAssertEqual(picks.map(\.entityId), ["pl1", "ar1", "al1"])
        XCTAssertEqual(picks[0].album.title, "Night Drive")
        XCTAssertEqual(picks[0].album.artist, "me")
        // The artist's card is the artist's name; a blank subtitle takes the fallback.
        XCTAssertEqual(picks[1].album.title, "Mira")
        XCTAssertEqual(picks[1].album.artist, loc("Artist"))
    }

    func test_should_dedupeByEntityAndId_when_sameEntityRepeats() {
        let records = [
            record(.album, "x", title: "Old", at: now - 100),
            record(.artist, "x", title: "Same id, an artist", at: now - 90),
            record(.album, "x", title: "New", at: now - 10),
        ]
        let picks = HomeHistory.activityPicks(records, resolve: resolve)
        XCTAssertEqual(picks.map(\.entity), [.album, .artist])
        XCTAssertEqual(picks[0].album.title, "New")
    }

    func test_should_putTheFirstAlbumOrPlaylistInTheHero_when_artistsLead() {
        func al(_ id: String) -> Album {
            Album(id: MediaId(provider: "subsonic", rawId: id), title: id, artist: "X", year: 0,
                  palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
        }
        var overlay = LibraryStore.HomeOverlay()
        overlay.now = now
        overlay.hasHistory = true
        overlay.activities = [
            HomeHistory.ActivityPick(album: al("A"), at: now - 1000, entity: .artist, entityId: "ar-A"),
            HomeHistory.ActivityPick(album: al("B"), at: now - 2000, entity: .playlist, entityId: "pl-B"),
            HomeHistory.ActivityPick(album: al("C"), at: now - 3000),
        ]
        let feed = LibraryStore.buildHome(recent: [], frequent: [], newest: [], highest: [], random: [],
                                          recentTracks: [], history: overlay)
        XCTAssertEqual(feed.activities.map(\.entity), [.playlist, .artist, .album])
        XCTAssertEqual(feed.activities[0].targetId, "pl-B")
        XCTAssertEqual(feed.activities[1].targetId, "ar-A")
        XCTAssertEqual(feed.activities[2].targetId, "C")
        // An artist's or playlist's card doesn't keep an album off the other shelves.
        let covers = Set(feed.activities.filter { $0.entity == .album }.map(\.album.id.rawId))
        XCTAssertEqual(covers, ["C"])
    }

    func test_should_labelAndShapeCards_when_entityDiffers() {
        func entry(_ e: ActivityEntity) -> ActivityEntry {
            ActivityEntry(id: "e", eyebrow: "", album: MockLibrary.allAlbums[0], entity: e)
        }
        XCTAssertEqual(entry(.album).typeLabel, loc("Album"))
        XCTAssertEqual(entry(.artist).typeLabel, loc("Artist"))
        XCTAssertEqual(entry(.playlist).typeLabel, loc("Playlist"))
        XCTAssertEqual(entry(.artist).backdrop, .artist)
        XCTAssertEqual(entry(.playlist).backdrop, .playlist)
    }

    func test_should_recordArtistAndPlaylistVisits_when_pagesOpen() {
        let h = HistoryStore(fileURL: nil, now: { Date(timeIntervalSince1970: 1_800_000_000) })
        h.recordArtistVisit(scope: "s", provider: "subsonic", artistId: "ar1", name: "Mira", coverKey: nil)
        h.recordPlaylistVisit(scope: "s", provider: "subsonic", playlistId: "pl1", name: "Night", owner: "me", coverKey: nil)
        h.recordArtistVisit(scope: "s", provider: "subsonic", artistId: "", name: "Nobody", coverKey: nil)
        let rows = h.recentActivities(scope: "s", provider: "subsonic")
        XCTAssertEqual(Set(rows.map(\.entity)), [.artist, .playlist])
        XCTAssertEqual(rows.first { $0.entity == .playlist }?.subtitle, "me")
    }
}
