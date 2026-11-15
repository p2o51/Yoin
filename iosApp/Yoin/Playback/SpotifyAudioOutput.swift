import Foundation
import os
@preconcurrency import SpotifyiOS
import UIKit

/// [AudioOutput] on the Spotify app (Android SpotifyAppRemotePlayer, reduced to what keeps Yoin's queue
/// the truth). Each song starts through the Web API as a two-song list, [current, next] (Codex N02:
/// an album context let Spotify carry on through the album while Yoin played a playlist), with
/// Spotify's own shuffle and repeat off, since the engine owns the play modes (N03). When Spotify moves
/// on to that next song, the engine is told it advanced; anything else, or the list running out, is the
/// end of the current song and the engine starts its next. App Remote reports the app's state back;
/// pause, resume and seek go through it.
///
/// Play modes (Codex N03): a mode change or a queue edit that changes the engine's next song hands
/// Spotify the new pair from the current position (paused: on the next play), so Spotify never carries
/// on to a stale song. Shuffle or repeat turned on in the Spotify app is taken over by the engine and
/// switched back off in Spotify. Repeat one hands [song, song]; the wrap to the second copy is an
/// advance.
///
/// On a Mac (the iPad app, `Platform.current.isMac`) there is no App Remote: the desktop Spotify app is a
/// Connect device like any other, driven by its id through the Web API. A start goes to the device that
/// is playing, else to this Mac's own Spotify app from the devices list, else it fails saying Spotify
/// isn't open here; a paused or idle device elsewhere never takes it (51, 2026-11-06; `macTarget`, the
/// native Mac's MacSpotifyOutput.decide).
///
/// `-silentAudio YES`: nothing is started (the Spotify app can't be muted from here); the engine still
/// moves through its queue. With the QA fake's player (`-spotifyFakePlayer YES`) the output runs all the
/// same: nothing there can sound.
@MainActor
final class SpotifyAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// The active Spotify account's Web API (start, transport, where it plays); set by the app.
    weak var control: SpotifySource? {
        didSet { control == nil ? stopWatching() : startWatching() }
    }
    /// Makes this the output the player drives, for Spotify playing something Yoin didn't start; false
    /// while the player plays another source's song (set by the app).
    var adopt: (() -> Bool)?

    private let remote: SpotifyRemote
    /// What a refused start leaves on the account's card (SpotifyIssues).
    private let issues: SpotifyIssues
    /// The Spotify app is on this device (a start here needs it).
    private let spotifyInstalled: @MainActor () -> Bool
    /// The refusal the current song is held on (see refuse).
    private var refused: SpotifyIssues.Issue?
    /// What Yoin runs on, as Spotify lists it: which Connect device is "here", and (a Mac) that here
    /// has no App Remote.
    private let device: SpotifyThisDevice
    private let silent = UserDefaults.standard.bool(forKey: "silentAudio") && !SpotifyFake.drivesPlayer
    private var current: Item?
    private var next: Item?
    /// The next song Spotify was handed with the current one (what it carries on to by itself).
    private var handedNext: Item?
    /// Another device: everything handed after the current song (a Web API start takes the queue,
    /// up to [handOffLimit], so the device carries on while iOS suspends Yoin; parity C-F1).
    private var handedList: [Item] = []
    /// The album or playlist the queue was started from (set by the app on each start): played as
    /// Spotify's own context on this iPhone, so Spotify carries on through it while Yoin is away.
    var contextPlan: SpotifyContextPlan?
    /// This iPhone plays [contextPlan] as a context (the current start went that way).
    private var contextActive = false
    /// The handed list went stale mid-song (the queue changed): re-handed at the next song, not now.
    private var staleHandoff = false
    private var generation = 0
    private var wantsPlay = false
    /// A start is on its way (Codex N04): a Resume now would wake the previous song first.
    private var starting = false
    /// Where the current start should begin (kept for a start that waits on a wake, N01).
    private var pendingStart: Double = 0
    private var lastTime: Double = 0
    private var lastTick = Date()
    private var playing = false
    private var duration: Double = 0
    /// The Spotify app has been seen on the current song (an end is only read after that).
    private var sawCurrent = false
    /// The position the last report gave (a song ended when Spotify stops at 0 right after its end).
    private var reportedTime: Double = 0
    private var timer: Timer?
    /// A coalesced re-hand of [current, next] after the engine's next changed.
    private var rehandTask: Task<Void, Never>?
    /// Mode reports right after a start still show Spotify's old modes; they're not the listener's.
    private var modesQuietUntil = Date.distantPast
    /// The Connect device Yoin drives (nil: Spotify's active device, or this iPhone once it plays).
    private var target: String?
    /// A start waits on the Spotify app waking (it hands back and connects).
    private var awaitingWake = false
    /// Spotify plays this song, not the queue's (shown through `outputExternalTrackDidChange`).
    private var externalURI: String?
    /// Reads where Spotify is while Yoin is in the foreground (App Remote's state stops when iOS
    /// suspends Yoin, and says nothing about another device).
    private var pollTimer: Timer?
    private var refreshTask: Task<Void, Never>?
    /// Spotify was found playing with nothing of Yoin's loaded: Yoin shows it and is its remote.
    private var adopted = false
    /// Looks for Spotify playing somewhere while Yoin is visible, even with its own player idle (51,
    /// 2026-11-06: open Yoin while the Pixel plays and Yoin shows that song).
    private var watchTimer: Timer?
    /// When App Remote last pushed a state, and the length of the song it named (Spotify's own song too).
    private var lastRemoteStateAt = Date.distantPast
    private var stateDurationSec: Double = 0
    /// The song whose run past its end has already asked me/player (once per song).
    private var endCheckedURI: String?
    /// A connection was dropped or replaced and the one after it hasn't pushed a state yet: me/player is
    /// read every [pollSec] meanwhile (audit 11-15 #6, #7).
    private var awaitingFirstState = false
    /// The connection now up has pushed a state (only replacing one that did counts against the gap).
    private var connectionSpoke = false
    /// Watches in a row that found App Remote quiet against me/player.
    private var silentStrikes = 0
    /// A command sent over App Remote that no state has answered yet (see awaitEcho).
    private var pendingEcho: PendingEcho?
    /// The engine answers a failure with a pause; after a failure Yoin reported that pause is not
    /// sent (a command is never error handling: a pause to a device that lost the song was refused).
    private var quietPause = false
    /// Spotify answered a start on another device but no read has shown it there yet: transport
    /// commands wait in [held], in order (a resume sent right after a start got 403 Restriction
    /// violated, 2026-11-06 Mac run). They go when the start is seen, or are dropped with it.
    private var landing = false
    private var held: [() -> Void] = []
    /// When the last start on another device went out, from where, and whether it's still waiting to
    /// be seen playing: a device that drops it is said so, not shown as a plain pause.
    private var startedAt = Date.distantPast
    private var startedAtSec: Double = 0
    private var awaitingPlay = false
    /// The last thing the engine did was stop (not load another song).
    private var stopped = false
    private var observers: [NSObjectProtocol] = []
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    /// A song as the engine names it: spotify-yoin://track/<id>.
    struct Item: Equatable, Sendable {
        var trackId: String
        var trackURI: String { "spotify:track:\(trackId)" }

        init?(_ url: URL?) {
            guard let url, url.scheme == SpotifyAudioOutput.scheme else { return nil }
            let id = url.lastPathComponent
            guard !id.isEmpty, id != "/" else { return nil }
            trackId = id
        }
    }

    nonisolated static let scheme = "spotify-yoin"

    /// The engine's stand-in stream URL for a Spotify song.
    nonisolated static func streamURL(trackId: String) -> URL {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "track"
        c.path = "/" + trackId
        return c.url ?? URL(string: "\(scheme)://track/unknown")!
    }

    nonisolated static func isSpotify(_ url: URL?) -> Bool { url?.scheme == scheme }

    init(remote: SpotifyRemote = .shared, issues: SpotifyIssues = .shared,
         spotifyInstalled: @escaping @MainActor () -> Bool = { SpotifyApp.isInstalled },
         device: SpotifyThisDevice? = nil) {
        self.remote = remote
        self.issues = issues
        self.spotifyInstalled = spotifyInstalled
        self.device = device ?? .current
        remote.onPlayerState = { [weak self] s in self?.playerState(s) }
        remote.onFailure = { [weak self] m in
            guard let self else { return }
            // The wake found no Spotify app, or Spotify handed back a Premium refusal: held, not skipped.
            if let issue = Self.refusal(m) { return self.refuse(issue, gen: self.generation) }
            // Anything else (Spotify installed but not taking the hand-off, a handback with an error, no
            // Client ID) is held on the song too. Reported as a plain failure it skipped to the next song,
            // which failed the same way, through the whole queue (audit 11-15 #1).
            self.holdStart(m, gen: self.generation)
        }
        remote.onWoken = { PlayTrace.log("SP woken") }
        remote.onConnected = { [weak self] in self?.connected() }
        remote.onDisconnected = { [weak self] in self?.disconnected() }
        let c = NotificationCenter.default
        observers.append(c.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.foreground() }
        })
        observers.append(c.addObserver(forName: SpotifyPlayingDevice.transferred, object: nil,
                                       queue: .main) { [weak self] n in
            let id = n.userInfo?["id"] as? String
            let name = n.userInfo?["name"] as? String
            let type = n.userInfo?["type"] as? String
            MainActor.assumeIsolated { self?.followTransfer(id: id, name: name, type: type) }
        })
        observers.append(c.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.becameActive() }
        })
        observers.append(c.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.background() }
        })
    }

    var currentTimeSec: Double {
        playing ? lastTime + Date().timeIntervalSince(lastTick) : lastTime
    }

    func load(_ current: URL, next: URL?, startSec: Double) {
        PlayTrace.log("SP load \(current.lastPathComponent) start=\(startSec)")
        generation += 1
        stopped = false
        rehandTask?.cancel()
        let item = Item(current)
        self.current = item
        self.next = Item(next)
        handedNext = nil
        handedList = []
        contextActive = false
        staleHandoff = false
        failedHere = false
        refused = nil
        reconnectsHere = 0
        relinkedURI = nil
        wokeHere = false
        triedAgainOnMac = false
        nextUnhanded = false
        wakeWait = nil
        awaitingWake = false
        externalURI = nil
        adopted = false
        quietPause = false
        resetStart()
        enqueueWhenPlaying = false
        sawCurrent = false
        endCheckedURI = nil
        stateDurationSec = 0
        pendingEcho = nil
        pendingResume = nil
        playing = false
        lastTime = startSec
        lastTick = Date()
        pendingStart = startSec
        duration = 0
        startTicking()
        guard let item else { return events?.outputDidFail(String(localized: "Not a Spotify song")) ?? () }
        let gen = generation
        guard !silent else {
            // A silent run starts nothing, but a start that could never run here is still said (once the
            // engine has finished its load).
            // (A Mac has no App Remote to need the app for: its own rule says when Spotify isn't open.)
            if !device.isMac, !spotifyInstalled() { Task { [weak self] in self?.refuse(.appMissing, gen: gen) } }
            return
        }
        starting = true
        Task { await start(item, gen: gen) }
    }

    /// Where a start goes (51, 2026-11-06): a device that is actually playing Spotify keeps it, and
    /// Yoin is its remote (Web API); otherwise this iPhone or iPad, through App Remote. me/player also
    /// names the last-used device when nothing plays (an asleep Pixel took a start and did nothing).
    /// A Mac has its own rule (startOnMac).
    private func start(_ item: Item, gen: Int) async {
        if device.isMac { return await startOnMac(item, gen: gen) }
        let now = (try? await control?.playerNow()) ?? nil
        PlayTrace.log("SP start now=\(Self.describe(now))")
        guard gen == generation else { return }
        if let now, let id = Self.remoteTarget(now, this: device) {
            target = id
            SpotifyPlayingDevice.shared.name = now.deviceName
            startPolling()
            return await send(item, gen: gen, modes: now)
        }
        target = nil
        SpotifyPlayingDevice.shared.name = nil
        stopPolling()
        await startHere(item, gen: gen, moveHereFirst: Self.idleElsewhere(now, this: device))
    }

    /// Spotify sits paused or idle on another device (me/player names one that isn't this iPhone or iPad
    /// and isn't playing): App Remote would start the song there (Android e7552df4, the 11-08 tablet test:
    /// a tap in Yoin played on 51's paused iPhone), so the start moves Spotify here first (moveHere).
    nonisolated static func idleElsewhere(_ now: SpotifySource.PlayerNow?, this: SpotifyThisDevice) -> Bool {
        guard let now else { return false }
        return !now.isPlaying && !this.matches(now)
    }

    /// This iPhone's or iPad's id in the account's Connect devices, for moving Spotify here: nil when it
    /// isn't listed with an id, or when two are named alike (iOS can't tell which one it is).
    nonisolated static func hereId(_ devices: [SpotifySource.Device], this: SpotifyThisDevice) -> String? {
        let mine = devices.filter { $0.hasId && this.matches($0) }
        return mine.count == 1 ? mine[0].id : nil
    }

    nonisolated static let moveHereReads = 3
    nonisolated static let moveHereGapSec: Double = 0.4

    /// Android bringSpotifyHereIfIdleElsewhere: only a device that is playing keeps a start (51,
    /// 2026-11-06), so with Spotify left paused elsewhere it comes back to this iPhone or iPad before App
    /// Remote's start: moved here as it was (paused) through the Web API (iOS's App Remote has no Connect
    /// switch), then a few quick reads until me/player names this device. Not listed, a failed move or a
    /// slow one: the start goes out as before.
    private func moveHere(gen: Int) async {
        guard let control else { return }
        do {
            let devices = try await control.devices()
            guard gen == generation else { return }
            guard let id = Self.hereId(devices, this: device) else {
                return PlayTrace.log("SP start: Spotify idles elsewhere; this \(device.word) isn't told apart in its devices")
            }
            PlayTrace.log("SP start: Spotify idles elsewhere; moving it to this \(device.word) first")
            try await control.transfer(to: id, play: false)
            for _ in 0..<Self.moveHereReads {
                try? await Task.sleep(for: .seconds(Self.moveHereGapSec))
                guard gen == generation else { return }
                if let now = try? await control.playerNow(), device.matches(now) { return }
            }
            PlayTrace.log("SP start: me/player doesn't name this \(device.word) yet; starting all the same")
        } catch {
            PlayTrace.log("SP start: moving Spotify here failed \(error)")
        }
    }

    nonisolated static func describe(_ now: SpotifySource.PlayerNow?) -> String {
        guard let now else { return "nothing active" }
        return "\(now.deviceName) [\(now.deviceType)] playing=\(now.isPlaying) track=\(now.trackURI ?? "-") at=\(now.progressMs)"
    }

    // MARK: A Mac (the Web API only)

    enum MacTarget: Equatable {
        /// [here]: this Mac's own Spotify app.
        case device(id: String, name: String, here: Bool)
        /// Nothing plays anywhere and this Mac's Spotify app isn't in the account's devices.
        case unreachable
    }

    /// The Mac's rule as a decision over what me/player shows and the account's devices (YoinMac
    /// MacSpotifyOutput.decide): the device Spotify is playing on, wherever it is; else this Mac's own
    /// Spotify app. A paused or idle device elsewhere never takes the start (51, 2026-11-06: a phone's
    /// Spotify asleep in the background took one and did nothing), and never another computer.
    nonisolated static func macTarget(now: SpotifySource.PlayerNow?, devices: [SpotifySource.Device],
                                      this: SpotifyThisDevice) -> MacTarget {
        if let now, now.isPlaying, let id = now.deviceId {
            return .device(id: id, name: now.deviceName, here: this.matches(now))
        }
        if let own = this.pick(devices) { return .device(id: own.id, name: own.name, here: true) }
        return .unreachable
    }

    /// A start on a Mac: never App Remote. The device is read (me/player, then the devices when nothing
    /// plays), named in every request, and the song goes out as it does to any other Connect device.
    private func startOnMac(_ item: Item, gen: Int) async {
        guard let control else { return failOnMac(String(localized: "Sign in to Spotify again in Settings."), gen: gen) }
        let now: SpotifySource.PlayerNow?
        let decided: MacTarget
        do {
            now = try await control.playerNow()
            // The devices are only asked for when nothing plays (the playing device is the answer).
            let playing = now?.isPlaying == true && now?.deviceId != nil
            let devices = playing ? [] : try await control.devices()
            decided = Self.macTarget(now: now, devices: devices, this: device)
        } catch {
            PlayTrace.log("SP start on a Mac: no answer \(error)")
            return failOnMac(SpotifySource.message(for: error), gen: gen)
        }
        PlayTrace.log("SP start on a Mac now=\(Self.describe(now)) target=\(decided)")
        guard gen == generation else { return }
        switch decided {
        case .unreachable:
            target = nil
            SpotifyPlayingDevice.shared.name = nil
            stopPolling()
            failOnMac(SpotifyFailureCopy.notOpenOnMac, gen: gen)
        case .device(let id, let name, let here):
            target = id
            SpotifyPlayingDevice.shared.name = here ? nil : name
            startPolling()
            await send(item, gen: gen, modes: now?.deviceId == id ? now : nil)
        }
    }

    /// A start on a Mac that can't go out holds the song (Retry starts it again where it was). The
    /// engine answers a failure with a pause: it isn't sent (a command is never error handling).
    private func failOnMac(_ message: String, gen: Int) {
        guard gen == generation else { return }
        starting = false
        failedHere = true
        quietPause = true
        events?.outputDidFail(message, network: true)
    }

    /// This Mac's Spotify app is the device Yoin drives (it is never named in Now Playing).
    private var onThisMac: Bool { device.isMac && target != nil && SpotifyPlayingDevice.shared.name == nil }

    /// A start to this Mac's Spotify that didn't land gets one more go per song (YoinMac
    /// MacSpotifyOutput.again; iOS b7cc9e27a): the devices are read afresh, so a Spotify that has quit
    /// meanwhile is said so (Open Spotify) and one that only stumbled plays.
    private var triedAgainOnMac = false

    /// Whether a start that didn't land tries once more: only to this Mac (a device playing elsewhere is
    /// the listener's), only once, and never for what another go can't change (no Premium, a rate limit).
    nonisolated static func retriesOnMac(alreadyTried: Bool, toThisMac: Bool, error: Error?) -> Bool {
        guard toThisMac, !alreadyTried else { return false }
        if let e = error as? SpotifyAPIError {
            if e.isForbidden || e == .unauthorized { return false }
            if case .rateLimited = e { return false }
        }
        return true
    }

    nonisolated static let macRetryGapMs = 300

    /// The one more go (see triedAgainOnMac); false: this start has had it, or isn't this Mac's.
    private func tryAgainOnMac(after error: Error?) -> Bool {
        guard Self.retriesOnMac(alreadyTried: triedAgainOnMac, toThisMac: onThisMac, error: error),
              let item = current else { return false }
        PlayTrace.log("SP start didn't land on this Mac: the devices again, once")
        triedAgainOnMac = true
        resetStart()
        starting = true
        let gen = generation
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.macRetryGapMs))
            guard let self, gen == self.generation else { return }
            await self.startOnMac(item, gen: gen)
        }
        return true
    }

    // MARK: This iPhone or iPad (App Remote — the SDK's flow)

    /// A Web API start to this iPhone's Spotify while it sits in the background was ignored and then
    /// stopped it (51's log, every run): this iPhone is only ever driven through App Remote. An asleep
    /// app is woken on the song (it plays it) and connects on the handback.
    private func startHere(_ item: Item, gen: Int, moveHereFirst: Bool = false) async {
        // No Spotify app to connect to or to wake (Android CouldNotFindSpotifyApp): said at once.
        guard spotifyInstalled() else { return refuse(.appMissing, gen: gen) }
        if await remote.connect(timeout: Self.localConnectSec) {
            guard gen == generation else { return }
            if moveHereFirst {
                await moveHere(gen: gen)
                guard gen == generation else { return }
            }
            if Self.contextStartEnabled, let plan = contextPlan, let events,
               let index = plan.contextIndex(of: item, following: events.outputQueueSnapshot()) {
                return startContextHere(item, plan: plan, index: index, gen: gen)
            }
            return startSingleHere(item, gen: gen)
        }
        guard gen == generation else { return }
        awaitingWake = true
        armWakeDeadline(item, gen: gen)
        remote.wake(playing: item.trackURI)
    }

    nonisolated static let localConnectSec: Double = 1.5

    /// The album / playlist context start on this iPhone (C-F1) is off until it has run on a real
    /// iPhone: on build 11 a start from an album page played in Spotify but Yoin held at 0:00 with
    /// "Playback unavailable" (51, 2026-11-06 23:03). `-spotifyContextStart YES` turns it on for QA.
    nonisolated static var contextStartEnabled: Bool { UserDefaults.standard.bool(forKey: "spotifyContextStart") }

    /// The queue is the album or playlist it was started from: Spotify plays that as its own context
    /// from the song (playItem + skipToTrackIndex), with Yoin's shuffle and repeat, and carries on
    /// through it by itself while iOS suspends Yoin. A context that can't be read starts the song alone.
    private func startContextHere(_ item: Item, plan: SpotifyContextPlan, index: Int, gen: Int) {
        PlayTrace.log("SP context start \(plan.uri) at=\(index)")
        remote.playContext(plan.uri, index: index) { [weak self] error in
            guard let self, gen == self.generation else { return }
            if let error {
                PlayTrace.log("SP context play failed \(error.localizedDescription): the song alone")
                return self.startSingleHere(item, gen: gen)
            }
            self.startedHere(item, context: true)
        }
    }

    /// The song alone through App Remote (the engine's next goes into Spotify's queue).
    private func startSingleHere(_ item: Item, gen: Int) {
        remote.play(item.trackURI) { [weak self] error in
            guard let self, gen == self.generation else { return }
            if let error {
                PlayTrace.log("SP AR play failed \(error.localizedDescription)")
                if let issue = Self.refusal(error.localizedDescription) { return self.refuse(issue, gen: gen) }
                self.playFailed(item, gen: gen)
            } else {
                self.startedHere(item)
            }
        }
    }

    /// App Remote answered a start with an error: Spotify may be playing the song all the same (51's
    /// build 11: it played, Yoin held at 0:00 with "Playback unavailable"). me/player decides: the song
    /// on this iPhone is a start that landed; otherwise the recovery's next step (recoverHere). One read
    /// while a fresh connection is still to be tried (a play that failed in 1 ms on a broken connection,
    /// 51's 11-08 log, didn't start anything), the full checks before a wake or the failure.
    private func playFailed(_ item: Item, gen: Int) {
        let next = Self.recovery(reconnects: reconnectsHere, woke: wokeHere)
        confirmOnWeb(item, gen: gen, reads: next == .reconnect ? 1 : Self.webChecks) { [weak self] in
            self?.recoverHere(item, gen: gen)
        }
    }

    /// App Remote can come up after a wake and say nothing for a minute and more (51's log, 23:02:07 to
    /// 23:03:36, while Spotify played the song): the Web API's me/player (a read) says what this iPhone
    /// plays. The song there (or Spotify's relinked copy of it) is the start, landed; otherwise [otherwise].
    private func confirmOnWeb(_ item: Item, gen: Int, reads: Int = SpotifyAudioOutput.webChecks,
                              otherwise: @escaping () -> Void) {
        guard let control else { return otherwise() }
        Task { [weak self] in
            // Right after a wake the Web API may not list this iPhone yet (204 at 23:02:02): a few reads.
            for attempt in 0..<max(1, reads) {
                if attempt > 0 { try? await Task.sleep(for: .seconds(Self.webCheckGapSec)) }
                let now = (try? await control.playerNow()) ?? nil
                guard let self, gen == self.generation else { return }
                PlayTrace.log("SP web check \(attempt + 1)/\(reads) \(Self.describe(now))")
                if let now, Self.landedHere(now, wanted: item.trackURI, this: self.device) {
                    PlayTrace.log("SP start landed (me/player)")
                    if now.trackURI != item.trackURI { self.relinkedURI = now.trackURI }
                    self.wakeWait = nil
                    self.startedHere(item)
                    // Its clock from me/player, then the local one until App Remote reports.
                    self.lastTime = Double(now.progressMs) / 1000
                    self.lastTick = Date()
                    self.playing = now.isPlaying
                    self.events?.outputTimeDidChange(self.lastTime)
                    return
                }
            }
            otherwise()
        }
    }

    nonisolated static let webChecks = 3
    nonisolated static let webCheckGapSec: Double = 1

    /// me/player shows the song on this iPhone or iPad (a start here that App Remote didn't report).
    nonisolated static func landedHere(_ now: SpotifySource.PlayerNow?, wanted: String, this: SpotifyThisDevice) -> Bool {
        guard let now, now.isPlaying, isSong(now, wanted) else { return false }
        return this.matches(now)
    }

    /// me/player plays [wanted]: its uri, or the copy Spotify relinked it to (linked_from names it).
    nonisolated static func isSong(_ now: SpotifySource.PlayerNow, _ wanted: String) -> Bool {
        now.trackURI == wanted || now.linkedFromURI == wanted
    }

    /// The relinked copy Spotify plays for the current song (App Remote names only the copy): read as it.
    private var relinkedURI: String?

    /// A state's song as Yoin names it: the relinked copy is the current song.
    private func ownURI(_ uri: String) -> String {
        if let relinkedURI, uri == relinkedURI, let current { return current.trackURI }
        return uri
    }

    nonisolated static func startLanded(playingURI: String?, wanted: String) -> Bool { playingURI == wanted }

    /// A reported failure clears once Spotify is seen playing the queue's song.
    nonisolated static func recovers(failed: Bool, paused: Bool) -> Bool { failed && !paused }

    /// A start here that didn't land (51, 2026-11-07: after the first song, another album said "Open
    /// Spotify on this iPhone" while Spotify was right there; 11-08: every album after a wake failed on
    /// the wake's broken connection). Twice, a fresh connection (no wake) and the song again; failing
    /// that, Spotify woken on the song as the first start does; failing that too, the failure, held
    /// until the listener taps Play (never a loop of trips to Spotify).
    private func recoverHere(_ item: Item, gen: Int) {
        guard gen == generation else { return }
        switch Self.recovery(reconnects: reconnectsHere, woke: wokeHere) {
        case .fail: return failStart(gen: gen)
        case .wake: return wakeHere(item, gen: gen)
        case .reconnect: break
        }
        reconnectsHere += 1
        PlayTrace.log("SP start didn't land: a fresh connection (\(reconnectsHere)/\(Self.maxReconnects))")
        remote.markBroken("the start didn't land")
        Task { [weak self] in
            guard let self else { return }
            if await self.remote.connect(timeout: Self.localConnectSec) {
                guard gen == self.generation else { return }
                return self.startSingleHere(item, gen: gen)
            }
            self.wakeHere(item, gen: gen)
        }
    }

    private func wakeHere(_ item: Item, gen: Int) {
        guard gen == generation else { return }
        PlayTrace.log("SP start: Spotify woken on the song")
        wokeHere = true
        awaitingWake = true
        armWakeDeadline(item, gen: gen)
        remote.wake(playing: item.trackURI)
    }

    /// A start that can't go on: held on the song with [message], never skipped; Spotify playing the song
    /// after all still clears it (apply).
    private func holdStart(_ message: String, gen: Int) {
        guard gen == generation else { return }
        PlayTrace.log("SP start held: \(message)")
        starting = false
        awaitingWake = false
        wakeWait = nil
        resetStart()
        playing = false
        failedHere = true
        // The engine answers a failure with a pause: there is nothing to pause.
        quietPause = true
        events?.outputDidFail(message, network: true)
    }

    /// A wake that never brings a connection (the handback carried neither a token nor an error, or the
    /// listener stayed in Spotify's sign-in and came back by hand) left Yoin at 0:00 with every button
    /// dead (audit 11-15 #2). After [wakeDeadlineSec] me/player decides: the song playing here is the
    /// start landing; otherwise the start is held with what to do. Returns to the front in between retry
    /// the connection (foreground → connectHereOrPoll), which finishes the wake as usual.
    private func armWakeDeadline(_ item: Item, gen: Int) {
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.wakeDeadlineSec))
            guard let self, gen == self.generation, self.awaitingWake else { return }
            PlayTrace.log("SP wake brought no connection in \(Int(Self.wakeDeadlineSec)) s: asking me/player")
            self.confirmOnWeb(item, gen: gen) { [weak self] in
                guard let self else { return }
                self.holdStart(SpotifyFailureCopy.openSpotify(on: self.device.word), gen: gen)
            }
        }
    }

    nonisolated static let wakeDeadlineSec: Double = 8

    /// The last resort: the failure, held; Spotify playing the song after all still clears it (apply).
    private func failStart(gen: Int) {
        guard gen == generation else { return }
        PlayTrace.log("SP start didn't land after a reconnect and a wake: held as a failure")
        starting = false
        failedHere = true
        // The engine answers a failure with a pause: a command is never error handling (51's log, 23:02:06.989).
        quietPause = true
        events?.outputDidFail(SpotifyFailureCopy.openSpotify(on: device.word), network: true)
    }

    /// A start Spotify can't take until the listener does something (Android SpotifyConnectFailure
    /// .SpotifyAppMissing / .PremiumRequired): held on the song with what to do, never skipped (the next
    /// song would be refused the same way), and remembered for the account's card.
    private func refuse(_ issue: SpotifyIssues.Issue, gen: Int) {
        guard gen == generation else { return }
        PlayTrace.log("SP refused: \(issue)")
        starting = false
        awaitingWake = false
        wakeWait = nil
        resetStart()
        playing = false
        // Spotify playing the song after all still clears it (apply).
        failedHere = true
        refused = issue
        // The engine answers a failure with a pause: there is nothing to pause.
        quietPause = true
        issues.note(issue, account: remote.account)
        events?.outputDidFail(issue.message, network: true)
    }

    #if DEBUG
    /// `-spotifyIssueDemo premium`: the current start refused as Spotify refuses a free account's.
    func demoRefuse(_ issue: SpotifyIssues.Issue) {
        generation += 1
        refuse(issue, gen: generation)
    }
    #endif

    /// The refusal a failure's text names: Yoin's own missing-app line, or Spotify saying the account
    /// needs Premium (Android userNotAuthorizedFailure reads the same word).
    nonisolated static func refusal(_ text: String) -> SpotifyIssues.Issue? {
        if let issue = SpotifyIssues.Issue(message: text) { return issue }
        return text.localizedCaseInsensitiveContains("premium") ? .premiumRequired : nil
    }

    /// Spotify refused the engine's next song: at this song's end the engine starts it itself.
    private var nextUnhanded = false

    /// Fresh connections per song before Spotify is woken; one wake before the failure.
    private var reconnectsHere = 0
    private var wokeHere = false
    nonisolated static let maxReconnects = 2

    enum Recovery: Equatable { case reconnect, wake, fail }

    /// What a start here that didn't land does next: a fresh connection the first [maxReconnects]
    /// times, then the wake, then the failure (a new song or the listener's Play starts over: load
    /// resets them).
    nonisolated static func recovery(reconnects: Int, woke: Bool) -> Recovery {
        woke ? .fail : reconnects < maxReconnects ? .reconnect : .wake
    }

    /// A failure was reported for the queue's song; Spotify playing it clears it (see apply).
    private var failedHere = false

    /// The song plays here: the engine's next goes into Spotify's queue (it carries on there while iOS
    /// suspends Yoin), Spotify's own modes off, the position and a pause applied.
    private func startedHere(_ item: Item, context: Bool = false) {
        PlayTrace.log("SP started here \(item.trackId)\(context ? " in its context" : "")")
        issues.appFound()
        issues.started(account: remote.account)
        starting = false
        awaitingWake = false
        playing = wantsPlay
        lastTick = Date()
        modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
        contextActive = context
        let snapshot = events?.outputQueueSnapshot() ?? OutputQueueSnapshot()
        // A context plays with Yoin's modes (Spotify runs it); a lone song with Spotify's off (the engine
        // runs the queue).
        remote.setShuffle(context && snapshot.shuffle)
        remote.setRepeat(context ? Self.spotifyRepeat(snapshot.repeatMode) : .off)
        if pendingStart > 1 { remote.seek(toMs: Int(pendingStart * 1000)) }
        lastTime = pendingStart > 1 ? pendingStart : 0
        if !wantsPlay { remote.pause() }
        if context {
            // The context carries on by itself: what it plays next is the engine's next while unshuffled.
            handedNext = snapshot.shuffle ? nil : next
            return
        }
        // The next goes into Spotify's queue once the song is seen playing: right after the start App Remote
        // refused it (-3000, 51's build 7 log, 18:22:38).
        enqueueWhenPlaying = true
    }

    nonisolated static func spotifyRepeat(_ mode: RepeatMode) -> SPTAppRemotePlaybackOptionsRepeatMode {
        switch mode {
        case .one: .track
        case .all: .context
        case .off: .off
        }
    }

    func playModesDidChange(shuffle: Bool, repeatMode: RepeatMode) {
        // Only a context Spotify runs takes Yoin's modes; a lone song keeps Spotify's off.
        guard contextActive, isHere, !silent, remote.isConnected else { return }
        modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
        remote.setShuffle(shuffle)
        remote.setRepeat(Self.spotifyRepeat(repeatMode))
    }

    private var enqueueWhenPlaying = false

    nonisolated static func readyToEnqueue(waiting: Bool, stateURI: String, current: String?, paused: Bool,
                                           positionMs: Int) -> Bool {
        waiting && stateURI == current && !paused && positionMs > 0
    }

    /// App Remote's "try again later" (-3000), or a connection that broke (it is replaced first): worth
    /// one more go a second later.
    nonisolated static func retriesEnqueue(errorCode: Int, alreadyRetried: Bool, broken: Bool = false) -> Bool {
        (errorCode == -3000 || broken) && !alreadyRetried
    }

    /// The engine's next, queued in Spotify once per song.
    private func enqueueNext(retried: Bool = false) {
        guard isHere, !contextActive, !onSpotifysOwnSong, handedNext == nil || retried, let next, remote.isConnected else { return }
        enqueueWhenPlaying = false
        handedNext = next
        nextUnhanded = false
        let gen = generation, song = current
        remote.enqueue(next.trackURI) { [weak self] error in
            guard let error, let self else { return }
            PlayTrace.log("SP enqueue failed \(error.localizedDescription)")
            guard gen == self.generation, self.current == song, self.next == next else { return }
            guard Self.retriesEnqueue(errorCode: (error as NSError).code, alreadyRetried: retried,
                                      broken: SpotifyRemote.breaksConnection(error)) else {
                // Spotify is left alone: at the song's end the engine starts its next itself
                // (outputCurrentDidFinish, not the queue's end).
                self.handedNext = nil
                self.nextUnhanded = true
                return
            }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(1))
                // A broken connection is replaced here (no wake).
                guard let self, await self.remote.connect(timeout: Self.localConnectSec) else { return }
                guard gen == self.generation, self.current == song, self.next == next else { return }
                self.enqueueNext(retried: true)
            }
        }
    }

    /// This iPhone's or iPad's own Spotify app, driven through App Remote. Never on a Mac: its Spotify
    /// app has an id and is driven like any other Connect device (see onThisMac).
    private var isHere: Bool { !device.isMac && SpotifyPlayingDevice.shared.name == nil }

    /// Add to queue while Spotify plays a song of its own (no Yoin queue under it): straight into
    /// Spotify's queue, in order (Android SpotifyAppRemotePlayer.playerApi.queue). Only on this phone,
    /// over App Remote; [done] says whether every song went in (Codex review 2026-11-10 #16).
    func enqueueOnSpotify(_ trackIds: [String], _ done: @escaping @MainActor (Bool) -> Void) {
        guard isHere, remote.isConnected, let first = trackIds.first else { return done(false) }
        remote.enqueue("spotify:track:\(first)") { [weak self] error in
            Task { @MainActor in
                if let error {
                    PlayTrace.log("SP queue add failed \(error.localizedDescription)")
                    return done(false)
                }
                guard let self, trackIds.count > 1 else { return done(true) }
                self.enqueueOnSpotify(Array(trackIds.dropFirst()), done)
            }
        }
    }


    /// Spotify plays a song that isn't the queue's (picked there, or found playing on open).
    private var onSpotifysOwnSong: Bool { externalURI != nil || adopted }

    /// The song a command is meant for: dropped when, before it goes out (or before a retry), another
    /// load happened or Spotify moved to another song (Codex: a late pause or seek landed on the next).
    struct Aim: Equatable {
        var generation: Int
        var uri: String?
    }

    private func aim(song: Bool) -> Aim {
        Aim(generation: generation, uri: song ? shownURI : nil)
    }

    /// The song Yoin shows as playing in Spotify.
    private var shownURI: String? { externalURI ?? current?.trackURI }

    nonisolated static func stillAimed(_ aim: Aim, generation: Int, shownURI: String?) -> Bool {
        aim.generation == generation && (aim.uri == nil || aim.uri == shownURI)
    }

    private func isStill(_ aim: Aim) -> Bool {
        Self.stillAimed(aim, generation: generation, shownURI: shownURI)
    }

    /// A connection came up: after a wake the woken app already plays the song; otherwise (back in
    /// the foreground) its state arrives by itself.
    private func connected() {
        issues.appFound()
        // The quiet spell is the new connection's, not the time since the old one last spoke (audit 11-15
        // #6: back from the lock screen it read minutes of silence and tore down a good connection).
        lastRemoteStateAt = Date()
        connectionSpoke = false
        silentStrikes = 0
        // App Remote reports this iPhone again: the fallback reads stop (see connectHereOrPoll); after a
        // drop or a replacement only once it has pushed a state (playerState).
        if isHere, !awaitingFirstState { stopPolling() }
        guard awaitingWake, starting, let current, !silent else { return }
        awaitingWake = false
        // The handback's connection comes up before Spotify's first state: read right away it shows no
        // song yet (51's build 11 log, 23:02:06.988, "woken without the song" 1 ms after the connection,
        // a play sent over the song already playing failed, and Yoin held at 0:00). So the wake waits up
        // to [wakeSettleSec] for a state that has the song; only one that never comes is finished by a
        // resume or a play.
        // A woken app's first connection may carry nothing (51's 11-08 log): SpotifyRemote replaces it,
        // and the replacement's first state lands here too.
        let gen = generation
        wakeWait = (current, gen)
        remote.getPlayerState { [weak self] s in
            guard let self else { return }
            self.wakeState(uri: s.map { self.ownURI($0.uri) }, paused: s?.isPaused ?? true, final: false)
        }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.wakeSettleSec))
            guard let self, self.wakeWait?.gen == gen else { return }
            self.wakeState(uri: self.lastWakeURI, paused: self.lastWakePaused, final: true)
        }
    }

    /// The woken app's start, waiting on its first real state.
    private var wakeWait: (item: Item, gen: Int)?
    private var lastWakeURI: String?
    private var lastWakePaused = true
    nonisolated static let wakeSettleSec: Double = 2

    enum WakeStep: Equatable { case wait, finish(WakeFinish) }

    /// One state seen after a wake's handback: the wanted song is the start landing (resume it only if
    /// it is paused while wanted); no song or another one is waited past until [final].
    nonisolated static func wakeStep(playingURI: String?, paused: Bool, wanted: String, wantsPlay: Bool,
                                     final: Bool) -> WakeStep {
        if playingURI != wanted, !final { return .wait }
        return .finish(wakeFinish(playingURI: playingURI, paused: paused, wanted: wanted, wantsPlay: wantsPlay))
    }

    private func wakeState(uri: String?, paused: Bool, final: Bool) {
        guard let wait = wakeWait, wait.gen == generation else { return }
        if let uri, !uri.isEmpty {
            lastWakeURI = uri
            lastWakePaused = paused
        }
        let current = wait.item, gen = wait.gen
        guard case .finish(let finish) = Self.wakeStep(playingURI: uri, paused: paused, wanted: current.trackURI,
                                                       wantsPlay: wantsPlay, final: final)
        else { return }
        wakeWait = nil
        lastWakeURI = nil
        guard finish != .none else { return startedHere(current) }
        if final, finish == .play {
            // No state with the song came over App Remote: me/player may still show it playing here
            // (the woken app played it; 51, 11-08). Only a Spotify without it gets the play.
            return confirmOnWeb(current, gen: gen) { [weak self] in self?.finishWake(current, finish, gen: gen) }
        }
        finishWake(current, finish, gen: gen)
    }

    private func finishWake(_ current: Item, _ finish: WakeFinish, gen: Int) {
        guard gen == generation else { return }
        PlayTrace.log("SP woken \(finish == .resume ? "paused on the song: resume" : "without the song: play it")")
        let done: (Error?) -> Void = { [weak self] error in
            guard let self, gen == self.generation else { return }
            if let error {
                PlayTrace.log("SP AR play failed \(error.localizedDescription)")
                if let issue = Self.refusal(error.localizedDescription) { return self.refuse(issue, gen: gen) }
                self.playFailed(current, gen: gen)
            } else {
                self.startedHere(current)
            }
        }
        if finish == .resume { remote.resume(done) } else { remote.play(current.trackURI, done) }
    }

    enum WakeFinish: Equatable { case none, resume, play }

    /// After a wake, the rest of the start (once): the song paused there resumes, another song (or none) is
    /// replaced by it; already playing it, nothing.
    nonisolated static func wakeFinish(playingURI: String?, paused: Bool, wanted: String, wantsPlay: Bool) -> WakeFinish {
        if playingURI != wanted { return .play }
        return wantsPlay && paused ? .resume : .none
    }

    /// Playback moved in Devices: commands and reads follow it there (this iPhone or iPad: App Remote;
    /// a Mac's own Spotify: its id, unnamed).
    private func followTransfer(id: String?, name: String?, type: String?) {
        guard let id, isDriving else { return }
        let here = name.map { device.matches(name: $0, type: type ?? device.type) } ?? false
        target = here && !device.isMac ? nil : id
        SpotifyPlayingDevice.shared.name = here ? nil : name
        if isHere {
            // Back to this iPhone: App Remote, or me/player every [pollSec] while it can't be reached
            // (audit 11-15 #52: one try, then only the 10 s watch followed it).
            stopPolling()
            connectHereOrPoll()
        } else {
            startPolling()
            refresh(after: 1)
        }
    }

    private func background() {
        stopPolling()
        stopWatching()
        remote.dropForBackground()
    }

    /// The Web API start: the current song and the queue after it (up to [handOffLimit], so the device
    /// carries on through it while iOS suspends Yoin; parity C-F1) from the remembered position. Spotify's own modes go off
    /// first, and only when [modes] (the device's state just read) has one on: a mode change right
    /// after the start reached an Android device while it loaded the song and left it with none (51's
    /// log, build 4: shuffle took 2 s, then the Pixel had no song).
    private func send(_ item: Item, gen: Int, modes: SpotifySource.PlayerNow? = nil) async {
        let next = self.next
        let account = remote.account
        let after = Self.handOff(next: next, upcoming: (events?.outputUpcoming(limit: Self.handOffLimit - 1) ?? []).compactMap(Item.init))
        // The start often runs as Spotify comes to the front: iOS would suspend Yoin mid-request.
        let bg = UIApplication.shared.beginBackgroundTask(withName: "spotify-start")
        defer { if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) } }
        do {
            let at = pendingStart > 1 ? Int(pendingStart * 1000) : 0
            let uris = [item.trackURI] + after.map(\.trackURI)
            PlayTrace.log("SP send PUT play \(uris) at=\(at) wantsPlay=\(wantsPlay) target=\(target ?? "-")")
            guard let control else { throw SpotifyPlaybackError(String(localized: "Sign in to Spotify again in Settings.")) }
            if let modes, modes.shuffle || modes.repeatState != "off" {
                PlayTrace.log("SP modes off before start shuffle=\(modes.shuffle) repeat=\(modes.repeatState)")
                await control.playModesOff(deviceId: target)
                guard gen == generation else { return }
            }
            let device = target
            // Asked before the request and before every retry: a newer start or another account wins.
            let still: SpotifyWebAPI.StillWanted = { [weak self, weak control] in
                await self?.startStillWanted(gen: gen, control: control) ?? false
            }
            try await control.startPlayback(uris: uris, positionMs: at, deviceId: device, while: still)
            PlayTrace.log("SP send done gen=\(gen == generation)")
            // Spotify took a start for this account: it may play.
            issues.started(account: account)
            guard gen == generation else {
                // Stopped while the start was on its way: it mustn't keep playing there (stop() sends
                // nothing for a start not yet answered).
                if stopped { Task { try? await control.pausePlayback(deviceId: device) } }
                return
            }
            handedList = after
            handedNext = after.first
            staleHandoff = false
            starting = false
            playing = wantsPlay
            lastTime = Double(at) / 1000
            lastTick = Date()
            modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
            startedAt = Date()
            startedAtSec = lastTime
            awaitingPlay = wantsPlay
            landing = true
            if !wantsPlay { held.append { [weak self] in self?.sendPause() } }
            verifyStarted(item, gen: gen)
            // The engine's next changed while this start was on its way.
            scheduleRehand()
        } catch {
            PlayTrace.log("SP send failed \(error)")
            guard gen == generation else { return }
            // This Mac's Spotify refused it (quit since the devices were read, or still coming up): the
            // devices again and the song once more, before anything is said.
            if !(error is SpotifyCommandDropped), tryAgainOnMac(after: error) { return }
            Self.log.error("start failed: \(error.localizedDescription, privacy: .public)")
            let message = SpotifySource.message(for: error)
            // A free account (403 Premium required): every song would be refused, so the queue is held.
            if let issue = SpotifyIssues.Issue(message: message) { return refuse(issue, gen: gen) }
            // This Mac's Spotify not taking a start holds the song, as its being closed does.
            if onThisMac { return failOnMac(Self.macStartFailure(error), gen: gen) }
            starting = false
            // No device to play on holds the queue (Retry) rather than skipping through it.
            events?.outputDidFail(message,
                                  network: error is SpotifyPlaybackError || PlaybackFailureKind.isNetwork(error))
        }
    }

    /// What a start this Mac's Spotify refused says: a device that is gone (404) is Spotify not open
    /// here; anything else is Spotify's own reason.
    nonisolated static func macStartFailure(_ error: Error) -> String {
        if let e = error as? SpotifyAPIError, e.isNotFound { return SpotifyFailureCopy.notOpenOnMac }
        return SpotifySource.message(for: error)
    }

    /// A start may still go out: same session, same account.
    private func startStillWanted(gen: Int, control asked: SpotifySource?) -> Bool {
        guard let asked else { return false }
        return Self.watchStillValid(asked: asked, current: control, askedGen: gen, generation: generation)
    }

    /// The device a start is sent to as a remote: one that is playing Spotify right now and isn't
    /// this iPhone or iPad ([this]; parity I23: an iPad is the Tablet named iPad). nil: start here.
    nonisolated static func remoteTarget(_ now: SpotifySource.PlayerNow?, this: SpotifyThisDevice) -> String? {
        guard let now, now.isPlaying, let id = now.deviceId, !this.matches(now) else { return nil }
        return id
    }

    /// A device can take a start (204) and do nothing (asleep): read it back after a moment and say
    /// so once, instead of showing a song that isn't playing.
    private func verifyStarted(_ item: Item, gen: Int) {
        Task {
            try? await Task.sleep(for: .seconds(Self.verifySec))
            guard gen == generation, let control else { return }
            // A read that failed (timeout, 401, 429) says nothing of the device: read again, and with no
            // answer at all the start stands (polling follows it) — only an answer can drop it (Codex 11-07).
            // Nothing there (204) counts only when said twice in a row, as the Mac reads it.
            var answer: SpotifySource.PlayerNow??
            var empties = 0
            for attempt in 0..<Self.verifyReads {
                if attempt > 0 { try? await Task.sleep(for: .seconds(1)) }
                guard gen == generation else { return }
                do {
                    let now = try await control.playerNow()
                    answer = .some(now)
                    if now != nil { break }
                    empties += 1
                    if empties >= 2 { break }
                } catch {
                    PlayTrace.log("SP verify read failed \(error)")
                    empties = 0
                }
            }
            // A single 204 among failed reads isn't the device stopping.
            if case .some(.none) = answer, empties < 2 { answer = nil }
            guard gen == generation else { return }
            if let now = answer { PlayTrace.log("SP verify \(Self.describe(now))") }
            switch Self.verifyOutcome(answer, wanted: item.trackURI, next: handedNext?.trackURI) {
            case .stands: PlayTrace.log("SP verify: no answer, the start stands")
            case .ours(let now): apply(now)
            case .other(let now):
                // Something else plays there (the start is late, or never took): Yoin shows that, and
                // moves back onto the queue's song if Spotify gets to it.
                land(false)
                apply(now)
            case .dropped: dropped()
            }
        }
    }

    nonisolated static let verifyReads = 3

    enum VerifyOutcome: Equatable {
        /// No read answered: nothing is known, the start stands.
        case stands
        case ours(SpotifySource.PlayerNow)
        case other(SpotifySource.PlayerNow)
        /// Spotify answered with no song there, or a stopped one that isn't ours.
        case dropped
    }

    /// What the reads after a start say: [answer] nil = every read failed; .some(nil) = 204, nothing there.
    nonisolated static func verifyOutcome(_ answer: SpotifySource.PlayerNow??, wanted: String, next: String?) -> VerifyOutcome {
        guard let answer else { return .stands }
        guard let now = answer else { return .dropped }
        if now.trackURI == wanted || (next != nil && now.trackURI == next) { return .ours(now) }
        if now.isPlaying, now.trackURI != nil { return .other(now) }
        return .dropped
    }

    /// The device took the start and then had no song, or never played it: said once, with Retry (which
    /// starts on this iPhone, as nothing plays there); no command follows and Yoin doesn't switch by itself.
    /// This Mac's own Spotify gets the song once more first.
    private func dropped() {
        PlayTrace.log("SP start dropped on \(SpotifyPlayingDevice.shared.name ?? "-")")
        if tryAgainOnMac(after: nil) { return }
        resetStart()
        // Spotify seen playing the song after all clears the failure (apply).
        failedHere = true
        lastTime = currentTimeSec
        lastTick = Date()
        playing = false
        wantsPlay = false
        quietPause = true
        events?.outputDidFail(Self.droppedMessage(device: SpotifyPlayingDevice.shared.name, here: device), network: true)
    }

    /// [device] nil: the device has no name to show (on a Mac, this Mac's own Spotify).
    nonisolated static func droppedMessage(device: String?, here: SpotifyThisDevice) -> String {
        if device == nil, here.isMac { return String(localized: "Spotify on this Mac stopped the song") }
        return String(localized: "Spotify on \(device ?? String(localized: "that device")) stopped the song. Retry plays it on this \(here.word).")
    }

    /// What a read within [dropWindowSec] of a start says of it: dropped (no song there, or the queue's
    /// song still parked at its start after [stalledSec]), waiting (not playing yet), or nil (it plays,
    /// or something else is the listener's).
    nonisolated static func startCheck(_ now: SpotifySource.PlayerNow?, uri: String, fromSec: Double,
                                       elapsed: Double) -> StartCheck? {
        guard elapsed < dropWindowSec else { return nil }
        guard let now, let playingURI = now.trackURI else { return .dropped }
        guard playingURI == uri, !now.isPlaying else { return nil }
        if Double(now.progressMs) / 1000 > fromSec + 1 { return nil }
        return elapsed > stalledSec ? .dropped : .waiting
    }

    enum StartCheck { case dropped, waiting }

    nonisolated static let dropWindowSec: Double = 20
    nonisolated static let stalledSec: Double = 6

    /// The start was seen ([ok]) or given up: held commands go, or are dropped with it. True when one
    /// went out: the read that showed the start is older than that command.
    @discardableResult
    private func land(_ ok: Bool) -> Bool {
        guard landing else { return false }
        landing = false
        let acts = held
        held = []
        PlayTrace.log("SP start \(ok ? "landed" : "not landed"), held=\(acts.count)")
        if ok { acts.forEach { $0() } }
        return ok && !acts.isEmpty
    }

    private func resetStart() {
        landing = false
        held = []
        awaitingPlay = false
        startedAt = .distantPast
    }

    /// A transport command to another device waits while a start lands there.
    private func holdWhileLanding(_ act: @escaping () -> Void) -> Bool {
        // Reads that never come (the network) don't hold commands for good.
        if landing, Date().timeIntervalSince(startedAt) > Self.dropWindowSec { land(true) }
        guard landing else { return false }
        PlayTrace.log("SP command held (start landing)")
        held.append(act)
        return true
    }

    private func sendPause() {
        transport(report: false, aim(song: true)) { try await $0.pausePlayback(deviceId: $1, while: $2) }
    }

    nonisolated static let verifySec: Double = 2.5

    /// The Spotify app went away (Codex N05): the clock stops where it had got to (audit 11-15 #27: it
    /// jumped back to App Remote's last report). Driving this iPhone with Yoin in front (Spotify swiped
    /// off, crashed, updated or signed out; audit 11-15 #7), a playing song is shown paused, me/player is
    /// read now and every [pollSec] (it shows a pause, a stop, or Spotify playing on), and the
    /// connection is tried again. Yoin's own closes (background, a replacement) never come here.
    private func disconnected() {
        lastTime = currentTimeSec
        playing = false
        lastTick = Date()
        events?.outputTimeDidChange(Self.shownTime(lastTime, durationSec: stateDurationSec))
        guard isDriving, isHere, !silent, !starting, !awaitingWake,
              UIApplication.shared.applicationState != .background else { return }
        PlayTrace.log("SP App Remote dropped by Spotify: reading me/player, reconnecting")
        awaitingFirstState = true
        if wantsPlay {
            wantsPlay = false
            events?.outputPlayingDidChangeExternally(false)
        }
        startPolling()
        refresh(here: true)
        connectHereOrPoll()
    }

    func preload(_ next: URL?) {
        self.next = Item(next)
        // A context carries on by itself: its next is the engine's while the queue follows it unshuffled.
        if contextActive, let snapshot = events?.outputQueueSnapshot() {
            handedNext = contextPlan?.follows(snapshot) == true && !snapshot.shuffle ? self.next : nil
            return
        }
        scheduleRehand()
    }

    /// Spotify was handed a next song the engine no longer has next: hand it the new pair from here,
    /// after a short pause so a run of taps on the mode button makes one start.
    private func scheduleRehand() {
        rehandTask?.cancel()
        // Spotify's own song plays (Codex, 2026-11-06): a queue edit or a shuffle change is Yoin's queue
        // only; a start of the pair would cut into it, and Spotify's queue isn't touched.
        guard !onSpotifysOwnSong else { return }
        // Here the next goes into Spotify's queue instead (it can't be taken back out).
        guard !isHere else { return enqueueNext() }
        guard !silent, !starting, playing, current != nil else { return }
        let upcoming = (events?.outputUpcoming(limit: Self.handOffLimit - 1) ?? []).compactMap(Item.init)
        let fresh = Self.handOff(next: next, upcoming: upcoming)
        switch Self.rehandTiming(handed: handedList, fresh: fresh, elapsedSec: currentTimeSec,
                                 remainingSec: duration > 0 ? duration - currentTimeSec : nil) {
        case .none:
            staleHandoff = false
            return
        case .atNextSong:
            // Not mid-song (it would cut in): the next song is the engine's, read right at the switch.
            staleHandoff = true
            if duration > 0 { refresh(after: max(duration - currentTimeSec, 0) + Self.endReadSlackSec) }
            return
        case .now:
            break
        }
        rehandTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.rehandDelayMs))
            guard !Task.isCancelled else { return }
            self?.rehand()
        }
    }

    private func rehand() {
        // Paused since it was scheduled: the next play hands the new list.
        guard !onSpotifysOwnSong, wantsPlay, playing, !starting, let current else { return }
        let fresh = Self.handOff(next: next, upcoming: (events?.outputUpcoming(limit: Self.handOffLimit - 1) ?? []).compactMap(Item.init))
        guard Self.rehandTiming(handed: handedList, fresh: fresh, elapsedSec: currentTimeSec,
                                remainingSec: duration > 0 ? duration - currentTimeSec : nil) == .now
        else { return }
        pendingStart = currentTimeSec
        starting = true
        let gen = generation
        Task { await send(current, gen: gen) }
    }

    /// Spotify carries on to [handed] by itself; re-hand when that's a song the engine no longer has
    /// next. Nothing handed (the list runs out after the current song) needs nothing: the end starts the
    /// engine's next. Near the end a restart would cut the song's last seconds; the end handles it.
    nonisolated static func needsRehand(handed: Item?, next: Item?, remainingSec: Double?) -> Bool {
        guard let handed, handed != next else { return false }
        return (remainingSec ?? .infinity) > rehandMinRemainingSec
    }

    nonisolated static let rehandMinRemainingSec: Double = 4

    /// What a Web API start hands after the current song: the engine's upcoming list, or (an engine that
    /// doesn't list it) just its next.
    nonisolated static func handOff(next: Item?, upcoming: [Item]) -> [Item] {
        upcoming.isEmpty ? (next.map { [$0] } ?? []) : upcoming
    }

    /// A Web API start carries the queue this far (Spotify's uris limit).
    nonisolated static let handOffLimit = 100

    enum RehandTiming: Equatable { case none, now, atNextSong }

    /// The device was handed [handed] after the current song; the engine now has [fresh]. Same list:
    /// nothing. Different: at the song's very start a new start costs nothing audible, so now; later
    /// it waits for the next song (51: never cut into a song), which the end's read starts as the
    /// engine's. Nothing handed (a list that ran out) waits for the end too.
    nonisolated static func rehandTiming(handed: [Item], fresh: [Item], elapsedSec: Double,
                                         remainingSec: Double?) -> RehandTiming {
        guard handed != fresh else { return .none }
        guard !handed.isEmpty else { return .atNextSong }
        if elapsedSec <= rehandEarlySec, (remainingSec ?? .infinity) > rehandMinRemainingSec { return .now }
        return .atNextSong
    }

    nonisolated static let rehandEarlySec: Double = 3
    /// A read this long after the song's expected end catches Spotify on the stale next at once.
    nonisolated static let endReadSlackSec: Double = 0.4

    /// Spotify moved on to [uri] by itself, beyond the next it was handed (it ran through its list or its
    /// context while iOS suspended Yoin): the song the engine should jump to, or nil when following
    /// it would leave Yoin's queue (a stale list, a context the queue no longer follows, a song that
    /// isn't in either). [handed] is the Web API list (another device); [context] this iPhone's.
    nonisolated static func jumpTarget(uri: String, handed: [Item], upcoming: [Item],
                                       context: SpotifyContextPlan?, snapshot: OutputQueueSnapshot) -> Item? {
        if let context {
            guard context.follows(snapshot), context.trackIds.contains(where: { "spotify:track:\($0)" == uri })
            else { return nil }
            return snapshot.urls.compactMap(Item.init).first { $0.trackURI == uri }
        }
        guard let i = handed.firstIndex(where: { $0.trackURI == uri }),
              Array(handed[...i]) == Array(upcoming.prefix(i + 1)) else { return nil }
        return handed[i]
    }
    nonisolated static let rehandDelayMs = 600
    nonisolated static let modesQuietSec: Double = 3

    func play() {
        PlayTrace.log("SP play starting=\(starting) connected=\(remote.isConnected)")
        wantsPlay = true
        quietPause = false
        // A start on its way plays when it lands; resuming now would sound the previous song.
        guard !silent, !starting else { return }
        // Paused with a stale next: start the new pair here instead of resuming the old one.
        // Never on this iPhone: the rehand is a Web API start (PUT me/player/play), and one aimed here
        // knocks this iPhone's Spotify off Connect (11-06 log; audit 11-15 #3). Here Play resumes through
        // App Remote, and a stale handed next is dealt with at the song's end.
        if externalURI == nil, !isHere, let current, Self.needsRehand(handed: handedNext, next: next, remainingSec: duration > 0 ? duration - lastTime : nil) {
            pendingStart = lastTime
            starting = true
            let gen = generation
            Task { await send(current, gen: gen) }
            return
        }
        playing = true
        lastTick = Date()
        if isHere {
            let viaRemote = remote.isConnected && !remote.broken
            let web: WebCommand = { try await $0.resumePlayback(deviceId: $1, while: $2) }
            command(aim(song: false), web: web, keepsResume: true) { $0.resume() }
            if viaRemote { awaitEcho("play", .paused(false), aim: aim(song: false), web: web) { $0.resume() } }
            return
        }
        let resume = { [weak self] in
            guard let self else { return }
            self.transport(report: true, self.aim(song: false)) { try await $0.resumePlayback(deviceId: $1, while: $2) }
        }
        if !holdWhileLanding(resume) { resume() }
    }

    func pause() {
        PlayTrace.log("SP pause starting=\(starting)")
        wantsPlay = false
        rehandTask?.cancel()
        if quietPause {
            quietPause = false
            PlayTrace.log("SP pause not sent (answer to a failure)")
            return
        }
        // The listener's own pause: a kept Play is void.
        pendingResume = nil
        guard !silent, !starting else { return }
        lastTime = currentTimeSec
        lastTick = Date()
        playing = false
        awaitingPlay = false
        if isHere {
            let viaRemote = remote.isConnected && !remote.broken
            let web: WebCommand = { try await $0.pausePlayback(deviceId: $1, while: $2) }
            command(aim(song: true), web: web) { $0.pause() }
            if viaRemote { awaitEcho("pause", .paused(true), aim: aim(song: true), web: web) { $0.pause() } }
            return
        }
        if !holdWhileLanding({ [weak self] in self?.sendPause() }) { sendPause() }
    }

    func seek(to sec: Double) {
        PlayTrace.log("SP seek \(sec)")
        let from = currentTimeSec
        lastTime = sec
        lastTick = Date()
        pendingStart = sec
        events?.outputTimeDidChange(sec)
        guard !silent, !starting else { return }
        let ms = Int(sec * 1000)
        if isHere {
            let viaWeb = !remote.isConnected || remote.broken
            let web: WebCommand = { try await $0.seekPlayback(toMs: ms, deviceId: $1, while: $2) }
            command(aim(song: true), web: web) { $0.seek(toMs: ms) }
            // A seek through the Web API has no App Remote state to answer it, and a seek to about where
            // Spotify already is may get none either (it didn't move).
            if !viaWeb, Self.seekExpectsEcho(fromSec: from, toSec: sec) {
                let sentAt = Date()
                // Unanswered on a fresh connection too: the Web API seeks (audit 11-15 #5), from where the
                // song would be by now.
                let late: WebCommand = { [weak self] control, id, still in
                    let more = await MainActor.run { (self?.playing ?? false) ? Int(Date().timeIntervalSince(sentAt) * 1000) : 0 }
                    try await control.seekPlayback(toMs: ms + more, deviceId: id, while: still)
                }
                awaitEcho("seek to \(ms) ms", .seeked(toMs: ms), aim: aim(song: true), web: late, resend: { [weak self] r in
                    // Sent again where the song would be by now, not back where the first one aimed.
                    let late = (self?.playing ?? false) ? Int(Date().timeIntervalSince(sentAt) * 1000) : 0
                    r.seek(toMs: ms + late)
                })
            }
            return
        }
        let seek = { [weak self] in
            guard let self else { return }
            self.transport(report: false, self.aim(song: true)) { try await $0.seekPlayback(toMs: ms, deviceId: $1, while: $2) }
        }
        if !holdWhileLanding(seek) { seek() }
    }

    typealias WebCommand = (SpotifySource, String?, @escaping SpotifyWebAPI.StillWanted) async throws -> Void

    /// What answers a command: a state paused or playing as asked, a state on another song (a skip), or
    /// a state at about the spot sought (a fresh connection's first state elsewhere isn't the seek).
    enum Echo: Equatable {
        case paused(Bool)
        case moved(from: String?)
        case seeked(toMs: Int)
    }

    struct PendingEcho {
        var token: UUID
        var echo: Echo
    }

    /// Whether a state answers [echo] (its song already as Yoin names it).
    nonisolated static func answers(_ echo: Echo, uri: String, paused: Bool, positionMs: Int) -> Bool {
        switch echo {
        case .paused(let want): paused == want
        case .moved(let from): uri != from
        case .seeked(let ms): positionMs >= ms - seekEchoSlackMs && positionMs <= ms + seekEchoAheadMs
        }
    }

    /// A seek's answer sits at the spot, give or take Spotify's rounding, or ahead of it by what played since.
    nonisolated static let seekEchoSlackMs = 1500
    nonisolated static let seekEchoAheadMs = 15_000

    /// The command is still what the listener wants: a pause or a play overtaken by the other (Yoin, or a
    /// state from the Spotify app taken as the listener's) isn't sent again.
    nonisolated static func stillWanted(_ echo: Echo, wantsPlay: Bool) -> Bool {
        if case .paused(let paused) = echo { return wantsPlay == !paused }
        return true
    }

    /// A pause or play waiting on its answer: a state that still shows the opposite is from before it,
    /// not the listener changing it in Spotify (audit 11-15 #5: a dropped pause's replacement connection
    /// reported playing and flipped Yoin back).
    private func echoOutstanding(statePaused: Bool) -> Bool {
        guard case .paused(let want) = pendingEcho?.echo else { return false }
        return want != statePaused
    }

    /// App Remote answers a command with a state (a seek at the new spot, a pause, a play, the next
    /// song). None that answers it within [seekEchoSec] is a connection that took the command nowhere
    /// (a tester's tapped lyric line, 2026-11-15; audit 11-15 #5 for pause, play and skip): a fresh
    /// connection, and [resend] on it unless its first state already shows the command done (a skip is
    /// sent again only while Spotify still plays the song it was sent from), checked once more. No fresh
    /// connection, a resend unanswered too, or a replacement shortly before: [web] to this iPhone's own
    /// device; failing that, me/player is read so Yoin shows what Spotify really does.
    private func awaitEcho(_ what: String, _ echo: Echo, aim: Aim, web: WebCommand?, again: Bool = false,
                           resend: ((SpotifyRemote) -> Void)?) {
        let token = UUID()
        pendingEcho = PendingEcho(token: token, echo: echo)
        let gen = generation, uri = shownURI
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.seekEchoSec))
            guard let self, self.pendingEcho?.token == token, gen == self.generation, uri == self.shownURI else { return }
            guard Self.stillWanted(echo, wantsPlay: self.wantsPlay) else { self.pendingEcho = nil; return }
            if !again, Date().timeIntervalSince(self.lastEchoReconnect) > Self.echoReconnectGapSec {
                self.lastEchoReconnect = Date()
                PlayTrace.log("SR \(what) unanswered: reconnecting")
                self.remote.markBroken("\(what) went unanswered")
                self.awaitingFirstState = true
                self.startPolling()
                if await self.remote.connect(timeout: SpotifyRemote.connectTimeoutSec) {
                    guard self.pendingEcho?.token == token, gen == self.generation, uri == self.shownURI else { return }
                    if let s = await self.remoteState(),
                       Self.answers(echo, uri: self.ownURI(s.uri), paused: s.isPaused, positionMs: s.positionMs) {
                        self.pendingEcho = nil
                        return PlayTrace.log("SR \(what): the fresh connection shows it done")
                    }
                    guard Self.stillWanted(echo, wantsPlay: self.wantsPlay), let resend else { self.pendingEcho = nil; return }
                    resend(self.remote)
                    return self.awaitEcho(what, echo, aim: aim, web: web, again: true, resend: resend)
                }
                guard self.pendingEcho?.token == token, gen == self.generation else { return }
            }
            self.pendingEcho = nil
            PlayTrace.log("SR \(what) unanswered: through the Web API")
            if let web, let control = self.control,
               await self.commandOverWeb(control, aim: aim, gen: gen, web) { return }
            guard gen == self.generation else { return }
            self.refresh(here: true)
        }
    }

    /// The Spotify app's state, read once over App Remote.
    private func remoteState() async -> RemotePlayerState? {
        await withCheckedContinuation { c in remote.getPlayerState { c.resume(returning: $0) } }
    }

    private var lastEchoReconnect = Date.distantPast
    nonisolated static let seekEchoSec: Double = 2.5
    nonisolated static let echoReconnectGapSec: Double = 30

    /// A seek within [seekEchoMinSec] of where the song is moves Spotify too little to be sure of an answer.
    nonisolated static func seekExpectsEcho(fromSec: Double, toSec: Double) -> Bool {
        abs(toSec - fromSec) >= seekEchoMinSec
    }

    nonisolated static let seekEchoMinSec: Double = 1.5

    /// A command to this iPhone's Spotify through App Remote, reconnecting first (the connection is
    /// closed while Yoin is in the background). One message when the app can't be reached.
    private func command(_ aim: Aim, web: WebCommand? = nil, keepsResume: Bool = false,
                         _ act: @escaping (SpotifyRemote) -> Void) {
        PlayTrace.log("SP command here connected=\(remote.isConnected)")
        if remote.isConnected, !remote.broken { return act(remote) }
        let gen = generation
        Task {
            guard await remote.connect(timeout: Self.localConnectSec) else {
                guard gen == generation else { return }
                // App Remote can't be reached, yet Spotify plays on this iPhone (a tester's 11-15 log:
                // every connect failed in 10 ms while Spotify played here, and each tapped lyric line
                // said "Open Spotify on this iPhone"): a seek, skip, pause or resume goes through the
                // Web API to this iPhone's own device, once me/player shows it is the one playing.
                // (Only a Web API *start* knocks this iPhone off Connect; see startHere.)
                if let web, let control, await self.commandOverWeb(control, aim: aim, gen: gen, web) { return }
                guard gen == generation else { return }
                playing = false
                // A Play that couldn't reach Spotify (suspended while paused) is kept: the first connection
                // after it (the listener opened Spotify from the banner and came back) plays, if Spotify
                // sits paused on the song (audit 11-15 #9, Android's pending resume).
                if keepsResume { pendingResume = (aim, Date()) }
                if wantsPlay {
                    wantsPlay = false
                    events?.outputPlayingDidChangeExternally(false)
                }
                return reportOnce(spotifyInstalled() ? SpotifyFailureCopy.openSpotify(on: device.word) : SpotifyFailureCopy.appMissing)
            }
            guard isStill(aim) else { return PlayTrace.log("SP command dropped (the song changed)") }
            act(remote)
        }
    }

    /// [web] on this iPhone's own Connect device, when me/player names it; false: it doesn't (or the read
    /// failed), and the caller says what to do.
    private func commandOverWeb(_ control: SpotifySource, aim: Aim, gen: Int, _ web: @escaping WebCommand) async -> Bool {
        guard let now = try? await control.playerNow(), device.matches(now), let id = now.deviceId else {
            PlayTrace.log("SP command: App Remote unreachable and me/player doesn't name this \(device.word)")
            return false
        }
        guard gen == generation, isStill(aim) else { return true }
        PlayTrace.log("SP command over the Web API to this \(device.word) (App Remote unreachable)")
        let still: SpotifyWebAPI.StillWanted = { [weak self] in await MainActor.run { self?.isStill(aim) ?? false } }
        do {
            try await web(control, id, still)
        } catch is SpotifyCommandDropped {
            PlayTrace.log("SP command dropped (the song changed)")
        } catch {
            PlayTrace.log("SP web command failed \(SpotifySource.message(for: error))")
            return false
        }
        refresh(after: 0.6, here: true)
        return true
    }

    /// One failure message per few seconds: the engine answers a failure by pausing, and a pause that
    /// failed again used to loop (25 pauses in 10 s against a vanished device).
    private func reportOnce(_ message: String) {
        guard Date().timeIntervalSince(lastReport) > Self.reportGapSec else { return }
        lastReport = Date()
        // Only when the engine has a song to hold the failure on, so its pause answers it (audit 11-15 #38:
        // with neither, no pause came and the listener's next one was swallowed).
        quietPause = current != nil || externalURI != nil
        failedHere = true
        if let issue = SpotifyIssues.Issue(message: message) {
            refused = issue
            issues.note(issue, account: remote.account)
        }
        events?.outputDidFail(message, network: true)
    }

    private var lastReport = Date.distantPast
    nonisolated static let reportGapSec: Double = 5

    /// A Play that didn't reach Spotify, and when (see command).
    private var pendingResume: (aim: Aim, at: Date)?
    nonisolated static let pendingResumeSec: Double = 90

    /// What a kept Play does on the first state after it: resume when Spotify sits paused on the song it
    /// was for, within [pendingResumeSec]; nil: drop it (Spotify plays, moved on, or it's too late).
    nonisolated static func resumesPending(shownURI: String?, aimedURI: String?, stateURI: String, paused: Bool,
                                           ageSec: Double) -> Bool {
        ageSec <= pendingResumeSec && paused && stateURI == shownURI && (aimedURI == nil || aimedURI == stateURI)
    }

    /// A transport command through the Web API on the device Yoin drives (App Remote's connection
    /// dies while iOS suspends Yoin, and can't reach another device), then a fresh read of the state.
    private func transport(report: Bool, _ aim: Aim,
                           _ act: @escaping (SpotifySource, String?, @escaping SpotifyWebAPI.StillWanted) async throws -> Void) {
        PlayTrace.log("SP transport target=\(target ?? "-")")
        let gen = generation
        let still: SpotifyWebAPI.StillWanted = { [weak self] in
            await MainActor.run { self?.isStill(aim) ?? false }
        }
        Task {
            do {
                guard let control else { throw SpotifyPlaybackError(String(localized: "Sign in to Spotify again in Settings.")) }
                try await act(control, target, still)
            } catch is SpotifyCommandDropped {
                PlayTrace.log("SP command dropped (the song changed)")
                return
            } catch {
                guard gen == generation else { return }
                Self.log.error("command failed: \(error.localizedDescription, privacy: .public)")
                PlayTrace.log("SP command failed \(SpotifySource.message(for: error))")
                // A vanished device: read where Spotify is now instead of trying again.
                playing = false
                if report {
                    let name = SpotifyPlayingDevice.shared.name ?? "that device"
                    let gone = (error as? SpotifyAPIError)?.isNotFound == true
                    // This Mac's Spotify gone from under a Play: Spotify isn't open here (Open Spotify, Retry).
                    reportOnce(gone && onThisMac ? SpotifyFailureCopy.notOpenOnMac
                               : gone ? String(localized: "Spotify on \(name) isn't responding — open Spotify there")
                               : SpotifySource.message(for: error))
                }
            }
            refresh(after: 0.6)
        }
    }

    // MARK: Reading where Spotify is (Web API)

    private var isDriving: Bool { current != nil || adopted }

    private func startWatching() {
        guard watchTimer == nil, !silent, control != nil, UIApplication.shared.applicationState != .background
        else { return }
        watchTimer = Timer.scheduledTimer(withTimeInterval: Self.watchSec, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.watch() }
        }
        watch()
    }

    private func stopWatching() {
        watchTimer?.invalidate()
        watchTimer = nil
    }

    /// One look at where Spotify plays (the remote poll covers a device Yoin already drives).
    /// [overrun]: asked because the song ran past its end with no state (enough on its own to replace a
    /// quiet connection).
    private func watch(overrun: Bool = false) {
        guard !silent, !starting, !awaitingWake, pollTimer == nil, let control else { return }
        let gen = generation
        Task { [weak self] in
            let read: SpotifySource.PlayerNow?
            do { read = try await control.playerNow() } catch { return }  // a failed read changes nothing
            // A poll started while this read was out doesn't void it (audit 11-15 #27: back in front,
            // the watch's read was dropped and nothing updated for another 4 s).
            guard let self, !self.starting, !self.awaitingWake else { return }
            // An answer for the account (or the session) that asked: one that comes back after an account
            // switch or a new start is the old one's (Codex 11-07).
            guard Self.watchStillValid(asked: control, current: self.control, askedGen: gen, generation: self.generation)
            else { return PlayTrace.log("SP watch: a stale answer, dropped") }
            guard let now = read else { return self.watchSawIdle() }
            PlayTrace.log("SP watch \(Self.describe(now)) driving=\(self.isDriving)")
            if self.isDriving, self.isHere, self.device.matches(now) {
                // App Remote reports this iPhone: me/player only checks that it still does. Without it,
                // me/player is the state, a pause too (audit 11-15 #7: only a playing one was applied).
                if self.remoteCarries { return self.checkRemoteSilent(now, decisive: overrun) }
                return self.apply(now)
            }
            guard now.isPlaying, now.trackURI != nil else { return }
            if self.isDriving {
                // Playing somewhere App Remote doesn't report from (another device, or this iPhone
                // with the connection closed): read it the Web API's way.
                return self.apply(now)
            }
            guard self.adopt?() == true else { return }
            PlayTrace.log("SP adopt \(Self.describe(now))")
            self.adopted = true
            self.generation += 1
            self.startTicking()
            let here = self.device.matches(now)
            // A Mac's own Spotify keeps its id (the Web API drives it); it just isn't named.
            self.target = here && !self.device.isMac ? nil : now.deviceId
            SpotifyPlayingDevice.shared.name = here ? nil : now.deviceName
            self.apply(now)
            if self.isHere {
                self.connectHereOrPoll()
            } else {
                self.startPolling()
            }
        }
    }

    nonisolated static let watchSec: Double = 10

    /// me/player answered 204: nothing plays on the account. A song Yoin shows playing on this iPhone
    /// with App Remote quiet for [silentAfterSec] has ended (the last of a list, after which Spotify
    /// goes idle and App Remote sent nothing): shown stopped rather than a clock running past -0:00,
    /// and the connection is replaced (once a minute at most).
    private func watchSawIdle() {
        guard isDriving, isHere, playing else { return }
        let since = Date().timeIntervalSince(lastRemoteStateAt)
        guard !remoteCarries || since >= Self.silentAfterSec else { return }
        PlayTrace.log("SP watch: nothing plays (204) while shown playing; App Remote quiet \(Int(since)) s")
        apply(nil)
        guard remoteCarries, replaceAllowed else { return }
        replaceConnection("silent while Spotify went idle")
    }

    /// App Remote carries this iPhone's state: connected, not marked broken, and not a replacement that
    /// has yet to push its first state. Short of that, me/player is what Yoin reads (audit 11-15 #6: the
    /// reads were gated on "connected", which a broken connection still reads as, and stayed off).
    private var remoteCarries: Bool { remote.isConnected && !remote.broken && !awaitingFirstState }

    /// Replacements are spaced: [silentReplaceGapSec], or [silentReplaceSpokeGapSec] once the connection
    /// now up has pushed a state (it worked, so going quiet again is news).
    private var replaceAllowed: Bool {
        Date().timeIntervalSince(lastSilentReplace) > (connectionSpoke ? Self.silentReplaceSpokeGapSec : Self.silentReplaceGapSec)
    }

    /// A connection that reads as connected but carries nothing is replaced, and me/player is read every
    /// [pollSec] until the new one pushes a state; a new one that doesn't come up keeps the reads going
    /// (audit 11-15 #6: the replacement's failure was ignored, leaving only the 10 s watch). Only
    /// replacing a connection that had spoken starts the gap: one that never did says nothing of the next.
    private func replaceConnection(_ reason: String) {
        if connectionSpoke { lastSilentReplace = Date() }
        silentStrikes = 0
        remote.markBroken(reason)
        awaitingFirstState = true
        startPolling()
        Task { [weak self] in
            guard let self else { return }
            if await self.remote.connect(timeout: SpotifyRemote.connectTimeoutSec) { return }
            self.connectHereOrPoll()
        }
    }

    /// App Remote for this iPhone's Spotify; while it can't be reached (a tester's 11-15 log: every
    /// connect failed while Spotify played here), me/player is read every [pollSec] as for a speaker,
    /// so the next song, a pause and the clock still follow Spotify. A connection that comes up stops it.
    private func connectHereOrPoll() {
        Task { [weak self] in
            guard let self else { return }
            if await self.remote.connect(timeout: Self.localConnectSec) { return }
            guard self.isHere, self.isDriving, !self.remote.isConnected else { return }
            PlayTrace.log("SP App Remote unreachable: reading me/player every \(Int(Self.pollSec)) s")
            self.startPolling()
        }
    }

    /// A connection that reads as connected can stop carrying anything while Yoin stays in front (a
    /// tester's 0.6.0 build, 2026-11-15: at a song's end Yoin sat at -0:00 until Spotify was opened
    /// and left, and a tapped lyric line didn't seek). me/player showing another song, or Spotify
    /// paused or playing against what App Remote last said, with no state pushed for
    /// [silentAfterSec], is such a connection: it is replaced, and the fresh one's first state is
    /// what Yoin follows (me/player itself can trail Spotify by a few seconds, so it isn't applied).
    private func checkRemoteSilent(_ now: SpotifySource.PlayerNow, decisive: Bool = false) {
        let since = Date().timeIntervalSince(lastRemoteStateAt)
        guard Self.remoteWentSilent(webURI: now.trackURI.map(ownURI), webPlaying: now.isPlaying,
                                    shownURI: shownURI, playing: playing, sinceRemoteSec: since)
        else { silentStrikes = 0; return }
        silentStrikes += 1
        // Twice in a row (me/player trails Spotify, or names a relinked copy by another id: one look
        // tore down good connections; audit 11-15 #6), or a song run past its end with no state. Spaced
        // so a mismatch that never clears can't turn into a reconnect on every watch.
        guard Self.replacesSilent(strikes: silentStrikes, decisive: decisive), replaceAllowed else { return }
        PlayTrace.log("SR silent for \(Int(since)) s: me/player has \(now.trackURI ?? "-") playing=\(now.isPlaying); reconnecting")
        replaceConnection("silent while Spotify moved on")
    }

    nonisolated static func replacesSilent(strikes: Int, decisive: Bool) -> Bool {
        decisive || strikes >= silentStrikesNeeded
    }

    nonisolated static let silentStrikesNeeded = 2
    nonisolated static let silentAfterSec: Double = 4
    nonisolated static let silentReplaceGapSec: Double = 60
    nonisolated static let silentReplaceSpokeGapSec: Double = 15
    private var lastSilentReplace = Date.distantPast

    nonisolated static func remoteWentSilent(webURI: String?, webPlaying: Bool, shownURI: String?, playing: Bool,
                                             sinceRemoteSec: Double) -> Bool {
        guard sinceRemoteSec >= silentAfterSec, let webURI, shownURI != nil else { return false }
        return webURI != shownURI || webPlaying != playing
    }

    /// The song ran past its end here with no word from App Remote: me/player is asked now, not at the
    /// next watch (up to [watchSec] later).
    nonisolated static func overran(timeSec: Double, durationSec: Double) -> Bool {
        durationSec > 0 && timeSec > durationSec + endOverrunSec
    }

    nonisolated static let endOverrunSec: Double = 2
    nonisolated static let restartedBelowMs = 3000

    /// The time shown: never past the song's end (the clock runs on between states; at an end App
    /// Remote didn't report, it read -0:00 and kept going).
    nonisolated static func shownTime(_ t: Double, durationSec: Double) -> Double {
        durationSec > 0 ? min(t, durationSec) : t
    }

    /// A watch answer counts only for the source that asked it, in the session it asked in.
    nonisolated static func watchStillValid(asked: AnyObject, current: AnyObject?, askedGen: Int, generation: Int) -> Bool {
        current === asked && askedGen == generation
    }

    private func startPolling() {
        guard pollTimer == nil, !silent, UIApplication.shared.applicationState != .background else { return }
        pollTimer = Timer.scheduledTimer(withTimeInterval: Self.pollSec, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func foreground() {
        startWatching()
        appMayBeBack()
        guard isDriving, !silent else { return }
        if isHere {
            // Back to App Remote's own state stream (Spotify kept playing while Yoin was away).
            connectHereOrPoll()
        } else {
            startPolling()
            refresh()
        }
    }

    /// The watch and the poll start once Yoin is active again. [foreground] runs at willEnterForeground,
    /// while applicationState still reads .background, so both timers' guards turned it down: a
    /// tester's build 28 log (2026-11-15) read me/player once on every return and never again, and a
    /// song that ended on the speaker left Yoin at -0:00 until the next return from Spotify. This
    /// iPhone with App Remote out of reach is polled the same way (connectHereOrPoll's own start ran
    /// into the same guard); a connection that comes up stops it.
    private func becameActive() {
        startWatching()
        guard isDriving, !silent else { return }
        guard !isHere || !remoteCarries else { return }
        startPolling()
        // This iPhone without App Remote: read now, not 4 s (or a 1.5 s connect) from now (audit 11-15 #27).
        if isHere { refresh(here: true) }
    }

    /// Back from the App Store with Spotify installed: its card stops asking for it, and a song held on
    /// "Install Spotify" goes back to a plain pause (Play starts it; Yoin doesn't open Spotify by itself).
    func appMayBeBack() {
        guard issues.appMissing, spotifyInstalled() else { return }
        issues.appFound()
        guard refused == .appMissing else { return }
        refused = nil
        failedHere = false
        quietPause = false
        events?.outputDidRecover(playing: false)
    }

    /// One read of GET /me/player (after [delay]), applied as the Spotify app's state is.
    /// [here]: this iPhone's own Spotify, read while App Remote can't be reached (it reports otherwise).
    private func refresh(after delay: Double = 0, here: Bool = false) {
        guard isDriving, !silent, control != nil, !isHere || here || !remoteCarries else { return }
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled, let self, let control = self.control else { return }
            let now: SpotifySource.PlayerNow?
            do { now = try await control.playerNow() } catch {
                // A failed read keeps what was last seen (only 204 means nothing plays).
                PlayTrace.log("SP read failed \(SpotifySource.message(for: error))")
                return
            }
            guard !Task.isCancelled else { return }
            PlayTrace.log("SP read \(Self.describe(now))")
            self.apply(now)
        }
    }

    nonisolated static let pollSec: Double = 4

    func stop() {
        PlayTrace.log("SP stop wantsPlay=\(wantsPlay)")
        generation += 1
        stopped = true
        rehandTask?.cancel()
        // Only a song Yoin has playing is paused: a stop after a start that never went out (Spotify
        // not installed, the wake failed) sent a pause to whatever device Spotify had active.
        if !silent, wantsPlay, playing, !starting, !awaitingWake, let control {
            let device = target
            Task { try? await control.pausePlayback(deviceId: device) }
        }
        stopPolling()
        refreshTask?.cancel()
        wakeWait = nil
        awaitingWake = false
        awaitingFirstState = false
        silentStrikes = 0
        pendingEcho = nil
        pendingResume = nil
        adopted = false
        externalURI = nil
        quietPause = false
        refused = nil
        resetStart()
        SpotifyPlayingDevice.shared.name = nil
        wantsPlay = false
        starting = false
        current = nil
        next = nil
        handedNext = nil
        handedList = []
        contextActive = false
        staleHandoff = false
        playing = false
        timer?.invalidate()
        timer = nil
    }

    // MARK: The Spotify app's state

    /// The Spotify app's state as App Remote and the Web API both report it.
    struct State {
        var uri: String
        var isPaused: Bool
        var positionMs: Int
        var durationMs: Int
        var shuffling: Bool
        var repeatRaw: UInt
        var title: String? = nil
        var artist: String? = nil
        var album: String? = nil
        var coverURL: URL? = nil
        /// The song's album id, when Spotify says (a hand-off of a song Spotify plays on its own names it).
        var albumId: String? = nil
    }

    /// `spotify:album:<id>` → `<id>`; nil for anything else (an episode's show, a local file's empty album).
    nonisolated static func albumId(fromURI uri: String?) -> String? {
        guard let uri, uri.hasPrefix("spotify:album:") else { return nil }
        let id = String(uri.dropFirst("spotify:album:".count))
        return id.isEmpty ? nil : id
    }

    private func playerState(_ s: RemotePlayerState) {
        let uri = ownURI(s.uri)
        lastRemoteStateAt = Date()
        if let p = pendingEcho, Self.answers(p.echo, uri: uri, paused: s.isPaused, positionMs: s.positionMs) { pendingEcho = nil }
        if let kept = pendingResume {
            pendingResume = nil
            if kept.aim.generation == generation,
               Self.resumesPending(shownURI: shownURI, aimedURI: kept.aim.uri, stateURI: uri, paused: s.isPaused,
                                   ageSec: Date().timeIntervalSince(kept.at)) {
                PlayTrace.log("SP the kept Play goes out (Spotify paused on the song)")
                // Its state (playing) is then taken as Spotify playing again: the failure clears, Yoin plays.
                quietPause = false
                remote.resume()
            }
        }
        // A wake's handback waits on this first real state (see connected).
        if wakeWait != nil { wakeState(uri: uri, paused: s.isPaused, final: false) }
        // App Remote speaks for this iPhone only.
        guard SpotifyPlayingDevice.shared.name == nil else { return }
        // It carries this iPhone's state: the fallback reads stop (audit 11-15 #6).
        connectionSpoke = true
        silentStrikes = 0
        if awaitingFirstState || pollTimer != nil, isHere {
            awaitingFirstState = false
            stopPolling()
        }
        apply(State(uri: uri, isPaused: s.isPaused, positionMs: s.positionMs, durationMs: s.durationMs,
                    shuffling: s.shuffling, repeatRaw: s.repeatRaw, title: s.title, artist: s.artist,
                    album: s.album, coverURL: Self.coverURL(imageIdentifier: s.imageIdentifier),
                    albumId: Self.albumId(fromURI: s.albumURI)))
    }

    /// A Web API read: nothing active is Spotify stopped where it was.
    private func apply(_ now: SpotifySource.PlayerNow?) {
        guard !starting, !awaitingWake, isDriving else { return }
        if awaitingPlay, !isHere, let current {
            switch Self.startCheck(now, uri: current.trackURI, fromSec: startedAtSec,
                                   elapsed: Date().timeIntervalSince(startedAt)) {
            case .dropped: return dropped()
            case .waiting: return
            case nil: awaitingPlay = false
            }
        }
        // A pause or seek made while the start was landing just went out: this read still shows the
        // song playing from before it (read as "played in Spotify", it flipped Yoin back to PAUSE's
        // other side until the next read). The command's own read follows.
        if landing, land(now?.trackURI == current?.trackURI || now?.trackURI == handedNext?.trackURI) { return }
        if let now, let copy = now.trackURI, let current, now.linkedFromURI == current.trackURI { relinkedURI = copy }
        guard let now, let uri = now.trackURI.map(ownURI) else {
            guard playing || wantsPlay else { return }
            lastTime = currentTimeSec
            lastTick = Date()
            playing = false
            if wantsPlay {
                wantsPlay = false
                events?.outputPlayingDidChangeExternally(false)
            }
            return
        }
        // Playback moved to another device in Spotify: Yoin follows it there.
        if let id = now.deviceId, id != target {
            let here = device.matches(now)
            // This iPhone stays unnamed and untargeted (App Remote's, as followTransfer and watch keep it):
            // its id as the target turned later commands into Web API starts aimed here (audit 11-15 #3).
            let newTarget: String? = here && !device.isMac ? nil : id
            if newTarget != target {
                target = newTarget
                SpotifyPlayingDevice.shared.name = here ? nil : now.deviceName
                if !isHere {
                    startPolling()
                } else {
                    // Back on this iPhone (moved in Spotify's own device picker): App Remote reports it
                    // again; the speaker's poll left on blocked the watch and its checks (audit 11-15 #28).
                    stopPolling()
                    connectHereOrPoll()
                }
            }
        }
        let repeatRaw: UInt = switch now.repeatState {
        case "track": SPTAppRemotePlaybackOptionsRepeatMode.track.rawValue
        case "context": SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue
        default: SPTAppRemotePlaybackOptionsRepeatMode.off.rawValue
        }
        apply(State(uri: uri, isPaused: !now.isPlaying, positionMs: now.progressMs, durationMs: now.durationMs,
                    shuffling: now.shuffle, repeatRaw: repeatRaw, title: now.trackName, artist: now.artistName,
                    album: now.albumName, coverURL: now.coverURL, albumId: now.albumId))
    }

    /// App Remote names a cover `spotify:image:<id>`; Spotify serves it at i.scdn.co/image/<id>.
    nonisolated static func coverURL(imageIdentifier: String) -> URL? {
        let prefix = "spotify:image:"
        guard imageIdentifier.hasPrefix(prefix) else { return nil }
        return URL(string: "https://i.scdn.co/image/" + imageIdentifier.dropFirst(prefix.count))
    }

    /// A song Spotify plays that isn't the queue's, as the engine shows it.
    nonisolated static func externalTrack(_ s: State) -> PlayableTrack? {
        guard let id = s.uri.split(separator: ":").last.map(String.init), !id.isEmpty,
              s.uri.hasPrefix("spotify:track:") || s.uri.hasPrefix("spotify:episode:") else { return nil }
        return PlayableTrack(id: id, provider: SpotifyMapping.provider, title: s.title ?? "Spotify",
                             artist: s.artist ?? "", album: s.album ?? "",
                             albumId: s.uri.hasPrefix("spotify:track:") ? s.albumId : nil,
                             durationSec: Double(s.durationMs) / 1000, streamURL: streamURL(trackId: id),
                             coverURL: s.coverURL)
    }

    private func apply(_ s: State) {
        PlayTrace.log("SP state \(s.uri) paused=\(s.isPaused) pos=\(s.positionMs) starting=\(starting) wantsPlay=\(wantsPlay)")
        let uri = s.uri
        if s.durationMs > 0 { stateDurationSec = Double(s.durationMs) / 1000 }
        // Another song, or the same one from its start again (repeat one): its end is checked anew.
        if uri != endCheckedURI || s.positionMs < Self.restartedBelowMs { endCheckedURI = nil }
        let before = reportedTime
        lastTime = Double(s.positionMs) / 1000
        reportedTime = lastTime
        lastTick = Date()
        playing = !s.isPaused
        guard !starting else { return }
        if Self.readyToEnqueue(waiting: enqueueWhenPlaying, stateURI: uri, current: current?.trackURI,
                               paused: s.isPaused, positionMs: s.positionMs) {
            enqueueNext()
        }
        if let current, uri == current.trackURI, Self.recovers(failed: failedHere, paused: s.isPaused) {
            // A failure Yoin reported while Spotify plays the song after all (51's build 11): the song is
            // playing, so the failure and its message go, and the clock follows Spotify's.
            if failedHere {
                PlayTrace.log("SP recovered: Spotify plays the queue's song")
                failedHere = false
                refused = nil
                quietPause = false
                starting = false
                wantsPlay = true
                events?.outputDidRecover(playing: true)
            }
        }
        if let current, uri == current.trackURI {
            // Spotify's own song keeps Spotify's modes; only the queue's are the engine's.
            takeOverModes(shuffling: s.shuffling, repeatRaw: s.repeatRaw)
            if externalURI != nil {
                // Back on the queue's song.
                externalURI = nil
                wantsPlay = playing
                events?.outputExternalTrackDidChange(nil, positionSec: lastTime, playing: playing)
            }
            sawCurrent = true
            let d = Double(s.durationMs) / 1000
            if d > 0, d != duration {
                duration = d
                events?.outputDurationDidChange(d)
            }
            // Repeat one: Spotify moved from the first copy of the song to the second.
            if !s.isPaused, d > 0, handedNext == current, lastTime < 1, before >= d - Self.endSlackSec - 1.5 {
                handedNext = nil
                next = nil
                events?.outputDidAdvanceToPreloaded()
                events?.outputTimeDidChange(lastTime)
                return
            }
            events?.outputTimeDidChange(lastTime)
            // The list ran out: Spotify stops at the song's end, or back at its start right after it.
            let atEnd = lastTime >= d - Self.endSlackSec || (lastTime < 1 && before >= d - Self.endSlackSec - 1.5)
            if s.isPaused, wantsPlay, d > 0, atEnd {
                sawCurrent = false
                // Spotify never got the next song: the engine carries on with it; else the list ran out.
                if nextUnhanded {
                    nextUnhanded = false
                    return events?.outputCurrentDidFinish() ?? ()
                }
                return events?.outputDidReachEnd() ?? ()
            }
            // Paused or played from the Spotify app or its lock screen card (not a state from before a pause
            // or play still waiting on its answer).
            if playing != wantsPlay, !echoOutstanding(statePaused: s.isPaused) {
                wantsPlay = playing
                events?.outputPlayingDidChangeExternally(playing)
            }
            return
        }
        if externalURI == nil, sawCurrent, current != nil {
            if let handed = handedNext, uri == handed.trackURI, handed == next {
                // Spotify carried on to the song the engine has next.
                current = handed
                self.next = nil
                if !handedList.isEmpty { handedList.removeFirst() }
                handedNext = contextActive ? nil : handedList.first
                return events?.outputDidAdvanceToPreloaded() ?? ()
            }
            if let events {
                let snapshot = events.outputQueueSnapshot()
                let upcoming = events.outputUpcoming(limit: Self.handOffLimit).compactMap(Item.init)
                if let target = Self.jumpTarget(uri: uri, handed: handedList, upcoming: upcoming,
                                                context: contextActive ? contextPlan : nil, snapshot: snapshot),
                   events.outputDidJump(to: Self.streamURL(trackId: target.trackId)) {
                    PlayTrace.log("SP caught up with Spotify on \(target.trackId)")
                    current = target
                    if let i = handedList.firstIndex(of: target) { handedList.removeFirst(i + 1) }
                    handedNext = contextActive ? (snapshot.shuffle ? nil : next) : handedList.first
                    sawCurrent = true
                    events.outputTimeDidChange(lastTime)
                    return
                }
            }
            if duration > 0, before >= duration - Self.carriedOnWindowSec {
                // The song ran out and Spotify carried on by itself to something else (the list was
                // stale, or its autoplay): the engine starts its next, back in Yoin's queue.
                sawCurrent = false
                return events?.outputCurrentDidFinish() ?? ()
            }
        }
        // Picked in Spotify, here or on the device Yoin drives (or a start that never took): the
        // listener's choice. Yoin shows that song and controls it there; the queue stays parked and
        // Spotify is never taken back to it — Yoin never cuts into playback.
        guard let track = Self.externalTrack(s) else { return }
        sawCurrent = false
        // A quiet pause was for the song before (audit 11-15 #38).
        if externalURI != uri { quietPause = false }
        externalURI = uri
        wantsPlay = playing
        events?.outputExternalTrackDidChange(track, positionSec: lastTime, playing: playing)
    }

    /// Next / previous on Spotify's own song: Spotify skips, here or on the device Yoin drives.
    func skipExternal(forward: Bool) {
        guard !silent else { return }
        if isHere {
            let viaRemote = remote.isConnected && !remote.broken
            let web: WebCommand = { try await $0.skipPlayback(forward: forward, deviceId: $1, while: $2) }
            let from = shownURI
            command(aim(song: true), web: web) { $0.skip(forward: forward) }
            if viaRemote {
                awaitEcho(forward ? "next" : "previous", .moved(from: from), aim: aim(song: true), web: web) {
                    $0.skip(forward: forward)
                }
            }
            return
        }
        transport(report: true, aim(song: true)) { try await $0.skipPlayback(forward: forward, deviceId: $1, while: $2) }
    }

    /// How close to its end the song was last seen for a switch to read as Spotify carrying on
    /// (the Web API is read every [pollSec]).
    nonisolated static let carriedOnWindowSec: Double = 8

    nonisolated static let endSlackSec: Double = 1.5

    /// Shuffle or repeat turned on in the Spotify app (Yoin keeps both off there): the engine takes the
    /// mode over and Spotify's goes back off. Off can't be told apart from Yoin's own setting.
    private func takeOverModes(shuffling: Bool, repeatRaw: UInt) {
        if contextActive {
            // A context runs on Yoin's modes: one changed in the Spotify app is the listener's, taken over
            // as it is (Spotify's isn't turned back off).
            guard Date() >= modesQuietUntil, let snapshot = events?.outputQueueSnapshot() else { return }
            let mode = Self.repeatMode(repeatRaw)
            guard shuffling != snapshot.shuffle || mode != snapshot.repeatMode else { return }
            modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
            events?.outputPlayModesDidChangeExternally(shuffle: shuffling, repeatMode: mode)
            return
        }
        guard Date() >= modesQuietUntil,
              let modes = Self.externalModes(shuffling: shuffling, repeatRaw: repeatRaw)
        else { return }
        modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
        let device = target
        Task { await control?.playModesOff(deviceId: device) }
        events?.outputPlayModesDidChangeExternally(shuffle: modes.shuffle, repeatMode: modes.repeatMode)
    }

    nonisolated static func repeatMode(_ raw: UInt) -> RepeatMode {
        switch raw {
        case SPTAppRemotePlaybackOptionsRepeatMode.track.rawValue: .one
        case SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue: .all
        default: .off
        }
    }

    /// What the Spotify app's modes ask of the engine; nil when both are off (Yoin's own state there).
    nonisolated static func externalModes(
        shuffling: Bool, repeatRaw: UInt
    ) -> (shuffle: Bool?, repeatMode: RepeatMode?)? {
        let repeatMode: RepeatMode? = switch repeatRaw {
        case SPTAppRemotePlaybackOptionsRepeatMode.track.rawValue: .one
        case SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue: .all
        default: nil
        }
        guard shuffling || repeatMode != nil else { return nil }
        return (shuffling ? true : nil, repeatMode)
    }

    private func startTicking() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.playing else { return }
                let t = self.currentTimeSec
                self.events?.outputTimeDidChange(Self.shownTime(t, durationSec: self.stateDurationSec))
                // Connected or not (audit 11-15 #7: a dropped connection never asked); a poll already reads.
                if self.isHere, self.endCheckedURI == nil, self.pollTimer == nil,
                   Self.overran(timeSec: t, durationSec: self.stateDurationSec) {
                    self.endCheckedURI = self.shownURI
                    PlayTrace.log("SP ran past the song's end with no state: asking me/player")
                    self.watch(overrun: true)
                }
            }
        }
    }
}

