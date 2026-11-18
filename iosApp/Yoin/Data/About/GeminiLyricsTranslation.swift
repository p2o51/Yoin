import Foundation

/// Android `GeminiService.translateLyricLines`: no search grounding, one
/// tagged block per source line, parsed back by index.
extension GeminiClient {
    /// Source-line index → translation (lines left out are absent).
    func translateLyricLines(apiKey: String, title: String, artist: String, lines: [String],
                             targetLanguage: String) async throws -> [Int: String] {
        let settings = resolvedAi(apiKey: apiKey)
        let prompt = Self.lyricsTranslationPrompt(title: title, artist: artist, lines: lines,
                                                  targetLanguage: targetLanguage)
        let raw = try await generateText(ai: settings, prompt: prompt, temperature: 0.8,
                                         useSearch: false, timeout: 45, requireNonEmpty: true)
        let parsed = Self.parseLineTranslations(raw, lineCount: lines.count)
        guard !parsed.isEmpty else { throw Failure(message: "No usable lyric translations in Gemini response") }
        return parsed
    }

    /// Android `buildLyricsTranslationPrompt`, word for word.
    static func lyricsTranslationPrompt(title: String, artist: String, lines: [String],
                                        targetLanguage: String) -> String {
        let indexed = lines.enumerated().map { "[\($0.offset)] \($0.element)" }.joined(separator: "\n")
        return """
        Translate these lyric lines into \(targetLanguage).

        Song: \(title)
        Artist: \(artist)

        Preserve line count and meaning. Keep imagery natural, concise, and singable.
        Return strictly one tagged block per source line:

        [L0]translation for line 0[/L0]
        [L1]translation for line 1[/L1]

        Lines:
        \(indexed)
        """
    }

    /// Android `parseLineTranslations`: `[Ln]…[/Ln]` blocks; failing those,
    /// one plain line per source line when the counts agree.
    static func parseLineTranslations(_ raw: String, lineCount: Int) -> [Int: String] {
        var tagged: [Int: String] = [:]
        if let re = try? NSRegularExpression(pattern: #"\[\s*L(\d+)\s*\](.*?)\[\s*/\s*L\1\s*\]"#,
                                             options: [.caseInsensitive, .dotMatchesLineSeparators]) {
            let ns = raw as NSString
            for m in re.matches(in: raw, range: NSRange(location: 0, length: ns.length)) {
                guard let i = Int(ns.substring(with: m.range(at: 1))), i >= 0, i < lineCount else { continue }
                let text = cleanLineTranslation(ns.substring(with: m.range(at: 2)))
                if !text.isEmpty { tagged[i] = text }
            }
        }
        if !tagged.isEmpty { return tagged }
        let plain = raw.components(separatedBy: .newlines).map(cleanLineTranslation).filter { !$0.isEmpty }
        guard plain.count == lineCount else { return [:] }
        return Dictionary(uniqueKeysWithValues: plain.enumerated().map { ($0.offset, $0.element) })
    }

    /// Android `cleanLineTranslation`: tag markers and leading "1." / "[1]" /
    /// "- " numbering dropped.
    static func cleanLineTranslation(_ text: String) -> String {
        var s = stripTags(text)
        for _ in 0..<3 {
            s = s.replacingOccurrences(of: #"^(?:[-*•]\s*)?(?:(?:[\[【(]\s*\d+\s*[\]】)])(?:[.)：:、])?|\d+[.)：:、])\s*"#,
                                       with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return s
    }
}
