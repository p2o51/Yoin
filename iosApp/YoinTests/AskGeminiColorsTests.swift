import SwiftUI
import UIKit
import XCTest
@testable import Yoin

/// Ask Gemini's words on its primaryContainer bar (Android AskGeminiBar: onPrimaryContainer for the
/// label, the typed question and the 60 % placeholder), in light and dark, on every mock cover.
@MainActor
final class AskGeminiColorsTests: XCTestCase {
    private func luminance(_ color: Color) -> Double {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        func lin(_ c: CGFloat) -> Double {
            let c = Double(c)
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private func contrast(_ a: Color, _ b: Color) -> Double {
        let (x, y) = (luminance(a), luminance(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    func test_should_useOnPrimaryContainer_when_drawingTheAskBarsWords() {
        for album in MockLibrary.allAlbums {
            for dark in [false, true] {
                let scheme = CoverScheme(album: album, dark: dark)
                XCTAssertEqual(AskGeminiBar.ink(scheme), scheme.onPrimaryContainer, "\(album.title) dark=\(dark)")
            }
        }
    }

    func test_should_readAtAA_when_theBarIsOnAnyCoverInLightAndDark() {
        for album in MockLibrary.allAlbums {
            for dark in [false, true] {
                let scheme = CoverScheme(album: album, dark: dark)
                let c = contrast(AskGeminiBar.ink(scheme), scheme.primaryContainer)
                XCTAssertGreaterThanOrEqual(c, 4.5, "\(album.title) dark=\(dark): \(c)")
            }
        }
    }

    func test_should_notUseWhite_when_dark_becauseTheDarkContainerIsALightTone() {
        // What the bar wore in dark before: white on the dark scheme's primaryContainer.
        let worst = MockLibrary.allAlbums.map { contrast(.white, CoverScheme(album: $0, dark: true).primaryContainer) }.min() ?? 0
        XCTAssertLessThan(worst, 4.5, "white is below AA on some dark cover containers")
    }
}
