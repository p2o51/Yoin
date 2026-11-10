import Foundation
import FoundationModels
import Observation
import UIKit
import YoinShared

/// The on-device model as the cards care about it (Android NanoStatus).
enum OnDeviceModelStatus: String, Sendable {
    case available
    /// Apple Intelligence is off in Settings.
    case notEnabled
    /// The system is still fetching the model.
    case notReady
    /// This device can't run it.
    case unsupported
}

/// One answer from the model, with its refusals spelled out (Android NanoAnswer).
enum OnDeviceAnswer: Sendable, Equatable {
    case text(String)
    /// Rate limited or busy: back off.
    case busy
    case failed
}

/// The model seam, so the processor is testable without Apple Intelligence.
protocol LyricHighlightModel: Sendable {
    func status() -> OnDeviceModelStatus
    func answer(_ prompt: String) async -> OnDeviceAnswer
    var name: String { get }
}

/// Apple Intelligence's on-device model (FoundationModels). One short question per song.
struct AppleOnDeviceLyricModel: LyricHighlightModel {
    var name: String { "apple-on-device" }

    func status() -> OnDeviceModelStatus {
        switch SystemLanguageModel.default.availability {
        case .available: return .available
        case .unavailable(.appleIntelligenceNotEnabled): return .notEnabled
        case .unavailable(.modelNotReady): return .notReady
        case .unavailable: return .unsupported
        }
    }

    func answer(_ prompt: String) async -> OnDeviceAnswer {
        let session = LanguageModelSession(instructions: LyricHighlightPrompt.instructions)
        do {
            let r = try await session.respond(to: prompt, options: GenerationOptions(
                temperature: 0.2, maximumResponseTokens: LyricHighlightPrompt.maxOutputTokens))
            let text = r.content.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? .failed : .text(text)
        } catch let e as LanguageModelSession.GenerationError {
            switch e {
            case .rateLimited, .concurrentRequests: return .busy
            default: return .failed
            }
        } catch {
            return .failed
        }
    }
}

/// Picks lyric cards with the on-device model, a few songs at a time, only while Yoin is in front (Android
/// LyricHighlightProcessor): the song playing first, then the selector's queue. Nothing runs on Low Power
/// Mode, a warm device, or a low battery that isn't charging; the budget caps the day.
@MainActor
@Observable
final class LyricHighlightProcessor {
    static let shared = LyricHighlightProcessor(model: AppleOnDeviceLyricModel())

    private(set) var status: OnDeviceModelStatus?
    @ObservationIgnored let model: any LyricHighlightModel
    @ObservationIgnored private let store: LyricHighlightStore
    @ObservationIgnored private var visit: Task<Void, Never>?

    static let startDelay: Duration = .seconds(10)
    static let retryUnusableAfterMs: Int64 = 30 * LyricHighlightSelector.dayMs
    static let minBattery: Float = 0.3

    init(model: any LyricHighlightModel, store: LyricHighlightStore? = nil) {
        self.model = model
        self.store = store ?? .shared
    }

    /// Asks the system again (Labs opening, its "Check again"); costs no inference.
    @discardableResult
    func checkNow() -> OnDeviceModelStatus {
        let s = model.status()
        status = s
        return s
    }

    /// Yoin came to the front: a visit after the start delay. Yoin going to the background stops it.
    func appBecameActive(_ app: AppModel = .shared) {
        checkNow()
        guard visit == nil, store.optedIn, status == .available else { return }
        visit = Task { [weak self] in
            try? await Task.sleep(for: Self.startDelay)
            await self?.runVisit(app)
            self?.visit = nil
        }
    }

    func appResignedActive() {
        visit?.cancel()
        visit = nil
    }

    /// The intro's "Turn on" and Labs' switch: on starts a visit now; off stops the model at once.
    func setOptedIn(_ on: Bool) {
        store.optedIn = on
        store.introSeen = true
        if on { appBecameActive() } else { appResignedActive() }
    }

    private func deviceIsRelaxed() -> Bool {
        let info = ProcessInfo.processInfo
        if info.isLowPowerModeEnabled { return false }
        if info.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue { return false }
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        if device.batteryState == .charging || device.batteryState == .full || device.batteryLevel < 0 { return true }
        return device.batteryLevel >= Self.minBattery
    }

    private struct Entry {
        var provider: String
        var scope: String?
        var songId: String
        var title: String
        var artist: String
        var album: String
        var albumId: String
        var coverKey: String?
        var durationMs: Int64
    }

