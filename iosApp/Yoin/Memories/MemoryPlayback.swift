import Foundation

/*
 * Playing from a memory (Android ui/memories/MemoryPlaybackContext.kt and YoinNavHost's
 * playMemoryTrack). Memories is an overlay on Home, not a page, so the queue's "playing from"
 * can't be read off the page under it: the start names the memory's album itself.
 */

extension MemoryCard {
    /// The "playing from" of a play started on this memory, a liner row or a note (Android
    /// MemoryEntry.toPlaybackActivityContext): its album, under the album's own name (the memory's
    /// title is the diary's, not the source's). A memory here is always an album's.
    var playingFrom: AppModel.PlayingFrom { .album(album) }
}

extension AppModel {
    /// A liner row plays its track from the start; a note from its moment. The queue is the
    /// memory's album, started there. The song already current is not restarted: it seeks there
    /// and plays on if it was paused (Android `requestSeek`). Another song's seek is held by the
    /// output until the song is ready (AppleMusicAudioOutput).
    func play(_ card: MemoryCard, track g: MemoryCard.DiaryTrack, at ms: Int64? = nil) {
        let album = card.album
        let current = player.currentTrack?.id == g.track.id.rawId
        if !current {
            play(album.tracks.map { PlaylistEntry(album: album, track: $0) }, startAt: g.index, from: card.playingFrom)
        }
        if let ms { player.seek(to: Double(ms) / 1000) }
        if current { player.resume() }
    }
}
