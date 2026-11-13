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

    /// How long playing another account's song waits for that account's source after the switch
    /// (Android OtherProfileSourceWaitMs).
    static let otherAccountSourceWait: Duration = .seconds(10)

    /// A row or note of another account's memory (Android YoinNavHost onPlayMemoryTrack): Yoin switches
    /// to [owner]'s account, as Home's other-accounts shelf does, then plays once its source is up. A
    /// switch that doesn't land says so and plays nothing.
    func play(_ card: MemoryCard, track g: MemoryCard.DiaryTrack, at ms: Int64? = nil,
              switchingTo owner: MemoryCard.Account) {
        Task {
            guard await switchAccount(to: owner.profileId, provider: card.album.id.provider) else { return }
            play(card, track: g, at: ms)
        }
    }

    /// Makes [profileId] the account in use and waits until the library reads from it ([provider]'s
    /// source: the store starts over a turn after the switch, and Spotify's is built behind it), at most
    /// [wait]. False when the switch didn't land (said in a toast, Android's switchFailures) or the
    /// source never came up.
    func switchAccount(to profileId: String, provider: String,
                       wait: Duration = AppModel.otherAccountSourceWait) async -> Bool {
        if profiles.activeId != profileId {
            let before = store.generation
            await profiles.switchTo(profileId)
            guard profiles.activeId == profileId else {
                PlaylistActions.shared.show(String(localized: "Couldn't switch account"))
                return false
            }
            let clock = ContinuousClock()
            let deadline = clock.now + wait
            while store.generation == before || !store.isReal || store.provider != provider {
                guard clock.now < deadline, !Task.isCancelled, profiles.activeId == profileId else { return false }
                try? await Task.sleep(for: .milliseconds(20))
            }
        }
        return store.isReal && store.provider == provider
    }
}