    private func runVisit(_ app: AppModel) async {
        let lib = app.store
        guard lib.isReal, let account = app.profiles.active.flatMap(MemoryLedgerSource.account(of:)) else { return }
        var done = 0
        for entry in await queue(app, account: account) {
            guard !Task.isCancelled, done < LyricHighlightBudget.perVisitLimit, deviceIsRelaxed() else { break }
            let now = Self.nowMs
            guard store.budget.allows(now: now, today: Self.today(now)) else { break }
            // Switched accounts mid-visit: the rest of the queue belongs to the old one.
            guard app.profiles.active.flatMap(MemoryLedgerSource.account(of:)) == account else { break }
            switch await process(entry, app: app) {
            case .stop: return
            case .picked, .unusable: done += 1
            case .skipped: break
            }
            try? await Task.sleep(for: .milliseconds(LyricHighlightBudget.gapMs))
        }
    }

    private enum Outcome { case picked, unusable, skipped, stop }

    /// The song playing now, then the selector's mix of heavy rotation, rediscover and fresh.
    private func queue(_ app: AppModel, account: LedgerAccount) async -> [Entry] {
        let now = Self.nowMs
        let settled = store.settled(provider: account.provider, scope: account.scope,
                                    retryAfter: now - Self.retryUnusableAfterMs)
        var out: [Entry] = []
        let t = app.player.currentTrack
        if let t, t.provider == account.provider, !settled.contains(t.id) {
            out.append(Entry(provider: t.provider, scope: account.scope, songId: t.id, title: t.title, artist: t.artist,
                             album: t.album, albumId: t.albumId ?? "", coverKey: LibraryStore.coverKey(t.coverURL, provider: t.provider),
                             durationMs: Int64(t.durationSec * 1000)))
        }
        let plays = HistoryStore.shared.plays(scope: MemoryLedgerSource.historyScope(account), provider: account.provider)
        let notes = NotesStore.shared.notes.filter { $0.provider == account.provider }
        let ratings = LocalRatings.shared
        let signals = Self.signals(plays: plays, noteCount: Dictionary(grouping: notes, by: \.trackId).mapValues(\.count),
                                   rating: { ratings.rating(provider: account.provider, songId: $0) }, now: now)
        var last: [String: PlayEvent] = [:]
        for p in plays where (last[p.songId]?.startedAt ?? .min) < p.startedAt { last[p.songId] = p }
        // Unplayed tracks from the best-rated albums whose track lists are loaded.
        var fresh: [LyricHighlightSelector.FreshTrack] = []
        var freshEntries: [String: Entry] = [:]
        let rated = AlbumMemoryStore.shared.albums.values.filter { $0.provider == account.provider && $0.rating >= 7 }
            .sorted { $0.rating > $1.rating }.prefix(3)
        for r in rated {
            if app.store.albumDetails[r.albumId] == nil { await app.store.loadAlbum(r.albumId) }
            guard let album = app.store.albumDetails[r.albumId] else { continue }
            for track in album.tracks.prefix(4) where last[track.id.rawId] == nil {
                fresh.append(.init(songId: track.id.rawId, albumRating: r.rating))
                freshEntries[track.id.rawId] = entry(track, album, account)
            }
        }
        let pools = LyricHighlightSelector.pools(signals, fresh: fresh, now: now)
        let skip = settled.union(out.map(\.songId))
        for c in LyricHighlightSelector.processingQueue(pools, skip: skip, limit: LyricHighlightBudget.perVisitLimit * 2) {
            if let p = last[c.songId] {
                out.append(Entry(provider: p.provider, scope: account.scope, songId: p.songId, title: p.title, artist: p.artist,
                                 album: p.album, albumId: p.albumId ?? "", coverKey: p.coverKey,
                                 durationMs: Int64(p.durationSec * 1000)))
            } else if let e = freshEntries[c.songId] {
                out.append(e)
            }
        }
        // Nothing heard here yet: start from the server's own lists, so the section isn't empty for days.
        if out.isEmpty, let lists = app.store.homeLists {
            for album in (lists.frequent + lists.recent).prefix(4) {
                if app.store.albumDetails[album.id.rawId] == nil { await app.store.loadAlbum(album.id.rawId) }
                guard let a = app.store.albumDetails[album.id.rawId] else { continue }
                for t in a.tracks.shuffled().prefix(2) where !settled.contains(t.id.rawId) { out.append(entry(t, a, account)) }
            }
        }
        return out
    }

    private func entry(_ t: Track, _ a: Album, _ account: LedgerAccount) -> Entry {
        Entry(provider: account.provider, scope: account.scope, songId: t.id.rawId, title: t.title, artist: t.artist ?? a.artist,
              album: a.title, albumId: a.id.rawId, coverKey: LibraryStore.coverKey(a.palette.cover?.url, provider: account.provider),
              durationMs: Int64(t.durationSec) * 1000)
    }

