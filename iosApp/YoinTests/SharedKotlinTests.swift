import SwiftUI
import XCTest
import YoinShared
@testable import Yoin

/// The Swift code that now calls YoinShared (Kotlin) against the Swift it
/// replaced: same values where the iOS port was faithful, Android's where it
/// was an approximation (documented in iosApp/docs/shared-kmp.md).
@MainActor
final class SharedKotlinTests: XCTestCase {
    /// The Balanced preset: the Current defaults with the Balanced overrides
    /// (as GlassLab.swift built it before it moved to YoinTokens.Glass) plus
    /// 51's 2026-11-03 decisions.
    func test_should_keep_the_balanced_glass_preset_when_read_from_shared_tokens() {
        var expected: [String: SurfaceStyle] = [:]
        var expectedExtras: [String: Double] = [:]
        for surface in GlassLab.surfaces {
            expected[surface.id] = surface.defaults
            for x in surface.extras { expectedExtras[x.0] = x.3 }
        }
        expectedExtras["detailTopBar"] = 4
        expectedExtras["albumPullTravel"] = 320
        expectedExtras["albumPullSnap"] = 0.3
        expectedExtras["albumStripExtend"] = 6
        expected["homeCards"] = SurfaceStyle(variant: .material, role: .auto, tintOpacity: 0.45,
                                             interactive: false, cornerRadius: 16)
        // 51, 2026-11-03: rating track and NP pills are glass; the selected bar
        // button's color goes into the glass.
        expected["ratingTrack"] = SurfaceStyle(role: .surfaceVariant, tintOpacity: 0.3, interactive: false)
        expected["npPills"] = SurfaceStyle(role: .primary, tintOpacity: 0.55)
        expected["barSelected"] = SurfaceStyle(tintOpacity: 0.85)
        expectedExtras["barSelectedStyle"] = 1
        expectedExtras["lyricsUnder"] = 0
        // Gemini preset extras: neutral (no effect) in Balanced.
        expectedExtras["darkTintMultiplier"] = 1
        expectedExtras["sheetPresenterDim"] = 0
        expectedExtras["searchChromeTint"] = 0
        // World preset extras: neutral (no effect) in Balanced.
        for (id, v) in [("heartLikedAccent", 1.0), ("liftInFlow", 0), ("liftRestOpacity", 0.6), ("neutralShadow", 0),
                        ("recedeScale", 0), ("recedeDim", 0), ("recedeBlur", 0), ("coverContactShadow", 0),
                        ("sheetRecedeScale", 0), ("sheetRecedeDim", 0), ("sheetRecedeBlur", 0), ("pillAmbient", 0),
                        ("darkAmbientStrength", 0), ("darkNeighbourStrength", 0), ("auraOnTop", 0)] {
            expectedExtras[id] = v
        }
        expected["transportSecondary"] = SurfaceStyle(tintOn: false, tintOpacity: 0, shadowOpacity: 0)
        expected["transportPrimary"] = SurfaceStyle(tintOpacity: 0.55, shadowOpacity: 0.18, contrastShadow: 0.22)
        expected["heart"] = SurfaceStyle(tintOn: false, tintOpacity: 0)
        // The NP floating chrome wears the pills' glass outside World.
        expected["npFloating"] = expected["npPills"]

        let lab = GlassLab.shared
        let saved = (lab.preset, lab.styles, lab.extras)
        lab.apply(.balanced)
        XCTAssertEqual(lab.styles, expected)
        XCTAssertEqual(lab.extras, expectedExtras)
        lab.styles = saved.1
        lab.extras = saved.2
        lab.preset = saved.0
    }

