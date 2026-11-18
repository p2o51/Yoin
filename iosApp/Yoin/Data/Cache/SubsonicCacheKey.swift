import Foundation

/// The cache key of a signed Subsonic REST url (Android `SubsonicCacheKey`):
/// the url without the parameters that sign it (the token `t` and salt `s`)
/// or name the protocol (`v`, `c`, `f`). The salt is drawn per client, so the
/// same song's url changes from one launch to the next, and a cache keyed on
/// the url would never find what the last launch stored. The key keeps the
/// server, the path, the user and the resource's own parameters (`id`,
/// `maxBitRate`, `format`, …), so two accounts, two servers or two encodings
/// never share an entry, and it holds no credential.
///
/// iOS sorts the kept parameters, so the order the client and
/// `SubsonicStreamURLs` append them in never splits one song into two keys.
/// Nothing in the key versions the file: one the server replaces under the
/// same id keeps the stored copy until the cache evicts it (as on Android).
enum SubsonicCacheKey {
    /// [url]'s key, or nil when [url] is not a signed Subsonic REST url (an
    /// unsigned one would carry the password, so it is never cached).
    static func of(_ url: URL) -> String? {
        guard var c = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = c.scheme?.lowercased(), scheme == "http" || scheme == "https",
              c.path.split(separator: "/").contains("rest") else { return nil }
        let items = c.queryItems ?? []
        guard items.contains(where: { $0.name == "t" }), items.contains(where: { $0.name == "s" }) else { return nil }
        let kept = items.filter { !signatureAndProtocol.contains($0.name) }
            .sorted { ($0.name, $0.value ?? "") < ($1.name, $1.value ?? "") }
        c.scheme = scheme
        c.host = c.host?.lowercased()
        c.queryItems = kept.isEmpty ? nil : kept
        c.fragment = nil
        c.user = nil
        c.password = nil
        return c.string
    }

    /// What `SubsonicClient` adds after the user: the signature, then the protocol.
    private static let signatureAndProtocol: Set<String> = ["t", "s", "v", "c", "f"]
}
