import Foundation

/// Chooses ordered `Accept-Language` header tags when reading Spotify track metadata for lyrics lookup.
///
/// Matches Android's `SpotifyNameLanguages` and `SpotifyMusicSource.nativeNames`:
/// 1. Device language first (gives localized track + ISRC on the initial read).
/// 2. ISRC country language (`TW` → `zh-TW`, `HK`/`MO` → `zh-HK`, `CN` → `zh-CN`, `JP` → `ja`, `KR` → `ko`)
///    when it differs from the device language (including Simplified vs Traditional Chinese script).
/// 3. `"en"` fallback for unlisted / foreign ISRCs (e.g. `US`, `GB`, or missing) when the device
///    language is not English.
enum SpotifyNameLanguages {
    static let englishTag = "en"

    private static let traditionalRegions: Set<String> = ["TW", "HK", "MO"]
    private static let isrcLanguages: [String: String] = [
        "TW": "zh-TW",
        "HK": "zh-HK",
        "MO": "zh-HK",
        "CN": "zh-CN",
        "JP": "ja",
        "KR": "ko",
    ]

    /// Ordered `Accept-Language` tags to try for a track with `isrc` on `locale`.
    static func acceptLanguages(isrc: String?, locale: Locale = .current) -> [String] {
        acceptLanguages(isrc: isrc, deviceLanguage: deviceLanguage(locale: locale))
    }

    /// Ordered `Accept-Language` tags for `isrc` given an already-resolved `deviceLanguage` tag.
    static func acceptLanguages(isrc: String?, deviceLanguage: String) -> [String] {
        let trimmed = deviceLanguage.trimmingCharacters(in: .whitespacesAndNewlines)
        let primary = (trimmed.isEmpty || trimmed.lowercased() == "und") ? englishTag : trimmed
        var tags = [primary]

        if let countryLang = isrcLanguage(isrc: isrc) {
            if !sameLanguage(countryLang, primary) {
                tags.append(countryLang)
            }
        } else if !sameLanguage(primary, englishTag) {
            tags.append(englishTag)
        }
        return tags
    }

    /// The device's language tag for the first read; falls back to `"en"` if undetermined.
    static func deviceLanguage(locale: Locale = .current) -> String {
        let bcp47 = locale.identifier(.bcp47)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !bcp47.isEmpty, bcp47.lowercased() != "und" else { return englishTag }
        return bcp47
    }

    /// The language of `isrc`'s country (`TW`/`HK`/`MO`/`CN`/`JP`/`KR`), or `nil` when unlisted/missing.
    static func isrcLanguage(isrc: String?) -> String? {
        let trimmed = isrc?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard trimmed.count >= 2 else { return nil }
        let country = String(trimmed.prefix(2)).uppercased()
        return isrcLanguages[country]
    }

    /// True when `a` and `b` represent the same language (for Chinese, also requires the same script).
    static func sameLanguage(_ a: String, _ b: String) -> Bool {
        let left = parseTag(a)
        let right = parseTag(b)
        guard !left.language.isEmpty, left.language == right.language else { return false }
        if left.language != "zh" { return true }
        return chineseScript(left) == chineseScript(right)
    }

    private struct TagParts {
        let language: String
        let script: String?
        let region: String?
    }

    private static func parseTag(_ raw: String) -> TagParts {
        let clean = raw
            .split(separator: "@", maxSplits: 1)
            .first
            .map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let parts = clean
            .replacingOccurrences(of: "_", with: "-")
            .split(separator: "-")
            .map(String.init)
        let language = parts.first?.lowercased() ?? ""
        var script: String?
        var region: String?
        for token in parts.dropFirst() {
            if token.count == 4, token.allSatisfy(\.isLetter) {
                script = token.prefix(1).uppercased() + token.dropFirst().lowercased()
            } else if (token.count == 2 && token.allSatisfy(\.isLetter)) ||
                        (token.count == 3 && token.allSatisfy(\.isNumber))
            {
                if region == nil { region = token.uppercased() }
            }
        }
        return TagParts(language: language, script: script, region: region)
    }

    private static func chineseScript(_ parts: TagParts) -> String {
        if let script = parts.script, !script.isEmpty {
            return script
        }
        if let region = parts.region, traditionalRegions.contains(region) {
            return "Hant"
        }
        return "Hans"
    }
}
