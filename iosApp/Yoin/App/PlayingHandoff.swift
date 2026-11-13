import Foundation
import Observation
import YoinShared

/// Handoff of what's playing (`com.gpo.yoin.playing`), the native Mac's activity (YoinMac MacHandoff) in
/// the same shape, so an iPhone, an iPad and a Mac continue each other. The Apple counterpart of Android's
/// Continue On (docs/continue-on.md in the Android repo): the system's own Handoff, no card (51, 2026-11-13).
///
/// Nothing secret goes in it (no server address, no user name, no token): the account's service, a hash
/// that names the account the same on every device, the song, its album or playlist, and where in it.
struct HandoffPlaying: Equatable, Sendable {
    static let activityType = "com.gpo.yoin.playing"
    /// How long a playing hand-off is caught up by the clock (Android: ten minutes); past that the song
    /// starts where it was when it was sent.
    static let catchUpLimitSec: Double = 600

    var provider: String
    var songId: String
    var title: String
    var artist: String
    var album: String
    var albumId: String?
    /// The playlist the queue was started from (iOS only; the Mac sends the album alone).
    var playlistId: String?
    /// `HandoffAccounts.key`: nil from a Mac, or for Apple Music.
    var account: String?
    var positionSec: Int
    var playing: Bool
    /// When it was written (seconds since 1970); nil from a Mac.
    var sentAt: Double?

    var userInfo: [String: Any] {
        var info: [String: Any] = [
            "provider": provider, "songId": songId, "title": title, "artist": artist, "album": album,
            "positionSec": positionSec, "playing": playing,
        ]
        if let albumId { info["albumId"] = albumId }
        if let playlistId { info["playlistId"] = playlistId }
        if let account { info["account"] = account }
        if let sentAt { info["sentAt"] = sentAt }
        return info
    }

    init(provider: String, songId: String, title: String, artist: String, album: String, albumId: String? = nil,
         playlistId: String? = nil, account: String? = nil, positionSec: Int, playing: Bool, sentAt: Double? = nil) {
        self.provider = provider
        self.songId = songId
        self.title = title
        self.artist = artist
        self.album = album
        self.albumId = albumId
        self.playlistId = playlistId
        self.account = account
        self.positionSec = positionSec
        self.playing = playing
        self.sentAt = sentAt
    }

