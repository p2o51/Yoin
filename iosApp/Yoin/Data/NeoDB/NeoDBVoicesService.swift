import Foundation
import os

/// What the "What others say" page reads (a seam for tests and debug stand-ins).
protocol NeoDBVoicesReading: Sendable {
    /// The NeoDB item for the album, with its spread and outside links; nil when NeoDB has nothing like it.
    func findAlbum(albumName: String, artist: String?) async throws -> NeoDBAlbumVoices?
    /// One page (1-based, 20 posts) of short comments and reviews, newest first.
    func voices(itemUuid: String, page: Int) async throws -> NeoDBVoicePage
    /// Where to search NeoDB when no item matched.
    func searchUrl(albumName: String, artist: String?) async -> String
    /// Scores from catalogues that publish them; each source fails quietly (a missing card, never an error).
    func referenceScores(_ album: NeoDBAlbumVoices) async -> [NeoDBReferenceScore]
}

/// Reads what other listeners on NeoDB wrote about an album (Android `NeoDbVoicesService`). Works signed
/// out: NeoDB's catalogue and public posts need no token, so signed out it reads neodb.social anonymously;
/// signed in, the user's own instance with their token. It never writes and never touches the sync mapping.
struct NeoDBVoicesService: NeoDBVoicesReading {
    static let defaultInstance = "https://neodb.social"
    /// Discogs and MusicBrainz ask callers to name themselves (Android's string).
    static let userAgent = "Yoin/1 ( https://github.com/p2o51/Yoin )"

    var api: NeoDBAPI
    /// The signed-in account's instance and token, or nil (read when a call starts).
    var session: @Sendable () async -> (instance: String, token: String)?
    var urlSession: URLSession

    private static let log = Logger(subsystem: "com.gpo.yoin", category: "NeoDBVoices")

    init(api: NeoDBAPI = NeoDBAPI(), urlSession: URLSession = NeoDBAPI.defaultSession,
         session: @escaping @Sendable () async -> (instance: String, token: String)? = {
             await MainActor.run { NeoDBAccount.shared.session }
         }) {
        self.api = api
        self.urlSession = urlSession
        self.session = session
    }

    private struct Access: Sendable {
        var instance: String
        var token: String?
    }

    /// A token only means something on its own instance; without one, neodb.social anonymously.
    private func access() async -> Access {
        if let s = await session(), !s.token.isEmpty, !s.instance.isEmpty { return Access(instance: s.instance, token: s.token) }
        return Access(instance: Self.defaultInstance, token: nil)
    }

    /// [call] with the stored token; turned away with it (401 or 403), once more without, on the same
    /// instance: a token revoked on NeoDB's site stays in the app, and the page reads signed out anyway.
    private func read<T>(_ access: Access, _ call: (String?) async throws -> T) async throws -> T {
        do {
            return try await call(access.token)
        } catch let error as NeoDBError where access.token != nil && (error.code == 401 || error.code == 403) {
            return try await call(nil)
        }
    }

    func findAlbum(albumName: String, artist: String?) async throws -> NeoDBAlbumVoices? {
        let access = await access()
        let query = NeoDBVoicesParsing.query(albumName: albumName, artist: artist)
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let items = try await read(access) { token in
            try await api.searchAlbumPublic(instance: access.instance, token: token, query: query)
        }
        return NeoDBVoicesParsing.pickAlbumItem(albumName: albumName, artistName: artist, items: items)
            .flatMap { NeoDBVoicesParsing.albumVoices(instance: access.instance, item: $0) }
    }

    func voices(itemUuid: String, page: Int) async throws -> NeoDBVoicePage {
        let access = await access()
        let posts = try await read(access) { token in
            try await api.listItemPosts(instance: access.instance, token: token, itemUuid: itemUuid, page: page)
        }
        return NeoDBVoicesParsing.page(page, posts)
    }

    func searchUrl(albumName: String, artist: String?) async -> String {
        NeoDBVoicesParsing.neoDBSearchUrl(instance: await access().instance, albumName: albumName, artist: artist)
    }

    /// Discogs and MusicBrainz, through the links NeoDB keeps on the item. RYM and AOTY have no API and
    /// turn automated reads away, so they are links on the page only.
    func referenceScores(_ album: NeoDBAlbumVoices) async -> [NeoDBReferenceScore] {
        async let discogs = quietly("discogs") { try await discogsScore(album.discogsUrl) }
        async let musicBrainz = quietly("musicbrainz") { try await musicBrainzScore(album.musicBrainzUrl) }
        return [await discogs, await musicBrainz].compactMap { $0 }
    }

    private func quietly(_ source: String, _ call: () async throws -> NeoDBReferenceScore?) async -> NeoDBReferenceScore? {
        do {
            return try await call()
        } catch {
            if !(error is CancellationError) {
                Self.log.warning("reference score from \(source, privacy: .public) failed: \(error.localizedDescription, privacy: .public)")
            }
            return nil
        }
    }

    private func discogsScore(_ url: String?) async throws -> NeoDBReferenceScore? {
        guard let url, let (kind, id) = NeoDBVoicesParsing.discogsId(url) else { return nil }
        let releaseId: Int64
        if kind == "master" {
            guard let main = (try await getJSON("https://api.discogs.com/masters/\(id)"))["main_release"] as? NSNumber
            else { return nil }
            releaseId = main.int64Value
        } else {
            releaseId = id
        }
        let release = try await getJSON("https://api.discogs.com/releases/\(releaseId)")
        guard let rating = (release["community"] as? [String: Any])?["rating"] as? [String: Any],
              let value = (rating["average"] as? NSNumber)?.doubleValue else { return nil }
        let votes = (rating["count"] as? NSNumber)?.intValue ?? 0
        guard votes > 0 else { return nil }
        return NeoDBReferenceScore(source: "Discogs", value: value, scale: 5, votes: votes, url: url)
    }

    private func musicBrainzScore(_ url: String?) async throws -> NeoDBReferenceScore? {
        guard let url, let (kind, mbid) = NeoDBVoicesParsing.musicBrainzId(url) else { return nil }
        let group: [String: Any]?
        if kind == "release" {
            group = try await getJSON("https://musicbrainz.org/ws/2/release/\(mbid)?inc=release-groups+ratings&fmt=json")["release-group"] as? [String: Any]
        } else {
            group = try await getJSON("https://musicbrainz.org/ws/2/release-group/\(mbid)?inc=ratings&fmt=json")
        }
        guard let rating = group?["rating"] as? [String: Any], let value = (rating["value"] as? NSNumber)?.doubleValue
        else { return nil }
        let votes = (rating["votes-count"] as? NSNumber)?.intValue ?? 0
        guard votes > 0 else { return nil }
        return NeoDBReferenceScore(source: "MusicBrainz", value: value, scale: 5, votes: votes, url: url)
    }

    private struct HTTPFailure: Error, LocalizedError {
        var status: Int
        var host: String
        var errorDescription: String? { "HTTP \(status) from \(host)" }
    }

    private func getJSON(_ address: String) async throws -> [String: Any] {
        guard let url = URL(string: address) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await urlSession.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw HTTPFailure(status: status, host: url.host ?? "") }
        return (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
}
