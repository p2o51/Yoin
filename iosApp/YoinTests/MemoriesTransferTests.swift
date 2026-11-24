import XCTest
import YoinShared
@testable import Yoin

/// Transfer Memories on iOS: matching on a catalog with the shared TrackMatcher (Android MemoryTransfer's
/// matching) and the picks `YoinBackup.applyTransfer` is handed after the review.
@MainActor
final class MemoriesTransferTests: XCTestCase {
    private final class FakeCatalog: MemoryCatalog {
        var tracks: [String: [CatalogTrack]] = [:]
        var albums: [String: [CatalogAlbum]] = [:]
        var byIsrc: [String: [CatalogTrack]] = [:]
        var described: [String: MatchTrack] = [:]
        private(set) var queries: [String] = []

        func searchTracks(_ query: String) async -> [CatalogTrack] { queries.append(query); return tracks[query] ?? [] }
        func searchAlbums(_ query: String) async -> [CatalogAlbum] { queries.append("album:" + query); return albums[query] ?? [] }
        func tracksByIsrc(_ isrcs: [String]) async -> [String: [CatalogTrack]] { byIsrc.filter { isrcs.contains($0.key) } }
        func describeSourceTrack(provider: String, trackId: String) async -> MatchTrack? { described[trackId] }
    }

    private let now: Int64 = 1_795_400_000_000
    private let from = TransferAccount(scope: "s-home", provider: "subsonic", displayName: "kiri", serverKey: "music.example.com",
                                       localProfileId: "p-home")
    private let to = TransferAccount(scope: "s-chen", provider: "spotify", displayName: "Chen", serverKey: nil, localProfileId: "p-sp")

    private func facade() -> YoinBackup {
        YoinBackup(domain: SyncDomainBridge(accounts: { .init(subsonic: [], appleMusic: false, storefront: nil) }), replica: nil,
                   links: TrackAlbumLinkStore(fileURL: nil), deviceId: "test", deviceName: "iPhone", appVersionName: "0.6.3",
                   appVersionCode: 36)
    }

    private func ref(_ id: String) -> SourceRef { SourceRef(provider: "subsonic", id: id) }

    /// Four noted songs on one album (two known by their plays, one only by its note, one unknown), an album
    /// rating on that album, and one on an album no song of it was matched to.
    private func items() -> MemoryItems {
        func note(_ id: String, _ title: String) -> SourceNote {
            SourceNote(id: "n-\(id)", track: ref(id), content: "rain", createdAt: now, updatedAt: now, title: title, artist: "Kiri Ono",
                       positionMs: nil)
        }
        return MemoryItems(
            songNotes: [note("t1", "Paper Lanterns"), note("t2", "Harbor Lights"), note("t3", "Undertow"), note("t4", "")],
            trackRatings: [], albumRatings: [SourceAlbumRating(album: ref("al1"), rating: 9, updatedAt: now),
                                             SourceAlbumRating(album: ref("al2"), rating: 7, updatedAt: now)],
            albumReviews: [], albumNotes: [], albumTitles: [], memoryTitles: [], memoryCopies: [], trackAlbumLinks: [],
            trackFacts: [ref("t1"): SourceTrackFacts(title: "Paper Lanterns", artist: "Kiri Ono", album: "Harbor", albumId: "al1",
                                                     durationSec: KotlinInt(int: 214)),
                         ref("t2"): SourceTrackFacts(title: "Harbor Lights", artist: "Kiri Ono", album: "Harbor", albumId: "al1",
                                                     durationSec: KotlinInt(int: 251))],
            albumFacts: [ref("al1"): SourceAlbumFacts(name: "Harbor", artist: "Kiri Ono"),
                         ref("al2"): SourceAlbumFacts(name: "Low Tide Static", artist: "Kiri Ono")])
    }

    private func song(_ id: String, _ title: String, _ seconds: Int32, album: String = "sp-al1") -> CatalogTrack {
        CatalogTrack(track: MatchTrack(id: "spotify:\(id)", title: title, artist: "Kiri Ono", album: "Harbor",
                                       durationSec: KotlinInt(int: seconds), isrc: nil),
                     albumProvider: "spotify", albumId: album)
    }

    func test_should_matchSongsAndAlbums_onTheTargetCatalog() async throws {
        let plan = try await facade().planTransfer(source: from, items: items(), target: to)
        XCTAssertEqual(plan.tracksToMatch.map(\.source.id), ["t1", "t2", "t3", "t4"])
        XCTAssertEqual(plan.directSongs, 0)
        let catalog = FakeCatalog()
        catalog.tracks["Paper Lanterns Kiri Ono"] = [song("s1", "Paper Lanterns", 215)]
        catalog.tracks["Harbor Lights Kiri Ono"] = [song("s2", "Harbor Lights", 250)]
        // Only a live take of "Undertow": offered, not taken.
        catalog.tracks["Undertow Kiri Ono"] = [song("s3", "Undertow (Live)", 300)]
        catalog.albums["Low Tide Static Kiri Ono"] = [CatalogAlbum(provider: "spotify", id: "sp-al2", name: "Low Tide Static", artist: "Kiri Ono")]
        var progress: [Int] = []

        let (tracks, albums) = await TransferMatching.match(plan, catalog: catalog) { done, _ in progress.append(done) }

        XCTAssertEqual(tracks.map(\.status), [.matched, .matched, .uncertain, .unmatched])
        XCTAssertEqual(tracks[0].chosen?.albumId, "sp-al1")
        XCTAssertEqual(tracks[2].candidates.map(\.track.id), ["spotify:s3"])
        // al1: where its two matched songs are; al2: by an album search.
        XCTAssertEqual(albums.map(\.source.id), ["al1", "al2"])
        XCTAssertEqual(albums.map(\.status), [.matched, .matched])
        XCTAssertEqual(albums.map { $0.chosen?.id }, ["sp-al1", "sp-al2"])
        XCTAssertEqual(progress, Array(1...6))
    }

