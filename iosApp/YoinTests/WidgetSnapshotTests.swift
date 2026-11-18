import XCTest
@testable import Yoin

/// Home screen widgets' snapshot (Widgets/WidgetSnapshot, WidgetPublisher; Android widget/, parity A-F10):
/// its encoding, its links, its words, and when the app writes a new one.
@MainActor
final class WidgetSnapshotTests: XCTestCase {
    private func snapshot(play: [WidgetSnapshot.Play] = [], memories: [WidgetSnapshot.Memory] = []) -> WidgetSnapshot {
        WidgetSnapshot(account: "p1", play: play, memories: memories, neutral: .fallback)
    }

    private let album = WidgetSnapshot.Play(entity: .album, provider: "subsonic", id: "al-1", title: "Tide",
                                            subtitle: "Wave", cover: "abc.jpg", spotify: false)
    private let memory = WidgetSnapshot.Memory(provider: "spotify", albumId: "4x", albumName: "Glow", artist: "Aurora",
                                               cover: nil, spotify: true, score: 8.75, lastHeardAt: 1_700_000_000_000,
                                               title: "Night Swim", palette: .fallback)

    func test_should_roundTrip_when_encoded() throws {
        let s = snapshot(play: [album], memories: [memory])
        let data = try WidgetStore.encode(s)
        XCTAssertEqual(WidgetStore.decode(data), s)
        // Sorted keys: the same snapshot always writes the same bytes.
        XCTAssertEqual(try WidgetStore.encode(s), data)
    }

    func test_should_readNothing_when_anotherVersionWroteIt() throws {
        var s = snapshot(play: [album])
        s.version = 99
        XCTAssertNil(WidgetStore.decode(try WidgetStore.encode(s)))
        XCTAssertNil(WidgetStore.decode(Data("not json".utf8)))
    }

    func test_should_keepNoURLs_when_written() throws {
        // Covers travel as file names only: a Subsonic cover URL carries the sign-in.
        let json = String(decoding: try WidgetStore.encode(snapshot(play: [album], memories: [memory])), as: UTF8.self)
        XCTAssertFalse(json.contains("http"))
        XCTAssertFalse(json.contains("token"))
    }

    func test_should_writeAndReload_only_when_somethingChanged() {
        let s = snapshot(play: [album])
        XCTAssertTrue(WidgetPublisher.shouldWrite(old: nil, new: s))
        XCTAssertFalse(WidgetPublisher.shouldWrite(old: s, new: s))
        var played = s
        played.play.insert(WidgetSnapshot.Play(entity: .playlist, provider: "subsonic", id: "pl", title: "Mix",
                                                subtitle: "", cover: nil, spotify: false), at: 0)
        XCTAssertTrue(WidgetPublisher.shouldWrite(old: s, new: played))
        var rated = snapshot(memories: [memory])
        let before = rated
        rated.memories[0].score = 9
        XCTAssertTrue(WidgetPublisher.shouldWrite(old: before, new: rated))
        var switched = s
        switched.account = "p2"
        XCTAssertTrue(WidgetPublisher.shouldWrite(old: s, new: switched))
    }

    func test_should_roundTripLinks_when_tapped() {
        let links: [WidgetLink] = [.open(entity: .album, provider: "subsonic", id: "al 1/x"),
                                   .play(entity: .playlist, provider: "spotify", id: "37i9"),
                                   .memory(provider: "subsonic", albumId: "al-2"), .app]
        for l in links { XCTAssertEqual(WidgetLink(url: l.url), l) }
        XCTAssertNil(WidgetLink(url: URL(string: "yoin://auth/spotify/callback")!))
        XCTAssertNil(WidgetLink(url: URL(string: "yoin://widget/open?entity=album&provider=x")!))
    }

    func test_should_sayTheScoreToOneDecimal_halfUp() {
        XCTAssertEqual(WidgetCopy.score(8.75), "8.8")
        XCTAssertEqual(WidgetCopy.score(9), "9.0")
        XCTAssertEqual(WidgetCopy.score(7.04), "7.0")
    }

    func test_should_sayHowLongAgo_inAndroidsSteps() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func ago(_ days: Double) -> String? {
            WidgetCopy.lastHeard(Int64((now.timeIntervalSince1970 - days * 86_400) * 1000), now: now)
        }
        XCTAssertNil(WidgetCopy.lastHeard(nil, now: now))
        XCTAssertEqual(ago(0.5), zh ? "今天" : "Today")
        XCTAssertEqual(ago(1), zh ? "1 天前" : "1 Day Ago")
        XCTAssertEqual(ago(12), zh ? "12 天前" : "12 Days Ago")
        XCTAssertEqual(ago(95), zh ? "3 个月前" : "3 Months Ago")
        XCTAssertEqual(ago(800), zh ? "2 年前" : "2 Years Ago")
    }

    func test_should_putEligibleMemoriesMostRecentlyHeardFirst() {
        func m(_ id: String, played: Int64?, eligible: Bool = true) -> AlbumMemory {
            AlbumMemory(album: Album(id: MediaId(provider: "subsonic", rawId: id), title: id, artist: "", year: 0,
                                     palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: []),
                        totalTracks: 1, ratedTrackCount: 0, coverage: 0, averageSongRating: nil, albumRating: 8,
                        noteCount: 0, lastWrittenAt: 1, playCount: 1, firstPlayedAt: played, lastPlayedAt: played,
                        isMemoryEligible: eligible)
        }
        let order = WidgetPublisher.memoryOrder([m("a", played: 10), m("b", played: 30), m("c", played: 20, eligible: false)])
        XCTAssertEqual(order.map(\.albumId), ["b", "a"])
    }

    func test_should_nameCoversWithoutTheURL() {
        let ref = CoverRef(url: URL(string: "http://h:4533/rest/getCoverArt.view?id=al-1&size=300&t=secret&s=salt")!)
        let name = WidgetPublisher.coverName(ref)
        XCTAssertTrue(name.hasSuffix(".jpg"))
        XCTAssertFalse(name.contains("secret"))
        // The sign-in in the query doesn't change the file.
        let other = CoverRef(url: URL(string: "http://h:4533/rest/getCoverArt.view?id=al-1&size=300&t=other&s=x")!)
        XCTAssertEqual(WidgetPublisher.coverName(other), name)
    }
}
