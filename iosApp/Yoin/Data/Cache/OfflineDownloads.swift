import CryptoKit
import Foundation
import Observation
import os
import SwiftUI
import YoinShared

/// Offline downloads (Android PR #131, `data/offline`): a song, an album or a
/// playlist kept on the device until the listener removes it, played with no
/// network. Subsonic only: Apple Music doesn't let an app store its songs.
///
/// What Android keeps in OfflineModel, on the same rules:
/// - A song is stored under the key of its plain stream url
///   (SubsonicCacheKey, no transcode, no credential): found whatever the
///   streaming quality is now, never shared between two accounts.
/// - The index holds no token and no signed url: a queued song is signed only
///   when its download opens, by the signed-in account's client.
/// - Each song lists every album, playlist or lone-song download that holds
///   it; it goes when the last of them is removed.
/// - Settings › Downloads: quality 320 by default, nothing over mobile data
///   until the listener allows it (Spotify's defaults).
/// - One account's downloads are invisible to another, and wait while it is
///   signed in.
@MainActor
@Observable
final class OfflineDownloads {
    static let shared = OfflineDownloads()

    enum Quality: String, CaseIterable, Codable, Sendable {
        case original, kbps320 = "320", kbps192 = "192", kbps128 = "128"

        var maxBitRateKbps: Int? {
            switch self {
            case .original: nil
            case .kbps320: 320
            case .kbps192: 192
            case .kbps128: 128
            }
        }

        var label: String {
            switch self {
            case .original: String(localized: "Original")
            case .kbps320: "320 kbps"
            case .kbps192: "192 kbps"
            case .kbps128: "128 kbps"
            }
        }

        static let `default` = Quality.kbps320
    }

    struct Collection: Codable, Hashable, Sendable {
        enum Kind: String, Codable, Sendable { case album, playlist, song }
        var kind: Kind
        /// The album's or playlist's raw id; the song's own for `.song`.
        var id: String
        var title: String
        var subtitle: String?
        /// The server's cover id (rebuilt into a url by the signed-in client).
        var coverId: String?

        /// One entry per album, playlist or lone song (Android `OfflineCollection.key`).
        var key: String { "\(kind.rawValue):\(id)" }
    }

    struct Song: Codable, Hashable, Sendable {
        enum State: String, Codable, Sendable { case queued, downloading, done, failed }
        /// SubsonicCacheKey of the plain stream url.
        var key: String
        /// The account it was downloaded under (AccountScope "baseUrl|user").
        var scope: String
        var songId: String
        var title: String
        var artist: String
        var album: String
        var albumId: String?
        var durationSec: Int
        var quality: Quality
        var collections: [Collection]
        var state: State = .queued
        var bytes: Int64 = 0
        /// The file's name in the downloads folder, once whole.
        var file: String?
        /// Addition order, for listing newest first.
        var order: Int = 0
    }

    /// What a page's Download row shows (Android `OfflineStatus`).
    enum Status: Equatable {
        case none
        case downloading(done: Int, total: Int)
        case downloaded
        case failed
    }

    /// An album, playlist or lone song as the listener downloaded it.
    struct Entry: Identifiable, Equatable {
        var collection: Collection
        var total: Int
        var done: Int
        var failed: Int
        var bytes: Int64
        var order: Int
        var id: String { collection.key }

        var status: Status {
            if total > 0 && done == total { return .downloaded }
            if failed > 0 && done + failed == total { return .failed }
            return .downloading(done: done, total: total)
        }
    }

    // MARK: Settings

    var quality: Quality {
        didSet { defaults.set(quality.rawValue, forKey: Self.qualityKey) }
    }
    /// Download using mobile data (off: downloads wait for Wi‑Fi).
    var overMobileData: Bool {
        didSet {
            defaults.set(overMobileData, forKey: Self.mobileKey)
            restartSession()
        }
    }

    // MARK: State

