import SwiftUI
import YoinShared

/*
 * The search's All page and Memories list as Home's Jump Back In tiles (Android ui/library/LibrarySearchTiles.kt;
 * owner 2026-11-20, #Yoin:#18 prototype v3.1). iOS draws covers bare (EntityBackdrop, Home's iOS decision) where
 * Android sets them on Material shapes. A song met through a note, an album through its memory's words, is a 1×2
 * with the words beside the cover and the hit inked like a marker pen. The layout (sections, best match, packing,
 * folding) is :shared's `YoinSearchPage`; tiles point back into the service's rows.
 */

/// The service's rows the page's tiles point into (SearchView's results).
struct SearchResultSet {
    var artists: [SearchView.ArtistHit] = []
    var albums: [Album] = []
    var playlists: [LibraryItem] = []
    var tracks: [(Album, Track)] = []

    var isEmpty: Bool { artists.isEmpty && albums.isEmpty && playlists.isEmpty && tracks.isEmpty }

    var names: SearchServiceNames {
        SearchServiceNames(songs: tracks, albums: albums, artists: artists.map { ($0.id, $0.name) }, playlists: playlists)
    }
}

/// What a tile does when tapped: the surface's navigation and playback, bound once (Android SearchTileActions).
struct SearchTileActions {
    var onSong: (Int) -> Void
    /// A note: [song] = the index of the returned song its tile is on, if any.
    var onNote: (_ note: YoinNoteHit, _ song: Int?, _ anchor: String) -> Void
    /// A returned album; [memory] = it holds one (opened on the scrapbook).
    var onAlbum: (_ index: Int, _ memory: Bool) -> Void
    /// An album known only from the ledger (another account's, or not returned): its scrapbook.
    var onLedgerAlbum: (_ album: YoinLedgerAlbum, _ anchor: String) -> Void
    var onArtist: (Int) -> Void
    var onPlaylist: (Int) -> Void
    var onShowAll: (YoinSearchSection) -> Void
}

enum SearchTileMetrics {
    static let gap: CGFloat = 12
    static let rowGap: CGFloat = 16
    static let bestArt: CGFloat = 104

    /// A cell's side for [columns] across [width] (inside the margins).
    static func cell(width: CGFloat, columns: Int) -> CGFloat {
        max(1, (width - gap * CGFloat(columns - 1)) / CGFloat(max(columns, 1)))
    }
}

/// The All page: the best-match card, then each section as rows of tiles, best section first.
struct SearchAllTiles: View {
    var page: YoinSearchPage
    var query: String
    var results: SearchResultSet
    var memories: SearchMemories
    /// "N less relevant from Spotify"; nil hides that line (a library the service doesn't rank).
    var providerName: String?
    var width: CGFloat
    var columns: Int
    var scheme: CoverScheme
    var actions: SearchTileActions
    var gate: AccountSwitchGate

    var body: some View {
        let cell = SearchTileMetrics.cell(width: width, columns: columns)
        VStack(alignment: .leading, spacing: 0) {
            if let best = page.best {
                SearchBestMatchCard(best: best, query: query, results: results, page: page, scheme: scheme,
                                    actions: actions)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
            }
            ForEach(page.sections, id: \.section) { section in
                if !section.rows.isEmpty {
                    let shown = section.rows.reduce(0) { $0 + $1.tiles.count }
                    SearchTileHeader(title: Self.title(section.section), showAll: section.tiles.count > shown,
                                     scheme: scheme) { actions.onShowAll(section.section) }
                    ForEach(section.rows, id: \.rowKey) { row in
                        SearchTileRowView(row: row, cell: cell, query: query, results: results, memories: memories,
                                          scheme: scheme, actions: actions, gate: gate)
                            .padding(.bottom, SearchTileMetrics.rowGap)
                    }
                    if section.folded > 0, let providerName {
                        SearchFoldedNote(count: Int(section.folded), providerName: providerName, scheme: scheme) {
                            actions.onShowAll(section.section)
                        }
                        .padding(.top, -4)
                    }
                }
            }
        }
    }

