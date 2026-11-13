import XCTest
import SwiftUI
@testable import Yoin

/// The tip page and its demo phone (Tips/): the frame's sizes, where the demo touch goes, the page's Next,
/// and the made-up data the scenes show. Numbers are Android's (ui/tips/TipDemoPhone.kt, TipScreen.kt).
@MainActor
final class TipDemoTests: XCTestCase {
    private func tip(_ id: String) -> Tip {
        Tip(id: id, icon: "sparkle", updated: "2026-10-17", title: id, text: id, titleZhHans: id, textZhHans: id)
    }

    // MARK: The frame

    func test_should_beAndroidsFrame_when_thePageIsNoTallerThanAPhone() {
        let m = DemoPhoneMetrics(container: CGSize(width: 402, height: 700))
        XCTAssertEqual(m.grow, 1)
        XCTAssertEqual(m.phone, CGSize(width: 168, height: 300))
        XCTAssertEqual(m.screen, CGSize(width: 360, height: 643))
        XCTAssertEqual(m.scale, 168.0 / 360.0, accuracy: 0.0001)
    }

    func test_should_beAndroidsFrame_when_thePageHasNoSizeYet() {
        XCTAssertEqual(DemoPhoneMetrics(container: .zero).phone, CGSize(width: 168, height: 300))
    }

    func test_should_growWithTheHeight_when_thePageIsTall() {
        // 36 % of the height over 300: a 1000-tall page seats a 1.2× phone.
        let m = DemoPhoneMetrics(container: CGSize(width: 800, height: 1000))
        XCTAssertEqual(m.grow, 1.2, accuracy: 0.0001)
        XCTAssertEqual(m.phone.width, 201.6, accuracy: 0.001)
        XCTAssertEqual(m.phone.height, 360, accuracy: 0.001)
        // The screen is laid out the same; only its drawing grows.
        XCTAssertEqual(m.screen, CGSize(width: 360, height: 643))
    }

    func test_should_stopAt1Point7_when_thePageIsVeryTall() {
        XCTAssertEqual(DemoPhoneMetrics(container: CGSize(width: 1400, height: 2000)).grow, 1.7)
    }

    func test_should_keepToHalfTheWidth_when_thePageIsNarrowAndTall() {
        // Half of 400 over 168 = 1.19, under the height's 1.56.
        let m = DemoPhoneMetrics(container: CGSize(width: 400, height: 1300))
        XCTAssertEqual(m.grow, 200.0 / 168.0, accuracy: 0.0001)
        XCTAssertEqual(m.phone.width, 200, accuracy: 0.001)
    }

    func test_should_beA20By9Phone_when_tall() {
        let m = DemoPhoneMetrics(container: CGSize(width: 402, height: 700), tall: true)
        XCTAssertEqual(m.phone.width, 135, accuracy: 0.001)
        XCTAssertEqual(m.phone.height, 300)
        XCTAssertEqual(m.screen, CGSize(width: 412, height: 916))
    }

    func test_should_keepAndroidsTiming() {
        XCTAssertEqual(DemoTiming.holdMs, 550)
        XCTAssertEqual(DemoTiming.startDelayMs, 500)
        XCTAssertEqual(DemoTiming.loopGapMs, 700)
        XCTAssertEqual(DemoTiming.takeoverIdleMs, 5_000)
        XCTAssertEqual([DemoTiming.appearMs, DemoTiming.tapDownMs, DemoTiming.rippleMs, DemoTiming.holdDownMs,
                        DemoTiming.dragDownMs, DemoTiming.liftMs], [180, 70, 420, 90, 80, 220])
        // The Expressive default spatial spring comes to rest in well under a second.
        XCTAssertGreaterThan(DemoTiming.springMs, 200)
        XCTAssertLessThan(DemoTiming.springMs, 1_000)
    }

    // MARK: Targets

    func test_should_beNil_when_thePartIsNotLaidOutYet() {
        let screen = CGSize(width: 360, height: 643)
        XCTAssertNil(DemoTargets.fraction(of: nil, in: screen, fx: 0.5, fy: 0.5))
        XCTAssertNil(DemoTargets.fraction(of: CGRect(x: 0, y: 0, width: 10, height: 10), in: .zero, fx: 0.5, fy: 0.5))
    }

