import XCTest
@testable import Yoin

/// AI copy names the AI service in use, app-wide (Android #326, 51 2026-11-25): Ask / writing / regenerate /
/// failed / nothing new / key missing / no key, and the answers' byline, for Gemini, DeepSeek, Qwen (千问) and a
/// Custom endpoint ("AI"); a Chinese name sits flush (问千问), a Latin one spaced (问 Gemini).
@MainActor
final class AiServiceNamingTests: XCTestCase {
    private static func bundle(_ language: String) -> Bundle {
        Bundle(path: Bundle.main.path(forResource: language, ofType: "lproj")!)!
    }

    private func name(_ provider: AiProvider, _ language: String) -> String {
        provider == .qwen ? Self.bundle(language).localizedString(forKey: "ai.provider.qwen", value: nil, table: nil)
            : AiProviderName.name(provider)
    }

    private func text(_ key: String, _ provider: AiProvider, _ language: String) -> String {
        AiProviderName.copy(Self.bundle(language).localizedString(forKey: key, value: nil, table: nil), name(provider, language))
    }

    /// Every template, every service, both languages: Android's `ai_*` strings word for word.
    func test_should_nameTheActiveService_inEveryAiMessage() {
        let expected: [(key: String, en: [AiProvider: String], zh: [AiProvider: String])] = [
            ("Ask %@", [.gemini: "Ask Gemini", .deepSeek: "Ask DeepSeek", .qwen: "Ask Qwen", .custom: "Ask AI"],
             [.gemini: "问 Gemini", .deepSeek: "问 DeepSeek", .qwen: "问千问", .custom: "问 AI"]),
            ("%@ is writing…", [.gemini: "Gemini is writing…", .qwen: "Qwen is writing…", .custom: "AI is writing…"],
             [.gemini: "Gemini 正在写…", .qwen: "千问正在写…", .deepSeek: "DeepSeek 正在写…"]),
            ("Regenerate with %@", [.deepSeek: "Regenerate with DeepSeek", .custom: "Regenerate with AI"],
             [.gemini: "用 Gemini 重新生成", .qwen: "用千问重新生成"]),
            ("Generate with %@", [.gemini: "Generate with Gemini"], [.qwen: "让千问生成", .deepSeek: "让 DeepSeek 生成"]),
            ("%@ couldn't answer. Try again.", [.deepSeek: "DeepSeek couldn't answer. Try again."],
             [.qwen: "千问没能回答，再试一次", .gemini: "Gemini 没能回答，再试一次"]),
            ("%@ had nothing new", [.qwen: "Qwen had nothing new", .custom: "AI had nothing new"],
             [.qwen: "千问没有新的内容", .deepSeek: "DeepSeek 没有新的内容"]),
            ("%@ API key missing", [.deepSeek: "DeepSeek API key missing", .gemini: "Gemini API key missing"],
             [.qwen: "缺少千问 API 密钥", .custom: "缺少 AI API 密钥"]),
            ("No %@ API key", [.gemini: "No Gemini API key", .qwen: "No Qwen API key"],
             [.deepSeek: "未设置 DeepSeek API 密钥", .qwen: "未设置千问 API 密钥"]),
            // Settings (51, 2026-11-26): the AI row before a key, and cloud sync's "What syncs".
            ("Song info, Ask %@, translation and Memory titles",
             [.deepSeek: "Song info, Ask DeepSeek, translation and Memory titles",
              .custom: "Song info, Ask AI, translation and Memory titles"],
             [.qwen: "歌曲信息、问千问、歌词翻译与 Memories\u{00A0}标\u{2060}题", .custom: "歌曲信息、问 AI、歌词翻译与 Memories\u{00A0}标\u{2060}题"]),
            ("Memory titles, About and Ask %@", [.gemini: "Memory titles, About and Ask Gemini", .custom: "Memory titles, About and Ask AI"],
             [.qwen: "Memories 标题、歌曲信息与问千问", .deepSeek: "Memories 标题、歌曲信息与问 DeepSeek"]),
        ]
        for row in expected {
            for (provider, want) in row.en { XCTAssertEqual(text(row.key, provider, "en"), want, row.key) }
            for (provider, want) in row.zh { XCTAssertEqual(text(row.key, provider, "zh-Hans"), want, row.key) }
        }
    }

    /// The names the screens and the About loader use follow the service chosen in Settings (an isolated settings
    /// store: the device's own choice is left alone).
    func test_should_followTheProviderChosenInSettings() {
        let suite = "AiServiceNamingTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = GeminiSettings(keychain: GeminiKeychain(service: "com.gpo.yoin.gemini.naming.\(UUID().uuidString)"), defaults: defaults)
        let loader = SongAboutLoader(settings: settings)
        for provider in [AiProvider.gemini, .deepSeek, .qwen, .custom] {
            settings.setProvider(provider)
            let name = AiProviderName.name(provider)
            XCTAssertEqual(loader.aiName, name)
            XCTAssertEqual(AiProviderName.name(settings.effectiveAiSettings.provider), name)
            XCTAssertEqual(AiProviderName.ask(name), AiProviderName.copy(loc("Ask %@"), name))
        }
        XCTAssertEqual(AiProviderName.name(.custom), "AI")
        XCTAssertEqual(AiProviderName.name(.qwen), loc("ai.provider.qwen"))
    }

    /// No copy says "Gemini" for generated content any more: the only catalog strings naming it are its own key
    /// field, a debug lab label and the Tips line (Gemini Nano / Apple Intelligence
    /// copy is not in the catalog under Gemini).
    func test_should_leaveNoGeminiOnlyAiCopy_inTheCatalog() throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "Localizable", withExtension: "xcstrings")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("Yoin/Localizable.xcstrings"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let strings = try XCTUnwrap(root["strings"] as? [String: Any])
        let allowed: Set<String> = [
            "Gemini", "Gemini API key", "Gemini extras",
        ]
        var offenders: [String] = []
        for (key, value) in strings {
            let zh = (((value as? [String: Any])?["localizations"] as? [String: Any])?["zh-Hans"] as? [String: Any])
                .flatMap { ($0["stringUnit"] as? [String: Any])?["value"] as? String } ?? ""
            if (key.contains("Gemini") || zh.contains("Gemini")) && !allowed.contains(key) { offenders.append(key) }
        }
        XCTAssertEqual(offenders.sorted(), [])
    }
}
