import AppKit
import Foundation
import os

/// Spotify songs on the Mac: there's no App Remote for macOS, so the engine drives Spotify Connect through
/// the Web API. Android's rule holds throughout: Yoin never starts or cuts into playback on another device.
/// A song only ever starts on this Mac's Spotify app (opened in the background when it isn't running), and
/// every later command names that device; once Spotify moves somewhere else, Yoin lets go of it.
///
/// One song at a time: Spotify is handed the current song only, and the engine starts the next one when
/// this one ends (Spotify's own queue can't be kept in step with Yoin's: Play Next, shuffle, removals).
@MainActor
final class MacSpotifyOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// The Spotify account in use (nil: no Spotify account).
    var source: () -> SpotifySource? = { nil }

    private var current: Item?
    private var generation = 0
    private var wantsPlay = false
    /// What Yoin owns: the account and device the current song started on (nil: nothing is Yoin's).
    private var owned: (source: SpotifySource, device: String)?
    private var lastTime: Double = 0
    private var lastTick = Date()
    private var playing = false
    private var duration: Double = 0
    /// Yoin's own command just went out: the next reads may still show the old state.
    private var quietUntil = Date.distantPast
    private var poll: Task<Void, Never>?
    private let silent = UserDefaults.standard.bool(forKey: "silentAudio")
    private static let log = Logger(subsystem: "com.gpo.yoin.mac", category: "spotify")

    struct Item: Equatable, Sendable {
        var trackId: String
        var uri: String { "spotify:track:\(trackId)" }

        init?(_ url: URL?) {
            guard let url, url.scheme == MacSpotifyOutput.scheme else { return nil }
            let id = url.lastPathComponent
            guard !id.isEmpty, id != "/" else { return nil }
            trackId = id
        }
    }

    nonisolated static let scheme = "spotify-yoin"

    /// The engine's stand-in stream URL for a Spotify song.
    nonisolated static func streamURL(trackId: String) -> URL {
        URL(string: "\(scheme)://track/\(trackId)")!
    }

    nonisolated static func isSpotify(_ url: URL?) -> Bool { url?.scheme == scheme }

    var currentTimeSec: Double { playing ? lastTime + Date().timeIntervalSince(lastTick) : lastTime }

    // MARK: AudioOutput

    func load(_ current: URL, next: URL?, startSec: Double) {
        generation += 1
        poll?.cancel()
        self.current = Item(current)
        playing = false
        lastTime = startSec
        lastTick = Date()
        duration = 0
        guard let item = self.current else { events?.outputDidFail("Not a Spotify song", network: false); return }
        guard !silent else { return }
        let gen = generation
        events?.outputIsBufferingDidChange(true)
        Task { await start(item, at: startSec, gen: gen) }
    }

    /// Spotify is handed one song at a time (see the type's note): nothing to preload.
    func preload(_ next: URL?) {}

    func play() {
        wantsPlay = true
        guard let o = owned else { return }
        command(o, "PUT", "me/player/play")
        playing = true
        lastTick = Date()
    }

    func pause() {
        wantsPlay = false
        lastTime = currentTimeSec
        playing = false
        guard let o = owned else { return }
        command(o, "PUT", "me/player/pause")
    }

    func seek(to sec: Double) {
        lastTime = sec
        lastTick = Date()
        guard let o = owned else { return }
        command(o, "PUT", "me/player/seek", ["position_ms": String(Int(sec * 1000))])
    }

    func stop() {
        generation += 1
        poll?.cancel()
        poll = nil
        playing = false
        current = nil
        if let o = owned { command(o, "PUT", "me/player/pause") }
        owned = nil
    }

    /// A command for the device Yoin owns, on the account that started it (never the active device).
    private func command(_ o: (source: SpotifySource, device: String), _ method: String, _ path: String,
                         _ query: [String: String] = [:]) {
        quietUntil = Date().addingTimeInterval(2.5)
        var q = query
        q["device_id"] = o.device
        Task { try? await o.source.api.send(method, path, q) }
    }

    // MARK: Starting

    enum Target: Equatable {
        case device(String)
        /// Another device is playing: start nothing, say where.
        case playingElsewhere(String)
        /// This Mac's Spotify app didn't come up.
        case unreachable
    }

    private func start(_ item: Item, at sec: Double, gen: Int) async {
        guard let s = source() else { fail("No Spotify account", gen: gen); return }
        do {
            var target = try await Self.target(s, launch: true)
            guard gen == generation else { return }
            if case .device = target {
                // Checked again right before starting: something may have started elsewhere meanwhile.
                if let now = try await s.playerNow(), now.isPlaying, !Self.isThisMac(now.deviceName) {
                    target = .playingElsewhere(now.deviceName)
                }
                guard gen == generation else { return }
            }
            switch target {
            case .playingElsewhere(let name):
                owned = nil
                fail("Spotify is playing on \(name). Choose Play Here to move it to this Mac.", gen: gen)
            case .unreachable:
                owned = nil
                fail("Can't reach Spotify. Open the Spotify app on this Mac, then try again.", gen: gen)
            case .device(let id):
                try await s.startPlayback(uris: [item.uri], positionMs: sec > 1 ? Int(sec * 1000) : 0, deviceId: id)
                guard gen == generation else {
                    // Stopped while the start was on its way: it mustn't keep playing.
                    if current == nil { try? await s.api.send("PUT", "me/player/pause", ["device_id": id]) }
                    return
                }
                owned = (s, id)
                quietUntil = Date().addingTimeInterval(2.5)
                // Yoin's queue and modes rule: Spotify's own shuffle / repeat stay off on this device.
                try? await s.api.send("PUT", "me/player/shuffle", ["state": "false", "device_id": id])
                try? await s.api.send("PUT", "me/player/repeat", ["state": "off", "device_id": id])
                guard gen == generation else { return }
                if !wantsPlay { command((s, id), "PUT", "me/player/pause") }
                playing = wantsPlay
                lastTick = Date()
                events?.outputIsBufferingDidChange(false)
                watch(item, owner: (s, id), gen: gen)
            }
        } catch {
            fail(SpotifySource.message(for: error), gen: gen)
        }
    }

    /// The device a start may use: this Mac's Spotify app, unless another device is playing.
    static func target(_ s: SpotifySource, launch: Bool) async throws -> Target {
        if let now = try await s.playerNow(), now.isPlaying, !isThisMac(now.deviceName) {
            return .playingElsewhere(now.deviceName)
        }
        if let id = try await localDevice(s, launch: launch) { return .device(id) }
        return .unreachable
    }

    /// This Mac's Spotify device (named after the Mac); with [launch], the Spotify app is opened in the
    /// background and waited for. Never another computer.
    static func localDevice(_ s: SpotifySource, launch: Bool) async throws -> String? {
        func find() async throws -> String? {
            try await s.devices().first { $0.type == "Computer" && isThisMac($0.name) }?.id
        }
        if let id = try await find() { return id }
        guard launch, let app = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.spotify.client") else {
            return nil
        }
        let config = NSWorkspace.OpenConfiguration()
        config.activates = false
        _ = try? await NSWorkspace.shared.openApplication(at: app, configuration: config)
        for _ in 0..<20 {
            try await Task.sleep(for: .milliseconds(500))
            if let id = try await find() { return id }
        }
        return nil
    }

    private static func isThisMac(_ name: String) -> Bool {
        let host = Host.current().localizedName ?? ""
        return !host.isEmpty && name.caseInsensitiveCompare(host) == .orderedSame
    }

    /// "Play Here" (the listener's choice): Spotify moves to this Mac's app, opened if needed.
    func playHere() async throws {
        guard let s = source() else { return }
        guard let id = try await Self.localDevice(s, launch: true) else {
            throw SpotifyAPIError.http(status: 404, message: "Spotify isn't open on this Mac. Open it, then try again.")
        }
        try await s.transfer(to: id)
    }

    // MARK: Following the device

    private struct NowDTO: Decodable {
        struct DeviceDTO: Decodable { let id: String? }
        struct ItemDTO: Decodable { let id: String?; let durationMs: Int? }
        let device: DeviceDTO?
        let isPlaying: Bool?
        let progressMs: Int?
        let item: ItemDTO?
    }

    /// Reads where Spotify is every second, for the device Yoin owns: the clock, a pause or play made in
    /// Spotify, the song's end, and Spotify moving away (then Yoin lets go).
    private func watch(_ item: Item, owner: (source: SpotifySource, device: String), gen: Int) {
        poll?.cancel()
        poll = Task { [weak self] in
            /// The furthest the song was seen playing: an end is a stop (or another song) after its last seconds.
            var furthest: Double = 0
            var wait: Duration = .seconds(1)
            while !Task.isCancelled {
                try? await Task.sleep(for: wait)
                wait = .seconds(1)
                guard let self, gen == self.generation else { return }
                let data: Data
                do {
                    data = try await owner.source.api.send("GET", "me/player")
                } catch SpotifyAPIError.rateLimited(let after) {
                    wait = .seconds(max(1, after))
                    continue
                } catch let e as SpotifyAuthError {
                    // Signed out (revoked): stop asking; the account shows it.
                    self.letGo(gen: gen, message: e.localizedDescription)
                    return
                } catch SpotifyAPIError.unauthorized {
                    self.letGo(gen: gen, message: "Spotify signed you out. Sign in again")
                    return
                } catch {
                    continue
                }
                guard gen == self.generation else { return }
                let now = data.isEmpty ? nil : try? SpotifyWebAPI.decoder.decode(NowDTO.self, from: data)
                let quiet = Date() < self.quietUntil
                let nearEnd = self.duration > 0 && furthest >= self.duration - 4
                // Spotify moved to another device (or nothing is active): that's not Yoin's any more.
                if !quiet, now?.device?.id != owner.device {
                    if nearEnd { self.ended(gen: gen) } else { self.letGo(gen: gen, message: nil) }
                    return
                }
                guard let now else { continue }
                let progress = now.progressMs.map { Double($0) / 1000 }
                if now.item?.id == item.trackId {
                    if let d = now.item?.durationMs, Double(d) / 1000 != self.duration {
                        self.duration = Double(d) / 1000
                        self.events?.outputDurationDidChange(self.duration)
                    }
                    if let progress {
                        self.lastTime = progress
                        self.lastTick = Date()
                        self.events?.outputTimeDidChange(progress)
                    }
                    let isPlaying = now.isPlaying ?? false
                    // Stopped back at the start after its last seconds: the song ended.
                    if !isPlaying, (progress ?? 0) < 1, nearEnd {
                        self.ended(gen: gen)
                        return
                    }
                    if let progress, isPlaying { furthest = max(furthest, progress) }
                    // Paused or played in Spotify itself.
                    if !quiet, isPlaying != self.playing {
                        self.playing = isPlaying
                        self.wantsPlay = isPlaying
                        self.events?.outputPlayingDidChangeExternally(isPlaying)
                    }
                } else if !quiet {
                    // Another song on this device: Spotify's autoplay after the end, or the listener's choice.
                    if nearEnd { self.ended(gen: gen) } else { self.letGo(gen: gen, message: nil) }
                    return
                }
            }
        }
    }

    private func ended(gen: Int) {
        guard gen == generation else { return }
        playing = false
        events?.outputDidReachEnd()
    }

    /// Spotify isn't Yoin's any more: no command goes there; the engine shows paused (or the message).
    private func letGo(gen: Int, message: String?) {
        guard gen == generation else { return }
        owned = nil
        playing = false
        wantsPlay = false
        if let message { events?.outputDidFail(message, network: true) }
        else { events?.outputPlayingDidChangeExternally(false) }
    }

    /// A start that can't go ahead holds the song (network: true keeps the queue where it is for Retry).
    private func fail(_ message: String, gen: Int) {
        guard gen == generation else { return }
        Self.log.error("start: \(message, privacy: .public)")
        events?.outputIsBufferingDidChange(false)
        events?.outputDidFail(message, network: true)
    }
}

