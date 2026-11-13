import XCTest
@testable import Yoin

/// Drag and drop (Library/YoinDrag.swift): what a drag remembers per account, which drops pass, where a
/// drop on a queue row lands, and the plain text other apps get.
@MainActor
final class YoinDragTests: XCTestCase {
    private let palette = CoverPalette(a: .red, b: .green, c: .blue)

    private func album(_ i: Int) -> Album {
        Album(id: MediaId(provider: "subsonic", rawId: "a\(i)"), title: "A\(i)", artist: "X", year: 2020,
              palette: palette, tracks: [])
    }

    private func song(_ i: Int) -> PlaylistEntry {
        PlaylistEntry(album: album(100 + i),
                      track: Track(id: MediaId(provider: "subsonic", rawId: "s\(i)"), number: 1, title: "S\(i)", durationSec: 100))
    }

    private func item(_ scope: String, _ id: String = "s1") -> YoinDragItem {
        YoinDragItem(kind: .song, scope: scope, id: id, text: "")
    }

    func test_should_keepToken_when_sameAccount() {
        var r = YoinDrag.Registry()
        let a = r.scope(1, makeToken: { "t1" })
        r.remember(song(1))
        XCTAssertEqual(r.scope(1, makeToken: { "t2" }), a)
        XCTAssertNotNil(r.songs["s1"])
    }

    func test_should_forgetAndRefuseOldDrags_when_accountChanges() {
        var r = YoinDrag.Registry()
        let old = r.scope(1, makeToken: { "t1" })
        r.remember(song(1))
        r.remember(album(1))
        _ = r.scope(2, makeToken: { "t2" })
        XCTAssertTrue(r.songs.isEmpty)
        XCTAssertTrue(r.albums.isEmpty)
        XCTAssertEqual(r.accepted([item(old), item("t2")]).map(\.scope), ["t2"])
    }

    func test_should_acceptNothing_when_nothingWasDragged() {
        let r = YoinDrag.Registry()
        XCTAssertTrue(r.accepted([item("")]).isEmpty)
    }

    func test_should_staySmall_when_manySongsDragged() {
        var r = YoinDrag.Registry()
        _ = r.scope(1)
        for i in 0..<YoinDrag.Registry.maxSongs { r.remember(song(i)) }
        XCTAssertEqual(r.songs.count, YoinDrag.Registry.maxSongs)
        r.remember(song(9999))
        XCTAssertEqual(r.songs.count, 1)
        XCTAssertNotNil(r.songs["s9999"])
    }

    func test_should_moveAddedBeforeAnchor_when_droppedOnARow() {
        let q = (0..<6).map { _ in UUID() }
        // q0 current, q1 q2 up next, q3 q4 just added at the end, q5 after them.
        let anchor = q[2]
        let move = YoinDrag.insertion(added: [q[3], q[4]], before: anchor, in: q)
        XCTAssertEqual(move?.offsets, IndexSet([3, 4]))
        XCTAssertEqual(move?.destination, 2)
        var queue = q
        let moving = move!.offsets.map { queue[$0] }
        for i in move!.offsets.sorted(by: >) { queue.remove(at: i) }
        queue.insert(contentsOf: moving, at: move!.destination)
        XCTAssertEqual(queue, [q[0], q[1], q[3], q[4], q[2], q[5]])
    }

    func test_should_notMove_when_alreadyBeforeAnchorOrAnchorGone() {
        let q = (0..<4).map { _ in UUID() }
        XCTAssertNil(YoinDrag.insertion(added: [q[1], q[2]], before: q[3], in: q))
        XCTAssertNil(YoinDrag.insertion(added: [q[1]], before: UUID(), in: q))
        XCTAssertNil(YoinDrag.insertion(added: [], before: q[1], in: q))
    }

    func test_should_giveTitleOverArtist_when_draggedToAnotherApp() {
        XCTAssertEqual(YoinDrag.text("Paper Moon", "Mina Asahi"), "Paper Moon\nMina Asahi")
        XCTAssertEqual(YoinDrag.text("Paper Moon", ""), "Paper Moon")
    }

    func test_should_takeDropsOnlyOnOwnPlaylists_when_subsonicUnread() {
        XCTAssertTrue(YoinDrag.subsonicAccepts(owner: "demo", username: "demo"))
        XCTAssertTrue(YoinDrag.subsonicAccepts(owner: nil, username: "demo"))
        XCTAssertTrue(YoinDrag.subsonicAccepts(owner: "", username: "demo"))
        XCTAssertFalse(YoinDrag.subsonicAccepts(owner: "someone", username: "demo"))
    }

    func test_should_carryPlainText_when_encoded() throws {
        let i = YoinDragItem(kind: .album, scope: "t", id: "a1", text: "A1\nX")
        let back = try JSONDecoder().decode(YoinDragItem.self, from: JSONEncoder().encode(i))
        XCTAssertEqual(back, i)
    }
}
