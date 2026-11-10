import SwiftUI
import UIKit
import YoinShared

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
        case .settings, .tips, .allMemories, .labs: return nil
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

/// Share and "Open in …" for the page in a detail menu. A Subsonic album or playlist has no public page:
/// Share asks the server for a share link first (Android rememberDetailShare, #175).
struct DetailLinkMenuItems: View {
    var target: DetailLinkTarget?
    var link: DetailWebLinkState
    @Environment(\.openURL) private var openURL
    @Environment(AppModel.self) private var model

    var body: some View {
        if let target, let url = link.url {
            ShareLink(item: WebLinks.shareText(title: target.title, url: url)) { Text("Share") }
            if let label = WebLinks.openInLabel(url) {
                Button(label) { openURL(url) }
            }
        } else if let target, SubsonicShareLinks.canShare(target.kind, target.id), let client = model.store.client {
            Button("Share") {
                SubsonicShareLinks.shared.share(target, client: client) { [model] in
                    model.playlistInfo(target.id.rawId).entries.map(\.track.id.rawId)
                }
            }
        }
    }
}

/// Share links made by a Subsonic server (`createShare`; 51 2026-11-08: share links only, no upload).
/// Asked for on the tap, never ahead (each one is a share the server keeps); a second tap while one is
/// being made is ignored. A playlist id the server won't take goes as its songs. A server that refused
/// for good isn't asked again this session: Share then sends the name alone (Android
/// SubsonicMusicSource.createShareLink).
@MainActor
final class SubsonicShareLinks {
    static let shared = SubsonicShareLinks()
    private var making = false
    private var refused = false

    /// Songs, albums and playlists on a server; an artist has no share.
    nonisolated static func canShare(_ kind: WebLinkKind, _ id: MediaId) -> Bool {
        kind != .artist && id.provider == MediaRef.companion.PROVIDER_SUBSONIC && !id.rawId.isEmpty
    }

    func share(_ target: DetailLinkTarget, client: SubsonicClient,
               playlistSongs: @escaping @MainActor () -> [String] = { [] }) {
        guard !refused else { return SystemShare.present(target.title) }
        guard !making else { return }
        making = true
        Task {
            defer { making = false }
            do {
                let made = try await link(target, client, playlistSongs)
                SystemShare.present(WebLinks.shareText(title: target.title, url: URL(string: made)))
            } catch {
                if let k = (error as NSError).kotlinException as? SubsonicException, k.refusesForGood { refused = true }
                PlaylistActions.shared.show(String(localized: "Couldn't make a share link on your server, so only the name was shared"))
                SystemShare.present(target.title)
            }
        }
    }

    private func link(_ target: DetailLinkTarget, _ client: SubsonicClient,
                      _ playlistSongs: @MainActor () -> [String]) async throws -> String {
        do {
            return try await client.createShare(ids: [target.id.rawId], description: target.title)
        } catch {
            // A playlist id only some servers take: the server said no, so its songs instead.
            guard target.kind == .playlist, (error as NSError).kotlinException is SubsonicException else { throw error }
            let songs = playlistSongs()
            guard !songs.isEmpty else { throw error }
            return try await client.createShare(ids: songs, description: target.title)
        }
    }

    /// Another account: its server may share.
    func reset() { refused = false }
}

/// The system share sheet for [text], from the top of the key window (a menu has already closed).
enum SystemShare {
    @MainActor
    static func present(_ text: String) {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first,
              var top = window.rootViewController else { return }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        // iPad: a popover needs somewhere to point; the middle of the window.
        sheet.popoverPresentationController?.sourceView = window
        sheet.popoverPresentationController?.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.midY, width: 0, height: 0)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        top.present(sheet, animated: true)
    }
}
