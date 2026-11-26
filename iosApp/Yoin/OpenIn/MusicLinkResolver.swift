import Foundation

/// Follows short links and reads what a link names (Android MusicLinkLookup).
@MainActor
protocol MusicLinkLookup: AnyObject {
    /// [link] with a short link followed to the item it names; nil when it leads nowhere Yoin reads.
    func resolve(_ link: OpenInLink) async -> OpenInLink?
    /// What [link] names, or nil when it can't be read now (offline, a timeout, a page without its tags).
    func metadata(_ link: OpenInLink) async -> MusicLinkMetadata?
}

/// Reads music links without the user's login (docs/open-in-yoin.md, Android MusicLinkResolver):
///
/// - Apple Music: the public catalog with Yoin's developer token (MusicKit's `MusicDataRequest`, which signs
///   catalog reads with the app's own token); the link's storefront, then `us`. No Music User Token.
/// - Spotify: the Spotify account in use's Web API when there is one; otherwise the public open.spotify.com
///   page, whose `<meta>` tags the shared `YoinSpotifyPageParser` reads. That request carries no cookie,
///   token or identifier: an ephemeral session, a generic browser user agent and `Accept-Language: en`.
/// - Short links (spotify.link): redirects followed by hand (≤ 5), then a landing page's first
///   open.spotify.com link.
///
/// Every read is short (6 s) and a failure comes back as nil: the card keeps its plain line.
@MainActor
final class MusicLinkResolver: MusicLinkLookup {
    /// One HTTP GET: the body (its first [limit] bytes are enough), the status, and the redirect's target
    /// when [followRedirects] is false and the answer is a redirect.
    struct Page: Sendable {
        var status: Int
        var location: String?
        var body: Data
    }

    typealias Fetch = @Sendable (_ url: URL, _ followRedirects: Bool) async throws -> Page
    typealias Catalog = @Sendable (_ storefront: String, _ type: String, _ id: String) async throws -> [AppleMusicJSON.Object]

    static let timeout: Duration = .seconds(6)
    static let maxRedirects = 5

    private let fetch: Fetch
    private let catalog: Catalog
    /// The Spotify account in use (nil: none): it reads songs, albums and artists through its own API.
    private let activeSpotify: @MainActor () -> SpotifySource?

    init(fetch: @escaping Fetch = MusicLinkResolver.liveFetch,
         catalog: @escaping Catalog = { try await AppleMusicSource.catalogResources(storefront: $0, type: $1, id: $2) },
         activeSpotify: @escaping @MainActor () -> SpotifySource? = { nil }) {
        self.fetch = fetch
        self.catalog = catalog
        self.activeSpotify = activeSpotify
    }

    func resolve(_ link: OpenInLink) async -> OpenInLink? {
        guard link.needsRedirectResolution else { return link }
        return await Self.limited { [fetch] in try await Self.followShortLink(link, fetch: fetch) }
    }

    func metadata(_ link: OpenInLink) async -> MusicLinkMetadata? {
        guard !link.needsRedirectResolution else { return nil }
        let found: MusicLinkMetadata?
        switch link.service {
        case .appleMusic:
            found = await Self.limited { [catalog] in try await Self.appleMusic(link, catalog: catalog) }
        case .spotify:
            if let api = await spotifyApi(link) {
                found = api
            } else {
                found = await Self.limited { [fetch] in try await Self.spotifyPage(link, fetch: fetch) }
            }
        }
        return found.flatMap { $0.isEmpty ? nil : $0 }
    }

    // MARK: Reads

    private static func followShortLink(_ link: OpenInLink, fetch: Fetch) async throws -> OpenInLink? {
        guard var url = URL(string: link.url) else { return nil }
        for _ in 0..<maxRedirects {
            let page = try await fetch(url, false)
            if (300..<400).contains(page.status) {
                guard let location = page.location, let next = URL(string: location, relativeTo: url)?.absoluteURL else {
                    return nil
                }
                if let parsed = OpenInLink.parse(next.absoluteString), !parsed.needsRedirectResolution { return parsed }
                url = next
                continue
            }
            guard (200..<300).contains(page.status) else { return nil }
            // A landing page instead of a redirect (Branch serves one to some agents): its first link.
            let body = String(decoding: page.body, as: UTF8.self)
            return Self.firstSpotifyLink(in: body)
        }
        return nil
    }

