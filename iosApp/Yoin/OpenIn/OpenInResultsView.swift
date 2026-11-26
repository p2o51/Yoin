import SwiftUI

/// What the Choose a match page opens on: the link (followed if it was short) and what the card already read.
struct OpenInResultsRequest: Hashable, Sendable {
    var link: OpenInLink
    var metadata: MusicLinkMetadata?
}

/// One row of Choose a match.
enum OpenInResultRow: Identifiable {
    case song(PlaylistEntry)
    case album(Album)
    case artist(LibraryItem)
    case playlist(LibraryItem)

    var id: String {
        switch self {
        case .song(let e): "t:\(e.track.id.provider):\(e.track.id.rawId)"
        case .album(let a): "al:\(a.id.provider):\(a.id.rawId)"
        case .artist(let a): "ar:\(a.id.provider):\(a.id.rawId)"
        case .playlist(let p): "pl:\(p.id.provider):\(p.id.rawId)"
        }
    }
}

/// A found link searched on the account in use (Android OpenInResultsViewModel): the link's title (read
/// without login when the card hadn't already), the account's search, then OpenInMatcher's ranking. Nothing
/// plays by itself here: the user picks.
@MainActor
@Observable
final class OpenInResultsModel {
    enum State {
        case loading
        /// [exact]: see OpenInMatcher; [service]: the account in use's service, which the page names.
        case results([OpenInResultRow], exact: Bool, service: String)
        case empty(service: String)
    }

    private(set) var state: State = .loading

    /// The account in use's service, as the page names it (Spotify, Apple Music, Subsonic).
    static func serviceName(_ store: LibraryStore) -> String {
        if store.apple != nil { return Profile.Service.appleMusic.name }
        if store.spotify != nil { return Profile.Service.spotify.name }
        return Profile.Service.subsonic.name
    }

    func load(_ request: OpenInResultsRequest, store: LibraryStore, lookup: MusicLinkLookup) async {
        let service = Self.serviceName(store)
        let empty = State.empty(service: service)
        guard store.isReal, let link = await lookup.resolve(request.link) else { state = empty; return }
        var metadata = request.metadata.flatMap { $0.isEmpty || $0.kind != link.kind ? nil : $0 }
        if metadata == nil { metadata = await lookup.metadata(link) }
        guard let metadata, let title = metadata.title, !metadata.isEmpty else { state = empty; return }
        let rows: [OpenInResultRow]
        let exact: Bool
        switch link.kind {
        case .track:
            let source = OpenInMatcher.sourceTrack(metadata)
            var byIsrc: [PlaylistEntry] = []
            if let isrc = metadata.isrc { byIsrc = await store.openInSongs(isrc: isrc) }
            let query = OpenInMatcher.searchQuery(metadata) ?? title
            var searched = await store.openInSearch(query)?.songs ?? []
            if searched.isEmpty, let titleOnly = OpenInMatcher.titleQuery(metadata), titleOnly != query {
                searched = await store.openInSearch(titleOnly)?.songs ?? []
            }
            let ranking = OpenInMatcher.rankTracks(source, byIsrc: byIsrc, searched: searched)
            (rows, exact) = (ranking.items.map(OpenInResultRow.song), ranking.exact)
        case .album:
            let artist = metadata.artist.flatMap { $0.isEmpty ? nil : $0 }
            var albums = await store.openInSearch([title, artist].compactMap { $0 }.joined(separator: " "))?.albums ?? []
            if albums.isEmpty, artist != nil { albums = await store.openInSearch(title)?.albums ?? [] }
            let ranking = OpenInMatcher.rankAlbums(name: title, artist: artist, albums)
            (rows, exact) = (ranking.items.map(OpenInResultRow.album), ranking.exact)
        case .artist:
            let ranking = OpenInMatcher.rankArtists(name: title, await store.openInSearch(title)?.artists ?? [])
            (rows, exact) = (ranking.items.map(OpenInResultRow.artist), ranking.exact)
        case .playlist:
            let ranking = OpenInMatcher.rankPlaylists(name: title, owner: metadata.artist,
                                                      await store.openInSearch(title)?.playlists ?? [])
            (rows, exact) = (ranking.items.map(OpenInResultRow.playlist), ranking.exact)
        case .shortLink:
            state = empty
            return
        }
        state = rows.isEmpty ? empty : .results(rows, exact: exact, service: service)
    }

