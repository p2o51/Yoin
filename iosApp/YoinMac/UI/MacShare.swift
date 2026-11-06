import MusicKit
import SwiftUI

/// What Share hands the system's share picker: Spotify and Apple Music items their public web link; a Subsonic
/// item only its words ("Title — Artist — Album"), never a server address or token.
@MainActor
enum MacShare {
    enum Content { case link(URL), text(String) }

    /// Apple Music's storefront for its links ("us", "jp"), read once.
    private static var storefront: String?

    static func loadStorefront() {
        guard storefront == nil else { return }
        Task { storefront = try? await MusicDataRequest.currentCountryCode }
    }

    static func song(_ t: Track, album: Album) -> Content {
        let words = [t.title, t.artist ?? album.artist, album.title].filter { !$0.isEmpty }.joined(separator: " — ")
        return link(provider: t.id.provider, kind: "track", appleKind: "song", id: t.id.rawId) ?? .text(words)
    }

    static func album(_ a: Album) -> Content {
        link(provider: a.id.provider, kind: "album", appleKind: "album", id: a.id.rawId)
            ?? .text("\(a.title) — \(a.artist)")
    }

    static func playlist(_ p: LibraryItem) -> Content {
        // Apple Music's own library playlists ("p.…") have no public page; its catalog ones ("pl.…") do.
        if p.id.provider == AppleMusicMapping.provider, !p.id.rawId.hasPrefix("pl.") { return .text(p.title) }
        return link(provider: p.id.provider, kind: "playlist", appleKind: "playlist", id: p.id.rawId) ?? .text(p.title)
    }

    private static func link(provider: String, kind: String, appleKind: String, id: String) -> Content? {
        if provider == SpotifyMapping.provider {
            return URL(string: "https://open.spotify.com/\(kind)/\(id)").map(Content.link)
        }
        if provider == AppleMusicMapping.provider, !id.hasPrefix("i."), !id.hasPrefix("l."), let cc = storefront {
            return URL(string: "https://music.apple.com/\(cc)/\(appleKind)/\(id)").map(Content.link)
        }
        return nil
    }
}

/// The context menus' Share item.
struct MacShareMenuItem: View {
    var content: MacShare.Content

    var body: some View {
        switch content {
        case .link(let url): ShareLink("Share…", item: url)
        case .text(let s): ShareLink("Share…", item: s)
        }
    }
}