    /// Play history turned into the selector's signals (Android LyricHighlightSignals).
    nonisolated static func signals(plays: [PlayEvent], noteCount: [String: Int], rating: (String) -> Double?,
                                    now: Int64) -> [LyricHighlightSelector.SongSignal] {
        var bySong: [String: (count: Int, last: Int64, heat: Double)] = [:]
        for p in plays {
            var s = bySong[p.songId] ?? (0, 0, 0)
            if p.completed { s.count += 1 }
            s.last = max(s.last, p.startedAt)
            let fraction = p.durationSec > 0 ? p.listenedSec / p.durationSec : (p.completed ? 1 : 0)
            s.heat += LyricHighlightSelector.heat(playedAt: p.startedAt, completed: p.completed ? max(fraction, 0.5) : fraction, now: now)
            bySong[p.songId] = s
        }
        return bySong.map { id, s in
            .init(songId: id, playCount: s.count, lastPlayedAt: s.last, heat: s.heat, rating: rating(id),
                  noteCount: noteCount[id] ?? 0)
        }
    }

    private func process(_ e: Entry, app: AppModel) async -> Outcome {
        let fetched: [(text: String, startMs: Int64?)]?
        do {
            fetched = try await Self.lyrics(e.provider, e.songId, title: e.title, artist: e.artist, durationMs: e.durationMs,
                                            client: app.store.client)
        } catch {
            return .stop
        }
        let base = LyricHighlightRow(provider: e.provider, scope: e.scope, songId: e.songId, title: e.title, artist: e.artist,
                                     album: e.album, albumId: e.albumId, coverKey: e.coverKey, durationMs: e.durationMs,
                                     text: nil, startMs: nil, fingerprint: nil, model: nil, attemptedAt: Self.nowMs)
        guard let fetched, let prepared = LyricHighlightPrompt.prepare(fetched) else {
            store.save(base)
            return .unusable
        }
        if let existing = store.row(provider: e.provider, scope: e.scope, songId: e.songId),
           existing.isUserPick || (existing.text != nil && existing.fingerprint == prepared.fingerprint) { return .skipped }
        let prompt = LyricHighlightPrompt.userPrompt(title: e.title, artist: e.artist, prepared)
        let now = Self.nowMs
        switch await model.answer(prompt) {
        case .text(let answer):
            store.budget = store.budget.afterSuccess(today: Self.today(now))
            guard !Task.isCancelled else { return .stop }
            var row = base
            row.fingerprint = prepared.fingerprint
            row.model = model.name
            if let pick = LyricHighlightPrompt.parse(answer, prepared) {
                row.text = pick.text
                row.startMs = pick.startMs
                store.save(row)
                return .picked
            }
            store.save(row)
            return .unusable
        case .busy:
            store.budget = store.budget.afterBusy(now: now, today: Self.today(now))
            return .stop
        case .failed:
            return .skipped
        }
    }

    /// The song's lyrics as lines: the listener's chosen or the cached automatic hit first, then the server
    /// (Subsonic), else the lyric providers. Throws when the network failed (the visit stops).
    static func lyrics(_ provider: String, _ songId: String, title: String, artist: String, durationMs: Int64,
                       client: SubsonicClient?) async throws -> [(text: String, startMs: Int64?)]? {
        let key = LocalRatings.key(provider: provider, songId: songId)
        let user = LyricsUserStore.shared
        if let lrc = user.chosen(key) ?? user.auto(key)?.lrc {
            let parsed = LrcParser.shared.parse(raw: lrc)
            if !parsed.lines.isEmpty { return lines(parsed) }
        }
        if provider == MediaRef.companion.PROVIDER_SUBSONIC, let client {
            if let l = try await client.lyricsFor(songId: songId, artist: artist, title: title), !l.lines.isEmpty { return lines(l) }
        }
        let ms = durationMs > 0 ? KotlinLong(value: durationMs) : nil
        guard let hit = try await NowPlayingData.shared.registry.autoFetch(title: title, artist: artist, durationMs: ms),
              !hit.lyrics.lines.isEmpty else { return nil }
        return lines(hit.lyrics)
    }

    private static func lines(_ l: Lyrics) -> [(text: String, startMs: Int64?)] {
        l.lines.map { ($0.text, l.synced ? $0.timeMs : nil) }
    }

    /// A line the listener shared or saved from the lyrics page: kept as the song's card, over the model's.
    func keepUserPick(_ lines: [String], startMs: Int64?, track t: PlayableTrack, account: LedgerAccount?) {
        guard let account, account.provider == t.provider else { return }
        let text = lines.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            .prefix(LyricHighlightStore.maxUserPickLines).joined(separator: "\n")
        guard !text.isEmpty else { return }
        store.save(LyricHighlightRow(provider: t.provider, scope: account.scope, songId: t.id, title: t.title, artist: t.artist,
                                     album: t.album, albumId: t.albumId ?? "",
                                     coverKey: LibraryStore.coverKey(t.coverURL, provider: t.provider),
                                     durationMs: Int64(t.durationSec * 1000), text: text, startMs: startMs,
                                     fingerprint: nil, model: LyricHighlightStore.userPick, attemptedAt: Self.nowMs))
    }

    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    static func today(_ now: Int64) -> Int64 {
        LyricHighlightBudget.dayOf(now, utcOffsetSec: TimeZone.current.secondsFromGMT())
    }
}
