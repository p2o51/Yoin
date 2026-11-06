import AppKit
import Foundation
import os

/// Spotify songs on the Mac: there's no App Remote for macOS, so the engine drives a Spotify Connect device
/// through the Web API (Android's start plan: PUT /me/player/play with the uris and a device id). The device
/// is this Mac's Spotify app, opened when it isn't running. Android's rule holds: a start never cuts into what
/// another device is playing; it says where instead, and "Play Here" moves Spotify to this Mac.
@MainActor
final class MacSpotifyOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// The Spotify account in use (nil: no Spotify account).
    var source: () -> SpotifySource? = { nil }

    private var current: Item?
    private var next: Item?
    private var generation = 0
    private var wantsPlay = false
    private var started = false
    private var lastTime: Double = 0
    private var lastTick = Date()
    private var playing = false
    private var duration: Double = 0
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
        self.current = Item(current)
        self.next = Item(next)
        started = false
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

    func preload(_ next: URL?) { self.next = Item(next) }

    func play() {
        wantsPlay = true
        guard started, let s = source() else { return }
        Task { try? await s.api.send("PUT", "me/player/play") }
        playing = true
        lastTick = Date()
    }

    func pause() {
        wantsPlay = false
        lastTime = currentTimeSec
        playing = false
        guard started, let s = source() else { return }
        Task { try? await s.api.send("PUT", "me/player/pause") }
    }

    func seek(to sec: Double) {
        lastTime = sec
        lastTick = Date()
        guard started, let s = source() else { return }
        Task { try? await s.api.send("PUT", "me/player/seek", ["position_ms": String(Int(sec * 1000))]) }
    }

    func stop() {
        generation += 1
        poll?.cancel()
        poll = nil
        let wasStarted = started
        started = false
        playing = false
        current = nil
        guard wasStarted, let s = source() else { return }
        Task { try? await s.api.send("PUT", "me/player/pause") }
    }

    // MARK: Starting

    /// Where a start goes when Spotify has no active device here (Android's rule).
    enum Target: Equatable {
        case device(String)
        /// Another device is playing: start nothing, say where.
        case playingElsewhere(String)
        /// No device to play on (the Spotify app didn't come up).
        case unreachable
    }

    private func start(_ item: Item, at sec: Double, gen: Int) async {
        guard let s = source() else { fail("No Spotify account", gen: gen); return }
        do {
            let target = try await Self.target(s)
            guard gen == generation else { return }
            switch target {
            case .playingElsewhere(let name):
                fail("Spotify is playing on \(name). Choose Play Here to move it to this Mac.", gen: gen)
                return
            case .unreachable:
                fail("Can't reach Spotify. Open the Spotify app on this Mac, then try again.", gen: gen)
                return
            case .device(let id):
                let uris = [item.uri] + (next.map { [$0.uri] } ?? [])
                try await s.startPlayback(uris: uris, positionMs: sec > 1 ? Int(sec * 1000) : 0, deviceId: id)
                guard gen == generation else { return }
                // Yoin's queue and modes rule: Spotify's own shuffle / repeat stay off.
                try? await s.api.send("PUT", "me/player/shuffle", ["state": "false", "device_id": id])
                try? await s.api.send("PUT", "me/player/repeat", ["state": "off", "device_id": id])
                if !wantsPlay { try? await s.api.send("PUT", "me/player/pause", ["device_id": id]) }
                started = true
                playing = wantsPlay
                lastTick = Date()
                events?.outputIsBufferingDidChange(false)
                watch(item, gen: gen)
            }
        } catch {
            fail(SpotifySource.message(for: error), gen: gen, network: true)
        }
    }

    /// The device a start may use: the active one unless another device is playing; else this Mac's
    /// Spotify app (opened if needed).
    static func target(_ s: SpotifySource) async throws -> Target {
        if let now = try await s.playerNow() {
            if now.isPlaying, !isThisMac(now.deviceName) { return .playingElsewhere(now.deviceName) }
            // Already here: keep it. Idle somewhere else: the song comes to this Mac, not to that device.
            if isThisMac(now.deviceName), let id = now.deviceId { return .device(id) }
        }
        if let id = try await thisMacDevice(s) { return .device(id) }
        // Not running: open it (in the background) and wait for it to show up as a device.
        if let app = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.spotify.client") {
            let config = NSWorkspace.OpenConfiguration()
            config.activates = false
            _ = try? await NSWorkspace.shared.openApplication(at: app, configuration: config)
        } else {
            return .unreachable
        }
        for _ in 0..<20 {
            try await Task.sleep(for: .milliseconds(500))
            if let id = try await thisMacDevice(s) { return .device(id) }
        }
        return .unreachable
    }

    private static func thisMacDevice(_ s: SpotifySource) async throws -> String? {
        let devices = try await s.devices()
        return (devices.first { $0.type == "Computer" && isThisMac($0.name) }
            ?? devices.first { $0.type == "Computer" })?.id
    }

    private static func isThisMac(_ name: String) -> Bool {
        let host = Host.current().localizedName ?? ""
        return !host.isEmpty && name.caseInsensitiveCompare(host) == .orderedSame
    }

    /// "Play Here": Spotify moves to this Mac's app (the listener's choice), then the song starts again.
    func playHere() async throws {
        guard let s = source() else { return }
        var id = try await Self.thisMacDevice(s)
        if id == nil, case .device(let d) = try await Self.target(s) { id = d }
        guard let id else { throw SpotifyAPIError.http(status: 404, message: "Open the Spotify app on this Mac first") }
        try await s.transfer(to: id)
    }

    // MARK: Following the device

    private struct NowDTO: Decodable {
        struct ItemDTO: Decodable { let id: String?; let durationMs: Int? }
        let isPlaying: Bool?
        let progressMs: Int?
        let item: ItemDTO?
    }

    /// Reads where Spotify is every second: the clock, a song that moved on to the handed next, the end.
    private func watch(_ item: Item, gen: Int) {
        poll?.cancel()
        poll = Task { [weak self] in
            var sawCurrent = false
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, gen == self.generation, let s = self.source() else { return }
                guard let data = try? await s.api.send("GET", "me/player"), !data.isEmpty,
                      let now = try? SpotifyWebAPI.decoder.decode(NowDTO.self, from: data) else { continue }
                guard gen == self.generation else { return }
                let id = now.item?.id
                if id == item.trackId {
                    sawCurrent = true
                    self.lastTime = Double(now.progressMs ?? 0) / 1000
                    self.lastTick = Date()
                    self.playing = now.isPlaying ?? false
                    if let d = now.item?.durationMs, Double(d) / 1000 != self.duration {
                        self.duration = Double(d) / 1000
                        self.events?.outputDurationDidChange(self.duration)
                    }
                    self.events?.outputTimeDidChange(self.lastTime)
                    // Spotify stops at 0 after the song when nothing was handed after it.
                    if sawCurrent, now.isPlaying == false, (now.progressMs ?? 0) == 0, self.duration > 0, self.wantsPlay {
                        self.events?.outputDidReachEnd()
                        return
                    }
                } else if sawCurrent, let next = self.next, id == next.trackId {
                    // It carried on to the song it was handed: the engine moves along.
                    self.current = next
                    self.next = nil
                    self.events?.outputDidAdvanceToPreloaded()
                    self.watch(next, gen: gen)
                    return
                } else if sawCurrent, now.isPlaying == true {
                    // Something else plays there now (changed in Spotify itself): Yoin stops following.
                    self.playing = false
                    self.events?.outputPlayingDidChangeExternally(false)
                    return
                }
            }
        }
    }

    private func fail(_ message: String, gen: Int, network: Bool = false) {
        guard gen == generation else { return }
        Self.log.error("start: \(message, privacy: .public)")
        events?.outputIsBufferingDidChange(false)
        events?.outputDidFail(message, network: network)
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
