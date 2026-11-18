import Foundation
import Observation
import os
import Security

/// Online AI v2 settings and answer language (Android `GeminiConfig` + `AiProviderStore`).
/// All API keys (Gemini, DeepSeek, Qwen, Custom, and the 5 web-search services) live exclusively
/// in the Keychain via `KeychainItem.upsert`; non-secret preferences and target language live in
/// `UserDefaults`. Keys are never stored in `UserDefaults` and never logged.
@MainActor
@Observable
final class GeminiSettings {
    static let shared = GeminiSettings(publishShared: true)

    static let defaultLanguage = "English"
    /// Android `GeminiConfig.SUPPORTED_TARGET_LANGUAGES`.
    static let languages = ["English", "Simplified Chinese", "Traditional Chinese", "Japanese", "Korean",
                            "Spanish", "French", "German"]

    static func normalizeLanguage(_ language: String?) -> String {
        languages.first { $0 == language } ?? defaultLanguage
    }

    private(set) var geminiKey: String
    private(set) var prefs: AiProviderPrefs
    private(set) var secrets: AiSecrets
    private(set) var targetLanguage: String

    /// Active provider's trimmed API key (matches `AiSettings.apiKey` for existing callers).
    var apiKey: String {
        aiSettings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// True when the active provider has everything required to perform a request.
    var hasKey: Bool {
        effectiveAiSettings.isReady
    }

    /// Active provider key for outgoing calls; preserves `#if DEBUG` `-geminiDebugKey` fallback when on Gemini.
    var requestKey: String {
        effectiveAiSettings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var aiSettings: AiSettings {
        AiSettings(prefs: prefs, secrets: secrets, geminiKey: geminiKey)
    }

    var effectiveAiSettings: AiSettings {
        var effectiveGemini = geminiKey
        #if DEBUG
        if prefs.provider == .gemini, effectiveGemini.isEmpty,
           let debugKey = defaults.string(forKey: "geminiDebugKey"), !debugKey.isEmpty
        {
            effectiveGemini = debugKey
        }
        #endif
        return AiSettings(prefs: prefs, secrets: secrets, geminiKey: effectiveGemini)
    }

    @ObservationIgnored private let keychain: GeminiKeychain
    @ObservationIgnored private let aiKeychain: AiKeychain
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let publishesSharedSnapshot: Bool

    private static let languageDefaultsKey = "geminiTargetLanguage"
    private static let prefsDefaultsKey = "aiProviderPrefs"
    private nonisolated static let sharedAiLock = OSAllocatedUnfairLock(initialState: AiSettings.geminiOnly(apiKey: ""))

    /// Lock-protected snapshot of `GeminiSettings.shared.effectiveAiSettings` for nonisolated callers
    /// such as `GeminiClient()` calls outside MainActor.
    nonisolated static func currentSharedAiSettings() -> AiSettings {
        sharedAiLock.withLock { $0 }
    }

    convenience init(
        keychain: GeminiKeychain = GeminiKeychain(),
        defaults: UserDefaults = .standard,
        aiKeychain: AiKeychain? = nil
    ) {
        let resolvedAiKeychain = aiKeychain ?? AiKeychain(
            service: keychain.service == "com.gpo.yoin.gemini" ? "com.gpo.yoin.ai" : "\(keychain.service).ai"
        )
        self.init(keychain: keychain, aiKeychain: resolvedAiKeychain, defaults: defaults, publishShared: false)
    }

    private convenience init(publishShared: Bool) {
        self.init(keychain: GeminiKeychain(), aiKeychain: AiKeychain(), defaults: .standard, publishShared: publishShared)
    }

    private init(
        keychain: GeminiKeychain,
        aiKeychain: AiKeychain,
        defaults: UserDefaults,
        publishShared: Bool
    ) {
        self.keychain = keychain
        self.aiKeychain = aiKeychain
        self.defaults = defaults
        self.publishesSharedSnapshot = publishShared
        self.geminiKey = keychain.load() ?? ""
        self.secrets = aiKeychain.loadAll()
        self.prefs = Self.loadPrefs(from: defaults)
        self.targetLanguage = Self.normalizeLanguage(defaults.string(forKey: Self.languageDefaultsKey))
        publishIfShared()
    }

    /// Legacy v1 setter for Gemini key: trimmed, ignores empty input, persists to Keychain.
    @discardableResult
    func saveKey(_ key: String) -> Bool {
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty, k != geminiKey else { return true }
        let status = keychain.save(k)
        guard Self.keychainAccepted(status) else { return false }
        geminiKey = k
        publishIfShared()
        return true
    }

    func key(for provider: AiProvider) -> String {
        switch provider {
        case .gemini: geminiKey
        case .deepSeek: secrets.deepSeekKey
        case .qwen: secrets.qwenKey
        case .custom: secrets.customKey
        }
    }

    /// Saves or clears (when trimmed is empty) the Keychain API key for `provider`.
    @discardableResult
    func saveKey(_ key: String, for provider: AiProvider) -> Bool {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        if provider == .gemini {
            guard trimmed != geminiKey else { return true }
            if trimmed.isEmpty {
                let status = keychain.delete()
                guard Self.keychainAcceptedOrNotFound(status) else { return false }
            } else {
                let status = keychain.save(trimmed)
                guard Self.keychainAccepted(status) else { return false }
            }
            geminiKey = trimmed
            publishIfShared()
            return true
        }
        guard let account = AiKeychain.Account(provider: provider) else { return false }
        guard trimmed != (secrets.key(for: provider) ?? "") else { return true }
        let status = aiKeychain.save(trimmed, for: account)
        guard Self.keychainAccepted(status) else { return false }
        secrets = secrets.withProviderKey(trimmed, for: provider)
        publishIfShared()
        return true
    }

    func searchKey(for service: AiSearchService) -> String {
        secrets.searchKey(for: service)
    }

    /// Saves or clears (when trimmed is empty) the Keychain API key for Custom's web search `service`.
    @discardableResult
    func saveSearchKey(_ key: String, for service: AiSearchService) -> Bool {
        guard let account = AiKeychain.Account(searchService: service) else { return true }
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed != secrets.searchKey(for: service) else { return true }
        let status = aiKeychain.save(trimmed, for: account)
        guard Self.keychainAccepted(status) else { return false }
        secrets = secrets.withSearchKey(trimmed, for: service)
        publishIfShared()
        return true
    }

    func setProvider(_ provider: AiProvider) {
        updatePrefs { $0.provider = provider }
    }

    func setTier(_ tier: AiTier) {
        updatePrefs { $0.tier = tier }
    }

    func setCustomFormat(_ format: AiApiFormat) {
        updatePrefs { $0.customFormat = format }
    }

    func setCustomBaseUrl(_ url: String) {
        updatePrefs { $0.customBaseUrl = url }
    }

    func setCustomModel(_ model: String) {
        updatePrefs { $0.customModel = model }
    }

    func setCustomThinking(_ thinking: AiThinking) {
        updatePrefs { $0.customThinking = thinking }
    }

    func setSearchService(_ service: AiSearchService) {
        updatePrefs { $0.searchService = service }
    }

    /// User changed language locally — notifies cloud sync via `onLocalChange`.
    @ObservationIgnored static var onLocalChange: (@MainActor () -> Void)?

    func saveLanguage(_ language: String) {
        let l = Self.normalizeLanguage(language)
        let changed = l != targetLanguage
        defaults.set(l, forKey: Self.languageDefaultsKey)
        targetLanguage = l
        if changed { Self.onLocalChange?() }
    }

    /// Sync's `translation_language` from another device.
    func syncSetLanguage(_ language: String) -> Bool {
        guard Self.languages.contains(language) else { return false }
        defaults.set(language, forKey: Self.languageDefaultsKey)
        targetLanguage = language
        return true
    }

    private func updatePrefs(_ mutate: (inout AiProviderPrefs) -> Void) {
        var next = prefs
        mutate(&next)
        guard next != prefs else { return }
        prefs = next
        if let data = try? JSONEncoder().encode(next) {
            defaults.set(data, forKey: Self.prefsDefaultsKey)
        }
        publishIfShared()
    }

    private func publishIfShared() {
        guard publishesSharedSnapshot else { return }
        let snapshot = effectiveAiSettings
        Self.sharedAiLock.withLock { $0 = snapshot }
    }

    private static func loadPrefs(from defaults: UserDefaults) -> AiProviderPrefs {
        guard let data = defaults.data(forKey: prefsDefaultsKey),
              let decoded = try? JSONDecoder().decode(AiProviderPrefs.self, from: data)
        else { return AiProviderPrefs() }
        return decoded
    }

    private static func keychainAccepted(_ status: OSStatus) -> Bool {
        status == errSecSuccess || status == errSecMissingEntitlement
    }

    private static func keychainAcceptedOrNotFound(_ status: OSStatus) -> Bool {
        status == errSecSuccess || status == errSecItemNotFound || status == errSecMissingEntitlement
    }
}

/// One Keychain item for the Gemini API key.
struct GeminiKeychain: Sendable {
    var service = "com.gpo.yoin.gemini"
    var account = "apiKey"

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    func load() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Keychain upsert (unsigned simulator builds get `errSecMissingEntitlement`).
    @discardableResult
    func save(_ key: String) -> OSStatus {
        KeychainItem.upsert(query: query, data: Data(key.utf8))
    }

    @discardableResult
    func delete() -> OSStatus {
        SecItemDelete(query as CFDictionary)
    }
}
