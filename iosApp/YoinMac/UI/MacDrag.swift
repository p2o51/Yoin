import AppKit
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    /// A song, album or playlist dragged inside Yoin (declared in the Mac Info.plist).
    static let yoinItem = UTType(exportedAs: "com.gpo.yoin.item")
}

/// What a drag carries: the kind, an opaque token for the account it came from (never its server or user name),
/// its id, and its words. Inside Yoin it drops on the
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
    /// Account key → a random token for this run: what a drag says about its account.
    private static var tokens: [String: String] = [:]

    static func token(_ accountKey: String) -> String {
        if let t = tokens[accountKey] { return t }
        let t = UUID().uuidString
        tokens[accountKey] = t
        return t
    }

    /// An account switch: the remembered items go (they belong to the old account).
    static func reset() {
        songs.removeAll()
        albums.removeAll()
    }

    /// Kept small: only what was dragged lately.
    private static func trim() {
        if songs.count > 300 { songs.removeAll() }
        if albums.count > 60 { albums.removeAll() }
    }

    static func song(_ e: PlaylistEntry, scope: String) -> YoinDragItem {
        trim()
        songs[e.track.id.rawId] = e
        return YoinDragItem(kind: .song, scope: token(scope), id: e.track.id.rawId,
                            text: "\(e.track.title) — \(e.track.artist ?? e.album.artist)")
    }

    static func album(_ a: Album, scope: String) -> YoinDragItem {
        trim()
        albums[a.id.rawId] = a
        return YoinDragItem(kind: .album, scope: token(scope), id: a.id.rawId, text: "\(a.title) — \(a.artist)")
    }

    static func playlist(_ p: LibraryItem, scope: String) -> YoinDragItem {
        YoinDragItem(kind: .playlist, scope: token(scope), id: p.id.rawId, text: p.title)
    }

    /// The songs the items stand for, in order; nothing from another account (or another run).
    static func entries(_ items: [YoinDragItem], model: MacModel) async -> [PlaylistEntry] {
        let scope = token(model.session.accountKey)
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

    /// Adds the items to the queue: after what's queued, or right before the queue entry [before] when dropped
    /// on its row (found again after each insert, so loading and other changes meanwhile don't shift it).
    static func queue(_ items: [YoinDragItem], before: UUID?, model: MacModel) {
        Task {
            let entries = await entries(items, model: model)
            for e in entries {
                let had = Set(model.player.queue.map(\.id))
                model.addToQueue(e)
                let q = model.player.queue
                guard let anchor = before, let added = q.firstIndex(where: { !had.contains($0.id) }),
                      let at = q.firstIndex(where: { $0.id == anchor }) else { continue }
                // Removing [added] first shifts the anchor left when it sat after it.
                model.player.move(from: added, to: added < at ? at - 1 : at)
            }
        }
    }

    /// A playlist a drop may write into: the account's own (Subsonic: owned by this user; Spotify: owned or
    /// collaborative).
    static func accepts(_ p: LibraryItem, model: MacModel) -> Bool {
        if model.session.isSpotify { return model.library.writablePlaylists.contains { $0.id == p.id } }
        guard model.session.client != nil, let user = model.session.account?.username else { return false }
        return p.owner == nil || p.owner?.isEmpty == true || p.owner == user
    }

    /// Adds the items' songs to playlist [id] (a writable one of the same account).
    static func add(_ items: [YoinDragItem], toPlaylist id: String, model: MacModel) {
        Task {
            let ids = await entries(items, model: model).map(\.track.id.rawId)
            guard !ids.isEmpty else { return }
            do {
                try await model.library.add(songIds: ids, toPlaylist: id)
            } catch {
                // Said, not swallowed: the server or Spotify refused the songs.
                let alert = NSAlert()
                alert.messageText = "Couldn't add to the playlist"
                alert.informativeText = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                alert.runModal()
            }
        }
    }
}