    static func title(_ section: YoinSearchSection) -> String {
        if section == .songs { return String(localized: "Songs") }
        if section == .albums { return String(localized: "Albums") }
        if section == .artists { return String(localized: "Artists") }
        return String(localized: "Playlists")
    }
}

/// The Memories chip: every note and memory the query met, one 1×2 a row on a phone (two side by side where the
/// window holds six columns or more).
struct SearchMemoriesList: View {
    var page: YoinSearchPage
    var query: String
    var results: SearchResultSet
    var memories: SearchMemories
    var width: CGFloat
    var columns: Int
    var scheme: CoverScheme
    var actions: SearchTileActions
    var gate: AccountSwitchGate

    var body: some View {
        let cell = SearchTileMetrics.cell(width: width, columns: columns)
        let perRow = max(1, columns / 3)
        let tileWidth = perRow == 1 ? width : cell * 3 + SearchTileMetrics.gap * 2
        let tiles = page.memories
        VStack(alignment: .leading, spacing: SearchTileMetrics.rowGap) {
            ForEach(Array(stride(from: 0, to: tiles.count, by: perRow)), id: \.self) { start in
                HStack(alignment: .top, spacing: SearchTileMetrics.gap) {
                    ForEach(tiles[start..<min(start + perRow, tiles.count)], id: \.key) { tile in
                        SearchTileView(tile: tile, cell: cell, width: tileWidth, query: query, results: results,
                                       memories: memories, scheme: scheme, actions: actions, gate: gate, textLines: 6)
                    }
                }
            }
        }
        .padding(.top, 8)
    }
}

/// "Nothing you've written matches." under the Memories chip.
struct SearchNoMemories: View {
    var scheme: CoverScheme

    var body: some View {
        Text("Nothing you've written matches.")
            .yoinRole(Tokens.type.bodyLarge)
            .foregroundStyle(scheme.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
    }
}

/// A section's header: its name, and Show all when its chip lists more than All shows.
private struct SearchTileHeader: View {
    var title: String
    var showAll: Bool
    var scheme: CoverScheme
    var onShowAll: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
            Spacer(minLength: 8)
            if showAll {
                Button(action: onShowAll) {
                    Text("Show all")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain).pointerHighlight()
                .padding(.trailing, -12)
            }
        }
        .frame(minHeight: 20)
        .padding(.top, Tokens.search.SECTION_HEADER_TOP.cg)
        .padding(.bottom, Tokens.search.SECTION_HEADER_BOTTOM.cg)
    }
}

/// "7 less relevant from Spotify": the service's rows the query doesn't appear in, behind the section's chip.
private struct SearchFoldedNote: View {
    var count: Int
    var providerName: String
    var scheme: CoverScheme
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                Text("\(count) less relevant from \(providerName)")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("Show all")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.primary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(scheme.surfaceContainer, in: .rect(cornerRadius: 16, style: .continuous))
            .contentShape(.rect)
        }
        .buttonStyle(.plain).pointerHighlight()
        .padding(.bottom, SearchTileMetrics.rowGap)
    }
}

/// One packed row: its 1×2 first, or after the singles when the row alternates.
private struct SearchTileRowView: View {
    var row: YoinSearchTileRow
    var cell: CGFloat
    var query: String
    var results: SearchResultSet
    var memories: SearchMemories
    var scheme: CoverScheme
    var actions: SearchTileActions
    var gate: AccountSwitchGate

