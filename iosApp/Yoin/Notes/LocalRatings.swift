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

    private(set) var ratings: [String: Double] = [:]
    @ObservationIgnored private(set) var updatedAt: [String: Int64] = [:]
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private var timesURL: URL {
        fileURL.deletingLastPathComponent().appendingPathComponent("ratings-times.json")
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

    func rating(provider: String, songId: String) -> Double? {
        ratings[Self.key(provider: provider, songId: songId)]
    }

    func set(_ rating: Double, provider: String, songId: String) {
        let key = Self.key(provider: provider, songId: songId)
        ratings[key] = min(max(rating, 0), 10)
        updatedAt[key] = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        persist()
        Self.onLocalChange?()
    }

    // MARK: Cloud sync (Data/Sync): writes that came from another device

    func syncPut(_ rating: Double, provider: String, songId: String, updatedAt time: Int64) {
        let key = Self.key(provider: provider, songId: songId)
        ratings[key] = min(max(rating, 0), 10)
        updatedAt[key] = time
        persist()
    }

    func syncRemove(provider: String, songId: String) {
        let key = Self.key(provider: provider, songId: songId)
        ratings[key] = nil
        updatedAt[key] = nil
        persist()
    }

    private func persist() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(ratings).write(to: fileURL, options: .atomic)
            try JSONEncoder().encode(updatedAt).write(to: timesURL, options: .atomic)
        } catch {
            // Kept in memory for this run.
        }
    }
}
