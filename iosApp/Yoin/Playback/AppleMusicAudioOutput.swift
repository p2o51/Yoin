import Foundation
@preconcurrency import MusicKit
import os

/// [AudioOutput] on MusicKit's `ApplicationMusicPlayer`: Apple Music songs
/// can't stream through AVPlayer, so the engine's queue drives this player
/// one song at a time. The engine keeps the queue, play modes, previous /
/// next and the scrobble clock; this output holds only the current song
/// (`applemusic://song/<raw id>` stands in for a stream URL).
///
/// A song ending is read off the player's state: it stops playing within a
/// moment of the song's end, or the player drops the entry / stops (the lock
/// screen's next on a one-song queue). The output then starts the preloaded
/// song itself and reports the advance, as AVQueuePlayer does. A pause or
/// play from the lock screen (which MusicKit owns while it plays) is
/// reported so the engine's play state follows.
///
/// `-silentAudio YES`: ApplicationMusicPlayer can't be muted, so nothing is
/// started at all; the engine still moves through its queue.
@MainActor
final class AppleMusicAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// Raw id → the MusicKit song (set by the app from `AppleMusicSource`).
    var resolve: ((String) async -> Song?)?

    private var player: ApplicationMusicPlayer { ApplicationMusicPlayer.shared }
    private let silent = UserDefaults.standard.bool(forKey: "silentAudio")
    private var currentId: String?
    private var nextId: String?
    /// Bumps on every load / stop; a resolve that comes back late is dropped.
    private var generation = 0
    /// The current song is in MusicKit's queue.
    private var loaded = false
    private var wantsPlay = false
    /// The player has been seen playing this song (end detection needs it).
    private var sawPlaying = false
    private var lastTime: Double = 0
    private var duration: Double = 0
    private var timer: Timer?
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "player")
    /// Within this of the end, a stop counts as the song ending.
    private static let endSlackSec: Double = 1.5

    var currentTimeSec: Double { loaded ? player.playbackTime : lastTime }

    func load(_ current: URL, next: URL?, startSec: Double) {
        generation += 1
        let gen = generation
        currentId = AppleMusicMapping.rawId(fromStreamURL: current)
        nextId = next.flatMap(AppleMusicMapping.rawId(fromStreamURL:))
        loaded = false
        sawPlaying = false
        lastTime = startSec
        duration = 0
        startTicking()
        guard let id = currentId else { return fail("Not an Apple Music song") }
        Task {
            guard let song = await resolve?(id) else {
                if gen == generation { fail("Song unavailable") }
                return
            }
            guard gen == generation else { return }
            await start(song, at: startSec, gen: gen)
        }
    }

    private func start(_ song: Song, at startSec: Double, gen: Int) async {
        duration = song.duration ?? 0
        if duration > 0 { events?.outputDurationDidChange(duration) }
        if silent {
            Self.log.info("silentAudio: not starting Apple Music \(song.id.rawValue, privacy: .public)")
            events?.outputIsBufferingDidChange(false)
            return
        }
        player.queue = ApplicationMusicPlayer.Queue(for: [song])
        player.state.repeatMode = MusicPlayer.RepeatMode.none
        player.state.shuffleMode = .off
        do {
            try await player.prepareToPlay()
            guard gen == generation else { return }
            loaded = true
            if startSec > 0 { player.playbackTime = startSec }
            if wantsPlay { try await player.play() }
            guard gen == generation else { return }
            events?.outputIsBufferingDidChange(false)
        } catch {
            guard gen == generation else { return }
            Self.log.error("Apple Music play failed: \(error.localizedDescription, privacy: .public)")
            fail(AppleMusicSource.message(for: error))
        }
    }

    func preload(_ next: URL?) {
        nextId = next.flatMap(AppleMusicMapping.rawId(fromStreamURL:))
    }

    func play() {
        wantsPlay = true
        guard loaded, !silent else { return }
        let gen = generation
        Task {
            do { try await player.play() } catch {
                guard gen == generation else { return }
                fail(AppleMusicSource.message(for: error))
            }
        }
    }

    func pause() {
        wantsPlay = false
        if loaded { player.pause() }
    }

    func seek(to sec: Double) {
        lastTime = sec
        if loaded { player.playbackTime = sec }
        events?.outputTimeDidChange(sec)
    }

    func stop() {
        generation += 1
        let wasLoaded = loaded
        loaded = false
        wantsPlay = false
        sawPlaying = false
        currentId = nil
        nextId = nil
        timer?.invalidate()
        timer = nil
        if wasLoaded { player.stop() }
    }

    private func fail(_ message: String) {
        wantsPlay = false
        events?.outputDidFail(message)
    }

    // MARK: Clock

    private func startTicking() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func tick() {
        guard loaded else { return }
        let status = player.state.playbackStatus
        let time = player.playbackTime
        if status == .playing {
            if !sawPlaying {
                sawPlaying = true
                events?.outputIsBufferingDidChange(false)
            }
            lastTime = time
            events?.outputTimeDidChange(time)
            if !wantsPlay {
                // Played from the lock screen / Control Center.
                wantsPlay = true
                events?.outputPlayingDidChangeExternally(true)
            }
            return
        }
        guard wantsPlay, sawPlaying else { return }
        let nearEnd = duration > 0 && lastTime >= duration - Self.endSlackSec
        if status == .stopped || player.queue.currentEntry == nil || nearEnd {
            songEnded()
        } else if status == .paused || status == .interrupted {
            wantsPlay = false
            events?.outputPlayingDidChangeExternally(false)
        }
    }

    private func songEnded() {
        guard let next = nextId else {
            Self.log.info("Apple Music reached end of queue")
            wantsPlay = false
            loaded = false
            events?.outputDidReachEnd()
            return
        }
        Self.log.info("Apple Music advanced to preloaded song")
        generation += 1
        let gen = generation
        currentId = next
        nextId = nil
        loaded = false
        sawPlaying = false
        lastTime = 0
        events?.outputDidAdvanceToPreloaded()
        Task {
            guard let song = await resolve?(next) else {
                if gen == generation { fail("Song unavailable") }
                return
            }
            guard gen == generation else { return }
            await start(song, at: 0, gen: gen)
        }
    }
}