    var body: some View {
        let wide = row.tiles.filter(\.wide)
        let singles = row.tiles.filter { !$0.wide }
        let ordered = row.wideAtEnd ? singles + wide : wide + singles
        HStack(alignment: .top, spacing: SearchTileMetrics.gap) {
            ForEach(ordered, id: \.key) { tile in
                SearchTileView(tile: tile, cell: cell,
                               width: tile.wide ? cell * 2 + SearchTileMetrics.gap : cell,
                               query: query, results: results, memories: memories, scheme: scheme, actions: actions,
                               gate: gate)
            }
        }
    }
}

/// One tile, by kind.
struct SearchTileView: View {
    var tile: YoinSearchTile
    var cell: CGFloat
    var width: CGFloat
    var query: String
    var results: SearchResultSet
    var memories: SearchMemories
    var scheme: CoverScheme
    var actions: SearchTileActions
    var gate: AccountSwitchGate
    var textLines = 4

    var body: some View {
        content
            .accountSwitchNotice(gate, anchor: tile.key)
    }

    @ViewBuilder
    private var content: some View {
        let index = Int(tile.index)
        switch tile.kind {
        case .song where results.tracks.indices.contains(index):
            let (album, track) = results.tracks[index]
            let note = tile.note
            SearchTileFrame(
                width: width, cell: cell, query: query, scheme: scheme,
                title: track.title, subtitle: track.artist ?? album.artist,
                art: { EntityBackdrop(entity: .song, palette: album.palette, seed: album.seed, size: $0, stroke: scheme.primary) },
                side: note.map { n in AnyView(SearchNoteSide(note: n, scheme: scheme, lines: textLines)) },
                onTap: {
                    if let note { actions.onNote(note, index, tile.key) } else { actions.onSong(index) }
                })
            // The song's menu on a secondary click, as Home's song cover (JBICell).
            .onSecondaryClick { PlaylistActions.shared.openMenu(PlaylistEntry(album: album, track: track), context: .library) }
            .yoinDraggable { YoinDrag.song(PlaylistEntry(album: album, track: track), model: .shared) }
        case .note:
            if let note = tile.note {
                SearchTileFrame(
                    width: width, cell: cell, query: query, scheme: scheme,
                    title: note.trackTitle ?? "", subtitle: note.album?.artist ?? "", otherAccount: note.isOtherAccount,
                    art: { EntityBackdrop(entity: .song, palette: memories.cover(note.album), seed: 0, size: $0,
                                          stroke: scheme.primary) },
                    side: AnyView(SearchNoteSide(note: note, scheme: scheme, lines: textLines)),
                    onTap: { actions.onNote(note, nil, tile.key) })
            }
        case .album where results.albums.indices.contains(index):
            let album = results.albums[index]
            let mark = tile.attachment?.mark ?? tile.memory?.mark
            let onCover = CoverMarks.onCover(provider: album.id.provider)
            SearchTileFrame(
                width: width, cell: cell, query: query, scheme: scheme, title: album.title, subtitle: album.artist,
                inlineMark: onCover ? nil : tile.attachment?.mark,
                art: { size in
                    EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: size, stroke: scheme.primary)
                        .overlay(alignment: .topTrailing) {
                            if onCover, let m = tile.attachment?.mark { SearchCoverMark(mark: m, palette: album.palette, scheme: scheme) }
                        }
                },
                side: tile.memory.map { hit in AnyView(SearchMemorySide(hit: hit, mark: mark, scheme: scheme, lines: textLines)) },
                onTap: { actions.onAlbum(index, tile.attachment != nil) })
            .yoinDraggable { YoinDrag.album(album, model: .shared) }
        case .memory:
            if let hit = tile.memory {
                let onCover = CoverMarks.onCover(provider: hit.album.provider)
                let palette = memories.cover(hit.album)
                SearchTileFrame(
                    width: width, cell: cell, query: query, scheme: scheme, title: hit.album.name,
                    subtitle: hit.album.artist, otherAccount: hit.isOtherAccount,
                    inlineMark: onCover ? nil : hit.mark,
                    art: { size in
                        EntityBackdrop(entity: .album, palette: palette, seed: 0, size: size, stroke: scheme.primary)
                            .overlay(alignment: .topTrailing) {
                                if onCover, let m = hit.mark { SearchCoverMark(mark: m, palette: palette, scheme: scheme) }
                            }
                    },
                    side: tile.wide ? AnyView(SearchMemorySide(hit: hit, mark: hit.mark, scheme: scheme, lines: textLines)) : nil,
                    onTap: { actions.onLedgerAlbum(hit.album, tile.key) })
            }
        case .artist where results.artists.indices.contains(index):
            let a = results.artists[index]
            SearchTileFrame(
                width: width, cell: cell, query: query, scheme: scheme, title: a.name, subtitle: nil, centered: true,
                art: { EntityBackdrop(entity: .artist, palette: a.palette, seed: a.seed, size: $0, stroke: scheme.primary) },
                side: nil, onTap: { actions.onArtist(index) })
        case .playlist where results.playlists.indices.contains(index):
            let p = results.playlists[index]
            SearchTileFrame(
                width: width, cell: cell, query: query, scheme: scheme, title: p.title, subtitle: p.owner,
                art: { EntityBackdrop(entity: .playlist, palette: p.palette, seed: 3, size: $0, stroke: scheme.primary) },
                side: nil, onTap: { actions.onPlaylist(index) })
            .yoinDraggable { YoinDrag.playlist(p, model: .shared) }
        default:
            EmptyView()
        }
    }
}

