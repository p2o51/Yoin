import XCTest
import YoinShared
@testable import Yoin

/// Volume leveling (Playback/ReplayGain.swift, Android #139).
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

    func test_should_findTheSongId_when_aStreamUrlIsGiven() {
        let url = URL(string: "https://music.example.com/rest/stream.view?u=a&t=b&s=c&id=song-9&maxBitRate=320")!
        XCTAssertEqual(AVAudioOutput.songId(url), "song-9")
    }
}

/// The per-song ReplayGain kept for the player (#213 review): song ids are per server, so nothing a song of
/// one account was tagged with ever plays on another account's song with the same id.
@MainActor
final class ReplayGainMemoryTests: XCTestCase {
    private let tagged = TrackAudio(trackGainDb: -9, albumGainDb: -7, trackPeak: 0.9, albumPeak: 1)
    private var scopeBefore: String?
    private var adoptsBefore = true

    override func setUp() async throws {
        scopeBefore = AccountScope.shared.subsonic
        adoptsBefore = AccountScope.shared.adoptsLegacyRows
        AccountScope.shared.adoptsLegacyRows = false
    }

    override func tearDown() async throws {
        AccountScope.shared.subsonic = scopeBefore
        AccountScope.shared.adoptsLegacyRows = adoptsBefore
    }

    func test_should_playAsIs_when_theSameIdComesBackUntagged() {
        let s = ReplayGainSettings.shared
        let id = "rg-\(UUID().uuidString)"
        s.remember(tagged, songId: id)
        XCTAssertNotEqual(s.applied(songId: id), .unity)
        s.remember(nil, songId: id)
        XCTAssertNil(s.audio(songId: id))
        XCTAssertEqual(s.applied(songId: id), .unity)
    }

    func test_should_forgetEverySong_when_theSubsonicAccountChanges() {
        let s = ReplayGainSettings.shared
        let id = "rg-\(UUID().uuidString)"
        AccountScope.shared.subsonic = "https://a.example|me"
        s.remember(tagged, songId: id)
        XCTAssertEqual(s.audio(songId: id), tagged)
        AccountScope.shared.subsonic = "https://a.example|me"
        XCTAssertEqual(s.audio(songId: id), tagged, "the same account keeps it")
        AccountScope.shared.subsonic = "https://b.example|me"
        XCTAssertNil(s.audio(songId: id))
        XCTAssertEqual(s.applied(songId: id), .unity)
    }
}

/// The format tags (Playback/ReplayGain.swift; Android AudioFormatTagsTest and SubsonicReplayGainVersionTest,
/// #121 #139).
final class FormatTagsTests: XCTestCase {
    private let hiRes = SongFormat(codec: "flac", bitRateKbps: 3000, bitDepth: 24, sampleRateHz: 96000, channels: 2)
    private let surround = SongFormat(codec: "flac", bitRateKbps: 2116, bitDepth: 16, sampleRateHz: 44100, channels: 6)

    func test_should_showDepthAndRate_when_lossless() {
        XCTAssertEqual(FormatTags.tags(hiRes), ["FLAC 24/96"])
        XCTAssertEqual(FormatTags.tags(surround), ["FLAC 16/44.1", "5.1"])
        XCTAssertEqual(FormatTags.tags(SongFormat(codec: "wav", bitRateKbps: 1411, sampleRateHz: 44100, channels: 2)),
                       ["WAV 44.1"], "no depth: the rate alone")
        XCTAssertEqual(FormatTags.khzLabel(88200), "88.2")
        XCTAssertEqual(FormatTags.khzLabel(192000), "192")
    }

    func test_should_showBitRate_when_lossy() {
        XCTAssertEqual(FormatTags.tags(SongFormat(codec: "mp3", bitRateKbps: 320)), ["MP3 320"])
        XCTAssertEqual(FormatTags.tags(SongFormat(codec: "opus", bitRateKbps: 192, channels: 2)), ["Opus 192"])
    }

    func test_should_showCodecOnly_when_nothingElseKnown() {
        XCTAssertEqual(FormatTags.tags(SongFormat(codec: "flac")), ["FLAC"])
        XCTAssertEqual(FormatTags.tags(nil), [])
    }

    func test_should_nameLayouts_when_moreThanTwoChannels() {
        XCTAssertNil(FormatTags.channelLayoutLabel(nil))
        XCTAssertNil(FormatTags.channelLayoutLabel(2))
        XCTAssertEqual(FormatTags.channelLayoutLabel(3), "2.1")
        XCTAssertEqual(FormatTags.channelLayoutLabel(6), "5.1")
        XCTAssertEqual(FormatTags.channelLayoutLabel(8), "7.1")
        XCTAssertEqual(FormatTags.channelLayoutLabel(12), "12ch")
    }

    func test_should_showDolbyAtmosAlone_when_codecIsEac3Joc() {
        var atmos = SongFormat(codec: "eac3-joc", bitRateKbps: 768, sampleRateHz: 48000, channels: 6)
        XCTAssertEqual(FormatTags.tags(atmos), ["Dolby Atmos"])
        atmos.channels = 2
        XCTAssertEqual(FormatTags.tags(atmos), ["Dolby Atmos"])
    }

    func test_should_spellDolbyCodecs_when_notAtmos() {
        XCTAssertEqual(FormatTags.tags(SongFormat(codec: "eac3", bitRateKbps: 640, channels: 6)), ["E-AC-3 640", "5.1"])
        XCTAssertEqual(FormatTags.tags(SongFormat(codec: "ac4")), ["AC-4"])
    }

