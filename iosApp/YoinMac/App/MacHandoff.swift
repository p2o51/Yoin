import Foundation

/// Handoff of what's playing (`com.gpo.yoin.playing`): the account's service, the song and its album, and
/// where in the song. Nothing secret (no server address, no token); the receiving app finds the song in
/// its own account for that service.
@MainActor
enum MacHandoff {
    static let playingType = "com.gpo.yoin.playing"

    static func fill(_ activity: NSUserActivity, model: MacModel) {
        guard let t = model.player.currentTrack else { return }
        activity.title = "\(t.title) — \(t.artist)"
        activity.isEligibleForHandoff = true
        activity.isEligibleForSearch = false
        activity.isEligibleForPublicIndexing = false
        var info: [String: Any] = [
            "provider": t.provider,
            "songId": t.id,
            "title": t.title,
            "artist": t.artist,
            "album": t.album,
            "positionSec": Int(model.player.progress.positionSec),
            "playing": model.player.isPlaying,
        ]
        if let albumId = t.albumId { info["albumId"] = albumId }
        activity.userInfo = info
        activity.needsSave = true
    }
}
