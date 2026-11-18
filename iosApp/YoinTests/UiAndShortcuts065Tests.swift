import CoreHaptics
import SwiftUI
import UIKit
import XCTest
@testable import Yoin

@MainActor
final class UiAndShortcuts065Tests: XCTestCase {
    private func sampleTrack(id: String = "t1", provider: String = SpotifyMapping.provider, durationSec: Double = 210) -> PlayableTrack {
        PlayableTrack(
            id: id,
            provider: provider,
            title: "Sample Song",
            artist: "Sample Artist",
            album: "Sample Album",
            albumId: "alb1",
            durationSec: durationSec,
            streamURL: URL(string: "https://example.com/\(id).mp3")!,
            coverURL: nil,
            sourceKbps: 320
        )
    }

    // MARK: - ShellBarScrollHide & Library FastScroller Inset

    func test_should_computeScrollHideDelta_onlyInsideScrollableRangeAndPastTop() {
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: 0, to: 48, maxOffset: 300), 48)
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: 48, to: 12, maxOffset: 300), -36)
        // Bounce past bottom and return back inside bottom must not hide the bar.
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: 300, to: 330, maxOffset: 300), 0)
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: 330, to: 300, maxOffset: 300), 0)
        // Pull further past top reveals; snapping back from top overscroll does not hide.
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: 0, to: -24, maxOffset: 300), -24)
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: -24, to: 0, maxOffset: 300), 0)
        // Unscrollable feed never hides the bar.
        XCTAssertEqual(ShellBarScrollHide.scrollHideDelta(from: 0, to: 40, maxOffset: 0), 0)
    }

    func test_should_updateAndResetFraction_when_scrollingAndDisabling() {
        let hide = ShellBarScrollHide()
        hide.scrolled(by: ShellBarScrollHide.hideDistance / 2)
        XCTAssertEqual(hide.fraction, 0.5, accuracy: 0.001)

        hide.scrolled(by: ShellBarScrollHide.hideDistance)
        XCTAssertEqual(hide.fraction, 1.0, accuracy: 0.001)

        hide.scrolled(by: -ShellBarScrollHide.hideDistance / 4)
        XCTAssertEqual(hide.fraction, 0.75, accuracy: 0.001)

        hide.showUnseen()
        XCTAssertEqual(hide.fraction, 0.0, accuracy: 0.001)
    }

    func test_should_reduceFastScrollerBottomInset_onPadComparedToPhone() {
        let phoneInset = LibraryScrollerMetrics.bottomInset(isPad: false)
        let padInset = LibraryScrollerMetrics.bottomInset(isPad: true)
        XCTAssertEqual(phoneInset, Tokens.library.BOTTOM_GROUP_PADDING.cg - ShellBarMetrics.clearance, accuracy: 0.01)
        XCTAssertEqual(padInset, Tokens.library.BOTTOM_GROUP_PADDING.cg - ShellBarMetrics.clearance - PadDockMetrics.extraShellClearance, accuracy: 0.01)
        XCTAssertLessThan(padInset, phoneInset)
    }

    // MARK: - TextInputFocus & Keyboard Shortcuts

    func test_should_detectTextInputResponders_andIgnorePlainViews() {
        XCTAssertTrue(TextInputFocus.isTextInputResponder(UITextField()))
        XCTAssertTrue(TextInputFocus.isTextInputResponder(UITextView()))
        XCTAssertTrue(TextInputFocus.isTextInputResponder(UISearchBar()))

        let field = UITextField()
        let innerView = UIView()
        field.addSubview(innerView)
        XCTAssertTrue(TextInputFocus.isTextInputResponder(innerView))

        XCTAssertFalse(TextInputFocus.isTextInputResponder(UIView()))
        XCTAssertFalse(TextInputFocus.isTextInputResponder(nil))
    }

    func test_should_reflectExplicitAndOverrideTextFocus_inIsActive() {
        let focus = TextInputFocus.shared
        defer {
            focus.testResponderProvider = nil
        }

        let plain = UIView()
        focus.testResponderProvider = { plain }
        XCTAssertFalse(focus.isActive)

        focus.pushExplicitFocus()
        XCTAssertTrue(focus.isActive)
        focus.popExplicitFocus()
        XCTAssertFalse(focus.isActive)

        let field = UITextField()
        focus.testResponderProvider = { field }
        XCTAssertTrue(focus.isActive)
    }

    func test_should_routeKeyboardFocusAndLyricsCommands_whenPlaying() {
        let model = AppModel()
        var focusCalls = 0
        var lyricsCalls = 0
        model.player.play(queue: [sampleTrack(provider: "subsonic", durationSec: 120)])
        model.npExpanded = true
        model.npKeyCommands[.full] = NowPlayingKeyCommandTarget(
            token: UUID(),
            toggleFocus: { focusCalls += 1 },
            toggleLyrics: { lyricsCalls += 1 }
        )

        model.toggleFocusFromKeyboard()
        model.toggleLyricsFromKeyboard()
        XCTAssertEqual(focusCalls, 1)
        XCTAssertEqual(lyricsCalls, 1)

        model.npExpanded = false
        model.pendingNPKeyCommand = nil
        model.toggleFocusFromKeyboard()
        XCTAssertEqual(model.pendingNPKeyCommand, .focus)
        XCTAssertTrue(model.npExpanded)
    }

    func test_should_clampKeyboardSeekWithinTrackDuration() {
        let model = AppModel()
        model.player.play(queue: [sampleTrack(provider: "subsonic", durationSec: 100)])
        model.seekFromKeyboard(by: -10)
        XCTAssertEqual(model.player.progress.positionSec, 0, accuracy: 0.5)
        model.seekFromKeyboard(by: 5)
        XCTAssertEqual(model.player.progress.positionSec, 5, accuracy: 0.5)
        model.seekFromKeyboard(by: 200)
        XCTAssertEqual(model.player.progress.positionSec, 100, accuracy: 0.5)
    }

    // MARK: - markSpotifyRevoked & Discoverable AppIntents

    func test_should_clearStaleExternalTrack_when_markSpotifyRevoked() {
        let model = AppModel()
        let accountId = "spotify-revoke-test"
        model.player.outputExternalTrackDidChange(sampleTrack(), positionSec: 42, playing: true)
        SpotifyPlayingDevice.shared.name = "Living Room"
        defer {
            SpotifyPlayingDevice.shared.name = nil
            model.player.stop()
        }

        XCTAssertNotNil(model.player.externalTrack)
        model.markSpotifyRevoked(accountId)

        XCTAssertTrue(model.profiles.spotifyRevoked.contains(accountId))
        XCTAssertNil(model.player.externalTrack)
        XCTAssertNil(model.player.currentTrack)
        XCTAssertNil(SpotifyPlayingDevice.shared.name)
    }

    func test_should_exposeDiscoverablePlaybackAppIntents() {
        XCTAssertTrue(PlayPausePlaybackIntent.isDiscoverable)
        XCTAssertTrue(NextTrackIntent.isDiscoverable)
        XCTAssertTrue(PreviousTrackIntent.isDiscoverable)
        XCTAssertEqual(YoinShortcutsProvider.appShortcuts.count, 3)
    }

    // MARK: - Now Playing Polish (Marquee, Glow Wave, CoreHaptics Pluck, 16pt Seat, About)

    func test_should_fadeTrailingAtRestAndBothEdgesWhileScrolling_inMarqueeText() {
        let restStops = MarqueeText.edgeMaskStops(width: 200, edgeFade: 28, scrollOffset: 0)
        XCTAssertEqual(restStops.count, 3)
        XCTAssertEqual(restStops.first?.location ?? -1, 0, accuracy: 0.001)

        let movingStops = MarqueeText.edgeMaskStops(width: 200, edgeFade: 28, scrollOffset: -18)
        XCTAssertEqual(movingStops.count, 4)
        XCTAssertEqual(movingStops.first?.location ?? -1, 0, accuracy: 0.001)
        XCTAssertEqual(movingStops.last?.location ?? -1, 1, accuracy: 0.001)
    }

    func test_should_handOverPreviousTranslationWave_when_toggledAgain() {
        let glow = NowPlayingGlow()
        XCTAssertEqual(NowPlayingGlow.Kind.translationWave.duration(1), 1.9, accuracy: 0.001)
        XCTAssertTrue(NowPlayingGlow.Kind.translationWave.moves)

        glow.glow(.translationWave, at: nil, value: 1, area: CGRect(x: 0, y: 120, width: 300, height: 400))
        glow.glow(.translationWave, at: nil, value: 0, area: CGRect(x: 0, y: 120, width: 300, height: 400))
        XCTAssertEqual(glow.lights.count, 2)
        XCTAssertNotNil(glow.lights[0].retired)
        XCTAssertNil(glow.lights[1].retired)
    }

    func test_should_buildMultiTransientPluckHaptics_andRunSafelyOnSimulator() {
        let events = YoinHaptics.pluckEvents(strength: 1.0)
        XCTAssertEqual(events.count, 4)
        XCTAssertEqual(events[0].relativeTime, 0.0, accuracy: 0.0001)
        XCTAssertGreaterThan(events[3].relativeTime, events[1].relativeTime)
        YoinHaptics.performPluck(opening: true)
        YoinHaptics.performPluck(opening: false)
    }

    func test_should_debounceLyricsSettledSeat_within16Points() {
        // 0.03 * 400 = 12pt <= 16pt -> keeps actual seat.
        let held = ExpandedLyricsList.lyricsSettledSeat(actual: 0.4, next: 0.43, band: 400)
        XCTAssertEqual(held, 0.4, accuracy: 0.0001)

        // 0.10 * 400 = 40pt > 16pt -> adopts next seat.
        let moved = ExpandedLyricsList.lyricsSettledSeat(actual: 0.4, next: 0.5, band: 400)
        XCTAssertEqual(moved, 0.5, accuracy: 0.0001)

        XCTAssertEqual(ExpandedLyricsList.lyricsSettledSeat(actual: nil, next: 0.5, band: 400), 0.5, accuracy: 0.0001)
    }

    func test_should_preserveServerFactsWhileSearching_andDetectOverflowInCompactAbout() {
        let track = sampleTrack(provider: "subsonic")
        let facts = SongFacts(track)
        let song = AboutSong(track)
        let loader = SongAboutLoader.shared

        let credits = SongCredits(lyricist: "Test Lyricist")
        XCTAssertTrue(NowPlayingAboutView.hasSourceRows(facts: nil, credits: credits))
        XCTAssertTrue(NowPlayingAboutView.hasSourceRows(facts: facts, credits: nil))
        XCTAssertFalse(NowPlayingAboutView.hasSourceRows(facts: nil, credits: nil))

        XCTAssertFalse(NowPlayingAboutView.compactOverflows(containerHeight: 140, contentHeight: 96))
        XCTAssertTrue(NowPlayingAboutView.compactOverflows(containerHeight: 140, contentHeight: 180))

        // Ensure .ready server facts are preserved inside .pending / .searching when AI key exists or loading.
        _ = NowPlayingData.AboutState.compose(server: .ready(facts), song: song, loader: loader)
        if case .searching(let kept) = NowPlayingData.AboutState.searching(facts) {
            XCTAssertEqual(kept, facts)
        } else {
            XCTFail("Expected facts carried on searching")
        }
        if case .pending(_, let kept) = NowPlayingData.AboutState.pending(song, facts) {
            XCTAssertEqual(kept, facts)
        } else {
            XCTFail("Expected facts carried on pending")
        }
    }
}