    private(set) var songs: [Song] = []
    /// The account whose downloads are shown and run (nil signed out).
    @ObservationIgnored var scope: () -> String? = { AccountScope.shared.subsonic }
    /// Signs a song's stream url for the signed-in account; nil when there is no client.
    @ObservationIgnored var sign: (_ songId: String, _ quality: Quality) -> URL? = { _, _ in nil }
    /// Fetches a signed url to a temporary file (tests answer without a network); nil: the URLSession.
    @ObservationIgnored var transfer: (@MainActor (URL) async throws -> (URL, URLResponse))?

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let directory: URL
    /// The album and playlist pages the downloads keep in the detail cache (Codex #10).
    @ObservationIgnored private let pins: DetailCachePins
    @ObservationIgnored private var session: URLSession
    /// The one transfer that owns the pump; its token tells it from one an account change cancelled
    /// (Codex #11: only the current transfer clears this and starts the next).
    @ObservationIgnored private var running: (key: String, token: Int, task: Task<Void, Never>)?
    @ObservationIgnored private var nextToken = 0
    @ObservationIgnored private var nextOrder = 0
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "offline")

    static let qualityKey = "downloadQuality"
    static let mobileKey = "downloadOverMobileData"

    init(defaults: UserDefaults = .standard, directory: URL? = nil, pins: DetailCachePins = .shared) {
        self.defaults = defaults
        self.pins = pins
        quality = Quality(rawValue: defaults.string(forKey: Self.qualityKey) ?? "") ?? .default
        overMobileData = defaults.bool(forKey: Self.mobileKey)
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("YoinOffline", isDirectory: true)
        self.directory = base
        session = Self.makeSession(overMobileData: defaults.bool(forKey: Self.mobileKey))
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var url = base
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        load()
    }

    // MARK: Reading

    /// This account's songs.
    var visible: [Song] {
        guard let s = scope() else { return [] }
        return songs.filter { $0.scope == s }
    }

    /// Newest first.
    var entries: [Entry] {
        var grouped: [String: (Collection, [Song])] = [:]
        for song in visible {
            for c in song.collections {
                grouped[c.key, default: (c, [])].1.append(song)
                grouped[c.key]?.0 = c
            }
        }
        return grouped.values.map { c, members in
            Entry(collection: c, total: members.count,
                  done: members.filter { $0.state == .done }.count,
                  failed: members.filter { $0.state == .failed }.count,
                  bytes: members.reduce(0) { $0 + $1.bytes },
                  order: members.map(\.order).max() ?? 0)
        }
        .sorted { $0.order > $1.order }
    }

    /// Bytes on disk for this account's downloads.
    var bytes: Int64 { visible.reduce(0) { $0 + $1.bytes } }

    func status(_ collectionKey: String) -> Status {
        entries.first { $0.collection.key == collectionKey }?.status ?? .none
    }

    static func albumKey(_ rawId: String) -> String { "album:\(rawId)" }
    static func playlistKey(_ rawId: String) -> String { "playlist:\(rawId)" }
    static func songKey(_ rawId: String) -> String { "song:\(rawId)" }

    /// The downloaded file a signed stream [url] plays, when it is whole on
    /// the device; nil to stream (a part-downloaded song never mixes its
    /// bytes with another encoding).
    func localFile(for url: URL) -> URL? {
        guard SubsonicStreamURLs.isStream(url),
              let key = SubsonicCacheKey.of(SubsonicStreamURLs.url(url, delivery: .original)),
              let song = songs.first(where: { $0.key == key && $0.state == .done }),
              let file = song.file else { return nil }
        let f = directory.appendingPathComponent(file)
        return FileManager.default.fileExists(atPath: f.path) ? f : nil
    }

    /// Whether this account's song [songId] is whole on the device.
    func isDownloaded(songId: String) -> Bool {
        visible.contains { $0.songId == songId && $0.state == .done }
    }

    // MARK: Writing

    /// Keeps [entries] on the device under [collection]. Songs another
    /// download already holds are only added to it.
    func download(_ collection: Collection, entries: [PlaylistEntry], keyOf: (PlaylistEntry) -> String?) {
        guard let s = scope() else { return }
        for e in entries where e.track.id.provider == MediaRef.companion.PROVIDER_SUBSONIC {
            guard let key = keyOf(e) else { continue }
            if let i = songs.firstIndex(where: { $0.key == key }) {
                songs[i].collections.removeAll { $0.key == collection.key }
                songs[i].collections.append(collection)
                if songs[i].state == .failed { songs[i].state = .queued }
                continue
            }
            nextOrder += 1
            songs.append(Song(key: key, scope: s, songId: e.track.id.rawId, title: e.track.title,
                              artist: e.track.artist ?? e.album.artist, album: e.album.title,
                              albumId: e.album.id.rawId.hasPrefix("song:") ? nil : e.album.id.rawId,
                              durationSec: e.track.durationSec, quality: quality,
                              collections: [collection], order: nextOrder))
        }
        save()
        pump()
    }

    /// Drops [collectionKey]: its songs go unless another download holds them.
    func remove(_ collectionKey: String) {
        guard let s = scope() else { return }
        var gone: [Song] = []
        songs = songs.compactMap { song in
            guard song.scope == s, song.collections.contains(where: { $0.key == collectionKey }) else { return song }
            var next = song
            next.collections.removeAll { $0.key == collectionKey }
            if next.collections.isEmpty {
                gone.append(song)
                return nil
            }
            return next
        }
        for song in gone { deleteFile(song) }
        if let r = running, gone.contains(where: { $0.key == r.key }) { r.task.cancel() }
        save()
        pump()
    }

    /// Tries [collectionKey]'s failed songs again; the ones already kept stay (Android OfflineToggle.kt:63).
    func retryFailed(_ collectionKey: String) {
        guard let s = scope() else { return }
        for i in songs.indices where songs[i].scope == s && songs[i].state == .failed
            && songs[i].collections.contains(where: { $0.key == collectionKey }) {
            songs[i].state = .queued
        }
        save()
        pump()
    }

    /// Settings › Downloads › Remove all: this account's downloads.
    func removeAll() {
        guard let s = scope() else { return }
        let gone = songs.filter { $0.scope == s }
        songs.removeAll { $0.scope == s }
        for song in gone { deleteFile(song) }
        if let r = running, gone.contains(where: { $0.key == r.key }) { r.task.cancel() }
        save()
    }

    /// Starts the next waiting song of the signed-in account (one at a time).
    func pump() {
        guard running == nil, let s = scope() else { return }
        while let next = songs.first(where: { $0.scope == s && ($0.state == .queued || $0.state == .downloading) }) {
            guard let url = sign(next.songId, next.quality) else { return }
            let key = next.key
            // Signed by another account's client (a switch between queueing and opening): never stored
            // under this account's key (Android resolveDownload, Codex #3).
            guard Self.key(ofSigned: url) == key else {
                log.error("download skipped: signed for another account")
                set(key) { $0.state = .failed }
                save()
                continue
            }
            set(key) { $0.state = .downloading }
            nextToken += 1
            let token = nextToken
            let task = Task { [weak self] in
                guard let self else { return }
                let result = await self.fetch(url, key: key)
                self.finish(key, token: token, result)
            }
            running = (key, token, task)
            return
        }
    }

    /// The download key of a signed stream url: its plain stream url's cache key (no transcode, no credential).
    static func key(ofSigned url: URL) -> String? {
        SubsonicCacheKey.of(SubsonicStreamURLs.url(url, delivery: .original))
    }

    /// The account changed (sign-in, sign-out, another server): its own downloads run.
    func accountChanged() {
        running?.task.cancel()
        running = nil
        for i in songs.indices where songs[i].state == .downloading { songs[i].state = .queued }
        pump()
    }

    // MARK: Downloading

    private enum Fetched {
        case file(URL, bytes: Int64)
        case failed
        case cancelled
    }

    private func fetch(_ url: URL, key: String) async -> Fetched {
        do {
            let (temp, response): (URL, URLResponse)
            if let transfer {
                (temp, response) = try await transfer(url)
            } else if let done = BackgroundTransfers.shared.takeFinished(key: key) {
                // Finished while Yoin was away (the system ended it mid-download).
                let typeFile = done.appendingPathExtension("type")
                let type = (try? String(contentsOf: typeFile, encoding: .utf8)) ?? "audio/mpeg"
                try? FileManager.default.removeItem(at: typeFile)
                temp = done
                response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": type])!
            } else {
                // A background session: the song keeps downloading while Yoin is suspended (Codex #12).
                (temp, response) = try await BackgroundTransfers.shared.download(url, key: key, overMobileData: overMobileData)
            }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                try? FileManager.default.removeItem(at: temp)
                return .failed
            }
            // A Subsonic error comes back as a 200 with an XML / JSON body.
            let type = http.value(forHTTPHeaderField: "Content-Type")?.lowercased() ?? ""
            if type.contains("xml") || type.contains("json") || type.hasPrefix("text/") {
                try? FileManager.default.removeItem(at: temp)
                return .failed
            }
            let name = Self.fileName(key) + "." + Self.fileExtension(type)
            let dest = directory.appendingPathComponent(name)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: temp, to: dest)
            let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
            return .file(dest, bytes: size ?? 0)
        } catch is CancellationError {
            return .cancelled
        } catch let e as URLError where e.code == .cancelled {
            return .cancelled
        } catch {
            log.error("download failed: \(error.localizedDescription, privacy: .public)")
            return .failed
        }
    }

    private func finish(_ key: String, token: Int, _ result: Fetched) {
        guard running?.token == token else {
            // A transfer an account change let go of: a whole file still counts; nothing else is its to change.
            if case .file(let f, let size) = result {
                if let song = songs.first(where: { $0.key == key }), song.state != .done {
                    set(key) {
                        $0.state = .done
                        $0.file = f.lastPathComponent
                        $0.bytes = size
                    }
                    save()
                } else if !songs.contains(where: { $0.key == key }) {
                    try? FileManager.default.removeItem(at: f)
                }
            }
            return
        }
        running = nil
        switch result {
        case .file(let f, let size):
            if songs.contains(where: { $0.key == key }) {
                set(key) {
                    $0.state = .done
                    $0.file = f.lastPathComponent
                    $0.bytes = size
                }
            } else {
                // Removed while it downloaded.
                try? FileManager.default.removeItem(at: f)
            }
        case .failed:
            set(key) { $0.state = .failed }
        case .cancelled:
            set(key) { $0.state = .queued }
        }
        save()
        pump()
    }

    private func set(_ key: String, _ change: (inout Song) -> Void) {
        guard let i = songs.firstIndex(where: { $0.key == key }) else { return }
        change(&songs[i])
    }

    private func deleteFile(_ song: Song) {
        guard let file = song.file else { return }
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(file))
    }

    private func restartSession() {
        session.invalidateAndCancel()
        session = Self.makeSession(overMobileData: overMobileData)
        accountChanged()
    }

    /// Off mobile data unless allowed: an expensive (cellular, hotspot) or
    /// Low Data path waits for Wi‑Fi instead of failing.
    private static func makeSession(overMobileData: Bool) -> URLSession {
        let c = URLSessionConfiguration.default
        c.allowsExpensiveNetworkAccess = overMobileData
        c.allowsConstrainedNetworkAccess = overMobileData
        c.waitsForConnectivity = true
        c.timeoutIntervalForResource = 60 * 60
        return URLSession(configuration: c)
    }

    nonisolated static func fileName(_ key: String) -> String {
        SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func fileExtension(_ contentType: String) -> String {
        if contentType.contains("mpeg") || contentType.contains("mp3") { return "mp3" }
        if contentType.contains("flac") { return "flac" }
        if contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac") { return "m4a" }
        if contentType.contains("ogg") { return "ogg" }
        if contentType.contains("wav") { return "wav" }
        return "audio"
    }

    // MARK: Index

    private var indexURL: URL { directory.appendingPathComponent("index.json") }

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let saved = try? JSONDecoder().decode([Song].self, from: data) else { return }
        // A download cut off by the app quitting starts again.
        songs = saved.map { var s = $0; if s.state == .downloading { s.state = .queued }; return s }
        nextOrder = songs.map(\.order).max() ?? 0
        pinDetails()
    }

    private func save() {
        pinDetails()
        guard let data = try? JSONEncoder().encode(songs) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }

    /// Every downloaded album's and playlist's page stays in the detail cache while it is downloaded: its
    /// songs play offline only if the page still lists them.
    private func pinDetails() {
        var entries: Set<DetailCachePins.Entry> = []
        for song in songs {
            for c in song.collections {
                switch c.kind {
                case .album: entries.insert(.init(scope: song.scope, kind: .album, id: c.id))
                case .playlist: entries.insert(.init(scope: song.scope, kind: .playlist, id: c.id))
                case .song: break
                }
            }
        }
        pins.set(entries)
    }
}