/// The Mac's output: AVQueuePlayer for streams, MusicKit for Apple Music, Spotify Connect for Spotify.
/// Switching stops the other one; only the active one's events reach the engine.
@MainActor
final class MacRoutingOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    private let outputs: [AudioOutput]
    private let pick: (URL?) -> Int
    private var active: Int = 0
    private var gates: [Gate] = []

    init(av: AudioOutput, apple: AppleMusicAudioOutput, spotify: MacSpotifyOutput) {
        outputs = [av, apple, spotify]
        pick = { url in
            if RoutingAudioOutput.isApple(url) { return 1 }
            if MacSpotifyOutput.isSpotify(url) { return 2 }
            return 0
        }
        gates = outputs.indices.map { Gate(self, $0) }
        for (i, o) in outputs.enumerated() { o.events = gates[i] }
    }

    var currentTimeSec: Double { outputs[active].currentTimeSec }

    func load(_ current: URL, next: URL?, startSec: Double) {
        let target = pick(current)
        if target != active {
            outputs[active].stop()
            active = target
        }
        outputs[active].load(current, next: pick(next) == target ? next : nil, startSec: startSec)
    }

    func preload(_ next: URL?) { outputs[active].preload(next == nil || pick(next) == active ? next : nil) }
    func play() { outputs[active].play() }
    func pause() { outputs[active].pause() }
    func seek(to sec: Double) { outputs[active].seek(to: sec) }
    func stop() { outputs[active].stop() }

    fileprivate func isActive(_ i: Int) -> Bool { i == active }

    @MainActor
    final class Gate: AudioOutputEvents {
        private weak var owner: MacRoutingOutput?
        private let index: Int

        init(_ owner: MacRoutingOutput, _ index: Int) {
            self.owner = owner
            self.index = index
        }

        private var target: AudioOutputEvents? {
            guard let owner, owner.isActive(index) else { return nil }
            return owner.events
        }

        func outputTimeDidChange(_ sec: Double) { target?.outputTimeDidChange(sec) }
        func outputDurationDidChange(_ sec: Double) { target?.outputDurationDidChange(sec) }
        func outputIsBufferingDidChange(_ buffering: Bool) { target?.outputIsBufferingDidChange(buffering) }
        func outputDidAdvanceToPreloaded() { target?.outputDidAdvanceToPreloaded() }
        func outputDidReachEnd() { target?.outputDidReachEnd() }
        func outputDidFail(_ message: String, network: Bool) { target?.outputDidFail(message, network: network) }
        func outputPlayingDidChangeExternally(_ playing: Bool) { target?.outputPlayingDidChangeExternally(playing) }
    }
}