    func test_should_askTheSourceForAnIsrc_onlyWhenSearchingDidntSettleIt() async throws {
        let plan = try await facade().planTransfer(source: from, items: items(), target: to)
        let catalog = FakeCatalog()
        catalog.tracks["Paper Lanterns Kiri Ono"] = [song("s1", "Paper Lanterns", 215)]
        catalog.described["t2"] = MatchTrack(id: "subsonic:t2", title: "Harbor Lights", artist: "Kiri Ono", album: nil, durationSec: nil,
                                             isrc: "JPAB01234567")
        catalog.byIsrc["JPAB01234567"] = [song("s2-isrc", "Harbor Lights (2024 Remaster)", 251)]

        let (tracks, _) = await TransferMatching.match(plan, catalog: catalog)

        XCTAssertEqual(tracks[0].chosen?.track.id, "spotify:s1")
        XCTAssertEqual(tracks[1].status, .matched)
        XCTAssertEqual(tracks[1].chosen?.track.id, "spotify:s2-isrc")
    }

    func test_should_leaveEverythingUnmatched_when_thereIsNoCatalog() async throws {
        let plan = try await facade().planTransfer(source: from, items: items(), target: to)
        let (tracks, albums) = await TransferMatching.match(plan, catalog: nil)
        XCTAssertTrue(tracks.allSatisfy { $0.status == .unmatched })
        XCTAssertTrue(albums.allSatisfy { $0.status == .unmatched })
    }

    func test_should_buildThePicks_fromTheReviewsDecisions() {
        let a = song("s1", "Paper Lanterns", 215), b = song("s3a", "Undertow (Live)", 300), c = song("s3b", "Undertow", 301)
        let tracks = [
            TransferTrackMatch(source: ref("t1"), sourceTrack: nil, status: .matched, candidates: [a], chosen: a),
            TransferTrackMatch(source: ref("t2"), sourceTrack: nil, status: .matched, candidates: [a], chosen: a),
            TransferTrackMatch(source: ref("t3"), sourceTrack: nil, status: .uncertain, candidates: [b, c], chosen: nil),
            TransferTrackMatch(source: ref("t4"), sourceTrack: nil, status: .unmatched, candidates: [], chosen: nil),
        ]
        var rows = TransferMatching.reviewRows(tracks)
        XCTAssertEqual(rows.map(\.checked), [true, true, false, false])
        XCTAssertEqual(rows.map(\.status), [.matched, .matched, .uncertain, .notfound])
        // The user unchecks t2 and picks the studio cut for t3.
        rows[1] = ReviewRow(position: 1, status: rows[1].status, candidates: rows[1].candidates, chosen: 0, checked: false)
        rows[2] = ReviewRow(position: 2, status: rows[2].status, candidates: rows[2].candidates, chosen: 1, checked: true)

        let picks = TransferMatching.trackPicks(tracks, rows: rows)

        XCTAssertEqual(picks.map(\.status), [.matched, .uncertain, .picked, .unmatched])
        XCTAssertEqual(picks.map { $0.chosen?.id }, ["s1", nil, "s3b", nil])
        XCTAssertEqual(picks[0].chosen?.provider, "spotify")
        XCTAssertEqual(picks[2].chosen?.albumId, "sp-al1")
        XCTAssertEqual(picks[2].chosen?.title, "Undertow")
    }

    /// End to end over the shared core: the picks copy what they name, and an earlier pick comes back next time.
    func test_should_copyWithThePicks_andReuseThemNextRun() async throws {
        let backup = facade()
        let plan = try await backup.planTransfer(source: from, items: items(), target: to)
        let catalog = FakeCatalog()
        catalog.tracks["Paper Lanterns Kiri Ono"] = [song("s1", "Paper Lanterns", 215)]
        let (tracks, albums) = await TransferMatching.match(plan, catalog: catalog)
        let rows = TransferMatching.reviewRows(tracks)
        XCTAssertEqual(TransferMatching.trackPicks(tracks, rows: rows).filter { $0.chosen != nil }.count, 1)
        XCTAssertEqual(TransferMatching.albumPicks(albums, plan: plan).count, 2)
    }
}

/// Android's copy as the backup and transfer pages read it (Localizable.xcstrings, manual keys).
@MainActor
final class PortabilityCopyTests: XCTestCase {
    func test_should_resolveEveryAndroidKey_inThisLanguage() {
        for key in ["settings_sync_export_title", "settings_sync_import_title", "settings_xfer_title", "settings_sync_read_checksum",
                    "settings_sync_import_verified_summary", "settings_xfer_step_copy", "playlist_restore_to_check", "settings_xfer_neodb_note"] {
            XCTAssertNotEqual(SyncCopy.s(key), key)
        }
        XCTAssertTrue(SyncCopy.s("settings_xfer_step", 2, 5, "X").contains("5"))
        XCTAssertTrue(SyncCopy.s("settings_xfer_clean_body", "kiri", "N", "Chen").contains("kiri"))
    }

    func test_should_splitAPluralStat_intoItsNumberAndUnit() {
        let one = SyncCopy.stat("settings_sync_unit_accounts", 1), many = SyncCopy.stat("settings_sync_unit_accounts", 3)
        XCTAssertEqual(one.0, "1")
        XCTAssertEqual(many.0, "3")
        XCTAssertNotNil(many.1)
        XCTAssertFalse(many.1!.contains("%"))
        XCTAssertEqual(SyncCopy.joined(["a"]), "a")
        XCTAssertTrue(SyncCopy.joined(["a", "b", "c"]).hasSuffix("c"))
    }
}
