import Foundation
import YoinShared

/// Whose words the generated text is, and who a question goes to: the AI service chosen in Settings
/// (Android `ui/component/AiProviderName.kt`, 51 2026-11-25: "Ask Gemini" while DeepSeek answers is wrong).
/// Gemini and DeepSeek by name, Qwen as 千问 in Chinese, a Custom endpoint as plain "AI".
enum AiProviderName {
    static func name(_ provider: AiProvider) -> String {
        switch provider {
        case .gemini: "Gemini"
        case .deepSeek: "DeepSeek"
        case .qwen: String(localized: "ai.provider.qwen")
        case .custom: "AI"
        }
    }

    /// The service in use now.
    @MainActor static var current: String { name(GeminiSettings.shared.effectiveAiSettings.provider) }

    /// Copy with the service's name filled in, the space before or after a Chinese name dropped (问千问,
    /// 问 Gemini): :shared's `tightenCjkSpaces`, the rule Android uses.
    static func copy(_ format: String, _ name: String) -> String {
        YoinAlbumAbout.shared.tightenCjkSpaces(text: String(format: format, name))
    }
}