    /// The first open.spotify.com link in a landing page that names an item.
    nonisolated static func firstSpotifyLink(in body: String) -> OpenInLink? {
        guard let regex = try? NSRegularExpression(pattern: "https://open\\.spotify\\.com/[A-Za-z0-9/_-]+") else { return nil }
        let range = NSRange(body.startIndex..., in: body)
        for match in regex.matches(in: body, range: range) {
            guard let r = Range(match.range, in: body) else { continue }
            if let link = OpenInLink.parse(String(body[r])), !link.needsRedirectResolution { return link }
        }
        return nil
    }

    private static func appleMusic(_ link: OpenInLink, catalog: Catalog) async throws -> MusicLinkMetadata? {
        let type: String
        switch link.kind {
        case .track: type = "songs"
        case .album: type = "albums"
        case .artist: type = "artists"
        case .playlist: type = "playlists"
        case .shortLink: return nil
        }
        var storefronts = [OpenInLink.defaultStorefront]
        if let own = link.storefront, own != storefronts[0] { storefronts.insert(own, at: 0) }
        for storefront in storefronts {
            // Another storefront may carry it (a link from a storefront this one lacks).
            guard let resources = try? await catalog(storefront, type, link.id), let first = resources.first else { continue }
            return MusicLinkMetadata.fromAppleMusicResource(link.kind, first)
        }
        return nil
    }

    private static func spotifyPage(_ link: OpenInLink, fetch: Fetch) async throws -> MusicLinkMetadata? {
        guard let url = URL(string: link.canonicalUrl) else { return nil }
        let page = try await fetch(url, true)
        guard (200..<300).contains(page.status) else { return nil }
        return MusicLinkMetadata.fromSpotifyPage(link.kind, html: String(decoding: page.body, as: UTF8.self))
    }

    /// The Spotify account in use reads it through its API; a playlist (the whole track list) stays with the page.
    private func spotifyApi(_ link: OpenInLink) async -> MusicLinkMetadata? {
        guard let spotify = activeSpotify() else { return nil }
        return await Self.limited { @MainActor in
            switch link.kind {
            case .track:
                guard let e = try await spotify.track(link.id) else { return nil }
                let isrc = await spotify.isrc(trackId: link.id)
                return MusicLinkMetadata(kind: .track, title: e.track.title, artist: e.track.artist ?? e.album.artist,
                                         album: e.album.title, isrc: isrc,
                                         durationSec: e.track.durationSec > 0 ? e.track.durationSec : nil,
                                         artworkUrl: e.album.coverURL)
            case .album:
                guard let a = try await spotify.album(link.id) else { return nil }
                return MusicLinkMetadata(kind: .album, title: a.title, artist: a.artist, artworkUrl: a.coverURL)
            case .artist:
                guard let a = try await spotify.artist(link.id) else { return nil }
                return MusicLinkMetadata(kind: .artist, title: a.name, artist: nil, artworkUrl: a.palette.cover?.url)
            case .playlist, .shortLink:
                return nil
            }
        }
    }

    /// [body] within [timeout]; nil on a timeout or any failure (cancellation included).
    private static func limited<T: Sendable>(_ body: @escaping @Sendable () async throws -> T?) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { try? await body() }
            group.addTask {
                try? await Task.sleep(for: timeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    // MARK: Network

    /// Generic, the same for everyone (Android's): nothing that tells one user from another.
    nonisolated static let browserUserAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    /// The head of a page holds its tags (a page is ~300 KB): what the parser is given at most.
    nonisolated static let pageBytes = 512 * 1024

    /// Its own ephemeral session: no cookies, no cache, no other request's headers.
    nonisolated static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieAcceptPolicy = .never
        c.httpShouldSetCookies = false
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.timeoutIntervalForRequest = 6
        c.timeoutIntervalForResource = 6
        return URLSession(configuration: c)
    }()

    nonisolated static let liveFetch: Fetch = { url, followRedirects in
        var request = URLRequest(url: url)
        request.setValue(browserUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        request.setValue("en", forHTTPHeaderField: "Accept-Language")
        let (data, response) = try await session.data(for: request, delegate: followRedirects ? nil : NoRedirects())
        let body = data.count > pageBytes ? data.prefix(pageBytes) : data
        let http = response as? HTTPURLResponse
        return Page(status: http?.statusCode ?? 0, location: http?.value(forHTTPHeaderField: "Location"), body: body)
    }

    /// Stops at a redirect, so each hop is looked at (the first one naming a supported link ends it).
    private final class NoRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? { nil }
    }
}