// MARK: - Download rows (Android OfflineToggle)

extension AppModel {
    /// The download the visible album or playlist page is held under (nil
    /// on other pages, signed out, or off Subsonic). On a wide window the album picked into the detail column
    /// (`padSelection`) is the visible page while nothing is pushed over it.
    var detailOfflineCollection: OfflineDownloads.Collection? {
        guard store.isReal, store.client != nil else { return nil }
        let visible = path.last ?? padSelection.map { Route.album($0) }
        switch visible {
        case .album(let a) where a.id.provider == MediaRef.companion.PROVIDER_SUBSONIC:
            return .init(kind: .album, id: a.id.rawId, title: a.title, subtitle: a.artist,
                         coverId: OfflineDownloads.coverId(a.palette.cover))
        case .playlist(let id):
            let p = playlistInfo(id)
            guard p.id.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return nil }
            return .init(kind: .playlist, id: id, title: p.title, subtitle: p.owner,
                         coverId: OfflineDownloads.coverId(p.palette.cover))
        default:
            return nil
        }
    }

    /// Plays the lone song [collection] names from what its download kept (title, artist, album, length, cover
    /// id), through the normal play entry; the stream url's cache key is the download's, so the file plays.
    /// No-op when the song's download is gone or another account's.
    func playDownloadedSong(_ collection: OfflineDownloads.Collection) {
        let offline = OfflineDownloads.shared
        guard collection.kind == .song,
              let song = offline.visible.first(where: { $0.collections.contains { $0.key == collection.key } }),
              store.client != nil else { return }
        let palette = store.client.map { SubsonicMapping(client: $0).palette(coverId: collection.coverId) }
            ?? CoverPalette(a: .gray, b: .gray, c: .gray)
        // A song with no album of its own keeps the "song:" stand-in id the rest of the app uses.
        let album = Album(id: MediaId(provider: AccountScope.subsonicProvider, rawId: song.albumId ?? "song:\(song.songId)"),
                          title: song.album, artist: song.artist, year: 0, palette: palette, tracks: [])
        let track = Track(id: MediaId(provider: AccountScope.subsonicProvider, rawId: song.songId), number: 1,
                          title: song.title, durationSec: song.durationSec, rating: nil)
        play([PlaylistEntry(album: album, track: track)])
    }

    /// Starts downloading [entries] under [collection] (keys from the signed-in client).
    func download(_ collection: OfflineDownloads.Collection, _ entries: [PlaylistEntry]) {
        OfflineDownloads.shared.download(collection, entries: entries) { e in
            store.playable(e).flatMap { SubsonicCacheKey.of($0.streamURL) }
        }
    }
}

