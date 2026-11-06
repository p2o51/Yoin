import AppKit
import Foundation
import os

/// Spotify songs on the Mac: there's no App Remote for macOS, so the engine drives Spotify Connect through
/// the Web API, as a remote. When Spotify is already active on a device (a phone, another computer, a
/// speaker), Yoin plays on and controls that device (51, 2026-11-06); with nothing active anywhere, it uses
/// this Mac's Spotify app (opened in the background when it isn't running). Every command names the device;
/// when Spotify moves to another device on the same song, Yoin follows it; something else playing there
/// means the listener took over, and Yoin lets go. Moving to this Mac is only ever the listener's choice.
///
/// One song at a time: Spotify is handed the current song only, and the engine starts the next one when
/// this one ends (Spotify's own queue can't be kept in step with Yoin's: Play Next, shuffle, removals).
@MainActor
final class MacSpotifyOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// The Spotify account in use (nil: no Spotify account).
    var source: () -> SpotifySource? = { nil }
    /// The device Yoin plays on, named for Now Playing (nil: none, or this Mac).
    var onDevice: (String?) -> Void = { _ in }
    /// Makes this the output the player drives, for Spotify playing something Yoin didn't start; false
    /// while the player plays another source's song (set by the app).
    var adopt: (() -> Bool)?
    /// When the current start went out and the device it went to: a device that drops the song right
    /// after a start is said so, not shown as a plain pause.
    private var startedAt = Date.distantPast
    private var startedOn = ""
    private var lookTimer: Timer?
    /// A Spotify account whose sign-in was refused: the look doesn't ask it again (a new sign-in is a new source).
    private weak var revoked: SpotifySource?
    /// A load's start is on its way to Spotify.
    private var starting = false

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
        showingExternal = false
        externalId = nil
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
        starting = true
        Task {
            await start(item, at: startSec, gen: gen)
            if gen == generation { starting = false }
        }
    }

    /// Spotify is handed one song at a time (see the type's note): nothing to preload.
    func preload(_ next: URL?) {}

    func play() {
        wantsPlay = true
        // A start on its way plays when it lands; a resume now went to the previous song (403 Restriction
        // violated in the 2026-11-06 end-to-end run, right after Next).
        guard !starting, let o = owned else { return }
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
        starting = false
        poll?.cancel()
        poll = nil
        playing = false
        current = nil
        if let o = owned { command(o, "PUT", "me/player/pause") }
        owned = nil
        onDevice(nil)
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
        case device(id: String, name: String)
        /// No device is active and this Mac's Spotify app didn't come up.
        case unreachable
    }

    private func start(_ item: Item, at sec: Double, gen: Int) async {
        guard let s = source() else { fail("No Spotify account", gen: gen); return }
        do {
            let target = try await Self.target(s, launch: true)
            guard gen == generation else { return }
            switch target {
            case .unreachable:
                owned = nil
                onDevice(nil)
                fail("Can't reach Spotify. Open the Spotify app on this Mac, then try again.", gen: gen)
            case .device(let id, let name):
                // Yoin's queue and modes rule: Spotify's own shuffle / repeat go off, before the start and
                // only when one is on (iOS 7702336e; the desktop app takes either order, 2026-11-06 runs).
                if let now = try? await s.playerNow(), now.deviceId == id, now.shuffle || now.repeatState != "off" {
                    try? await s.api.send("PUT", "me/player/shuffle", ["state": "false", "device_id": id])
                    try? await s.api.send("PUT", "me/player/repeat", ["state": "off", "device_id": id])
                    guard gen == generation else { return }
                }
                try await s.startPlayback(uris: [item.uri], positionMs: sec > 1 ? Int(sec * 1000) : 0, deviceId: id)
                guard gen == generation else {
                    // Stopped while the start was on its way: it mustn't keep playing.
                    if current == nil { try? await s.api.send("PUT", "me/player/pause", ["device_id": id]) }
                    return
                }
                owned = (s, id)
                startedAt = Date()
                startedOn = name
                quietUntil = Date().addingTimeInterval(2.5)
                guard gen == generation else { return }
                // Named only once it's really playing there.
                onDevice(Self.isThisMac(name) ? nil : name)
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

    /// The device a start uses (51, 2026-11-06): the device Spotify is playing on, wherever it is; else this
    /// Mac's Spotify app (opened in the background if needed), even when another device is active but paused
    /// (a phone's Spotify asleep in the background dropped the start). Only when this Mac's app can't be
    /// reached does a paused active device take it. Never a move while something plays elsewhere.
    static func target(_ s: SpotifySource, launch: Bool) async throws -> Target {
        let now = try await s.playerNow()
        if let now, now.isPlaying, let id = now.deviceId { return .device(id: id, name: now.deviceName) }
        let local = try await localDevice(s, launch: launch)
        // Opening Spotify here took a while: if the listener started playing somewhere meanwhile, that wins.
        let again = local == nil ? now : try await s.playerNow()
        return decide(now: again, local: local.map { ($0, Host.current().localizedName ?? "This Mac") })
    }

    /// The rule as a pure decision over what /me/player shows and this Mac's device (nil: not reachable).
    nonisolated static func decide(now: SpotifySource.PlayerNow?, local: (id: String, name: String)?) -> Target {
        if let now, now.isPlaying, let id = now.deviceId { return .device(id: id, name: now.deviceName) }
        if let local { return .device(id: local.id, name: local.name) }
        if let now, let id = now.deviceId { return .device(id: id, name: now.deviceName) }
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

    /// "Play on This Mac" (only ever the listener's choice): Spotify moves to this Mac's app, opened if
    /// needed; Yoin follows it there.
    func playHere() async throws {
        guard let s = source() else { return }
        guard let id = try await Self.localDevice(s, launch: true) else {
            throw SpotifyAPIError.http(status: 404, message: "Spotify isn't open on this Mac. Open it, then try again.")
        }
        // Opening Spotify here can take seconds: another account since, and nothing goes out for this one.
        guard source() === s else { return }
        try await s.transfer(to: id)
        guard source() === s else { return }
        if let o = owned, o.source === s {
            owned = (s, id)
            quietUntil = Date().addingTimeInterval(2.5)
        }
        onDevice(nil)
    }

    // MARK: Following the device

    private struct NowDTO: Decodable {
        struct DeviceDTO: Decodable { let id: String?; let name: String? }
        struct ItemDTO: Decodable {
            struct Named: Decodable { let name: String? }
            struct Image: Decodable { let url: String? }
            struct AlbumDTO: Decodable { let id: String?; let name: String?; let images: [Image]? }
            let id: String?
            let name: String?
            let durationMs: Int?
            let artists: [Named]?
            let album: AlbumDTO?
        }
        let device: DeviceDTO?
        let isPlaying: Bool?
        let progressMs: Int?
        let item: ItemDTO?
        let shuffleState: Bool?
        /// "off", "track" or "context".
        let repeatState: String?
    }

    /// Reads where Spotify is every second, for the device Yoin owns: the clock, a pause or play made in
    /// Spotify, the song's end, and Spotify moving away (then Yoin lets go).
    private func watch(_ item: Item?, owner start: (source: SpotifySource, device: String), gen: Int) {
        poll?.cancel()
        poll = Task { [weak self] in
            /// The furthest the song was seen playing: an end is a stop (or another song) after its last seconds.
            var furthest: Double = 0
            var wait: Duration = .seconds(1)
            var owner = start
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
                    // Signed out (revoked): stop asking; the account shows it, and the 10 s look leaves it be.
                    self.revoked = owner.source
                    self.letGo(gen: gen, message: e.localizedDescription)
                    return
                } catch SpotifyAPIError.unauthorized {
                    self.revoked = owner.source
                    self.letGo(gen: gen, message: "Spotify signed you out. Sign in again")
                    return
                } catch {
                    continue
                }
                guard gen == self.generation else { return }
                let now = data.isEmpty ? nil : try? SpotifyWebAPI.decoder.decode(NowDTO.self, from: data)
                let quiet = Date() < self.quietUntil
                let nearEnd = self.duration > 0 && furthest >= self.duration - 4
                guard let now else {
                    // Nothing active any more: after its last seconds that's the end, else Spotify stopped.
                    if !quiet {
                        if nearEnd { self.ended(gen: gen) }
                        else if item != nil, Date().timeIntervalSince(self.startedAt) < 20 {
                            // The device took the start and then dropped it (a phone's Spotify asleep in
                            // the background did, 51's 2026-11-06 log): said so; no command follows.
                            let name = Self.isThisMac(self.startedOn) ? "this Mac" : self.startedOn
                            self.letGo(gen: gen, message: "Spotify on \(name) stopped the song. Open Spotify there, or choose another device in Devices.")
                        } else { self.letGo(gen: gen, message: nil) }
                        return
                    }
                    continue
                }
                // Moved to another device (Spotify Connect, the listener's move): Yoin follows it there.
                if let id = now.device?.id, id != owner.device {
                    owner = (owner.source, id)
                    self.owned = owner
                    let name = now.device?.name ?? "another device"
                    self.onDevice(Self.isThisMac(name) ? nil : name)
                }
                let progress = now.progressMs.map { Double($0) / 1000 }
                let isQueueSong = item != nil && now.item?.id == item?.trackId
                if isQueueSong, self.showingExternal {
                    // The queue's own song is back.
                    self.showingExternal = false
                    self.events?.outputExternalTrackDidChange(nil, positionSec: progress ?? 0, playing: now.isPlaying ?? false)
                }
                if isQueueSong {
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
                    // Shuffle / repeat turned on in Spotify itself: the engine takes them (Yoin owns the play
                    // modes, as on iOS) and Spotify's own go off again.
                    if !quiet {
                        let repeatMode: RepeatMode? = switch now.repeatState {
                        case "track": .one
                        case "context": .all
                        default: nil
                        }
                        let shuffle = now.shuffleState == true
                        if shuffle || repeatMode != nil {
                            self.quietUntil = Date().addingTimeInterval(2.5)
                            Task {
                                try? await owner.source.api.send("PUT", "me/player/shuffle", ["state": "false", "device_id": owner.device])
                                try? await owner.source.api.send("PUT", "me/player/repeat", ["state": "off", "device_id": owner.device])
                            }
                            self.events?.outputPlayModesDidChangeExternally(shuffle: shuffle ? true : nil, repeatMode: repeatMode)
                        }
                    }
                    // Paused or played in Spotify itself.
                    if !quiet, isPlaying != self.playing {
                        self.playing = isPlaying
                        self.wantsPlay = isPlaying
                        self.events?.outputPlayingDidChangeExternally(isPlaying)
                    }
                } else if !quiet {
                    // Another song there: after this one's last seconds it's Spotify's autoplay (the end, the
                    // engine goes on); otherwise the listener picked it in Spotify — Now Playing shows it
                    // (iOS dfbe17ea), the queue stays parked under it, controls act on it.
                    if nearEnd, !self.showingExternal { self.ended(gen: gen); return }
                    if let t = Self.external(now.item), t.id != self.externalId || (now.isPlaying ?? false) != self.playing
                        || abs((progress ?? 0) - self.currentTimeSec) > 3 {
                        self.externalId = t.id
                        self.showingExternal = true
                        self.playing = now.isPlaying ?? false
                        self.lastTime = progress ?? 0
                        self.lastTick = Date()
                        self.events?.outputExternalTrackDidChange(t, positionSec: progress ?? 0,
                                                                  playing: now.isPlaying ?? false)
                    } else if let progress, self.showingExternal {
                        // Its clock, every read (the engine's progress follows the output's reports).
                        self.lastTime = progress
                        self.lastTick = Date()
                        self.events?.outputTimeDidChange(progress)
                    }
                }
            }
        }
    }

    /// Spotify plays a song that isn't the queue's (see `watch`).
    private var showingExternal = false
    private var externalId: String?

    // MARK: Looking at Spotify while Yoin drives nothing

    /// On launch, whenever Yoin comes to the front, and every 10 s: a look at where Spotify plays
    /// (51, 2026-11-06: open Yoin while Spotify plays and Yoin shows that song). Spotify playing with
    /// nothing of Yoin's loaded makes Yoin its remote there; the look never sends a command.
    func startWatching() {
        guard !silent, lookTimer == nil else { return }
        lookTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.look() }
        }
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil,
                                               queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.look() }
        }
        Task { [weak self] in
            // The account comes up a moment after launch.
            for _ in 0..<20 {
                try? await Task.sleep(for: .milliseconds(500))
                if self?.source() != nil { break }
            }
            self?.look()
        }
    }

    private func look() {
        guard !silent, owned == nil, current == nil, let s = source(), s !== revoked else { return }
        let gen = generation
        Task { [weak self] in
            guard let data = try? await s.api.send("GET", "me/player"), !data.isEmpty,
                  let now = try? SpotifyWebAPI.decoder.decode(NowDTO.self, from: data),
                  now.isPlaying == true, let id = now.device?.id, let t = Self.external(now.item) else { return }
            guard let self, gen == self.generation, self.owned == nil, self.current == nil,
                  self.source() === s, self.adopt?() == true else { return }
            PlayTrace.log("SP adopt \(t.id) on …\(id.suffix(4))")
            self.generation += 1
            let mine = self.generation
            self.owned = (s, id)
            let name = now.device?.name ?? ""
            self.onDevice(Self.isThisMac(name) ? nil : name)
            self.showingExternal = true
            self.externalId = t.id
            self.playing = true
            self.wantsPlay = true
            self.lastTime = now.progressMs.map { Double($0) / 1000 } ?? 0
            self.lastTick = Date()
            self.events?.outputExternalTrackDidChange(t, positionSec: self.lastTime, playing: true)
            self.watch(nil, owner: (s, id), gen: mine)
        }
    }

    /// Devices: the listener moved Spotify to [deviceId] (the transfer is made); Yoin drives it from now on.
    func adopt(deviceId: String, name: String, on s: SpotifySource) {
        guard let o = owned, o.source === s else { return }
        owned = (s, deviceId)
        quietUntil = Date().addingTimeInterval(2.5)
        onDevice(Self.isThisMac(name) ? nil : name)
    }

    /// Next / previous on Spotify's own song: Spotify skips, on the device Yoin drives.
    func skipExternal(forward: Bool) {
        guard let o = owned else { return }
        command(o, "POST", forward ? "me/player/next" : "me/player/previous")
    }

    /// Spotify's song as the engine shows it (title, artists, album and its cover).
    private static func external(_ i: NowDTO.ItemDTO?) -> PlayableTrack? {
        guard let i, let id = i.id else { return nil }
        let artist = (i.artists ?? []).compactMap(\.name).joined(separator: ", ")
        return PlayableTrack(id: id, provider: SpotifyMapping.provider, title: i.name ?? "", artist: artist,
                             album: i.album?.name ?? "", albumId: i.album?.id,
                             durationSec: Double(i.durationMs ?? 0) / 1000, streamURL: streamURL(trackId: id),
                             coverURL: i.album?.images?.first?.url.flatMap(URL.init(string:)))
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
        onDevice(nil)
        playing = false
        wantsPlay = false
        if let message { events?.outputDidFail(message, network: true) }
        else { events?.outputPlayingDidChangeExternally(false) }
    }

    /// A start that can't go ahead holds the song (network: true keeps the queue where it is for Retry).
    private func fail(_ message: String, gen: Int) {
        guard gen == generation else { return }
        Self.log.error("start: \(message, privacy: .public)")
        onDevice(nil)
        events?.outputIsBufferingDidChange(false)
        events?.outputDidFail(message, network: true)
    }
}
