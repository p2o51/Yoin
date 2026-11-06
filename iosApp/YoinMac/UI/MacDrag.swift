import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// A song, album or playlist dragged inside Yoin (declared in the Mac Info.plist).
    static let yoinItem = UTType(exportedAs: "com.gpo.yoin.item")
}

/// What a drag carries: the kind, the account it came from, its id, and its words. Inside Yoin it drops on the
/// queue or a playlist of the same account; anywhere else it's plain text, "Title — Artist" (never a server
/// address or token).
struct YoinDragItem: Codable, Transferable {
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

/// The dragged items' full values for this run (a drag carries ids only) and the drops.
@MainActor
enum MacDrag {
    private static var songs: [String: PlaylistEntry] = [:]
    private static var albums: [String: Album] = [:]

    static func song(_ e: PlaylistEntry, scope: String) -> YoinDragItem {
        songs[e.track.id.rawId] = e
        return YoinDragItem(kind: .song, scope: scope, id: e.track.id.rawId,
                            text: "\(e.track.title) — \(e.track.artist ?? e.album.artist)")
    }

    static func album(_ a: Album, scope: String) -> YoinDragItem {
        albums[a.id.rawId] = a
        return YoinDragItem(kind: .album, scope: scope, id: a.id.rawId, text: "\(a.title) — \(a.artist)")
    }

    static func playlist(_ p: LibraryItem, scope: String) -> YoinDragItem {
        YoinDragItem(kind: .playlist, scope: scope, id: p.id.rawId, text: p.title)
    }

    /// The songs the items stand for, in order; nothing from another account (or another run).
    static func entries(_ items: [YoinDragItem], model: MacModel) async -> [PlaylistEntry] {
        let scope = model.session.accountKey
        let gen = model.library.accountGeneration
        var out: [PlaylistEntry] = []
        for item in items where item.scope == scope {
            switch item.kind {
            case .song:
                if let e = songs[item.id] { out.append(e) }
            case .album:
                guard let a = albums[item.id] else { continue }
                let full = await model.library.fullAlbum(a)
                out += full.tracks.map { PlaylistEntry(album: full, track: $0) }
            case .playlist:
                await model.library.loadPlaylist(item.id)
                out += model.library.playlist(item.id)?.entries ?? []
            }
            guard gen == model.library.accountGeneration else { return [] }
        }
        return out
    }

    /// Adds the items to the queue: after what's queued, or before the upcoming song at [before] (a play-order
    /// index) when dropped on a row.
    static func queue(_ items: [YoinDragItem], before: Int?, model: MacModel) {
        Task {
            let entries = await entries(items, model: model)
            var at = before
            for e in entries {
                let had = Set(model.player.queue.map(\.id))
                model.addToQueue(e)
                guard let target = at, let added = model.player.queue.firstIndex(where: { !had.contains($0.id) }) else { continue }
                model.player.move(from: added, to: added < target ? target - 1 : target)
                at = (added < target ? target - 1 : target) + 1
            }
        }
    }

    /// Adds the items' songs to playlist [id] (a writable one of the same account).
    static func add(_ items: [YoinDragItem], toPlaylist id: String, model: MacModel) {
        guard model.library.writablePlaylists.contains(where: { $0.id.rawId == id }) else { return }
        Task {
            let ids = await entries(items, model: model).map(\.track.id.rawId)
            guard !ids.isEmpty else { return }
            try? await model.library.add(songIds: ids, toPlaylist: id)
        }
    }
}
