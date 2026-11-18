import CoreGraphics
import Foundation
import Security

/// Online AI providers supported by AI settings v2 (matching Android `AiProvider`).
enum AiProvider: String, Codable, CaseIterable, Sendable, Identifiable {
    case gemini = "Gemini"
    case deepSeek = "DeepSeek"
    case qwen = "Qwen"
    case custom = "Custom"

    var id: String { rawValue }

    /// Quality tiers offered by this service. Qwen has a single optimal tier; Custom uses the typed model.
    var tiers: [AiTier] {
        switch self {
        case .gemini, .deepSeek: [.good, .best]
        case .qwen: [.good]
        case .custom: []
        }
    }

    /// Services reachable directly from mainland China without a proxy (`中国直连`).
    var isDirectInChina: Bool {
        self == .deepSeek || self == .qwen
    }
}

/// Quality tier (`好` / `最佳`). Exact meaning per provider lives on `AiSettings.modelId` / `searchQueries`.
enum AiTier: String, Codable, CaseIterable, Sendable, Identifiable {
    case good = "Good"
    case best = "Best"

    var id: String { rawValue }
}

/// API dialect for Custom endpoints.
enum AiApiFormat: String, Codable, CaseIterable, Sendable, Identifiable {
    case openAi = "OpenAi"
    case anthropic = "Anthropic"

    var id: String { rawValue }
}

/// Reasoning / thinking budget levels for Custom endpoints.
enum AiThinking: String, Codable, CaseIterable, Sendable, Identifiable {
    case off = "Off"
    case low = "Low"
    case medium = "Medium"
    case high = "High"

    var id: String { rawValue }
}

/// External web search backends selectable for Custom (in UI menu order).
enum AiSearchService: String, Codable, CaseIterable, Sendable, Identifiable {
    case deepSeek = "DeepSeek"
    case exa = "Exa"
    case tavily = "Tavily"
    case brave = "Brave"
    case doubao = "Doubao"
    case none = "None"

    var id: String { rawValue }
}

/// Non-secret user preferences for AI settings v2. Stored as JSON in `UserDefaults`; keys never live here.
struct AiProviderPrefs: Codable, Equatable, Sendable {
    var provider: AiProvider = .gemini
    var tier: AiTier = .good
    var customFormat: AiApiFormat = .openAi
    var customBaseUrl: String = ""
    var customModel: String = ""
    var customThinking: AiThinking = .off
    var searchService: AiSearchService = .deepSeek

    /// The tier effectively used for `provider`, falling back to its single tier without overwriting `tier`.
    var activeTier: AiTier {
        if provider.tiers.contains(tier) { return tier }
        return provider.tiers.first ?? tier
    }

    enum CodingKeys: String, CodingKey {
        case provider, tier, customFormat, customBaseUrl, customModel, customThinking, searchService
    }

    init(
        provider: AiProvider = .gemini,
        tier: AiTier = .good,
        customFormat: AiApiFormat = .openAi,
        customBaseUrl: String = "",
        customModel: String = "",
        customThinking: AiThinking = .off,
        searchService: AiSearchService = .deepSeek
    ) {
        self.provider = provider
        self.tier = tier
        self.customFormat = customFormat
        self.customBaseUrl = customBaseUrl
        self.customModel = customModel
        self.customThinking = customThinking
        self.searchService = searchService
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = (try? c.decodeIfPresent(AiProvider.self, forKey: .provider)) ?? .gemini
        tier = (try? c.decodeIfPresent(AiTier.self, forKey: .tier)) ?? .good
        customFormat = (try? c.decodeIfPresent(AiApiFormat.self, forKey: .customFormat)) ?? .openAi
        customBaseUrl = (try? c.decodeIfPresent(String.self, forKey: .customBaseUrl)) ?? ""
        customModel = (try? c.decodeIfPresent(String.self, forKey: .customModel)) ?? ""
        customThinking = (try? c.decodeIfPresent(AiThinking.self, forKey: .customThinking)) ?? .off
        searchService = (try? c.decodeIfPresent(AiSearchService.self, forKey: .searchService)) ?? .deepSeek
    }
}