    /// World (2026-11-04) is the default: Gemini's colours outside Now
    /// Playing, the cover colour inside it at least Balanced's strength.
    func test_should_default_to_world_with_gemini_shell_and_strong_np_colour_when_applied() {
        let lab = GlassLab.shared
        let saved = (lab.preset, lab.styles, lab.extras)
        defer { lab.styles = saved.1; lab.extras = saved.2; lab.preset = saved.0 }
        lab.apply(.gemini)
        let gemini = (lab.styles, lab.extras)
        lab.apply(.balanced)
        let balanced = (lab.styles, lab.extras)
        lab.reset()
        XCTAssertEqual(lab.preset, .world)
        // Outside NP: Gemini's colours.
        for id in ["barUnselected", "barSelected", "homeCards"] {
            XCTAssertEqual(lab.style(id).role, gemini.0[id]?.role, id)
            XCTAssertEqual(lab.style(id).tintOpacity, gemini.0[id]?.tintOpacity ?? -1, id)
        }
        XCTAssertEqual(lab.extra("coverBlur"), gemini.1["coverBlur"])
        // Inside NP: the cover colour at least Balanced's.
        for id in ["ambientStrength", "neighbourStrength"] {
            XCTAssertGreaterThanOrEqual(lab.extra(id), balanced.1[id] ?? 0, id)
        }
        XCTAssertGreaterThanOrEqual(lab.style("transportPrimary").tintOpacity,
                                    balanced.0["transportPrimary"]?.tintOpacity ?? 0)
        XCTAssertEqual(lab.style("transportPrimary").role, .auto)
        // R1: the in-flow fixtures are tonal, the chrome over the lyrics glass.
        // 51 (11-04): Queue / Devices / Write stay glass, on a neutral floor
        // (PAUSE the one accent); the rating is the solid fixture that lifts.
        XCTAssertEqual(lab.style("npPills").variant, .regular)
        XCTAssertTrue(lab.neutralTint("npPills"))
        XCTAssertEqual(lab.extra("liftInFlow"), 1)
        XCTAssertEqual(lab.style("heart").variant, .none)
        // The pinned filter chips: the HIG glass track with one tinted lozenge.
        XCTAssertEqual(SegmentChipStyle.current, .glassTrack)
        XCTAssertTrue(SegmentChipStyle.current.isTrack)
        XCTAssertEqual(lab.style("transportSecondary").variant, .regular)
        XCTAssertEqual(lab.style("npFloating").variant, .regular)
        // R5: the mini player is the NP room folded.
        XCTAssertEqual(lab.style("miniPlayer").role, lab.style("npBackground").role)
    }

    /// The scrapbook's FNV-1a as AlbumScrapbookView.swift computed it.
    func test_should_hash_like_the_swift_fnv_when_tilting_scrapbook_pieces() {
        func fnv(_ s: String) -> UInt32 {
            var h: UInt32 = 0x811C_9DC5
            for u in s.utf16 { h ^= UInt32(u); h = h &* 0x0100_0193 }
            return h
        }
        for s in ["", "al1", "al1/cover", "al7/12/tk", "余韻/receipt", "🎵/heart", "spotify:album:4aaw/3/nt"] {
            XCTAssertEqual(Int64(fnv(s)), ScrapbookTilt.shared.hash(text: s), s)
        }
    }

    /// iPhone 17 Pro's column (778 pt): Android's budget squeezes the lower
    /// gaps to keep two clean lyric lines; every slot sums to the height.
    func test_should_fill_the_column_when_resolving_the_now_playing_budget() {
        for (w, h) in [(402.0, 778.0), (402.0, 700.0), (440.0, 846.0), (375.0, 600.0)] {
            let b = NowPlayingBudget(width: w, height: h, forceShort: false)
            let sum = b.topBar + b.cover + b.coverGap + (b.oneLineLyric ? 0 : b.tabs + b.tabGap)
                + b.lyricWindow + b.transport + b.hero + b.pills + b.slack
            XCTAssertEqual(sum, h, accuracy: 0.001, "\(w) × \(h)")
            XCTAssertEqual(b.cover, min(w - 48 - 12 - 56, 312), "\(w) × \(h)")
        }
        let short = NowPlayingBudget(width: 402, height: 778, forceShort: true)
        XCTAssertTrue(short.oneLineLyric)
        XCTAssertTrue(short.inlineHero)
        XCTAssertEqual(short.lyricWindow, 44)
        let shortSum = short.topBar + short.cover + short.coverGap + short.lyricWindow + short.transport
            + short.hero + short.pills + short.slack
        XCTAssertEqual(shortSum, 778, accuracy: 0.001)
    }

    func test_should_cut_every_mock_cover_into_bands_when_building_the_spectrum() {
        for album in MockLibrary.allAlbums {
            let strips = AlbumSpectrum.strips(for: album)
            XCTAssertEqual(strips.count, 28)
            XCTAssertEqual(Set(strips.map(\.slot)), Set(0..<28), album.title)
        }
    }
}