    func test_should_pointInsideThePart_when_givenFractionsOfIt() throws {
        let screen = CGSize(width: 360, height: 640)
        let part = CGRect(x: 180, y: 64, width: 90, height: 32)
        let middle = try XCTUnwrap(DemoTargets.fraction(of: part, in: screen, fx: 0.5, fy: 0.5))
        XCTAssertEqual(middle.x, 225.0 / 360.0, accuracy: 0.0001)
        XCTAssertEqual(middle.y, 80.0 / 640.0, accuracy: 0.0001)
        // Past the part (Android's fan items sit below the avatar: fy > 1), and nudged by screen points.
        let below = try XCTUnwrap(DemoTargets.fraction(of: part, in: screen, fx: 0, fy: 2, dx: 18, dy: -32))
        XCTAssertEqual(below.x, 198.0 / 360.0, accuracy: 0.0001)
        XCTAssertEqual(below.y, 96.0 / 640.0, accuracy: 0.0001)
    }

    func test_should_goToTheMiddle_when_theTouchHasNoTargetYet() {
        let targets = DemoTargets()
        let finger = DemoFinger(targets: targets)
        XCTAssertEqual(finger.at("nowhere"), CGPoint(x: 0.5, y: 0.5))
        targets.screen = CGSize(width: 360, height: 640)
        targets.report("pill", CGRect(x: 0, y: 0, width: 180, height: 64))
        XCTAssertEqual(finger.at("pill"), CGPoint(x: 0.25, y: 0.05))
    }

    func test_should_startHidden_when_theTouchIsMade() {
        let finger = DemoFinger(targets: DemoTargets())
        XCTAssertEqual(finger.alpha, 0)
        XCTAssertEqual(finger.press, 0)
        XCTAssertEqual(finger.ring, 0)
        XCTAssertEqual(finger.ripple, 1, "a ripple at 1 has spread out and gone")
    }

    // MARK: The page

    func test_should_goToTheNextUnread_when_pressingNext() {
        let tips = ["A", "B", "C", "D"].map(tip)
        XCTAssertEqual(Tips.nextUnread(after: "A", read: [], in: tips)?.id, "B")
        XCTAssertEqual(Tips.nextUnread(after: "A", read: ["B"], in: tips)?.id, "C")
        // The current tip is being read: it is never its own next.
        XCTAssertEqual(Tips.nextUnread(after: "B", read: [], in: tips)?.id, "C")
    }

    func test_should_wrapToTheStart_when_theUnreadOneIsBehind() {
        let tips = ["A", "B", "C", "D"].map(tip)
        XCTAssertEqual(Tips.nextUnread(after: "D", read: ["B", "C"], in: tips)?.id, "A")
        XCTAssertEqual(Tips.nextUnread(after: "C", read: ["A", "D"], in: tips)?.id, "B")
    }

    func test_should_beDone_when_everyOtherTipIsRead() {
        let tips = ["A", "B", "C"].map(tip)
        XCTAssertNil(Tips.nextUnread(after: "B", read: ["A", "C"], in: tips))
        XCTAssertNil(Tips.nextUnread(after: "B", read: ["A", "B", "C"], in: tips))
        XCTAssertNil(Tips.nextUnread(after: "A", read: [], in: [tip("A")]))
    }

    func test_should_startFromTheFirstUnread_when_theTipIsUnknown() {
        let tips = ["A", "B"].map(tip)
        XCTAssertEqual(Tips.nextUnread(after: "Gone", read: ["A"], in: tips)?.id, "B")
    }

    func test_should_sayItsPlace_when_onATip() {
        XCTAssertEqual(Tips.positionLine(index: 2, total: 19), "3 / 19")
    }

    func test_should_turnAndShrinkWithThePager_when_aPageSlidesAway() {
        XCTAssertEqual(TipMetrics.pageOffset(position: 3, index: 3), 0)
        XCTAssertEqual(TipMetrics.pageOffset(position: 3.5, index: 3), 0.5)
        XCTAssertEqual(TipMetrics.pageOffset(position: 2.25, index: 3), -0.75)
        // A page further off is held at a whole page away.
        XCTAssertEqual(TipMetrics.pageOffset(position: 9, index: 3), 1)
        XCTAssertEqual(TipMetrics.pageOffset(position: 0, index: 3), -1)
        XCTAssertEqual([TipMetrics.tile, TipMetrics.symbol, TipMetrics.buttonHeight, TipMetrics.prose], [64, 30, 56, 640])
        XCTAssertEqual(TipMetrics.tileTurnDegrees, 40)
        XCTAssertEqual(TipMetrics.tileShrink, 0.2)
    }

