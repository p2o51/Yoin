import Observation
import SwiftUI
import UIKit
import YoinShared

/**
 * A mention's cover (Android YoinRepository.mentionArtworkUrl, owner 2026-11-24): a song's from its own plays
 * first, else its album's; an album's from the loaded album, else a play of it, else the album loaded once; an
 * artist's from the loaded artist page, else the page loaded once. Asked once per mention and kept for the
 * session (by account and link); nil while unknown, and the pill shows its kind's symbol meanwhile.
 */
@MainActor
@Observable
final class NoteMentionArtwork {
    static let shared = NoteMentionArtwork()

    /// Answers by account and mention link: the cover, or nil (none).
    private(set) var answers: [String: CoverRef?] = [:]
    /// Mock covers (screenshots): the mesh drawn once.
    private(set) var mockImages: [String: UIImage] = [:]
    @ObservationIgnored private var asked: Set<String> = []

    /// The cover for [mention] (read in a body: one landing later redraws); asks for it the first time.
    func cover(_ mention: YoinNoteMention) -> CoverRef? {
        let store = AppModel.shared.store
        guard store.isReal, let id = NoteMentions.shared.resolvedId(mention) else { return nil }
        let provider = store.provider
        let key = "\(provider)|\(store.historyScope)|\(mention.link)"
        if let answer = answers[key] { return answer }
        if let now = Self.known(mention, id: id, provider: provider, store: store) {
            answers[key] = .some(now)
            return now
        }
        guard asked.insert(key).inserted else { return nil }
        nonisolated(unsafe) let asking = mention
        Task { [weak self] in
            let found = await Self.load(asking, id: id, store: store)
            self?.answers[key] = .some(found)
        }
        return nil
    }

    /// A mock album's (or a mock song's album's) mesh as an image: the screenshots' covers.
    func mockImage(_ mention: YoinNoteMention) -> UIImage? {
        guard !AppModel.shared.store.isReal, mention.provider == "mock" else { return nil }
        if let hit = mockImages[mention.link] { return hit }
        let album: Album? = switch mention.kind {
        case .album: MockLibrary.allAlbums.first { $0.id.rawId == mention.id }
        case .track: MockLibrary.allAlbums.first { $0.tracks.contains { $0.id.rawId == mention.id } }
        default: nil
        }
        guard let album else { return nil }
        let renderer = ImageRenderer(content: CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
            .frame(width: 48, height: 48))
        renderer.scale = 2
        guard let image = renderer.uiImage else { return nil }
        mockImages[mention.link] = image
        return image
    }

    /// What is already loaded or played: no network.
    private static func known(_ mention: YoinNoteMention, id: String, provider: String, store: LibraryStore) -> CoverRef? {
        let history = HistoryStore.shared
        switch mention.kind {
        case .track:
            if let play = history.mostRecentPlay(scope: store.historyScope, provider: provider, songId: id) {
                if let ref = palette(play.coverKey, id: id, store: store).cover { return ref }
                if let albumId = play.albumId, let ref = store.album(albumId)?.palette.cover { return ref }
            }
            return nil
        case .album:
            if let ref = store.album(id)?.palette.cover { return ref }
            let play = history.plays(scope: store.historyScope, provider: provider).last { $0.albumId == id }
            return play.flatMap { palette($0.coverKey, id: id, store: store).cover }
        case .artist:
            return store.artistPage(id)?.palette.cover
        default:
            return nil
        }
    }

    /// Asks the service once (album detail / artist page, both cached by the store).
    private static func load(_ mention: YoinNoteMention, id: String, store: LibraryStore) async -> CoverRef? {
        switch mention.kind {
        case .album:
            await store.loadAlbum(id)
            return store.album(id)?.palette.cover
        case .artist:
            await store.loadArtist(id)
            return store.artistPage(id)?.palette.cover
        case .track:
            let track = Track(id: MediaId(provider: store.provider, rawId: id), number: 1, title: mention.title,
                              durationSec: 1, artist: mention.artist)
            guard let albumId = await store.loadSongLinks(for: track).albumId else { return nil }
            await store.loadAlbum(albumId)
            return store.album(albumId)?.palette.cover
        default:
            return nil
        }
    }

    /// A history row's cover key as the account reads it (Subsonic: a cover id; else the artwork URL).
    private static func palette(_ coverKey: String?, id: String, store: LibraryStore) -> CoverPalette {
        guard let coverKey, !coverKey.isEmpty else { return store.placeholderPalette(coverId: nil) }
        if store.provider == MediaRef.companion.PROVIDER_SUBSONIC { return store.placeholderPalette(coverId: coverKey) }
        return AppleMusicMapping.palette(URL(string: coverKey), key: id)
    }
}