extension OfflineDownloads {
    /// The cover's server id (its url is signed, so never stored).
    static func coverId(_ cover: CoverRef?) -> String? {
        cover.flatMap { URLComponents(url: $0.url, resolvingAgainstBaseURL: false)?.queryItems?
            .first { $0.name == "id" }?.value }
    }

    /// The row's words for [status] (Android strings_offline): Download, Downloading n/m
    /// (a tap cancels), Remove download; a failed one offers to try again.
    static func rowLabel(_ status: Status) -> String {
        switch status {
        case .none: String(localized: "Download")
        case .downloading(let done, let total): String(localized: "Downloading \(done)/\(total)")
        case .downloaded: String(localized: "Remove download")
        case .failed: String(localized: "Download again")
        }
    }

    static func rowIcon(_ status: Status) -> String {
        switch status {
        case .none, .failed: "download"
        case .downloading: "close"
        case .downloaded: "delete"
        }
    }

    /// The row's tap for [status].
    func rowTapped(_ status: Status, key: String, start: () -> Void) {
        switch status {
        case .none: start()
        case .failed:
            // The songs already kept stay; the failed ones go again, and any the collection gained since.
            retryFailed(key)
            start()
        case .downloading, .downloaded: remove(key)
        }
    }
}

/// The Download row in an album's ▾ or a playlist's ⋮ menu.
struct OfflineMenuButton: View {
    @Environment(AppModel.self) private var model
    var collection: OfflineDownloads.Collection
    /// The songs to keep, when the page isn't the top of `model.path` (the iPad column's root album).
    var entries: (() async -> [PlaylistEntry])?

    var body: some View {
        let offline = OfflineDownloads.shared
        let status = offline.status(collection.key)
        Button {
            offline.rowTapped(status, key: collection.key) {
                let c = collection
                let entries = entries
                Task {
                    let songs = if let entries { await entries() } else { await model.detailEntries() }
                    model.download(c, songs)
                }
            }
        } label: {
            Label { Text(OfflineDownloads.rowLabel(status)) } icon: { Image("yoin." + OfflineDownloads.rowIcon(status)) }
        }
    }
}