    func test_should_cycleSixSilhouettes_when_pagingThroughTips() {
        let own = (0..<6).map { "\(TipTileShape.lobes($0))" }
        XCTAssertEqual(Set(own).count, 6, "neighbouring tips never share a silhouette")
        XCTAssertEqual("\(TipTileShape.lobes(6))", own[0])
        // The first tip grows in from the last silhouette (Android floorMod).
        XCTAssertEqual("\(TipTileShape.lobes(-1))", own[5])
    }

    func test_should_onlyShowDemos_when_theTipExists() {
        XCTAssertEqual(TipIllustration.scenes, [
            "SpotifyElsewhere", "MemoryLedger", "LyricHighlight", "AccountQuickSwitch", "FocusCover", "Downloads",
            "EditHome", "CoverMenu", "SongMenu", "PlayModes", "KeepScreenOn", "MiniPlayerMenu", "NoteMoments",
            "LibrarySearch", "Labs", "Name",
        ])
        for id in TipIllustration.scenes {
            XCTAssertNotNil(Tips.tip(id), "\(id) has a demo but is not a tip")
            XCTAssertTrue(TipIllustration.has(id))
        }
        XCTAssertFalse(TipIllustration.has("PullForMemories"))
    }

    // MARK: The made-up library

    func test_should_keepAndroidsDemoLibrary() {
        XCTAssertEqual(TipDemo.albums.map(\.title), ["Harbour Lights", "Paper Moons", "Low Tide", "Night Garden", "Golden Hour", "Blue Rooms"])
        XCTAssertEqual(TipDemo.albums.map(\.artist), ["Mira Sol", "The Lanterns", "Ines Varga", "Okto", "Mira Sol", "Halden"])
        XCTAssertEqual(TipDemo.trackTitles.count, 8)
        XCTAssertTrue(TipDemo.albums.allSatisfy { $0.id.provider == TipDemo.provider && $0.palette.cover == nil },
                      "nothing in the demo reaches an account or fetches an image")
        XCTAssertEqual(TipDemo.accounts.map(\.service), [.subsonic, .spotify, .appleMusic])
        XCTAssertEqual(TipDemo.accounts.map(TipDemo.shape(of:)), [0, 1, 2])
        XCTAssertEqual(TipDemo.accounts.map(\.title), ["Mira", "Okto", "Apple Music"])
    }

    func test_should_dealThreeLyricCards_when_theDemoOpens() {
        let cards = TipDemo.lyricCards
        XCTAssertEqual(cards.count, 3)
        XCTAssertEqual(cards.map(\.isUserPick), [false, false, true])
        XCTAssertEqual(cards.map(\.title), Array(TipDemo.trackTitles.prefix(3)))
        XCTAssertEqual(Set(cards.map(\.id)).count, 3)
        for card in cards { XCTAssertNotNil(TipDemo.lyricPalettes[card.id]) }
        XCTAssertEqual(LyricHighlightDemo.turns, [1, 2, 1])
    }