    /// QA screenshots: the page as it would be once read.
    func show(_ state: State) { self.state = state }
}

/// Choose a match (Android OpenInResultsActivity, a PushPage): the account in use's closest results for a
/// link from another service, best first. The note says the match couldn't be confirmed, unless the best row
/// is the same item by the playlist-transfer rules (then the list just leads with it).
struct OpenInResultsView: View {
    var request: OpenInResultsRequest

    @Environment(AppModel.self) private var model
    @State private var results = OpenInResultsModel()

    var body: some View {
        SettingsPage(title: String(localized: "Choose a match")) { app, _ in
            content(app)
                .animation(Yoin.effects, value: stateKey)
        }
        .task(id: "\(model.store.generation)|\(request.link.key)") {
            #if DEBUG
            if let demo = OpenInDemo.results(for: request) { results.show(demo); return }
            #endif
            await results.load(request, store: model.store, lookup: model.openIn.lookup)
        }
    }

    private var stateKey: Int {
        switch results.state {
        case .loading: 0
        case .results: 1
        case .empty: 2
        }
    }

    @ViewBuilder
    private func content(_ app: CoverScheme) -> some View {
        switch results.state {
        case .loading:
            ProgressView()
                .tint(app.primary)
                .frame(maxWidth: .infinity)
                .padding(.top, 24)
                .transition(.opacity)
        case .empty(let service):
            note(String(localized: "No matching results found on \(service)."), app)
                .transition(.opacity)
        case .results(let rows, let exact, let service):
            VStack(alignment: .leading, spacing: 0) {
                if !exact {
                    note(String(localized: "Couldn't confirm an exact match. Here are the closest results on \(service)."), app)
                }
                ForEach(rows) { row($0, app) }
            }
            .transition(.opacity)
        }
    }

    private func note(_ text: String, _ app: CoverScheme) -> some View {
        Text(text)
            .yoinRole(Tokens.type.bodyLarge)
            .foregroundStyle(app.onSurfaceVariant)
            .padding(.horizontal, 8)
            .padding(.bottom, 16)
    }

    @ViewBuilder
    private func row(_ row: OpenInResultRow, _ app: CoverScheme) -> some View {
        switch row {
        case .song(let e):
            // Only the song picked plays (Android playSearchResults(listOf(track))).
            LibrarySongRow(album: e.album, track: e.track, current: model.playback.track.id == e.track.id,
                           scheme: app, horizontalPadding: 8)
                .trackRow(for: e) { model.play([e], startAt: 0) }
        case .album(let a):
            entity(a.title, a.artist, palette: a.palette, round: false, app) { model.open(a) }
        case .artist(let a):
            entity(a.title, nil, palette: a.palette, round: true, app) { model.openArtist(a.title, id: a.id.rawId) }
        case .playlist(let p):
            entity(p.title, p.owner ?? p.subtitle, palette: p.palette, round: false, app) { model.openPlaylist(p.id.rawId) }
        }
    }

    /// Android EntityRow: 16 × 10, a 48 thumb (round for an artist), titleSmall over bodySmall.
    private func entity(_ title: String, _ subtitle: String?, palette: CoverPalette, round: Bool, _ app: CoverScheme,
                        open: @escaping () -> Void) -> some View {
        Button(action: open) {
            HStack(spacing: Tokens.library.ROW_GAP.cg) {
                CoverArt(palette: palette, cornerRadius: 0, seed: 3)
                    .frame(width: Tokens.library.ROW_THUMB.cg, height: Tokens.library.ROW_THUMB.cg)
                    .clipShape(round ? AnyShape(.circle) : AnyShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous)))
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleSmall)
                        .foregroundStyle(app.onSurface)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .lineLimit(1)
                            .yoinRole(Tokens.type.bodySmall)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, Tokens.library.ROW_PADDING_V.cg)
            .contentShape(.rect)
        }
        .buttonStyle(.plain).pointerHighlight()
    }
}
