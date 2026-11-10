import XCTest
@testable import Yoin

/// Volume leveling and the quality tag (Playback/ReplayGain.swift, Android #121 #139).
final class ReplayGainTests: XCTestCase {
    private let tagged = TrackAudio(trackGainDb: -9, albumGainDb: -7, trackPeak: 0.9, albumPeak: 1, samplingRate: 96000,
                                    bitDepth: 24, channelCount: 2)

    func test_should_pickAlbumThenTrack_when_levelingByAlbum() {
        XCTAssertEqual(AppliedReplayGain.of(tagged, mode: .album).db, -3, accuracy: 1e-9)
        XCTAssertEqual(AppliedReplayGain.of(tagged, mode: .track).db, -5, accuracy: 1e-9)
        var trackOnly = tagged
        trackOnly.albumGainDb = nil
        XCTAssertEqual(AppliedReplayGain.of(trackOnly, mode: .album).db, -5, accuracy: 1e-9)
    }

    func test_should_playAsIs_when_offOrUntagged() {
        XCTAssertEqual(AppliedReplayGain.of(tagged, mode: .off), .unity)
        XCTAssertEqual(AppliedReplayGain.of(nil, mode: .album), .unity)
        XCTAssertEqual(AppliedReplayGain.of(TrackAudio(), mode: .album), .unity)
    }

    func test_should_stayUnderThePeak_when_thePreampWouldClip() {
        let quiet = TrackAudio(trackGainDb: 2, albumGainDb: nil, trackPeak: 0.6, albumPeak: nil)
        let g = AppliedReplayGain.of(quiet, mode: .track)
        XCTAssertTrue(g.peakLimited)
        XCTAssertEqual(g.db, -20 * log10(0.6), accuracy: 1e-9)
        // iOS can only turn a song down.
        XCTAssertEqual(g.linear, 1)
        XCTAssertEqual(AppliedReplayGain.of(tagged, mode: .album).linear, pow(10, -3.0 / 20), accuracy: 1e-9)
    }

    func test_should_nameTheFormat_when_theServerSaysEnough() {
        XCTAssertEqual(QualityTag.label(suffix: "flac", bitRateKbps: 2400, audio: tagged), "FLAC 24/96")
        var cd = tagged
        cd.bitDepth = 16
        cd.samplingRate = 44100
        XCTAssertEqual(QualityTag.label(suffix: "flac", bitRateKbps: nil, audio: cd), "FLAC 16/44.1")
        XCTAssertEqual(QualityTag.label(suffix: "mp3", bitRateKbps: 320, audio: nil), "MP3 320")
        var surround = tagged
        surround.channelCount = 6
        XCTAssertEqual(QualityTag.label(suffix: "flac", bitRateKbps: nil, audio: surround), "5.1")
        surround.channelCount = 8
        XCTAssertEqual(QualityTag.label(suffix: "flac", bitRateKbps: nil, audio: surround), "7.1")
        XCTAssertNil(QualityTag.label(suffix: nil, bitRateKbps: 320, audio: nil))
    }

    func test_should_findTheSongId_when_aStreamUrlIsGiven() {
        let url = URL(string: "https://music.example.com/rest/stream.view?u=a&t=b&s=c&id=song-9&maxBitRate=320")!
        XCTAssertEqual(AVAudioOutput.songId(url), "song-9")
    }
}

/// The server's now-playing view (Playback/PlaybackReport.swift, Android #130).
final class PlaybackReportTrackerTests: XCTestCase {
    func test_should_reportStartPauseResumeAndStop_when_thePlayerMoves() {
        var t = PlaybackReportTracker()
        XCTAssertEqual(t.update(songId: "a", isPlaying: true, positionMs: 0).map(\.state), [.starting])
        XCTAssertEqual(t.update(songId: "a", isPlaying: true, positionMs: 500), [])
        XCTAssertEqual(t.update(songId: "a", isPlaying: false, positionMs: 9_000).map(\.state), [.paused])
        XCTAssertEqual(t.update(songId: "a", isPlaying: true, positionMs: 9_000).map(\.state), [.playing])
        let next = t.update(songId: "b", isPlaying: true, positionMs: 0)
        XCTAssertEqual(next.map(\.songId), ["a", "b"])
        XCTAssertEqual(next.map(\.state), [.stopped, .starting])
        XCTAssertEqual(next[0].positionMs, 9_000)
        XCTAssertEqual(t.update(songId: nil, isPlaying: false, positionMs: 0).map(\.state), [.stopped])
        XCTAssertEqual(t.update(songId: nil, isPlaying: false, positionMs: 0), [])
    }
}
