import Foundation
import Observation

/// Android's rating scale: the Now Playing bar is 0–10 in tenths, stored
/// locally as is (`local_ratings.rating`); Subsonic gets 0–5 stars,
/// `round(rating / 2)` (`YoinRepository.storeRating`).
enum RatingScale {
    static func serverRating(local: Double) -> Int32 {
        let clamped = min(max(local, 0), 10)
        // Kotlin roundToInt: half away from zero, the same for non-negatives.
        return Int32(min(max((clamped / 2).rounded(.toNearestOrAwayFromZero), 0), 5))
    }

    /// A server's 1–5 stars on the local scale, for songs never rated here.
    static func local(fromServer stars: Int?) -> Double? {
        guard let stars, stars > 0 else { return nil }
        return Double(min(stars, 5) * 2)
    }
}

/// Ratings given on this device, per (provider, song). Android keeps the
/// rating locally whatever the provider does with it; so does this, in
/// Application Support (`Yoin/ratings.json`), with each rating's change time
/// beside it (`Yoin/ratings-times.json`, epoch ms, for cloud sync's
/// last-writer-wins; ratings saved before it read as time 0).
@MainActor
@Observable
final class LocalRatings {
    static let shared = LocalRatings(fileURL: LocalRatings.defaultURL)

    /// Every rating row, as stored. A 0 here is "not rated" (a cleared row,
    /// kept so the clear syncs) unless its key is in `zeroScores`: Android
    /// 557a58681's rule, and the sync wire's (bare `rating: 0` = not rated,
    /// `"zero": true` = a 0.0 score). Read through `rating(provider:songId:)`.
    private(set) var ratings: [String: Double] = [:]
    /// Keys whose stored 0 is a real 0.0 score (`ratings-zero.json`). A 0
    /// saved before this file existed reads as not rated, as Android's DB v33
    /// migration does.
    private(set) var zeroScores: Set<String> = []
    @ObservationIgnored private(set) var updatedAt: [String: Int64] = [:]
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private var timesURL: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("ratings-times.json")
    }
    @ObservationIgnored private var zeroURL: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("ratings-zero.json")
    }

    /// A rating changed by the user (not by cloud sync): Data/Sync debounces an upload.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([String: Double].self, from: data) {
            ratings = saved
        }
        if let data = try? Data(contentsOf: timesURL),
           let saved = try? JSONDecoder().decode([String: Int64].self, from: data) {
            updatedAt = saved
        }
        if let data = try? Data(contentsOf: zeroURL),
           let saved = try? JSONDecoder().decode(Set<String>.self, from: data) {
            zeroScores = saved
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("ratings.json")
    }

    static func key(provider: String, songId: String) -> String { "\(provider):\(songId)" }

    /// (provider, songId) of a [key]; the provider never holds a colon.
    static func parts(of key: String) -> (provider: String, songId: String)? {
        guard let colon = key.firstIndex(of: ":") else { return nil }
        return (String(key[..<colon]), String(key[key.index(after: colon)...]))
    }

    /// The song's score; nil = not rated (never, or cleared). 0 is a score.
    func rating(provider: String, songId: String) -> Double? {
        rating(key: Self.key(provider: provider, songId: songId))
    }

    func rating(key: String) -> Double? {
        guard let v = ratings[key] else { return nil }
        return v == 0 && !zeroScores.contains(key) ? nil : v
    }

    /// A row exists for the song (rated, or cleared and kept for sync).
    func hasRow(key: String) -> Bool { ratings[key] != nil }

    func set(_ rating: Double, provider: String, songId: String) {
        store(rating, key: Self.key(provider: provider, songId: songId),
              time: Int64((Date().timeIntervalSince1970 * 1000).rounded()))
        Self.onLocalChange?()
    }

    /// Back to not rated (the scrapbook's Clear; nowhere else offers it).
    /// The row stays, at 0 without the zero mark, so the clear syncs
    /// (track ratings don't propagate deletes).
    func clear(provider: String, songId: String) {
        let key = Self.key(provider: provider, songId: songId)
        guard rating(key: key) != nil else { return }
        store(nil, key: key, time: Int64((Date().timeIntervalSince1970 * 1000).rounded()))
        Self.onLocalChange?()
    }

    // MARK: Cloud sync (Data/Sync): writes that came from another device

    /// nil = the row arrived as "not rated".
    func syncPut(_ rating: Double?, provider: String, songId: String, updatedAt time: Int64) {
        store(rating, key: Self.key(provider: provider, songId: songId), time: time)
    }

    func syncRemove(provider: String, songId: String) {
        let key = Self.key(provider: provider, songId: songId)
        ratings[key] = nil
        zeroScores.remove(key)
        updatedAt[key] = nil
        persist()
    }

    private func store(_ rating: Double?, key: String, time: Int64) {
        let v = rating.map { min(max($0, 0), 10) }
        ratings[key] = v ?? 0
        if v == 0 { zeroScores.insert(key) } else { zeroScores.remove(key) }
        updatedAt[key] = time
        persist()
    }

    private func persist() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(ratings).write(to: fileURL, options: .atomic)
            try JSONEncoder().encode(updatedAt).write(to: timesURL, options: .atomic)
            try JSONEncoder().encode(zeroScores).write(to: zeroURL, options: .atomic)
        } catch {
            // Kept in memory for this run.
        }
    }
}