/// Routes the engine's output calls by URL: `applemusic://` to
/// [AppleMusicAudioOutput], everything else to AVQueuePlayer. Switching
/// stops the other one; only the active one's events reach the engine.
@MainActor
final class RoutingAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?

    let av: AudioOutput
    let apple: AppleMusicAudioOutput
    private var active: AudioOutput
    private var gates: [EventsGate] = []

    init(av: AudioOutput, apple: AppleMusicAudioOutput) {
        self.av = av
        self.apple = apple
        active = av
        gates = [EventsGate(self, av), EventsGate(self, apple)]
        av.events = gates[0]
        apple.events = gates[1]
    }

    static func isApple(_ url: URL?) -> Bool { url?.scheme == AppleMusicMapping.streamScheme }

    var currentTimeSec: Double { active.currentTimeSec }

    func load(_ current: URL, next: URL?, startSec: Double) {
        let target: AudioOutput = Self.isApple(current) ? apple : av
        if target !== active {
            active.stop()
            active = target
        }
        active.load(current, next: Self.isApple(next) == Self.isApple(current) ? next : nil, startSec: startSec)
    }

    func preload(_ next: URL?) {
        // A song from the other source can't be preloaded: the queue stops there.
        let sameKind = next == nil || Self.isApple(next) == (active === apple)
        active.preload(sameKind ? next : nil)
    }

    func play() { active.play() }
    func pause() { active.pause() }
    func seek(to sec: Double) { active.seek(to: sec) }
    func stop() { active.stop() }

    fileprivate func isActive(_ o: AudioOutput) -> Bool { o === active }

    /// Forwards one child's events while that child is the active output.
    @MainActor
    final class EventsGate: AudioOutputEvents {
        private weak var owner: RoutingAudioOutput?
        private weak var child: AudioOutput?

        init(_ owner: RoutingAudioOutput, _ child: AudioOutput) {
            self.owner = owner
            self.child = child
        }

        private var target: AudioOutputEvents? {
            guard let owner, let child, owner.isActive(child) else { return nil }
            return owner.events
        }

        func outputTimeDidChange(_ sec: Double) { target?.outputTimeDidChange(sec) }
        func outputDurationDidChange(_ sec: Double) { target?.outputDurationDidChange(sec) }
        func outputIsBufferingDidChange(_ buffering: Bool) { target?.outputIsBufferingDidChange(buffering) }
        func outputDidAdvanceToPreloaded() { target?.outputDidAdvanceToPreloaded() }
        func outputDidReachEnd() { target?.outputDidReachEnd() }
        func outputDidFail(_ message: String) { target?.outputDidFail(message) }
        func outputPlayingDidChangeExternally(_ playing: Bool) { target?.outputPlayingDidChangeExternally(playing) }
    }
}
