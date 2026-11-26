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

    /// Copy naming the service in use now (Android `aiString`).
    @MainActor static func current(_ format: String) -> String { copy(format, current) }

    // The AI copy app-wide (Android `ai_*`, 51 2026-11-25), each naming [name]: generated text and questions are
    // the chosen service's, never "Gemini" while DeepSeek answers. Gemini Nano / Apple Intelligence and Tips copy
    // are not these.
    static func ask(_ name: String) -> String { copy(String(localized: "Ask %@"), name) }
    static func writing(_ name: String) -> String { copy(String(localized: "%@ is writing…"), name) }
    static func generateWith(_ name: String) -> String { copy(String(localized: "Generate with %@"), name) }
    static func regenerateWith(_ name: String) -> String { copy(String(localized: "Regenerate with %@"), name) }
    static func failed(_ name: String) -> String { copy(String(localized: "%@ couldn't answer. Try again."), name) }
    static func nothingNew(_ name: String) -> String { copy(String(localized: "%@ had nothing new"), name) }
    static func keyMissing(_ name: String) -> String { copy(String(localized: "%@ API key missing"), name) }
    static func noKey(_ name: String) -> String { copy(String(localized: "No %@ API key"), name) }
    /// Settings › AI features before a key is set (Android `settings_gemini_summary_empty`, 51 2026-11-26).
    static func settingsSummary(_ name: String) -> String {
        copy(String(localized: "Song info, Ask %@, translation and Memory titles"), name)
    }
    /// Cloud sync's "What syncs" line for the AI's words (51 2026-11-26: it said 问 Gemini whatever answered).
    static func syncedAboutAndAsk(_ name: String) -> String {
        copy(String(localized: "Memory titles, About and Ask %@"), name)
    }
}