/// A tile: the cover [cell] square (with [side] beside it on a 1×2), then the caption under both. The whole tile
/// is one press target; the cover takes the press, as on Home.
private struct SearchTileFrame<Art: View>: View {
    var width: CGFloat
    var cell: CGFloat
    var query: String
    var scheme: CoverScheme
    var title: String
    var subtitle: String?
    var otherAccount = false
    var centered = false
    var inlineMark: YoinLedgerMark? = nil
    @ViewBuilder var art: (CGFloat) -> Art
    var side: AnyView?
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: centered ? .center : .leading, spacing: 0) {
                HStack(alignment: .top, spacing: SearchTileMetrics.gap) {
                    art(cell)
                    if let side {
                        side
                            .padding(.top, 2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                Spacer().frame(height: 6)
                HStack(spacing: 4) {
                    if let inlineMark { SearchInlineMark(mark: inlineMark, ink: scheme.tertiary) }
                    MarkedText(text: title, query: query, role: Tokens.type.titleSmall, color: scheme.onSurface,
                               ink: scheme.tertiaryContainer, onInk: scheme.onTertiaryContainer, lines: 1)
                }
                if (subtitle?.nonBlank != nil) || otherAccount {
                    HStack(spacing: 4) {
                        if otherAccount {
                            Circle().fill(scheme.secondary).frame(width: 8, height: 8)
                        }
                        if let subtitle, subtitle.nonBlank != nil {
                            MarkedText(text: subtitle, query: query, role: Tokens.type.bodySmall,
                                       color: scheme.onSurfaceVariant, ink: scheme.tertiaryContainer, onInk: scheme.onTertiaryContainer, lines: 1)
                        }
                    }
                }
            }
            .frame(width: width, alignment: centered ? .center : .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .pointerHighlight()
    }
}

/// A note beside its song's cover: "Your note", where it was written, and the note with the hit inked.
private struct SearchNoteSide: View {
    var note: YoinNoteHit
    var scheme: CoverScheme
    var lines: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Your note")
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
            if let position = note.positionMs?.int64Value {
                Text(verbatim: Self.time(position))
                    .yoinRole(Tokens.type.headlineSmall)
                    .monospacedDigit()
                    .foregroundStyle(scheme.tertiary)
            }
            MarkedText(fragment: note.fragment, role: Tokens.type.bodyMedium, color: scheme.onSurface,
                       ink: scheme.tertiaryContainer, onInk: scheme.onTertiaryContainer, lines: lines)
        }
    }

    /// m:ss (Android formatNoteTime).
    static func time(_ ms: Int64) -> String {
        let s = ms / 1000
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// A memory's words beside its album's cover: its score and mark, its title, the words with the hit inked.
private struct SearchMemorySide: View {
    var hit: YoinMemoryHit
    var mark: YoinLedgerMark?
    var scheme: CoverScheme
    var lines: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            let score = mark?.score?.floatValue ?? hit.albumScore?.floatValue
            if mark != nil || score != nil {
                HStack(spacing: 6) {
                    if let mark { SearchInlineMark(mark: mark, ink: scheme.tertiary, size: 16) }
                    if let score {
                        Text(verbatim: String(format: "%.1f", score))
                            .yoinRole(Tokens.type.headlineSmall)
                            .monospacedDigit()
                            .foregroundStyle(scheme.tertiary)
                    }
                }
            }
            Text(hit.title ?? String(localized: "Your memory"))
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .lineLimit(1)
            MarkedText(fragment: hit.fragment, role: Tokens.type.bodyMedium, color: scheme.onSurface,
                       ink: scheme.tertiaryContainer, onInk: scheme.onTertiaryContainer, lines: lines)
        }
    }
}

