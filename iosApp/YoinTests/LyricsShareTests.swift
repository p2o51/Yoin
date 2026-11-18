import SwiftUI
import XCTest
@testable import Yoin

/// The lyric share image's formats and tones (NowPlayingLyricsTools, Android LyricsShareCard).
@MainActor
final class LyricsShareTests: XCTestCase {
    func test_should_keepTheCardAsIs_when_itFitsTheStory() {
        XCTAssertEqual(LyricsShareLayout.storyScale(card: CGSize(width: 300, height: 400)), 1)
    }

    func test_should_shrinkTheCardToFit_when_thePickIsLong() {
        // Room: 304 × 496. A 376-wide card scales to its width; a tall one to its height.
        XCTAssertEqual(LyricsShareLayout.storyScale(card: CGSize(width: 376, height: 300)), 304 / 376, accuracy: 0.0001)
        XCTAssertEqual(LyricsShareLayout.storyScale(card: CGSize(width: 300, height: 992)), 0.5, accuracy: 0.0001)
    }

    func test_should_offerFourTones_inAndroidsOrder() {
        XCTAssertEqual(LyricsCardTone.allCases, [.soft, .deep, .vivid, .mist])
        XCTAssertEqual(LyricsShareFormat.allCases, [.card, .story])
    }

    func test_should_renderBothFormats_when_exporting() {
        let snap = LyricsShareSnapshot(lines: ["One line", "Another"], translations: [nil, "一行"], passageStarts: [],
                                       title: "Song", artist: "Artist")
        let scheme = CoverScheme(seed: .purple, dark: true)
        for format in LyricsShareFormat.allCases {
            let r = ImageRenderer(content: LyricsShareImage(snapshot: snap, scheme: scheme, format: format, tone: .vivid))
            r.scale = 3
            let size = r.uiImage?.size ?? .zero
            if format == .story {
                XCTAssertEqual(size.width * 3, 1080)
                XCTAssertEqual(size.height * 3, 1920)
            } else {
                XCTAssertEqual(size.width, LyricsShareLayout.cardWidth)
            }
        }
    }
}
