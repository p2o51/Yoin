import SwiftUI

/*
 * Now Playing on the tip page's demo phone (Tips/TipNowPlayingScenes.swift; Android
 * ui/tips/TipNowPlayingScenes.kt). `NowPlayingView(demo:)` draws its own phone column over what this holds:
 * a made-up song, its lines and its playhead. Nothing is played, read from an account, written or opened;
 * the play-mode button and the cover answer here, the rest of the transport does nothing.
 */

/// The demo's playhead, read by the progress row alone (PlaybackPosition), as the engine's ticks are.
@MainActor
@Observable
final class DemoPlayhead {
    var positionSec: Double
    let durationSec: Double

    init(positionSec: Double, durationSec: Double) {
        self.positionSec = positionSec
        self.durationSec = durationSec
    }
}

@MainActor
@Observable
final class NowPlayingDemo {
    let album: Album
    let track: Track
    /// The song's lines and when each begins (seconds), in order.
    let lyrics: [(at: Double, text: String)]
    /// The format tag after the artist ("FLAC 24/96"), as a lossless Subsonic song shows.
    let formatTag: String?
    let playhead: DemoPlayhead
    /// The scene is the one on screen: the song "plays" (the word reads PAUSE, the wave runs).
    var isPlaying = false
    /// The line being sung (-1 before the first): moves with the playhead, a line at a time.
    private(set) var activeLyric: Int
    var shuffle = false
    var repeatMode: RepeatMode = .all
    /// The play-mode button is split into shuffle | repeat.
    var split = false
    /// One more per scripted tap on a split segment: the button's merge waits for a quiet spell after it.
    var splitTouches = 0
    /// The cover is focused.
    var focused = false
    /// The song's score on the rating bar and its heart: the phone's own (a press there changes only these).
    var rating: Double?
    var favorite: Bool
    /// The song's note moments (ms into it): the phone's own, written by its Write pill.
    var noteAnchorsMs: [Int64] = []
    /// A scrub the script acts out on the wave progress bar (a fraction of it); nil: none.
    var scrub: Double?
    /// The cover was held (the song's sheet, in the app).
    @ObservationIgnored var onCoverHold: () -> Void = {}

    init(album: Album, track: Track, lyrics: [(at: Double, text: String)], startSec: Double, formatTag: String?) {
        self.album = album
        self.track = track
        self.lyrics = lyrics
        self.formatTag = formatTag
        rating = track.rating
        favorite = track.starred
        playhead = DemoPlayhead(positionSec: startSec, durationSec: Double(max(track.durationSec, 1)))
        activeLyric = Self.activeLine(lyrics.map(\.at), at: startSec)
    }

    /// What the page reads in the engine's place.
    var snapshot: PlaybackSnapshot {
        PlaybackSnapshot(album: album, track: track, positionSec: 0, isPlaying: isPlaying, lyricLine: "",
                         nextLyricLine: "", lyrics: lyrics.map(\.text), activeLyric: activeLyric)
    }

    /// The last line begun by [position]; -1 before the first.
    nonisolated static func activeLine(_ starts: [Double], at position: Double) -> Int {
        (starts.lastIndex { $0 <= position }) ?? -1
    }

    /// The playhead moves on by [seconds], wrapping at the song's end; the sung line follows.
    func advance(by seconds: Double) {
        let d = playhead.durationSec
        playhead.positionSec = (playhead.positionSec + seconds).truncatingRemainder(dividingBy: d)
        let line = Self.activeLine(lyrics.map(\.at), at: playhead.positionSec)
        if line != activeLyric { activeLyric = line }
    }

    /// The Write pill: the moment writing began is kept as a note's anchor (the app files the note there).
    func write() {
        noteAnchorsMs.append(Int64((playhead.positionSec * 1000).rounded()))
    }

    /// The note moments as fractions of the bar.
    var noteAnchors: [Double] {
        noteAnchorsMs.map { Double($0) / 1000 / playhead.durationSec }.sorted()
    }

    /// The round button's tap (PlayerEngine.cyclePlayMode): repeat all → shuffle → shuffle + repeat one →
    /// repeat one → repeat all; from repeat off, back to repeat all.
    func cyclePlayMode() {
        (shuffle, repeatMode) = Self.cycled(shuffle: shuffle, repeatMode: repeatMode)
    }

    nonisolated static func cycled(shuffle: Bool, repeatMode: RepeatMode) -> (shuffle: Bool, repeatMode: RepeatMode) {
        switch (shuffle, repeatMode) {
        case (false, .all): (true, .all)
        case (true, .all): (true, .one)
        case (true, .one): (false, .one)
        case (false, .one): (false, .all)
        case (_, .off): (shuffle, .all)
        }
    }
}

extension EnvironmentValues {
    /// The demo phone's playhead; nil in the app itself (PlaybackPosition reads the engine).
    @Entry var demoPlayhead: DemoPlayhead? = nil
}
