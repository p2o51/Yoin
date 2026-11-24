import Foundation
import YoinShared

/*
 * 被提及 / Mentioned in (Android data/notes/NoteBacklinks.kt): for an album, every song note, album note and
 * album review of the account in use that mentions the album or one of its songs, newest first. A mention on
 * the album's own provider counts by id (and, on Spotify / Apple Music, by name when the id differs: library vs
 * catalogue ids, reissues); one on another provider by name (MentionMatcher through YoinNoteReader). Writings
 * on the album itself (its review, its album notes, its songs' notes) are left out: they are on that page.
 */

/// The album a Mentioned in section is for; [tracks] count toward it.
struct BacklinkAlbum: Equatable {
    struct Song: Equatable {
        var id: String
        var title: String
        var artist: String?
    }

    var provider: String
    var id: String
    var title: String
    var artist: String?
    var tracks: [Song]
}

/// Where a backlink was written (all on the account's own provider).
enum BacklinkSource: Hashable {
    /// [albumId]: the song's album when Yoin knows it (a play); [coverKey]: a play's stored cover.
    case songNote(noteId: String, trackId: String, title: String, artist: String, positionMs: Int64?,
                  albumId: String?, coverKey: String?)
    case albumNote(noteId: String, albumId: String, albumName: String, artist: String)
    /// [albumName] / [artist]: as the loaded album, Yoin's plays or album notes know them; nil when none does.
    case review(albumId: String, albumName: String?, artist: String?)

    /// The album it sits on, when known.
    var albumId: String? {
        switch self {
        case .songNote(_, _, _, _, _, let albumId, _): albumId
        case .albumNote(_, let albumId, _, _): albumId
        case .review(let albumId, _, _): albumId
        }
    }

    /// A stable order for writings written at the same moment.
    var sortKey: String {
        switch self {
        case .songNote(let id, _, _, _, _, _, _): "n:\(id)"
        case .albumNote(let id, _, _, _): "a:\(id)"
        case .review(let albumId, _, _): "r:\(albumId)"
        }
    }
}

/// One writing as the backlink search reads it.
struct BacklinkWriting: Equatable {
    var source: BacklinkSource
    var text: String
    /// Epoch ms.
    var at: Int64
}

/// One Mentioned in clipping: the writing, a one-line [snippet] and where the names pointing here sit in it
/// ([marks], UTF-16 offsets into [snippet]).
struct NoteBacklink: Identifiable, Equatable {
    var source: BacklinkSource
    var text: String
    var snippet: String
    var at: Int64
    var marks: [Range<Int>]

    var id: String { source.sortKey }
}

enum NoteBacklinks {
    /// Services whose same-service mentions with another id are matched by name too (Android BacklinkNameMatchedServices).
    static let nameMatchedServices: Set<String> = [SpotifyMapping.provider, AppleMusicMapping.provider]

    /// The writings among [writings] that mention [album], newest first. Pure.
    static func find(_ writings: [BacklinkWriting], album: BacklinkAlbum) -> [NoteBacklink] {
        let reader = YoinNoteReader.shared
        return writings
            .filter { !owns(album, $0.source) && reader.hasMentions(text: $0.text) }
            .compactMap { writing -> NoteBacklink? in
                guard reader.mentions(text: writing.text).contains(where: { isMentioned(album, by: $0) }) else { return nil }
                let snippet = reader.snippetWithMentions(text: writing.text)
                let marks = snippet.mentions.filter { isMentioned(album, by: $0.mention) }
                    .map { Int($0.start)..<Int($0.end) }
                return NoteBacklink(source: writing.source, text: writing.text, snippet: snippet.text, at: writing.at,
                                    marks: marks)
            }
            .sorted { $0.at != $1.at ? $0.at > $1.at : $0.source.sortKey < $1.source.sortKey }
    }