    /// Read back; nil without a service or a song. Numbers may arrive as any NSNumber.
    init?(userInfo: [AnyHashable: Any]?) {
        guard let u = userInfo else { return nil }
        func text(_ k: String) -> String? { (u[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
        guard let provider = text("provider"), let songId = text("songId") else { return nil }
        self.provider = provider
        self.songId = songId
        title = text("title") ?? ""
        artist = text("artist") ?? ""
        album = text("album") ?? ""
        albumId = text("albumId")
        playlistId = text("playlistId")
        account = text("account")
        positionSec = max(0, (u["positionSec"] as? NSNumber)?.intValue ?? 0)
        playing = (u["playing"] as? NSNumber)?.boolValue ?? false
        sentAt = (u["sentAt"] as? NSNumber)?.doubleValue
    }

    /// Where to start here: the sent spot, plus the time since it was sent while it was playing there
    /// (at most [catchUpLimitSec]); short of the song's end when its length is known.
    func startSec(now: Double, durationSec: Double?) -> Double {
        var at = Double(positionSec)
        if playing, let sentAt {
            let elapsed = now - sentAt
            if elapsed > 0, elapsed <= Self.catchUpLimitSec { at += elapsed }
        }
        if let d = durationSec, d > 1 { at = min(at, d - 1) }
        return max(0, at)
    }
}

/// Which account here a hand-off belongs to (Android ContinueOn.matchProfile).
@MainActor
enum HandoffAccounts {
    /// The account's id as every device works it out (Android AccountKeys' Subsonic rule, `s-` + the first
    /// 24 hex of sha256 of its fingerprint): the server and user for Subsonic, the user for Spotify. A hash,
    /// so the payload names neither. Nil for Apple Music (one per device) or what can't be read.
    static func key(of p: Profile, spotifyUserId: String? = nil) -> String? {
        let f = AccountFingerprints.shared
        switch p.service {
        case .subsonic:
            guard let url = p.baseUrl, let user = p.username, let fp = Profile.identity(baseUrl: url, username: user)
            else { return nil }
            return f.deterministicId(fingerprint: fp)
        case .spotify:
            guard let user = p.spotifyUserId ?? spotifyUserId, !user.isEmpty else { return nil }
            return f.deterministicId(fingerprint: f.spotify(userId: user))
        case .appleMusic:
            return nil
        }
    }

    /// The account of [provider] the key names; with no key on one side, the only account of that service
    /// that could be it, else the one in use (Android's rule). Nil: this device lacks it.
    static func match(provider: String, account: String?, profiles: [Profile], activeId: String?,
                      key: (Profile) -> String?) -> Profile? {
        let candidates = profiles.filter { PlaylistBackups.provider(of: $0) == provider }
        let keys = Dictionary(uniqueKeysWithValues: candidates.map { ($0.id, key($0)) })
        if let account, let hit = candidates.first(where: { keys[$0.id] == account }) { return hit }
        let open = account != nil ? candidates.filter { (keys[$0.id] ?? nil) == nil } : candidates
        if open.count == 1 { return open[0] }
        return open.first { $0.id == activeId }
    }
}

/// Advertises the loaded song as the current Handoff activity and pauses here when another device
/// takes it (Android's pause channel; Apple tells the sender itself). Spotify is left playing: the other
/// device's start moves Spotify Connect there, and a pause from here could land on it.
@MainActor
final class PlayingHandoff: NSObject, NSUserActivityDelegate {
    static let shared = PlayingHandoff()

    private weak var model: AppModel?
    private var activity: NSUserActivity?
    private var refresh: Timer?

    func start(_ model: AppModel) {
        guard self.model == nil else { return }
        self.model = model
        sync()
        observe()
        // The spot moves while it plays: the system asks for it again (userActivityWillSave) when it next saves.
        refresh = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.activity?.needsSave = true }
        }
        #if DEBUG
        // `-handoffDemo "subsonic|s-field-03|al-field|95|0"` (service|song|album|seconds|playing): 6 s after
        // launch, a hand-off with that payload arrives, as from another device (a simulator can't get one).
        if let spec = UserDefaults.standard.string(forKey: "handoffDemo") {
            let p = spec.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            if p.count >= 2 {
                let h = HandoffPlaying(provider: p[0], songId: p[1], title: "", artist: "", album: "",
                                       albumId: p.count > 2 && !p[2].isEmpty ? p[2] : nil, account: nil,
                                       positionSec: p.count > 3 ? Int(p[3]) ?? 0 : 0,
                                       playing: p.count > 4 && p[4] == "1")
                Task { @MainActor in
                    try? await Task.sleep(for: .seconds(6))
                    await model.continueHandoff(h)
                }
            }
        }
        #endif
    }

    /// What changes the activity: the song, play / pause, the account, where the queue came from. Not the
    /// spot itself (it moves every tick); that is read when the system saves.
    private func observe() {
        withObservationTracking {
            _ = model?.player.currentTrack
            _ = model?.player.isPlaying
            _ = model?.profiles.activeId
            _ = model?.playingFrom
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.sync()
                self?.observe()
            }
        }
    }

    private func sync() {
        guard let model, let t = model.player.currentTrack else {
            activity?.invalidate()
            activity = nil
            return
        }
        let a = activity ?? {
            let a = NSUserActivity(activityType: HandoffPlaying.activityType)
            a.isEligibleForHandoff = true
            a.isEligibleForSearch = false
            a.isEligibleForPublicIndexing = false
            a.delegate = self
            activity = a
            return a
        }()
        a.title = "\(t.title) — \(t.artist)"
        fill(a)
        a.needsSave = true
        a.becomeCurrent()
    }

    /// The activity this object made (the only one it is the delegate of), filled as of now.
    private func fillCurrent() {
        if let activity { fill(activity) }
    }

    /// The payload as of now.
    private func fill(_ a: NSUserActivity) {
        guard let model, let h = Self.payload(model) else { return }
        a.userInfo = h.userInfo
    }