/// The best match, large: the tile at hero size, what it is, its name, and a way in.
private struct SearchBestMatchCard: View {
    var best: YoinSearchBestMatch
    var query: String
    var results: SearchResultSet
    var page: YoinSearchPage
    var scheme: CoverScheme
    var actions: SearchTileActions

    private struct Shown {
        var title: String
        var subtitle: String
        var kind: String
        var art: AnyView
        var open: () -> Void
        var memory: YoinMemoryAttachment?
    }

    private var shown: Shown? {
        let i = Int(best.index)
        let size = SearchTileMetrics.bestArt
        switch best.kind {
        case .song where results.tracks.indices.contains(i):
            let (album, track) = results.tracks[i]
            return Shown(title: track.title, subtitle: track.artist ?? album.artist, kind: String(localized: "Songs"),
                         art: AnyView(EntityBackdrop(entity: .song, palette: album.palette, seed: album.seed, size: size,
                                                     stroke: scheme.primary)),
                         open: { actions.onSong(i) })
        case .album where results.albums.indices.contains(i):
            let album = results.albums[i]
            let memory = attachment(album)
            let onCover = CoverMarks.onCover(provider: album.id.provider)
            return Shown(title: album.title, subtitle: album.artist, kind: String(localized: "Albums"),
                         art: AnyView(EntityBackdrop(entity: .album, palette: album.palette, seed: album.seed, size: size,
                                                     stroke: scheme.primary)
                             .overlay(alignment: .topTrailing) {
                                 if onCover, let m = memory?.mark { SearchCoverMark(mark: m, palette: album.palette, scheme: scheme) }
                             }),
                         open: { actions.onAlbum(i, memory != nil) }, memory: memory)
        case .artist where results.artists.indices.contains(i):
            let a = results.artists[i]
            return Shown(title: a.name, subtitle: "", kind: String(localized: "Artists"),
                         art: AnyView(EntityBackdrop(entity: .artist, palette: a.palette, seed: a.seed, size: size,
                                                     stroke: scheme.primary)),
                         open: { actions.onArtist(i) })
        case .playlist where results.playlists.indices.contains(i):
            let p = results.playlists[i]
            return Shown(title: p.title, subtitle: p.owner ?? "", kind: String(localized: "Playlists"),
                         art: AnyView(EntityBackdrop(entity: .playlist, palette: p.palette, seed: 3, size: size,
                                                     stroke: scheme.primary)),
                         open: { actions.onPlaylist(i) })
        default:
            return nil
        }
    }