/// A Spotify start with nowhere to play, said so the listener knows what to do.
struct SpotifyPlaybackError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// The Connect device Yoin is a remote for (nil: this iPhone), shown by Now Playing.
@MainActor
@Observable
final class SpotifyPlayingDevice {
    static let shared = SpotifyPlayingDevice()
    var name: String?
    /// Posted by Devices after a transfer (userInfo: id, name).
    nonisolated static let transferred = Notification.Name("SpotifyPlayingDevice.transferred")
}

/// The album or playlist a Spotify queue was started from, as Spotify's context (Android SpotifyStartPlan's
/// native context): its URI and the queue's songs with their index in it.
struct SpotifyContextPlan: Equatable, Sendable {
    /// spotify:album:… or spotify:playlist:…
    var uri: String
    /// The queue's songs in the context's order (Spotify ids).
    var trackIds: [String]
    /// Each of [trackIds]' index in the context (a playlist's items that aren't songs count).
    var indices: [Int]

    /// The queue (as the engine plays it) is still the context: the same songs, none queued by the
    /// listener, and (unshuffled) in the context's order. Shuffled, Spotify picks the order.
    func follows(_ snapshot: OutputQueueSnapshot) -> Bool {
        guard !snapshot.userQueued else { return false }
        let ids = snapshot.urls.map { $0.scheme == SpotifyAudioOutput.scheme ? $0.lastPathComponent : "" }
        return snapshot.shuffle ? ids.sorted() == trackIds.sorted() : ids == trackIds
    }

    /// Where [item] sits in the context, when the queue still follows it.
    func contextIndex(of item: SpotifyAudioOutput.Item, following snapshot: OutputQueueSnapshot) -> Int? {
        guard follows(snapshot), let i = trackIds.firstIndex(of: item.trackId), indices.indices.contains(i) else { return nil }
        return indices[i]
    }
}
