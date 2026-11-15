import SwiftUI

/// Spotify albums' place in the account's library (Android AlbumDetailViewModel.librarySaved and the
/// ▾ menu's Save to library / Remove from library, parity B-F3): read when the album's page bar shows,
/// flipped at once on a tap, flipped back with a message when the write fails.
@MainActor
@Observable
final class AlbumLibraryState {
    static let shared = AlbumLibraryState()

    /// Raw Spotify album id → in the library. Absent: not read (the row waits, as Android's does).
    private(set) var saved: [String: Bool] = [:]
    @ObservationIgnored private var writing: Set<String> = []
    /// The store generation [saved] was read in: another account's states aren't this one's.
    @ObservationIgnored private var generation: Int?

    /// Forgets the states read for another account (or source) than [store]'s current one.
    private func follow(_ store: LibraryStore) {
        guard generation != store.generation else { return }
        generation = store.generation
        saved = [:]
        writing = []
    }

    /// The album's state, read once per page (Spotify albums only).
    func load(_ album: Album, store: LibraryStore) async {
        follow(store)
        guard Self.applies(album), let spotify = store.spotify, !writing.contains(album.id.rawId) else { return }
        let gen = store.generation
        if let state = await spotify.isAlbumSaved(album.id.rawId), gen == store.generation,
           !writing.contains(album.id.rawId) {
            saved[album.id.rawId] = state
        }
    }

    func toggle(_ album: Album, store: LibraryStore) {
        follow(store)
        let id = album.id.rawId
        guard let spotify = store.spotify, let was = saved[id] else { return }
        let target = !was
        saved[id] = target
        writing.insert(id)
        let gen = store.generation
        Task {
            defer { if gen == generation { writing.remove(id) } }
            do {
                try await spotify.setAlbumSaved(target, album: id)
                guard gen == store.generation else { return }
                // Library › Albums takes the change in place (no whole-list read).
                store.patchSavedAlbum(album, saved: target)
            } catch {
                guard gen == store.generation else { return }
                saved[id] = was
                PlaylistActions.shared.show(target ? String(localized: "Couldn't save the album") : String(localized: "Couldn't remove the album"))
            }
        }
    }

    nonisolated static func applies(_ album: Album) -> Bool { album.id.provider == SpotifyMapping.provider }

    nonisolated static func label(saved: Bool) -> String { saved ? String(localized: "Remove from library") : String(localized: "Save to library") }

    /// For tests: a known state.
    func set(_ state: Bool?, for rawId: String) { saved[rawId] = state }
}

/// The ▾ menu's row, once the album's state is known.
struct AlbumLibraryMenuButton: View {
    @Environment(AppModel.self) private var model
    var album: Album

    var body: some View {
        if AlbumLibraryState.applies(album), let saved = AlbumLibraryState.shared.saved[album.id.rawId] {
            Button(AlbumLibraryState.label(saved: saved)) { AlbumLibraryState.shared.toggle(album, store: model.store) }
        }
    }
}