    func test_should_nameTheCodec_when_givenASuffixOrAMimeType() {
        XCTAssertEqual(SongFormat.codec(suffix: " FLAC "), "flac")
        XCTAssertEqual(SongFormat.codec(suffix: "ogg"), "vorbis")
        XCTAssertEqual(SongFormat.codec(suffix: "wv"), "wavpack")
        XCTAssertEqual(SongFormat.codec(suffix: "dsf"), "dsd")
        XCTAssertEqual(SongFormat.codec(suffix: "m4a"), "aac")
        XCTAssertEqual(SongFormat.codec(suffix: "m4a", bitDepth: 16), "alac")
        XCTAssertEqual(SongFormat.codec(suffix: "wma"), "wma")
        XCTAssertNil(SongFormat.codec(suffix: " "))
        XCTAssertNil(SongFormat.codec(suffix: nil))
        XCTAssertEqual(SongFormat.codec(mime: "audio/eac3-joc"), "eac3-joc")
        XCTAssertEqual(SongFormat.codec(mime: "audio/eac3"), "eac3")
        XCTAssertEqual(SongFormat.codec(mime: "audio/ac4"), "ac4")
        XCTAssertEqual(SongFormat.codec(mime: "audio/MPEG"), "mp3")
        XCTAssertNil(SongFormat.codec(mime: "audio/mp4"))
    }

    func test_should_haveNoFormat_when_m4aFieldsAreAllZero() {
        // Navidrome and an E-AC-3 MP4: nothing read, so no "AAC".
        let unread = TrackAudio(samplingRate: 0, bitDepth: 0, channelCount: 0)
        XCTAssertNil(SongFormat.subsonic(suffix: "m4a", bitRateKbps: 0, audio: unread))
        XCTAssertNil(SongFormat.subsonic(suffix: "mp4", bitRateKbps: nil, audio: nil))
        XCTAssertNil(SongFormat.subsonic(suffix: nil, bitRateKbps: nil, audio: nil))
    }

    func test_should_keepAacAndAlac_when_m4aHasRealFields() {
        let aac = SongFormat.subsonic(suffix: "m4a", bitRateKbps: 256, audio: TrackAudio(samplingRate: 44100, channelCount: 2))
        XCTAssertEqual(aac?.codec, "aac")
        XCTAssertEqual(FormatTags.tags(aac), ["AAC 256"])
        let alac = SongFormat.subsonic(suffix: "m4a", bitRateKbps: 1411, audio: TrackAudio(samplingRate: 96000, bitDepth: 24))
        XCTAssertEqual(alac?.codec, "alac")
        XCTAssertEqual(FormatTags.tags(alac), ["ALAC 24/96"])
        XCTAssertEqual(FormatTags.tags(.subsonic(suffix: "flac", bitRateKbps: 0, audio: nil)), ["FLAC"])
    }

    func test_should_tagASong_when_theServerSaysEnough() {
        let id = MediaId(provider: MediaRef.companion.PROVIDER_SUBSONIC, rawId: "s1")
        var t = Track(id: id, number: 1, title: "Frost", durationSec: 199, bitRateKbps: 2116, suffix: "flac",
                      audio: TrackAudio(trackGainDb: -9, samplingRate: 44100, bitDepth: 16, channelCount: 6))
        XCTAssertEqual(t.formatTags, ["FLAC 16/44.1", "5.1"])
        t.audio?.channelCount = 2
        XCTAssertEqual(t.formatTags, ["FLAC 16/44.1"])
        t.suffix = "mp3"
        t.bitRateKbps = 320
        XCTAssertEqual(t.formatTags, ["MP3 320"])
        // Spotify, Apple Music: nothing known, no tags.
        XCTAssertEqual(Track(id: id, number: 1, title: "Frost", durationSec: 199).formatTags, [])
        // Channels without a codec: the layout alone.
        XCTAssertEqual(Track(id: id, number: 1, title: "Frost", durationSec: 199, bitRateKbps: 900,
                             audio: TrackAudio(channelCount: 6)).formatTags, ["5.1"])
    }

    func test_should_readTheMimeType_when_theServerSendsNoSuffix() {
        let mapping = SubsonicMapping { _ in nil }
        func song(suffix: String?, type: String?) -> SongInfo {
            SongInfo(ref: MediaRef(provider: MediaRef.companion.PROVIDER_SUBSONIC, rawId: "s1"), id: "s1", title: "Frost",
                     artist: "Ada Vale", artistId: nil, album: nil, albumId: nil, track: nil, disc: nil, durationSec: nil,
                     coverArtId: nil, year: nil, genre: nil, starred: false, starredAt: nil, userRating: nil,
                     suffix: suffix, contentType: type, bitRate: KotlinInt(int: 192), size: nil, path: nil, playCount: nil)
        }
        XCTAssertEqual(mapping.track(song(suffix: nil, type: "audio/opus")).formatTags, ["Opus 192"])
        XCTAssertEqual(mapping.track(song(suffix: "", type: "audio/mpeg")).formatTags, ["MP3 192"])
        XCTAssertEqual(mapping.track(song(suffix: "ogg", type: "audio/mpeg")).formatTags, ["Vorbis 192"], "the suffix first")
        XCTAssertEqual(mapping.track(song(suffix: nil, type: nil)).formatTags, [])
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
