import Foundation

/// The songs a start or a queue action hands the player, after the ones Apple Music won't stream
/// ([Track.notStreamed]) are taken out (Android player/PlayableQueue.kt).
enum PlayableQueue {
    /// The entries a start plays and where it starts in them (Android playableQueue).
    ///
    /// A song Apple won't stream is left out, and [startAt] moves to where the started song lands.
    /// [explicitStart] says the listener picked that song (a row tap): if it is itself one Apple won't
    /// stream, the list is left as given, so the service's own error reaches the listener instead of
    /// another song quietly playing. A Play or Shuffle button picks no song, so such a song there gives
    /// way to the next one that plays (the last one before it, at the end of the list). A list with
    /// nothing playable, or nothing held back, is left as given.
    static func start(_ entries: [PlaylistEntry], startAt: Int, explicitStart: Bool) -> (entries: [PlaylistEntry], startAt: Int) {
        let kept = select(entries.map(\.track.notStreamed), startAt: startAt, explicitStart: explicitStart)
        return (kept.indices.map { entries[$0] }, kept.startAt)
    }

    /// [start] on the flags alone: the indices kept, in order, and the start's index among them.
    static func select(_ unplayable: [Bool], startAt: Int, explicitStart: Bool) -> (indices: [Int], startAt: Int) {
        let all = Array(unplayable.indices)
        guard unplayable.contains(true), unplayable.contains(false) else { return (all, startAt) }
        let start = min(max(startAt, 0), unplayable.count - 1)
        if explicitStart && unplayable[start] { return (all, startAt) }
        let kept = all.filter { !unplayable[$0] }
        // The playable songs ahead of the start: its own new index, or for a held-back start, the next one's.
        return (kept, min((0..<start).count { !unplayable[$0] }, kept.count - 1))
    }

    /// What Play next / Add to queue hands the queue: all but the songs Apple won't stream (Android
    /// queueableSongs).
    static func queueable(_ entries: [PlaylistEntry]) -> [PlaylistEntry] {
        entries.filter { !$0.track.notStreamed }
    }
}
