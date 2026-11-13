import CoreTransferable
import YoinShared
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// A song, album or playlist dragged inside Yoin (declared in Info.plist; the native Mac's type).
    static let yoinItem = UTType(exportedAs: "com.gpo.yoin.item")
}

/// What a drag carries (ported from the native Mac's MacDrag): the kind, an opaque token for the account
/// it came from (never its server or user name), its id, and its words. Inside Yoin it drops on the queue
/// or on a writable playlist of the same account; anywhere else it is plain text, the title over the
/// artist (never a server address or a token).
struct YoinDragItem: Codable, Hashable, Transferable {
    enum Kind: String, Codable { case song, album, playlist }
    var kind: Kind
    var scope: String
    var id: String
    var text: String

    static var transferRepresentation: some TransferRepresentation {
        CodableRepresentation(contentType: .yoinItem)
        ProxyRepresentation(exporting: \.text)
    }
}

/// The dragged items' full values for this run (a drag carries ids only), and the drops: the queue
/// (Now Playing's Queue pill and the queue sheet) and playlist rows.
///
/// Where a drag starts: song rows (Library, Search, album and playlist pages: `trackRow`), and Library's
/// album and playlist cells and playlist rows. A pointer drags at once (the iPad app on a Mac, an iPad
/// with a trackpad); a finger lifts the row after the long press that opens its sheet, as before.
@MainActor
enum YoinDrag {
    /// What one account's drags remember. A new account (a new store generation) starts empty, and its
    /// token is new, so a drag begun under the old account drops nothing.
    struct Registry {
        private(set) var generation: Int?
        private(set) var token = ""
        private(set) var songs: [String: PlaylistEntry] = [:]
        private(set) var albums: [String: Album] = [:]

        /// Kept small: only what was dragged lately (the Mac's bounds).
        static let maxSongs = 300
        static let maxAlbums = 60

        /// The token for [generation]; a different generation forgets everything before it.
        mutating func scope(_ generation: Int, makeToken: () -> String = { UUID().uuidString }) -> String {
            if self.generation != generation {
                self.generation = generation
                token = makeToken()
                songs.removeAll()
                albums.removeAll()
            }
            return token
        }

        mutating func remember(_ e: PlaylistEntry) {
            if songs.count >= Self.maxSongs { songs.removeAll() }
            songs[e.track.id.rawId] = e
        }

        mutating func remember(_ a: Album) {
            if albums.count >= Self.maxAlbums { albums.removeAll() }
            albums[a.id.rawId] = a
        }

        /// The items this account can use, in order: another account's (or run's) never pass.
        func accepted(_ items: [YoinDragItem]) -> [YoinDragItem] {
            items.filter { $0.scope == token && !token.isEmpty }
        }
    }

    private(set) static var registry = Registry()

    static func song(_ e: PlaylistEntry, model: AppModel) -> YoinDragItem {
        let scope = registry.scope(model.store.generation)
        registry.remember(e)
        return YoinDragItem(kind: .song, scope: scope, id: e.track.id.rawId,
                            text: text(e.track.title, e.track.artist ?? e.album.artist))
    }

    static func album(_ a: Album, model: AppModel) -> YoinDragItem {
        let scope = registry.scope(model.store.generation)
        registry.remember(a)
        return YoinDragItem(kind: .album, scope: scope, id: a.id.rawId, text: text(a.title, a.artist))
    }

    static func playlist(_ p: LibraryItem, model: AppModel) -> YoinDragItem {
        YoinDragItem(kind: .playlist, scope: registry.scope(model.store.generation), id: p.id.rawId, text: p.title)
    }

    /// Plain text for other apps: the title, the artist on the next line (no separator glyph).
    nonisolated static func text(_ title: String, _ artist: String) -> String {
        artist.isEmpty ? title : "\(title)\n\(artist)"
    }

    /// The songs the items stand for, in order; nothing from another account, and nothing at all when the
    /// account changes while an album or playlist loads.
    static func entries(_ items: [YoinDragItem], model: AppModel) async -> [PlaylistEntry] {
        let store = model.store
        _ = registry.scope(store.generation)
        let gen = store.generation
        var out: [PlaylistEntry] = []
        for item in registry.accepted(items) {
            switch item.kind {
            case .song:
                if let e = registry.songs[item.id] { out.append(e) }
            case .album:
                guard let a = registry.albums[item.id] else { continue }
                let full = await store.fullAlbum(model.resolved(a))
                out += full.tracks.map { PlaylistEntry(album: full, track: $0) }
            case .playlist:
                await store.loadPlaylist(item.id)
                out += model.playlistInfo(item.id).entries
            }
            guard gen == store.generation else { return [] }
        }
        return out
    }

    // MARK: Queue