/// In-memory API key store (backed exclusively by Keychain). Custom descriptions redact all secrets.
struct AiSecrets: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    var deepSeekKey: String = ""
    var qwenKey: String = ""
    var customKey: String = ""
    var searchKeyDeepSeek: String = ""
    var searchKeyExa: String = ""
    var searchKeyTavily: String = ""
    var searchKeyBrave: String = ""
    var searchKeyDoubao: String = ""

    var description: String { "AiSecrets(redacted)" }
    var debugDescription: String { "AiSecrets(redacted)" }

    func searchKey(for service: AiSearchService) -> String {
        switch service {
        case .deepSeek: searchKeyDeepSeek
        case .exa: searchKeyExa
        case .tavily: searchKeyTavily
        case .brave: searchKeyBrave
        case .doubao: searchKeyDoubao
        case .none: ""
        }
    }

    func withSearchKey(_ key: String, for service: AiSearchService) -> AiSecrets {
        var copy = self
        switch service {
        case .deepSeek: copy.searchKeyDeepSeek = key
        case .exa: copy.searchKeyExa = key
        case .tavily: copy.searchKeyTavily = key
        case .brave: copy.searchKeyBrave = key
        case .doubao: copy.searchKeyDoubao = key
        case .none: break
        }
        return copy
    }

    func key(for provider: AiProvider) -> String? {
        switch provider {
        case .gemini: nil
        case .deepSeek: deepSeekKey
        case .qwen: qwenKey
        case .custom: customKey
        }
    }

    func withProviderKey(_ key: String, for provider: AiProvider) -> AiSecrets {
        var copy = self
        switch provider {
        case .gemini: break
        case .deepSeek: copy.deepSeekKey = key
        case .qwen: copy.qwenKey = key
        case .custom: copy.customKey = key
        }
        return copy
    }
}

/// Keychain storage for non-Gemini provider keys and all five external web search keys.
struct AiKeychain: Sendable {
    enum Account: String, CaseIterable, Sendable {
        case deepSeek = "deepSeekKey"
        case qwen = "qwenKey"
        case custom = "customKey"
        case searchDeepSeek = "searchKeyDeepSeek"
        case searchExa = "searchKeyExa"
        case searchTavily = "searchKeyTavily"
        case searchBrave = "searchKeyBrave"
        case searchDoubao = "searchKeyDoubao"

        init?(provider: AiProvider) {
            switch provider {
            case .gemini: return nil
            case .deepSeek: self = .deepSeek
            case .qwen: self = .qwen
            case .custom: self = .custom
            }
        }

        init?(searchService: AiSearchService) {
            switch searchService {
            case .deepSeek: self = .searchDeepSeek
            case .exa: self = .searchExa
            case .tavily: self = .searchTavily
            case .brave: self = .searchBrave
            case .doubao: self = .searchDoubao
            case .none: return nil
            }
        }
    }

    var service = "com.gpo.yoin.ai"

    private func query(for account: Account) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account.rawValue,
        ]
    }

    func load(_ account: Account) -> String? {
        var q = query(for: account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func loadAll() -> AiSecrets {
        AiSecrets(
            deepSeekKey: load(.deepSeek) ?? "",
            qwenKey: load(.qwen) ?? "",
            customKey: load(.custom) ?? "",
            searchKeyDeepSeek: load(.searchDeepSeek) ?? "",
            searchKeyExa: load(.searchExa) ?? "",
            searchKeyTavily: load(.searchTavily) ?? "",
            searchKeyBrave: load(.searchBrave) ?? "",
            searchKeyDoubao: load(.searchDoubao) ?? ""
        )
    }

    @discardableResult
    func save(_ value: String, for account: Account) -> OSStatus {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            let status = SecItemDelete(query(for: account) as CFDictionary)
            return status == errSecItemNotFound ? errSecSuccess : status
        }
        return KeychainItem.upsert(query: query(for: account), data: Data(trimmed.utf8))
    }

    func deleteAll() {
        for account in Account.allCases {
            SecItemDelete(query(for: account) as CFDictionary)
        }
    }
}

/// Complete resolved AI v2 configuration passed into `GeminiClient` calls.
struct AiSettings: Equatable, Sendable {
    enum SearchKind: Equatable, Sendable {
        case builtIn
        case external
        case none
    }

    static let geminiGood = "gemini-3.1-flash-lite"
    static let geminiBest = "gemini-3.5-flash-lite"
    static let deepSeekModel = "deepseek-flash"
    static let qwenModel = "qwen-flash"
    static let deepSeekBaseURL = "https://api.deepseek.com"
    static let qwenBaseURL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    var prefs: AiProviderPrefs
    var secrets: AiSecrets
    var geminiKey: String

    init(prefs: AiProviderPrefs = AiProviderPrefs(), secrets: AiSecrets = AiSecrets(), geminiKey: String = "") {
        self.prefs = prefs
        self.secrets = secrets
        self.geminiKey = geminiKey
    }

    var provider: AiProvider { prefs.provider }

    var apiKey: String {
        switch provider {
        case .gemini: geminiKey
        case .deepSeek: secrets.deepSeekKey
        case .qwen: secrets.qwenKey
        case .custom: secrets.customKey
        }
    }

    private var trimmedKey: String { apiKey.trimmingCharacters(in: .whitespacesAndNewlines) }

