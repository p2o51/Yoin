import SwiftUI

/// What a public link points at (Android WebLinkKind); the raw value is the URL segment both Spotify and
/// Apple Music use.
enum WebLinkKind: String, Hashable, Sendable {
    case album, artist, playlist, track
}

/// Public web links for Share and "Open in …" (Android MusicMetadata.webUrl, detailShareText, openInLabel).
/// Spotify and Apple Music have them; a self-hosted server has none, nor does an item that lives only in
/// the user's library (an Apple `library:` id).
enum WebLinks {
    /// The link to [id], or nil where there is none. [appleStorefront]: the user's Apple Music storefront
    /// (`us`), needed for Apple's links.
    static func url(kind: WebLinkKind, id: MediaId, appleStorefront: String? = nil) -> URL? {
        let raw = id.rawId
        guard !raw.isEmpty else { return nil }
        switch id.provider {
        case SpotifyMapping.provider:
            // Base-62 ids only: a "song:" stand-in for a loose song's album has no page.
            guard raw.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
            return URL(string: "https://open.spotify.com/\(kind.rawValue)/\(raw)")
        case AppleMusicMapping.provider:
            // Catalog ids only (digits; "pl." for curated playlists): a library id has no public page.
            let catalog = raw.allSatisfy { $0.isASCII && $0.isNumber } || (kind == .playlist && raw.hasPrefix("pl."))
            guard catalog, let shop = appleStorefront, !shop.isEmpty else { return nil }
            return URL(string: "https://music.apple.com/\(shop)/\(kind.rawValue)/\(raw)")
        default:
            return nil
        }
    }

    /// "Open in Spotify" / "Open in Apple Music" for [url]'s host; nil for any other link.
    static func openInLabel(_ url: URL?) -> String? {
        switch url?.host {
        case "open.spotify.com": String(localized: "Open in Spotify")
        case "music.apple.com": String(localized: "Open in Apple Music")
        default: nil
        }
    }

    /// What Share sends: the page's [title], with its link on the next line when it has one.
    static func shareText(title: String, url: URL?) -> String {
        url.map { "\(title)\n\($0.absoluteString)" } ?? title
    }
}

/// The page a detail menu's Share and "Open in …" are about.
struct DetailLinkTarget: Hashable, Sendable {
    var kind: WebLinkKind
    var id: MediaId
    /// First line of the shared text: the album and its artist, a playlist's or artist's name.
    var title: String
}

extension AppModel {
    /// The page on top (a pushed route, else the album picked into the column, [root]) as a link target;
    /// nil for the mock library and for pages that have no link to share.
    func detailLinkTarget(root: Album? = nil) -> DetailLinkTarget? {
        guard store.isReal else { return nil }
        switch path.last {
        case .album(let a): return Self.linkTarget(album: a)
        case .playlist(let id):
            let info = playlistInfo(id)
            return DetailLinkTarget(kind: .playlist, id: info.id, title: info.title)
        case .artist(let ref):
            guard let id = ref.id else { return nil }
            return DetailLinkTarget(kind: .artist, id: MediaId(provider: store.provider, rawId: id), title: ref.name)
        case .settings, .tips: return nil
        case nil: return root.flatMap(Self.linkTarget(album:))
        }
    }

    private static func linkTarget(album a: Album) -> DetailLinkTarget {
        DetailLinkTarget(kind: .album, id: a.id, title: [a.title, a.artist].filter { !$0.isEmpty }.joined(separator: " – "))
    }
}

/// A detail page's public link, resolved once per page (Apple Music's needs the storefront).
@MainActor
@Observable
final class DetailWebLinkState {
    private(set) var url: URL?

    func load(_ target: DetailLinkTarget?, apple: AppleMusicSource?) async {
        guard let target else { url = nil; return }
        var shop: String?
        if target.id.provider == AppleMusicMapping.provider { shop = await apple?.webStorefront() }
        guard !Task.isCancelled else { return }
        url = WebLinks.url(kind: target.kind, id: target.id, appleStorefront: shop)
    }
}

/// Share and "Open in …" for the page in a detail menu; nothing where the page has no public link.
struct DetailLinkMenuItems: View {
    var target: DetailLinkTarget?
    var link: DetailWebLinkState
    @Environment(\.openURL) private var openURL

    var body: some View {
        if let target, let url = link.url {
            ShareLink(item: WebLinks.shareText(title: target.title, url: url)) { Text("Share") }
            if let label = WebLinks.openInLabel(url) {
                Button(label) { openURL(url) }
            }
        }
    }
}
