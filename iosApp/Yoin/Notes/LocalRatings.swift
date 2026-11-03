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
/// Application Support (`Yoin/ratings.json`).
@MainActor
@Observable
final class LocalRatings {
    static let shared = LocalRatings(fileURL: LocalRatings.defaultURL)

    private(set) var ratings: [String: Double] = [:]
    @ObservationIgnored private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([String: Double].self, from: data) {
            ratings = saved
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("ratings.json")
    }

    static func key(provider: String, songId: String) -> String { "\(provider):\(songId)" }

    func rating(provider: String, songId: String) -> Double? {
        ratings[Self.key(provider: provider, songId: songId)]
    }

    func set(_ rating: Double, provider: String, songId: String) {
        ratings[Self.key(provider: provider, songId: songId)] = min(max(rating, 0), 10)
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try JSONEncoder().encode(ratings).write(to: fileURL, options: .atomic)
        } catch {
            // Kept in memory for this run.
        }
    }
}