    /// The memory kept on [album] (the Albums section's tile for it carries it).
    private func attachment(_ album: Album) -> YoinMemoryAttachment? {
        page.sections.lazy.flatMap(\.tiles).first { $0.kind == .album && Int($0.index) == Int(best.index) }?.attachment
    }

    var body: some View {
        if let shown {
            Button(action: shown.open) {
                HStack(spacing: 16) {
                    shown.art
                    VStack(alignment: .leading, spacing: 0) {
                        Text(shown.kind)
                            .yoinRole(Tokens.type.labelMedium)
                            .foregroundStyle(scheme.onSurfaceVariant)
                        MarkedText(text: shown.title, query: query, role: Tokens.type.headlineSmall,
                                   color: scheme.onSurface, ink: scheme.tertiaryContainer, onInk: scheme.onTertiaryContainer, lines: 2)
                        if shown.subtitle.nonBlank != nil {
                            Text(shown.subtitle)
                                .yoinRole(Tokens.type.bodyMedium)
                                .foregroundStyle(scheme.onSurfaceVariant)
                                .lineLimit(1)
                        }
                        if let memory = shown.memory {
                            let score = (memory.albumScore ?? memory.mark?.score).map { String(format: "%.1f", $0.floatValue) }
                            let line = [memory.title, score].compactMap { $0 }.joined(separator: " · ")
                            HStack(spacing: 6) {
                                if let m = memory.mark { SearchInlineMark(mark: m, ink: scheme.tertiary) }
                                if !line.isEmpty {
                                    Text(verbatim: line)
                                        .yoinRole(Tokens.type.bodyMedium)
                                        .foregroundStyle(scheme.tertiary)
                                        .lineLimit(1)
                                }
                            }
                            .padding(.top, 6)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(16)
                .background(scheme.surfaceContainer, in: .rect(cornerRadius: 28, style: .continuous))
                .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .pointerHighlight()
        }
    }
}

extension YoinSearchTileRow {
    /// Stable across reloads: its tiles' keys.
    var rowKey: String { tiles.map(\.key).joined(separator: "|") }
}

// MARK: Marks

extension YoinLedgerMark {
    /// Home's mark for this memory: your score filled, your tracks' average a line, notes only dashed.
    var homeMark: AlbumMemoryMark {
        if kind == .albumrating { return .solid }
        if kind == .trackaverage { return .outline }
        return .dashed
    }
}

/// The memory mark on a cover's corner, as Home draws it.
private struct SearchCoverMark: View {
    var mark: YoinLedgerMark
    var palette: CoverPalette
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        MemoryMarkView(mark: mark.homeMark, ink: cardInk(palette, dark: colorScheme == .dark), bg: scheme.background)
    }
}

/// The memory mark beside a title instead of on the cover: Spotify's covers carry nothing (CoverMarks). Filled =
/// your score, a line = your tracks' average, dashed = notes only.
private struct SearchInlineMark: View {
    var mark: YoinLedgerMark
    var ink: Color
    var size: CGFloat = 12

    var body: some View {
        let cookie = CookieShape(lobes: 12, depth: 0.12)
        Group {
            switch mark.homeMark {
            case .solid: cookie.fill(ink)
            case .outline: cookie.stroke(ink, lineWidth: 1.25)
            case .dashed: cookie.stroke(ink, style: StrokeStyle(lineWidth: 1.25, dash: [2, 1.5]))
            }
        }
        .frame(width: size, height: size)
    }
}

// MARK: Marker-pen highlight

/// Marks the hit's characters for [MarkerRenderer].
struct MarkerHitAttribute: TextAttribute {}

/// [text] with the query's hit inked like a marker pen (Android MarkedText): a band over the lower part of each
/// line the hit covers, a little slanted and ragged, in the theme's tertiary container, under the glyphs. Ranges
/// are UTF-16 [start, end), -1 / -1 for none.
struct MarkedText: View {
    var text: String
    var start: Int
    var end: Int
    var role: YoinTokens.TypographyStyle
    var color: Color
    var ink: Color
    /// The hit's glyphs on the ink (on a dark scheme the ink is light: the text's own colour would vanish).
    var onInk: Color
    var lines: Int

    init(text: String, query: String, role: YoinTokens.TypographyStyle, color: Color, ink: Color, onInk: Color,
         lines: Int) {
        let m = YoinMemorySearch.companion.match(query: query, text: text)
        self.init(text: text, start: Int(m.start), end: Int(m.end), role: role, color: color, ink: ink, onInk: onInk,
                  lines: lines)
    }

    init(fragment: YoinSearchFragment, role: YoinTokens.TypographyStyle, color: Color, ink: Color, onInk: Color,
         lines: Int) {
        self.init(text: fragment.text, start: Int(fragment.highlightStart), end: Int(fragment.highlightEnd), role: role,
                  color: color, ink: ink, onInk: onInk, lines: lines)
    }

    init(text: String, start: Int, end: Int, role: YoinTokens.TypographyStyle, color: Color, ink: Color, onInk: Color,
         lines: Int) {
        self.text = text
        self.start = start
        self.end = end
        self.role = role
        self.color = color
        self.ink = ink
        self.onInk = onInk
        self.lines = lines
    }

    /// [text] cut at a UTF-16 [start, end): before, the hit, after; nil when there is no hit.
    static func split(_ text: String, start: Int, end: Int) -> (String, String, String)? {
        let ns = text as NSString
        guard start >= 0, end > start, end <= ns.length else { return nil }
        return (ns.substring(to: start), ns.substring(with: NSRange(location: start, length: end - start)),
                ns.substring(from: end))
    }

    var body: some View {
        Group {
            if let (before, hit, after) = Self.split(text, start: start, end: end) {
                Text("\(Text(verbatim: before))\(Text(verbatim: hit).foregroundStyle(onInk).customAttribute(MarkerHitAttribute()))\(Text(verbatim: after))")
                    .textRenderer(MarkerRenderer(ink: ink))
            } else {
                Text(verbatim: text)
            }
        }
        .modifier(MarkedTextRole(role: role, wraps: lines > 1))
        .foregroundStyle(color)
        .lineLimit(lines)
        .truncationMode(.tail)
    }
}

/// One line: the role's line box; wrapping: the role's line height on every line (Home's cards), at the height
/// its lines take.
private struct MarkedTextRole: ViewModifier {
    var role: YoinTokens.TypographyStyle
    var wraps: Bool

    func body(content: Content) -> some View {
        if wraps {
            content.homeType(role).fixedSize(horizontal: false, vertical: true)
        } else {
            content.yoinRole(role)
        }
    }
}

/// Draws a marker band behind each run of the hit, then the text as laid out (Android drawMarker).
struct MarkerRenderer: TextRenderer {
    var ink: Color

    func draw(layout: Text.Layout, in ctx: inout GraphicsContext) {
        for line in layout {
            var band: CGRect?
            for run in line where run[MarkerHitAttribute.self] != nil {
                let r = run.typographicBounds.rect
                band = band.map { $0.union(r) } ?? r
            }
            if let r = band {
                let h = r.height
                let left = r.minX - h * 0.12, right = r.maxX + h * 0.12
                var path = Path()
                path.move(to: CGPoint(x: left, y: r.minY + h * 0.40))
                path.addLine(to: CGPoint(x: right, y: r.minY + h * 0.34))
                path.addLine(to: CGPoint(x: right - h * 0.04, y: r.maxY - h * 0.06))
                path.addLine(to: CGPoint(x: left + h * 0.05, y: r.maxY - h * 0.02))
                path.closeSubpath()
                ctx.fill(path, with: .color(ink))
                ctx.stroke(path, with: .color(ink), style: StrokeStyle(lineWidth: h * 0.10, lineJoin: .round))
            }
            ctx.draw(line)
        }
    }
}