    /// Whether [mention] points at [album] or one of its songs (Android BacklinkTarget.isMentionedBy).
    static func isMentioned(_ album: BacklinkAlbum, by mention: YoinNoteMention) -> Bool {
        let sameProvider = mention.provider == album.provider
        if sameProvider {
            switch mention.kind {
            case .album where mention.id == album.id: return true
            case .track where album.tracks.contains(where: { $0.id == mention.id }): return true
            default: break
            }
            if !nameMatchedServices.contains(album.provider) { return false }
        }
        let reader = YoinNoteReader.shared
        switch mention.kind {
        case .album:
            return reader.namesMatchAlbum(mention: mention, title: album.title, artist: album.artist)
        case .track:
            return reader.trackMentionOnAlbum(mention: mention, albumTitle: album.title, albumArtist: album.artist)
                || album.tracks.contains {
                    reader.namesMatchTrack(mention: mention, title: $0.title, artist: $0.artist ?? album.artist,
                                           album: album.title)
                }
        default:
            return false
        }
    }

    /// A writing already on the album's own page.
    private static func owns(_ album: BacklinkAlbum, _ source: BacklinkSource) -> Bool {
        switch source {
        case .albumNote(_, let albumId, _, _), .review(let albumId, _, _):
            return albumId == album.id
        case .songNote(_, let trackId, _, _, _, let albumId, _):
            return albumId == album.id || album.tracks.contains { $0.id == trackId }
        }
    }

    // MARK: The account's writings

    /// The account in use's song notes, album notes and reviews on [provider] (read in a body: a change redraws).
    @MainActor
    static func writings(provider: String, scope: String) -> [BacklinkWriting] {
        let history = HistoryStore.shared
        let plays = history.plays(scope: scope, provider: provider)
        var out: [BacklinkWriting] = []
        for n in NotesStore.shared.notes where n.provider == provider && YoinNoteReader.shared.hasMentions(text: n.content) {
            let play = history.mostRecentPlay(scope: scope, provider: provider, songId: n.trackId)
            let albumId = play?.albumId.flatMap { $0.isEmpty || $0.hasPrefix("song:") ? nil : $0 }
            out.append(BacklinkWriting(source: .songNote(noteId: n.id, trackId: n.trackId, title: n.title, artist: n.artist,
                                                         positionMs: n.positionMs, albumId: albumId, coverKey: play?.coverKey),
                                       text: n.content, at: n.createdAt))
        }
        let albumNotes = AlbumNotesStore.shared.notes(provider: provider)
        for n in albumNotes where YoinNoteReader.shared.hasMentions(text: n.content) {
            out.append(BacklinkWriting(source: .albumNote(noteId: n.id, albumId: n.albumId, albumName: n.albumName,
                                                          artist: n.artist),
                                       text: n.content, at: n.createdAt))
        }
        for r in AlbumMemoryStore.shared.albums.values where r.provider == provider {
            guard let review = r.review, YoinNoteReader.shared.hasMentions(text: review) else { continue }
            let loaded = AppModel.shared.store.album(r.albumId)
            let play = plays.last { $0.albumId == r.albumId }
            let note = albumNotes.first { $0.albumId == r.albumId }
            let name = loaded?.title ?? play?.album ?? note?.albumName
            let artist = loaded?.artist ?? play?.artist ?? note?.artist
            out.append(BacklinkWriting(source: .review(albumId: r.albumId, albumName: name?.isEmpty == false ? name : nil,
                                                       artist: artist?.isEmpty == false ? artist : nil),
                                       text: review, at: r.reviewUpdatedAt ?? r.updatedAt))
        }
        return out
    }

    /// The Mentioned in clippings for [album] on the account in use (the mock library reads its own "mock" notes).
    @MainActor
    static func current(_ album: Album) -> [NoteBacklink] {
        let store = AppModel.shared.store
        let provider = album.id.provider
        guard provider == (store.isReal ? store.provider : "mock") else { return [] }
        let target = BacklinkAlbum(provider: provider, id: album.id.rawId, title: album.title, artist: album.artist,
                                   tracks: album.tracks.map { .init(id: $0.id.rawId, title: $0.title, artist: $0.artist) })
        return find(writings(provider: provider, scope: store.isReal ? store.historyScope : ""), album: target)
    }
}