    static func payload(_ model: AppModel, now: Date = Date()) -> HandoffPlaying? {
        guard let t = model.player.currentTrack else { return nil }
        let playlistId: String? = if case .playlist(let id, _) = model.playingFrom { id } else { nil }
        let account = model.profiles.active.flatMap {
            HandoffAccounts.key(of: $0, spotifyUserId: model.profiles.store.spotify($0.id)?.userId)
        }
        return HandoffPlaying(provider: t.provider, songId: t.id, title: t.title, artist: t.artist, album: t.album,
                              albumId: t.albumId, playlistId: playlistId, account: account,
                              positionSec: Int(model.player.progress.positionSec), playing: model.player.isPlaying,
                              sentAt: now.timeIntervalSince1970)
    }

    nonisolated func userActivityWillSave(_ userActivity: NSUserActivity) {
        // On the main thread it is filled before the save; elsewhere the last fill stands (its sentAt lets the
        // other device catch a playing song up) and this one is ready for the next.
        if Thread.isMainThread {
            MainActor.assumeIsolated { self.fillCurrent() }
        } else {
            Task { @MainActor in self.fillCurrent() }
        }
    }

    nonisolated func userActivityWasContinued(_ userActivity: NSUserActivity) {
        Task { @MainActor in
            guard let model = self.model, let t = model.player.currentTrack,
                  t.provider != SpotifyMapping.provider else { return }
            model.player.pause()
        }
    }
}

extension AppModel {
    /// A hand-off from another of the listener's Apple devices: the same account here (switched to when it
    /// isn't the one in use), the song from its album or playlist at the other device's spot, paused if it
    /// was paused there, and Now Playing open. Without that account here, a toast says so.
    func continueHandoff(_ h: HandoffPlaying) async {
        let profiles = profiles
        let target = HandoffAccounts.match(provider: h.provider, account: h.account, profiles: profiles.profiles,
                                           activeId: profiles.activeId) { p in
            HandoffAccounts.key(of: p, spotifyUserId: profiles.store.spotify(p.id)?.userId)
        }
        guard let target else {
            PlaylistActions.shared.show(
                String(localized: "This account isn't on this device yet. Add it in Settings to continue here."),
                action: .init(label: String(localized: "Settings")) { [weak self] in self?.openSettings() })
            return
        }
        if target.id != profiles.activeId {
            await profiles.switchTo(target.id)
            guard profiles.activeId == target.id else {
                // Said here, not left on the account's card for Settings to open on later.
                profiles.clearFailure()
                PlaylistActions.shared.show(String(localized: "Couldn't switch account"))
                return
            }
        }
        // The library follows the account a moment later (Spotify's source is built on observation).
        for _ in 0..<40 where !(store.isReal && store.provider == h.provider) {
            try? await Task.sleep(for: .milliseconds(100))
        }
        startGeneration += 1
        let gen = startGeneration
        guard let (entries, index, from) = await handoffQueue(h), gen == startGeneration else {
            if gen == startGeneration {
                PlaylistActions.shared.show(String(localized: "Couldn't load this song here."))
            }
            return
        }
        let duration = Double(entries[index].track.durationSec)
        play(entries, startAt: index, from: from,
             startSec: h.startSec(now: Date().timeIntervalSince1970, durationSec: duration), paused: !h.playing)
        if isPad { openNowPlayingOnPad() } else { setNowPlaying(expanded: true) }
    }

    /// The hand-off's song in its playlist (when it came from one and is in it), else in its album.
    private func handoffQueue(_ h: HandoffPlaying) async -> ([PlaylistEntry], Int, PlayingFrom?)? {
        func at(_ entries: [PlaylistEntry]) -> Int? { entries.firstIndex { $0.track.id.rawId == h.songId } }
        if let id = h.playlistId {
            await store.loadPlaylist(id)
            if let p = store.playlist(id), let i = at(p.entries) {
                return (p.entries, i, .playlist(id: id, title: p.title))
            }
        }
        var albumId = h.albumId
        if albumId == nil, let client = store.client {
            // The Mac's payload always names the album; a server song without one asks the server.
            albumId = (try? await client.getSong(id: h.songId))?.albumId
        }
        guard let albumId else { return nil }
        await store.loadAlbum(albumId)
        guard let a = store.album(albumId) else { return nil }
        let entries = a.tracks.map { PlaylistEntry(album: a, track: $0) }
        guard let i = at(entries) else { return nil }
        return (entries, i, .album(a))
    }
}
