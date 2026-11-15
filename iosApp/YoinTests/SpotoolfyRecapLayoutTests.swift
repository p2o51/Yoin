import XCTest
@testable import Yoin

/// The recap's keyword burst and cover wall (Android RecapLayoutTest).
final class SpotoolfyRecapLayoutTests: XCTestCase {
    func test_should_placeFirstWordAtCentre_when_areaIsEmpty() {
        let boxes = SpotoolfyRecapLayout.spiral([CGSize(width: 100, height: 40)], width: 300, height: 400,
                                                obstacles: [], gap: 6, step: 4)
        XCTAssertEqual(boxes, [RecapBox(x: 100, y: 180, width: 100, height: 40)])
    }

    func test_should_neverOverlapWordsOrCards_when_manyWordsArePlaced() {
        let card = RecapBox(x: 0, y: 0, width: 200, height: 80)
        let sizes = (0..<30).map { i in CGSize(width: 60 + CGFloat(i % 4) * 20, height: 30 + CGFloat(i % 3) * 8) }
        let placed = SpotoolfyRecapLayout.spiral(sizes, width: 320, height: 420, obstacles: [card], gap: 6, step: 4)
            .compactMap { $0 }
        XCTAssertGreaterThan(placed.count, 10)
        for (i, box) in placed.enumerated() {
            XCTAssertFalse(box.overlaps(card, gap: 6))
            XCTAssertTrue(box.x >= 0 && box.y >= 0 && box.x + box.width <= 320 && box.y + box.height <= 420)
            for other in placed.dropFirst(i + 1) { XCTAssertFalse(box.overlaps(other, gap: 6)) }
        }
    }

    func test_should_leaveWordOut_when_itCannotFit() {
        let boxes = SpotoolfyRecapLayout.spiral([CGSize(width: 500, height: 40)], width: 300, height: 400,
                                                obstacles: [], gap: 6, step: 4)
        XCTAssertEqual(boxes.count, 1)
        XCTAssertNil(boxes[0])
    }

    func test_should_placeTheSameWay_when_laidOutAgain() {
        let sizes = (0..<12).map { CGSize(width: 50 + CGFloat($0) * 3, height: 32) }
        let first = SpotoolfyRecapLayout.spiral(sizes, width: 300, height: 300, obstacles: [], gap: 6, step: 4)
        XCTAssertEqual(first, SpotoolfyRecapLayout.spiral(sizes, width: 300, height: 300, obstacles: [], gap: 6, step: 4))
    }

    func test_should_sizeWordsByRank_when_buildingTheBurst() {
        let keywords = (0..<20).map { RecapKeyword(text: "w\($0)", count: 20 - $0, notes: []) }
        let words = SpotoolfyRecapLayout.words(keywords, emoji: [RecapEmoji(emoji: "🔥", count: 9)])
        XCTAssertEqual(words.count, 21)
        XCTAssertEqual(words[4].text, "🔥")
        XCTAssertNil(words[4].keyword)
        XCTAssertEqual(words[0].tier, .largest)
        XCTAssertEqual(words.first { $0.text == "w3" }?.tier, .large)
        XCTAssertEqual(words.last?.tier, .small)
    }

    func test_should_fillTheWall_when_thereAreFewOrNoCovers() {
        XCTAssertEqual(SpotoolfyRecapLayout.wallCovers([]).count, 20)
        XCTAssertTrue(SpotoolfyRecapLayout.wallCovers([]).allSatisfy { $0 == nil })
        XCTAssertEqual(SpotoolfyRecapLayout.wallCovers(["a", "b"]).prefix(4), ["a", "b", "a", "b"])
        XCTAssertEqual(SpotoolfyRecapLayout.wallCovers((0..<40).map(String.init)).count, 24)
    }

    func test_should_keepEveryTileInside_when_laidOutOnAWall() {
        let spots = SpotoolfyRecapLayout.wall(count: 24, tile: 58, width: 340, height: 300)
        XCTAssertEqual(spots.count, 24)
        for s in spots {
            XCTAssertTrue(s.center.x >= 29 && s.center.x <= 340 - 29)
            XCTAssertTrue(s.center.y >= 29 && s.center.y <= 300 - 29)
        }
    }
}
