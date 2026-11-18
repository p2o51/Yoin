import Foundation

/// Spotify Connect devices Yoin has seen, per account (Android SpotifyDeviceMemory, owner 2026-11-08).
///
/// The Web API lists only devices online in Spotify's cloud; speakers on the local network (Sonos,
/// Cast) show up there only while Spotify plays on them or soon after. Once seen, a speaker stays in
/// the Devices sheet as recently used for `ttl`, and a tap tries the transfer. If it is asleep the
/// transfer fails and the sheet says to pick it once in Spotify.
struct SpotifyDeviceMemory {
    struct Remembered: Codable, Equatable, Sendable {
        var id: String
        var name: String
        var type: String
        var lastSeenMs: Int64
        /// Spotify gave it an id (false: a Sonos named without one).
        var hasId: Bool = true
        /// Spotify won't take a Web API transfer to it.
        var restricted: Bool = false
    }

    /// 30 days (Android SPOTIFY_DEVICE_MEMORY_TTL_MS).
    static let ttlMs: Int64 = 30 * 24 * 60 * 60 * 1000
    /// Android SPOTIFY_DEVICE_MEMORY_MAX.
    static let max = 12

    var defaults: UserDefaults = .standard

    private func key(_ scope: String) -> String { "spotifyDevices:\(scope)" }

    func recall(_ scope: String) -> [Remembered] {
        guard let data = defaults.data(forKey: key(scope)) else { return [] }
        return (try? JSONDecoder().decode([Remembered].self, from: data)) ?? []
    }

    func remember(_ scope: String, _ devices: [Remembered]) {
        guard let data = try? JSONEncoder().encode(devices) else { return }
        defaults.set(data, forKey: key(scope))
    }

    /// What the sheet shows for `listed` under the account `scope`, the memory updated on the way.
    func rows(_ listed: [SpotifySource.Device], scope: String, nowMs: Int64) -> [SpotifySource.Device] {
        let merged = Self.merge(recall(scope), listed: listed, nowMs: nowMs)
        remember(scope, merged)
        return Self.withRemembered(listed, merged)
    }

    /// `remembered` updated with what Spotify listed just now: listed devices refresh their name and
    /// time, expired ones drop, newest first (Android mergeRememberedSpotifyDevices).
    static func merge(_ remembered: [Remembered], listed: [SpotifySource.Device], nowMs: Int64) -> [Remembered] {
        // Sonos included: Spotify lists it only while it plays, so remembering it is the point.
        let fresh = listed.filter { !$0.remembered }.map {
            Remembered(id: $0.id, name: $0.name, type: $0.type, lastSeenMs: nowMs, hasId: $0.hasId, restricted: $0.restricted)
        }
        let freshIds = Set(fresh.map(\.id))
        let kept = remembered.filter { !freshIds.contains($0.id) && (0...ttlMs).contains(nowMs - $0.lastSeenMs) }
        return Array((fresh + kept).sorted { $0.lastSeenMs > $1.lastSeenMs }.prefix(max))
    }

    /// Rows for remembered devices Spotify didn't list this time, after the listed ones (Android
    /// withRememberedSpotifyDevices).
    static func withRemembered(_ listed: [SpotifySource.Device], _ remembered: [Remembered]) -> [SpotifySource.Device] {
        let ids = Set(listed.map(\.id))
        // A device can come back under a new id (a reinstall); its name is listed then.
        let names = Set(listed.map { fold($0.name) })
        return listed + remembered
            .filter { !ids.contains($0.id) && !names.contains(fold($0.name)) }
            .map {
                SpotifySource.Device(id: $0.id, name: $0.name, type: $0.type, isActive: false,
                                     hasId: $0.hasId, restricted: $0.restricted, remembered: true)
            }
    }

    private static func fold(_ name: String) -> String { name.trimmingCharacters(in: .whitespaces).lowercased() }
}