    func test_should_layAWeekWall_when_buildingTheDemoLedger() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        calendar.firstWeekday = 2
        let today = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 13, hour: 7)))
        let state = TipDemo.ledgerState(today: today, calendar: calendar)
        // 16 days kept: a note or a review each, and a score on every other one.
        XCTAssertEqual(state.entries.count, 24)
        XCTAssertEqual(state.stats.writings, 16)
        XCTAssertEqual(state.stats.ratedTracks, 8)
        XCTAssertEqual(state.stats.albums, 6)
        XCTAssertEqual(state.entries.map(\.at), state.entries.map(\.at).sorted(by: >), "newest first")
        XCTAssertTrue(state.entries.allSatisfy { Double($0.at) / 1000 <= today.timeIntervalSince1970 }, "nothing dated after now")
        // The week scale, whatever the count says: 26 weeks, 13 × 2.
        XCTAssertEqual(state.grid.scale, .week)
        XCTAssertEqual(state.grid.cells.count, 26)
        XCTAssertEqual(state.grid.cells.count, TipDemo.weekColumns * 2)
        for album in Set(state.entries.compactMap(\.album)) { XCTAssertNotNil(state.covers[album.key]) }
        XCTAssertFalse(state.isOther(try XCTUnwrap(state.activeAccount)))
    }

    func test_should_pickTheNewestWeekWithSomething_when_theDemoTapsACell() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let today = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 11, day: 13, hour: 7)))
        let grid = TipDemo.ledgerState(today: today, calendar: calendar).grid
        let pick = try XCTUnwrap(TipDemo.ledgerPick(grid))
        XCTAssertFalse(grid.cells[pick].isEmpty)
        XCTAssertFalse(grid.cells[pick].future)
        // Today's entry is in this week: the last cell.
        XCTAssertEqual(pick, grid.cells.count - 1)
        XCTAssertNil(TipDemo.ledgerPick(MemoryGrid(scale: .week, cells: [])))
    }

    func test_should_pointAtTheCellsMiddle_when_givenItsIndex() {
        let first = TipDemo.weekCellFraction(0)
        XCTAssertEqual(first.x, 0.5 / 13, accuracy: 0.0001)
        XCTAssertEqual(first.y, 0.25, accuracy: 0.0001)
        let last = TipDemo.weekCellFraction(25)
        XCTAssertEqual(last.x, 12.5 / 13, accuracy: 0.0001)
        XCTAssertEqual(last.y, 0.75, accuracy: 0.0001)
        // The second row starts under the first.
        XCTAssertEqual(TipDemo.weekCellFraction(13).x, first.x, accuracy: 0.0001)
    }

    // MARK: Now Playing on the phone

    func test_should_cycleLikeTheEngine_when_tappingThePlayModeButton() {
        var mode: (shuffle: Bool, repeatMode: RepeatMode) = (false, .all)
        var seen: [String] = []
        for _ in 0..<4 {
            mode = NowPlayingDemo.cycled(shuffle: mode.shuffle, repeatMode: mode.repeatMode)
            seen.append("\(mode.shuffle)-\(mode.repeatMode)")
        }
        XCTAssertEqual(seen, ["true-all", "true-one", "false-one", "false-all"], "back where it started after four")
        let off = NowPlayingDemo.cycled(shuffle: true, repeatMode: .off)
        XCTAssertTrue(off.shuffle)
        XCTAssertEqual(off.repeatMode, .all)
    }

    func test_should_singTheLastLineBegun_when_thePlayheadMoves() {
        let starts = TipDemo.lyrics.map(\.at)
        XCTAssertEqual(NowPlayingDemo.activeLine(starts, at: 58), 1)
        XCTAssertEqual(NowPlayingDemo.activeLine(starts, at: 60), 2)
        XCTAssertEqual(NowPlayingDemo.activeLine(starts, at: 500), starts.count - 1)
        XCTAssertEqual(NowPlayingDemo.activeLine([10, 20], at: 5), -1)
    }

    func test_should_wrapAtTheSongsEnd_when_thePlayheadAdvances() {
        let demo = TipDemo.nowPlaying()
        XCTAssertEqual(demo.playhead.positionSec, TipDemo.songStartSec)
        XCTAssertEqual(demo.snapshot.activeLyric, 1)
        demo.advance(by: 2)
        XCTAssertEqual(demo.snapshot.activeLyric, 2, "0:60 starts the third line")
        demo.advance(by: Double(TipDemo.songSec))
        XCTAssertEqual(demo.playhead.positionSec, TipDemo.songStartSec + 2, accuracy: 0.001)
        XCTAssertEqual(demo.track.durationSec, 214)
        XCTAssertTrue(demo.favorite)
        XCTAssertEqual(demo.album.id.provider, TipDemo.provider, "nothing reaches an account")
    }

    // MARK: The song sheet on the phone

    func test_should_listTheAppsRows_when_theSheetIsAnAlbumsSong() {
        let quick = DemoSongSheetRows.quick(nowPlaying: false, favorite: false)
        XCTAssertEqual(quick.map(\.id), ["next", "queue", "favorite", "share"])
        let groups = DemoSongSheetRows.groups(nowPlaying: false)
        XCTAssertEqual(groups.map { $0.map(\.icon) }, [["playlist"], ["note", "artist"]], "an album's own song has no Go to album")
    }

    func test_should_putTheFavoriteFirst_when_theSheetIsThePlayingSong() {
        let quick = DemoSongSheetRows.quick(nowPlaying: true, favorite: true)
        XCTAssertEqual(quick.map(\.id), ["favorite", "playlist", "share"])
        XCTAssertTrue(quick[0].checked)
        XCTAssertEqual(quick[0].icon, "favorite_filled")
        XCTAssertTrue(quick[1].closes)
        XCTAssertEqual(DemoSongSheetRows.groups(nowPlaying: true).map { $0.map(\.icon) }, [["album", "note", "artist"]])
    }

    func test_should_turnDownloadIntoRemove_when_theSongIsDownloaded() {
        let before = DemoSongSheetRows.groups(nowPlaying: false, offline: OfflineDownloads.Status.none)
        XCTAssertEqual(before[0].map(\.icon), ["playlist", "download"])
        let after = DemoSongSheetRows.groups(nowPlaying: false, offline: .downloaded)
        XCTAssertEqual(after[0].map(\.icon), ["playlist", "delete"])
        XCTAssertEqual(after[0][1].label, OfflineDownloads.rowLabel(.downloaded))
        XCTAssertEqual(DemoSongSheetRows.offlineTarget(.none), "menuRow.download")
        XCTAssertEqual(DemoSongSheetRows.offlineTarget(.downloaded), "menuRow.delete")
    }

    func test_should_checkAQueueButtonForAMoment_when_tapped() {
        let quick = DemoSongSheetRows.quick(nowPlaying: false, favorite: false, done: ["next"])
        XCTAssertEqual(quick[0].icon, "check")
        XCTAssertEqual(quick[1].icon, "queue")
    }

    // MARK: The album page on the phone

    func test_should_listSevenSongs_when_theDemoOpensAnAlbum() {
        let album = TipDemo.albumPage
        XCTAssertEqual(album.title, "Paper Moons")
        XCTAssertEqual(album.tracks.count, 7)
        XCTAssertEqual(album.tracks.map(\.starred), [false, true, false, false, false, false, false])
        XCTAssertTrue(album.tracks.contains { $0.number == TipDemo.notedTrack })
        XCTAssertLessThan(SongMenuDemo.heldRow, album.tracks.count)
    }

    func test_should_slideOntoTwoAccounts_when_theDemoSwitches() {
        XCTAssertEqual(AccountQuickSwitchDemo.slides, [0, 1])
        XCTAssertLessThan(AccountQuickSwitchDemo.slides.max() ?? 0, TipDemo.accounts.count - 1)
    }

    // MARK: Slice 3

    func test_should_haveAScene_when_aTipAndroidActsOutExistsHere() {
        // Every tip but the Memories deck's two (PullForMemories, MemoriesAllAccounts: no demo deck yet), the
        // Devices sheet's (SpotifySpeakers), Handoff's (ContinueOn: the system's offer, no Yoin card) and the
        // two iOS-only ones (docs/fidelity/tips.md, Without a scene).
        let without = Tips.all.map(\.id).filter { !TipIllustration.has($0) }
        XCTAssertEqual(Set(without), ["PullForMemories", "MemoriesAllAccounts", "SpotifySpeakers", "ContinueOn",
                                      "VolumeLeveling", "HomeScreenWidgets"])
    }

    func test_should_keepTheSecondWriteWasTappedAt_when_theDemoWrites() {
        let demo = TipDemo.nowPlaying()
        demo.write()
        XCTAssertEqual(demo.noteAnchorsMs, [58_000])
        XCTAssertEqual(demo.noteAnchors.first ?? 0, 58.0 / 214.0, accuracy: 0.0001)
        demo.advance(by: 10)
        demo.write()
        XCTAssertEqual(demo.noteAnchorsMs, [58_000, 68_000])
    }

    func test_should_startTheScrubPastTheNote_when_theDemoScrubsBack() {
        // From 14 % past the anchor to 5 % before it, as Android's.
        XCTAssertEqual(NoteMomentsDemo.scrubFrom, 0.14)
        XCTAssertEqual(NoteMomentsDemo.scrubPast, 0.05)
    }

    func test_should_spreadEachRingInTurn_when_aNoteSounds() {
        XCTAssertNil(NameDemo.ringProgress(age: 0, ring: 0))
        XCTAssertEqual(NameDemo.ringProgress(age: 1.2, ring: 0) ?? 0, 0.5, accuracy: 0.0001)
        // The second ring follows 0.22 s behind.
        XCTAssertNil(NameDemo.ringProgress(age: 0.2, ring: 1))
        XCTAssertEqual(NameDemo.ringProgress(age: 1.42, ring: 1) ?? 0, 0.5, accuracy: 0.0001)
        XCTAssertNil(NameDemo.ringProgress(age: 2.4, ring: 0))
        XCTAssertEqual(NameDemo.ringing, 2.84, accuracy: 0.0001)
    }

    func test_should_mixArtistsIntoTheAlbums_when_theDemoShowsLibrary() {
        let items = TipDemo.libraryItems
        XCTAssertEqual(items.count, TipDemo.albums.count + 2)
        XCTAssertEqual(items.filter { $0.kind == .artist }.map(\.title), ["The Lanterns", "Mira Sol"])
        XCTAssertEqual(items[2].kind, .artist)
        XCTAssertEqual(Set(items.map(\.id)).count, items.count)
    }
}