    var isReady: Bool {
        guard !trimmedKey.isEmpty else { return false }
        if provider != .custom { return true }
        return !prefs.customBaseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !prefs.customModel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var modelId: String {
        switch provider {
        case .gemini:
            prefs.activeTier == .best ? Self.geminiBest : Self.geminiGood
        case .deepSeek:
            Self.deepSeekModel
        case .qwen:
            Self.qwenModel
        case .custom:
            prefs.customModel.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    var searchKind: SearchKind {
        switch provider {
        case .gemini, .qwen:
            .builtIn
        case .deepSeek:
            .external
        case .custom:
            (prefs.searchService == .none || searchApiKey.isEmpty) ? .none : .external
        }
    }

    var searchService: AiSearchService {
        provider == .deepSeek ? .deepSeek : prefs.searchService
    }

    var searchApiKey: String {
        let raw = provider == .deepSeek ? secrets.deepSeekKey : secrets.searchKey(for: prefs.searchService)
        return raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var searchQueries: Int {
        (provider == .deepSeek && prefs.activeTier == .best) ? 2 : 1
    }

    /// Backwards-compatible v1 Gemini configuration.
    static func geminiOnly(apiKey: String) -> AiSettings {
        AiSettings(prefs: AiProviderPrefs(), secrets: AiSecrets(), geminiKey: apiKey)
    }

    /// Filter chips when a Custom endpoint exposes dozens of models (matching Android `modelChips`).
    static func filteredModelChips(ids: [String], typed: String, limit: Int = 24) -> [String] {
        if ids.count <= limit { return ids }
        let query = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty { return Array(ids.prefix(limit)) }
        return Array(ids.lazy.filter { $0.localizedCaseInsensitiveContains(query) }.prefix(limit))
    }
}

// MARK: - Benchmark Chart & Labels

protocol AiChartPoint {
    var accuracy: Float { get }
    var seconds: Float { get }
    var costPer1000: Float { get }
    var estimated: Bool { get }
}

extension AiChartPoint {
    var speedScore: Float {
        let raw = (AiTierPoint.slowestSeconds - seconds) /
            (AiTierPoint.slowestSeconds - AiTierPoint.fastestSeconds) * 100
        return min(max(raw, 0), 100)
    }

    var overall: Float {
        AiTierPoint.accuracyWeight * accuracy + (1 - AiTierPoint.accuracyWeight) * speedScore
    }
}

struct AiTierPoint: AiChartPoint, Equatable, Sendable {
    static let fastestSeconds: Float = 1
    static let slowestSeconds: Float = 10
    static let accuracyWeight: Float = 0.7

    let provider: AiProvider
    let tier: AiTier
    let accuracy: Float
    let seconds: Float
    let costPer1000: Float
    var costEstimated: Bool = false
    var estimated: Bool = false
}

enum AiTierBenchmark {
    static let points: [AiTierPoint] = [
        AiTierPoint(provider: .gemini, tier: .good, accuracy: 95, seconds: 2.8, costPer1000: 0.54),
        AiTierPoint(provider: .gemini, tier: .best, accuracy: 95, seconds: 2.6, costPer1000: 0.95, costEstimated: true),
        AiTierPoint(provider: .deepSeek, tier: .good, accuracy: 92, seconds: 3.7, costPer1000: 2.89),
        AiTierPoint(provider: .deepSeek, tier: .best, accuracy: 93, seconds: 4.4, costPer1000: 3.9, estimated: true),
        AiTierPoint(provider: .qwen, tier: .good, accuracy: 83, seconds: 4.9, costPer1000: 0.57),
    ]

    static func point(provider: AiProvider, tier: AiTier) -> AiTierPoint? {
        points.first { $0.provider == provider && $0.tier == tier }
    }
}

struct AiSearchPoint: AiChartPoint, Equatable, Sendable {
    let service: AiSearchService
    let accuracy: Float
    let seconds: Float
    let costPer1000: Float
    var estimated: Bool = false
}

enum AiSearchBenchmark {
    static let points: [AiSearchPoint] = [
        AiSearchPoint(service: .deepSeek, accuracy: 91.9, seconds: 3.69, costPer1000: 2.89),
        AiSearchPoint(service: .exa, accuracy: 92.7, seconds: 3.94, costPer1000: 9.04),
        AiSearchPoint(service: .tavily, accuracy: 88, seconds: 5.7, costPer1000: 17.2, estimated: true),
        AiSearchPoint(service: .brave, accuracy: 80, seconds: 3.2, costPer1000: 5.7, estimated: true),
        AiSearchPoint(service: .doubao, accuracy: 88.2, seconds: 4.47, costPer1000: 2.38),
        AiSearchPoint(service: .none, accuracy: 65.0, seconds: 2.40, costPer1000: 0.32),
    ]

    static func point(service: AiSearchService) -> AiSearchPoint {
        points.first { $0.service == service } ?? points[0]
    }
}

enum AiChartSet: CaseIterable, Sendable {
    case tiers
    case search

    var points: [any AiChartPoint] {
        switch self {
        case .tiers: AiTierBenchmark.points
        case .search: AiSearchBenchmark.points
        }
    }

    func range(for axis: AiAxis) -> ClosedRange<Float> {
        switch axis {
        case .speed: AiTierPoint.fastestSeconds...AiTierPoint.slowestSeconds
        case .cost: self == .tiers ? 0...4 : 0...20
        case .accuracy: self == .tiers ? 70...100 : 60...100
        case .overall: 50...100
        }
    }

    static func forProvider(_ provider: AiProvider) -> AiChartSet {
        provider == .custom ? .search : .tiers
    }
}

enum AiAxis: CaseIterable, Sendable {
    case speed
    case cost
    case accuracy
    case overall

    var higherIsBetter: Bool {
        self == .accuracy || self == .overall
    }

    func value(_ point: any AiChartPoint) -> Float {
        switch self {
        case .speed: point.seconds
        case .cost: point.costPer1000
        case .accuracy: point.accuracy
        case .overall: point.overall
        }
    }

    func fraction(_ point: any AiChartPoint, in set: AiChartSet) -> Float {
        let range = set.range(for: self)
        let raw = (value(point) - range.lowerBound) / (range.upperBound - range.lowerBound)
        let clamped = min(max(raw, 0), 1)
        return higherIsBetter ? clamped : 1 - clamped
    }

    func axisValue(in set: AiChartSet, worst: Bool) -> String {
        let atMin = higherIsBetter == worst
        let range = set.range(for: self)
        let v = atMin ? range.lowerBound : range.upperBound
        let num = Self.trimNumber(v)
        switch self {
        case .speed: return "\(num)s"
        case .cost: return "$\(num)"
        case .accuracy: return "\(num)%"
        case .overall: return num
        }
    }

    static func trimNumber(_ value: Float) -> String {
        if value == Float(Int(value)) { return String(Int(value)) }
        return String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }
}

let aiChartViews: [(x: AiAxis, y: AiAxis)] = [
    (.cost, .overall),
    (.speed, .accuracy),
    (.cost, .accuracy),
    (.speed, .cost),
]

/// Places benchmark dot labels beside each dot avoiding plot edges, dots, and earlier labels.
enum AiChartLabels {
    static func place(
        plot: CGRect,
        dots: [CGPoint],
        dotRadius: CGFloat,
        gap: CGFloat,
        labels: [(index: Int, size: CGSize)]
    ) -> [CGPoint] {
        var placed: [CGRect] = []
        return labels.map { index, size in
            let centre = dots[index]
            let sides = [
                CGPoint(x: gap, y: -size.height / 2),
                CGPoint(x: -gap - size.width, y: -size.height / 2),
                CGPoint(x: -size.width / 2, y: -gap - size.height),
                CGPoint(x: -size.width / 2, y: gap),
                CGPoint(x: 0, y: -gap - size.height),
                CGPoint(x: 0, y: gap),
                CGPoint(x: -size.width, y: -gap - size.height),
                CGPoint(x: -size.width, y: gap),
            ]
            let best = sides.min { lhs, rhs in
                clashes(side: lhs, centre: centre, size: size, index: index,
                        plot: plot, dots: dots, dotRadius: dotRadius, placed: placed)
                    < clashes(side: rhs, centre: centre, size: size, index: index,
                              plot: plot, dots: dots, dotRadius: dotRadius, placed: placed)
            } ?? sides[0]
            placed.append(CGRect(origin: CGPoint(x: centre.x + best.x, y: centre.y + best.y), size: size))
            return best
        }
    }

    private static func clashes(
        side: CGPoint,
        centre: CGPoint,
        size: CGSize,
        index: Int,
        plot: CGRect,
        dots: [CGPoint],
        dotRadius: CGFloat,
        placed: [CGRect]
    ) -> Int {
        let box = CGRect(origin: CGPoint(x: centre.x + side.x, y: centre.y + side.y), size: size)
        var count = 0
        if box.minX < plot.minX || box.maxX > plot.maxX || box.minY < plot.minY || box.maxY > plot.maxY {
            count += 1
        }
        for (i, dot) in dots.enumerated() where i != index {
            let nearX = min(max(dot.x, box.minX), box.maxX)
            let nearY = min(max(dot.y, box.minY), box.maxY)
            let dx = dot.x - nearX
            let dy = dot.y - nearY
            if dx * dx + dy * dy < dotRadius * dotRadius {
                count += 1
            }
        }
        for prior in placed where prior.intersects(box) {
            count += 1
        }
        return count
    }
}
