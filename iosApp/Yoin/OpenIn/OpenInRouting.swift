import Foundation
import YoinShared

/*
 * "Open in Yoin" and the clipboard music-link card (docs/open-in-yoin.md; Android data/openin). The links
 * are read by the shared Kotlin parser (`:shared` openin: YoinMusicLinkParser, YoinSpotifyPageParser), the
 * same rules as Android; what follows is the iOS side: where a link goes, what it names, the card.
 */

/// A music link as Swift keeps it: the shared parser's `YoinMusicLink`, copied into a Sendable value.
struct OpenInLink: Hashable, Sendable {
    enum Service: String, Sendable { case spotify, appleMusic }
    enum Kind: String, Sendable { case track, album, playlist, artist, shortLink }

    var service: Service
    var kind: Kind
    /// The service's own id (Spotify base-62, an Apple Music catalog id or `pl.…`), or a short link's code.
    var id: String
    /// The Apple Music storefront the URL names; nil when it names none and for Spotify.
    var storefront: String?
    /// The link as it was found (what a short link is followed from).
    var url: String
    /// "Service:Kind:id" as the shared link spells it: the same item whatever the link's decorations.
    var key: String
    /// The service's canonical web URL for the item (a short link: [url]).
    var canonicalUrl: String

    var needsRedirectResolution: Bool { kind == .shortLink }

    init(_ link: YoinMusicLink) {
        service = link.service == .spotify ? .spotify : .appleMusic
        kind = switch link.kind {
        case .track: .track
        case .album: .album
        case .playlist: .playlist
        case .artist: .artist
        default: .shortLink
        }
        id = link.id
        storefront = link.storefront
        url = link.url
        key = link.key
        canonicalUrl = link.canonicalUrl
    }

    /// The first supported link in [text] (shared text, the clipboard), by the shared parser. Never throws.
    static func findFirst(_ text: String) -> OpenInLink? {
        YoinMusicLinkParser.shared.findFirst(text: text).map(OpenInLink.init)
    }

    /// [value] read as one link (a redirect's target), or nil.
    static func parse(_ value: String) -> OpenInLink? {
        YoinMusicLinkParser.shared.parse(value: value).map(OpenInLink.init)
    }

    /// The storefront a catalog read uses when the link names none (`YoinMusicLink.DEFAULT_STOREFRONT`).
    static var defaultStorefront: String { YoinMusicLink.companion.DEFAULT_STOREFRONT }

    /// The service's name as the card says it ("Spotify link in clipboard").
    var serviceName: String { service.profileService.name }
}

extension OpenInLink.Service {
    /// The accounts this service's links belong to.
    var profileService: Profile.Service {
        switch self {
        case .spotify: .spotify
        case .appleMusic: .appleMusic
        }
    }

    /// Yoin's provider token for the service's ids (`MediaId.provider`).
    var provider: String {
        switch self {
        case .spotify: SpotifyMapping.provider
        case .appleMusic: AppleMusicMapping.provider
        }
    }
}

/// Where a link goes (owner 2026-11-24): by the user's accounts, not only the one in use. An account on the
/// link's service is a hit, and the link opens there (switching to it first). None: the link was only
/// found, and its title is searched on the account in use for the user to choose. A found link never plays
/// by itself.
enum OpenInTarget: Hashable, Sendable {
    case hit(profileId: String, profileName: String)
    case found

    var isHit: Bool { if case .hit = self { true } else { false } }
}

/// What a hit does with its item, by the raw id on the link's own service.
enum OpenInHitAction: Hashable, Sendable {
    case playTrack(String)
    case openAlbum(String)
    case openArtist(String)
    case openPlaylist(String)
}

enum OpenInRouting {
    /// [link]'s target among [profiles]. Of several accounts on its service the one in use wins, else the
    /// oldest (the first added), so the same link always lands on the same account (Android OpenInRouting).
    static func route(_ link: OpenInLink, profiles: [Profile], activeId: String?) -> OpenInTarget {
        route(service: link.service.profileService, profiles: profiles, activeId: activeId)
    }

    static func route(service: Profile.Service, profiles: [Profile], activeId: String?) -> OpenInTarget {
        let owners = profiles.filter { $0.service == service }
        let owner = owners.first { $0.id == activeId }
            ?? owners.min { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
        guard let owner else { return .found }
        return .hit(profileId: owner.id, profileName: owner.title)
    }

    /// What opening [link] on its own service does; nil for a short link not yet followed.
    static func hitAction(_ link: OpenInLink) -> OpenInHitAction? {
        switch link.kind {
        case .track: .playTrack(link.id)
        case .album: .openAlbum(link.id)
        case .artist: .openArtist(link.id)
        case .playlist: .openPlaylist(link.id)
        case .shortLink: nil
        }
    }
}

/// What a link names, read without the user's login: the card's cover and lines, and what a found link
/// searches for. [artist] is a playlist's curator. Any field may be missing (Android MusicLinkMetadata).
struct MusicLinkMetadata: Hashable, Sendable {
    var kind: OpenInLink.Kind
    var title: String?
    var artist: String?
    var album: String? = nil
    var isrc: String? = nil
    var durationSec: Int? = nil
    var artworkUrl: URL? = nil

    var isEmpty: Bool { (title ?? "").trimmingCharacters(in: .whitespaces).isEmpty }

    /// A Spotify page's `<meta>` tags (shared YoinSpotifyPageParser); a playlist's owner stands for the artist.
    static func fromSpotifyPage(_ kind: OpenInLink.Kind, html: String) -> MusicLinkMetadata {
        let page = YoinSpotifyPageParser.shared.parse(html: html)
        return MusicLinkMetadata(kind: kind, title: page.title,
                                 artist: kind == .playlist ? page.owner : page.artist,
                                 album: page.album, durationSec: page.durationSec.map { Int($0.int32Value) },
                                 artworkUrl: page.imageUrl.flatMap(URL.init(string:)))
    }

    /// An Apple Music catalog resource (`GET /v1/catalog/{storefront}/{songs|albums|artists|playlists}/{id}`'s
    /// `data[0]`), read with the developer token alone.
    static func fromAppleMusicResource(_ kind: OpenInLink.Kind, _ resource: AppleMusicJSON.Object) -> MusicLinkMetadata? {
        let attributes = AppleMusicJSON.attributes(resource)
        guard !attributes.isEmpty else { return nil }
        let artwork = AppleMusicMapping.artworkURL((attributes["artwork"] as? AppleMusicJSON.Object)?["url"] as? String,
                                                   size: 600)
        if kind == .track, let song = AppleMusicMapping.song(resource) {
            return MusicLinkMetadata(kind: kind, title: song.title, artist: song.artist.isEmpty ? nil : song.artist,
                                     album: song.album.isEmpty ? nil : song.album, isrc: song.isrc,
                                     durationSec: song.durationMs.map { $0 / 1000 }, artworkUrl: artwork ?? song.artwork)
        }
        return MusicLinkMetadata(kind: kind, title: AppleMusicJSON.string(attributes, "name"),
                                 artist: AppleMusicJSON.string(attributes, "artistName")
                                     ?? AppleMusicJSON.string(attributes, "curatorName"),
                                 artworkUrl: artwork)
    }
}