    /// Adds the items to the queue (after what the user queued, the sheet's Add to queue), or right before
    /// the queue entry [before] when dropped on its row (found again after the insert, so loading meanwhile
    /// doesn't shift it). Says how many went in, as the song sheet does.
    static func queue(_ items: [YoinDragItem], before: UUID? = nil, model: AppModel) {
        guard !registry.accepted(items).isEmpty else { return }
        Task {
            let entries = await entries(items, model: model)
            guard !entries.isEmpty else { return }
            let had = Set(model.player.queue.map(\.id))
            withAnimation(Yoin.spatial) {
                model.enqueue(entries, next: false) { ok in
                    guard ok else { return PlaylistActions.shared.show(String(localized: "Couldn't add to queue")) }
                    if let before {
                        let q = model.player.queue.map(\.id)
                        if let move = insertion(added: q.filter { !had.contains($0) }, before: before, in: q) {
                            model.player.move(fromOffsets: move.offsets, toOffset: move.destination)
                        }
                    }
                    PlaylistActions.shared.show(String(localized: "Added \(entries.count) songs to the queue"))
                }
            }
        }
    }

    /// The `move(fromOffsets:toOffset:)` that puts the [added] ids (in queue order) right before [anchor];
    /// nil when either is missing or they already sit there.
    nonisolated static func insertion(added: [UUID], before anchor: UUID, in queue: [UUID])
        -> (offsets: IndexSet, destination: Int)? {
        let addedSet = Set(added)
        let offsets = IndexSet(queue.indices.filter { addedSet.contains(queue[$0]) })
        guard !offsets.isEmpty, !addedSet.contains(anchor), let at = queue.firstIndex(of: anchor) else { return nil }
        // Already a block right before the anchor.
        if let first = offsets.first, offsets.count == at - first, offsets.last == at - 1 { return nil }
        return (offsets, at)
    }

    // MARK: Playlists

    /// A playlist a drop may write into: the account's own (the song sheet's Remove rule: Subsonic, owned
    /// by this user or nobody, or the server says it's writable; Spotify, made by this user or, once read,
    /// collaborative). Signed out, the mock's playlists take drops (and only confirm).
    static func accepts(_ p: LibraryItem, model: AppModel) -> Bool {
        guard p.kind == .playlist, !p.id.rawId.hasPrefix("pending:") else { return false }
        let store = model.store
        if let info = store.playlist(p.id.rawId) { return PlaylistActions.shared.canEditSongs(info, store: store) }
        if p.id.provider == "mock" { return !store.isReal }
        if p.id.provider == SpotifyMapping.provider { return store.spotify != nil && p.ownedByMe == true }
        guard store.client != nil, p.id.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return false }
        return subsonicAccepts(owner: p.owner, username: store.session.account?.username)
    }

    /// A Subsonic playlist not read yet: the user's, or nobody's.
    nonisolated static func subsonicAccepts(owner: String?, username: String?) -> Bool {
        guard let owner, !owner.isEmpty else { return true }
        return owner == username
    }

    /// Adds the items' songs to playlist [p] ("Added to …" or "Couldn't add to …"); a playlist dropped on
    /// itself adds nothing.
    static func add(_ items: [YoinDragItem], to p: LibraryItem, model: AppModel) {
        let items = registry.accepted(items).filter { !($0.kind == .playlist && $0.id == p.id.rawId) }
        guard !items.isEmpty else { return }
        Task {
            let entries = await entries(items, model: model)
            guard !entries.isEmpty else { return }
            let pick = PlaylistActions.Pick(id: p.id.rawId, name: p.title, songCount: p.count, palette: p.palette)
            PlaylistActions.shared.add(entries, to: pick, store: model.store)
        }
    }

    /// M3's dragged state layer: the content colour at 16 % behind a drop target while a drag is over it.
    static let targetLayer: Double = 0.16

    #if DEBUG
    /// `-dropPreview YES` (debug, iPad): drop targets show as if a drag were over them, for screenshots.
    static let preview = PointerHover.available && UserDefaults.standard.bool(forKey: "dropPreview")
    #else
    static let preview = false
    #endif
}

extension View {
    /// Library's album or playlist cell, or a song row: dragged out as a Yoin item (plain text elsewhere).
    func yoinDraggable(_ item: @escaping @MainActor () -> YoinDragItem) -> some View {
        draggable(item())
    }

    /// A drop target: [accepts] false refuses the drop and stays unlit. While a drag is over it, the
    /// dragged state layer shows behind it in [shape] (effects spring). On a glass control, attach it
    /// before the glass, so the layer sits between the glass and the label.
    func yoinDropTarget(in shape: some Shape, ink: Color = .primary, accepts: Bool = true,
                        perform: @escaping @MainActor ([YoinDragItem]) -> Void) -> some View {
        modifier(YoinDropTarget(shape: shape, ink: ink, accepts: accepts, perform: perform))
    }
}

private struct YoinDropTarget<S: Shape>: ViewModifier {
    var shape: S
    var ink: Color
    var accepts: Bool
    var perform: @MainActor ([YoinDragItem]) -> Void
    @State private var targeted = false

    func body(content: Content) -> some View {
        let lit = accepts && (targeted || YoinDrag.preview)
        content
            .background {
                shape.fill(ink.opacity(lit ? YoinDrag.targetLayer : 0))
                    .animation(Yoin.effects, value: lit)
                    .allowsHitTesting(false)
            }
            .dropDestination(for: YoinDragItem.self) { items, _ in
                guard accepts, !items.isEmpty else { return false }
                perform(items)
                return true
            } isTargeted: { targeted = $0 }
    }
}
