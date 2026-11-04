import Foundation
import Observation
import Security

/// The user's Gemini key and answer language: Android `data/local/GeminiConfig`.
/// The key lives in the Keychain (generic password, this device only), the
/// language in UserDefaults. Neither is ever logged.
@MainActor
@Observable
final class GeminiSettings {
    static let shared = GeminiSettings()

    static let defaultLanguage = "English"
    /// Android `GeminiConfig.SUPPORTED_TARGET_LANGUAGES`.
    static let languages = ["English", "Simplified Chinese", "Traditional Chinese", "Japanese", "Korean",
                            "Spanish", "French", "German"]

    static func normalizeLanguage(_ language: String?) -> String {
        languages.first { $0 == language } ?? defaultLanguage
    }

    private(set) var apiKey: String
    private(set) var targetLanguage: String
    var hasKey: Bool { !requestKey.isEmpty }

    /// The key requests use. DEBUG: `-geminiDebugKey <any>` stands in (never
    /// stored), for the fake endpoint of `-geminiDebugBaseURL`.
    var requestKey: String {
        #if DEBUG
        if apiKey.isEmpty, let k = defaults.string(forKey: "geminiDebugKey"), !k.isEmpty { return k }
        #endif
        return apiKey
    }

    @ObservationIgnored private let keychain: GeminiKeychain
    @ObservationIgnored private let defaults: UserDefaults
    private static let languageDefaultsKey = "geminiTargetLanguage"

    init(keychain: GeminiKeychain = GeminiKeychain(), defaults: UserDefaults = .standard) {
        self.keychain = keychain
        self.defaults = defaults
        apiKey = keychain.load() ?? ""
        targetLanguage = Self.normalizeLanguage(defaults.string(forKey: Self.languageDefaultsKey))
    }

    /// Android `saveGeminiApiKey`: trimmed; blank is never saved.
    func saveKey(_ key: String) {
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty, k != apiKey else { return }
        keychain.save(k)
        apiKey = k
    }

    func saveLanguage(_ language: String) {
        let l = Self.normalizeLanguage(language)
        defaults.set(l, forKey: Self.languageDefaultsKey)
        targetLanguage = l
    }
}

/// One Keychain item for the key.
struct GeminiKeychain: Sendable {
    var service = "com.gpo.yoin.gemini"
    var account = "apiKey"

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    func load() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The Keychain's status (unsigned simulator builds get errSecMissingEntitlement).
    @discardableResult
    func save(_ key: String) -> OSStatus {
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = Data(key.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil)
    }

    func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
