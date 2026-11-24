import SwiftUI
import YoinShared

/// The Mentioned in section's head (Android BacklinksSection): an @ on a cookie of the ink, the title and the
/// count.
struct ScrapBacklinksHeader: View {
    var count: Int
    var scheme: CoverScheme

    var body: some View {
        HStack(spacing: 10) {
            YoinIcon("at", size: 18)
                .foregroundStyle(scheme.onPrimary)
                .frame(width: 30, height: 30)
                .background(scheme.primary, in: CookieShape())
            Text("Mentioned in")
                .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                .foregroundStyle(scheme.onSurface)
            Text("\(count) places")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurface.opacity(0.6))
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// One clipping (Android BacklinkClipping): the source's cover, its name, "Note · 1:23 · 3 days ago", and a
/// line of the writing with the names that point here marked as by a highlighter. A tap opens where it was
/// written (the caller's action).
struct ScrapBacklinkCard: View {
    var link: NoteBacklink
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        let name = Self.name(link.source)
        let meta = Self.meta(link, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    cover
                        .frame(width: 34, height: 34)
                        .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                    VStack(alignment: .leading, spacing: 0) {
                        Text(name)
                            .yoinRole(Tokens.type.titleSmall)
                            .foregroundStyle(scheme.onSurface)
                            .lineLimit(1)
                        metaLine(meta)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    YoinIcon("chevron_right", size: 20)
                        .foregroundStyle(scheme.onSurface.opacity(0.6))
                }
                Text(Self.highlighted(link.snippet, marks: link.marks, marker: scheme.tertiaryContainer.opacity(0.55)))
                    .font(.gsf(15))
                    .lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.5))
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.surfaceContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .contentShape(.rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale()).pointerHighlight()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(name), \(meta.joined(separator: " · ")): \(link.snippet)"))
        .accessibilityAddTraits(.isButton)
    }

    /// Kind and moment, then the date muted (Android MetaLine).
    private func metaLine(_ parts: [String]) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(parts.enumerated()), id: \.offset) { i, part in
                if i > 0 { Text(verbatim: " · ") }
                Text(part).opacity(i == parts.count - 1 ? 0.6 : 1)
            }
        }
        .yoinRole(Tokens.type.labelMedium)
        .foregroundStyle(scheme.onSurface)
        .lineLimit(1)
    }

    @ViewBuilder
    private var cover: some View {
        if let palette = Self.palette(link.source) {
            CoverArt(palette: palette, cornerRadius: 0)
        } else {
            scheme.surfaceContainerHighest.overlay {
                YoinIcon(Self.isSong(link.source) ? "music_note" : "album", size: 20)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
        }
    }

    private static func isSong(_ source: BacklinkSource) -> Bool {
        if case .songNote = source { true } else { false }
    }

    static func name(_ source: BacklinkSource) -> String {
        switch source {
        case .songNote(_, _, let title, _, _, _, _): title
        case .albumNote(_, _, let albumName, _): albumName
        case .review(_, let albumName, _): albumName ?? ""
        }
    }

    /// "Note", its moment, when (Android: kind · moment · date).
    static func meta(_ link: NoteBacklink, nowMs: Int64) -> [String] {
        var out: [String]
        switch link.source {
        case .songNote(_, _, _, _, let positionMs, _, _):
            out = [String(localized: "Note")]
            if let positionMs { out.append(NotesStore.formatPosition(positionMs)) }
        case .albumNote:
            out = [String(localized: "Album note")]
        case .review:
            out = [String(localized: "notes_backlink_kind_review", defaultValue: "Review")]
        }
        out.append(NotesStore.formatDate(link.at, nowMs: nowMs))
        return out
    }

    /// The source's cover: the mock's mesh, the loaded album's, else a play's stored cover.
    @MainActor
    private static func palette(_ source: BacklinkSource) -> CoverPalette? {
        let store = AppModel.shared.store
        if !store.isReal {
            let id = source.albumId ?? {
                if case .songNote(_, let trackId, _, _, _, _, _) = source {
                    return MockLibrary.allAlbums.first { $0.tracks.contains { $0.id.rawId == trackId } }?.id.rawId
                }
                return nil
            }()
            return MockLibrary.allAlbums.first { $0.id.rawId == id }?.palette
        }
        if let id = source.albumId, let album = store.album(id), album.palette.cover != nil { return album.palette }
        if case .songNote(_, _, _, _, _, _, let coverKey) = source, let coverKey, !coverKey.isEmpty {
            let p = store.provider == MediaRef.companion.PROVIDER_SUBSONIC
                ? store.placeholderPalette(coverId: coverKey)
                : AppleMusicMapping.palette(URL(string: coverKey), key: coverKey)
            return p.cover != nil ? p : nil
        }
        return nil
    }

    /// [snippet] with [marks] (UTF-16 offsets) as by a highlighter: those words only, SemiBold on the marker.
    static func highlighted(_ snippet: String, marks: [Range<Int>], marker: Color) -> AttributedString {
        var out = AttributedString(snippet)
        let utf16 = snippet.utf16
        for mark in marks {
            let lo = max(0, min(mark.lowerBound, utf16.count)), hi = max(lo, min(mark.upperBound, utf16.count))
            guard hi > lo,
                  let from = String.Index(utf16.index(utf16.startIndex, offsetBy: lo), within: snippet),
                  let to = String.Index(utf16.index(utf16.startIndex, offsetBy: hi), within: snippet),
                  let a = AttributedString.Index(from, within: out),
                  let b = AttributedString.Index(to, within: out) else { continue }
            out[a..<b].backgroundColor = marker
            out[a..<b].font = .gsf(15, .semibold)
        }
        return out
    }

    /// Where a clipping takes the reader (Android NoteMentionController.openSource): the album it was written on;
    /// a song note opens its song's piece.
    static func target(_ source: BacklinkSource, provider: String) -> NoteMentionTarget {
        switch source {
        case .songNote(_, let trackId, let title, let artist, _, _, _):
            .track(provider: provider, id: trackId, title: title, artist: artist)
        case .albumNote(_, let albumId, let albumName, let artist):
            .albumScrapbook(provider: provider, id: albumId, title: albumName, artist: artist)
        case .review(let albumId, let albumName, let artist):
            .albumScrapbook(provider: provider, id: albumId, title: albumName ?? "", artist: artist)
        }
    }
}
