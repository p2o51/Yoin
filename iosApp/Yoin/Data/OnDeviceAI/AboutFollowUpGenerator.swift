import Foundation
import UIKit
import YoinShared

/// Suggested follow-up questions for Now Playing's About → Ask (Android AboutFollowUpGenerator): Apple
/// Intelligence reads only the About rows already on screen and proposes two or three short questions, which go
/// ahead of the static quick prompts (the online AI answers whichever is tapped). Results are kept per About
/// snapshot (an LRU of 32 by the prompt's fingerprint), so reopening Ask on the same song is instant. Off in Labs,
/// no Apple Intelligence, a language the model doesn't write, Yoin in the background, a refusal or a timeout:
/// an empty list, silently, and the static questions carry on.
@MainActor
final class AboutFollowUpGenerator {
    static let shared = AboutFollowUpGenerator(model: AppleOnDeviceTextModel(timeout: .seconds(20)))

    private let model: any OnDeviceTextModel
    private let enabled: @MainActor () -> Bool
    private let isForeground: @MainActor () -> Bool
    private let language: @MainActor () -> String
    private var cache: [String: [String]] = [:]
    /// Least recently used first.
    private var order: [String] = []
    private var inFlight: [String: Task<[String], Never>] = [:]

    static let cacheSize = 32

    init(model: any OnDeviceTextModel,
         enabled: @escaping @MainActor () -> Bool = { LyricHighlightStore.shared.aboutFollowUpsEnabled },
         isForeground: @escaping @MainActor () -> Bool = { UIApplication.shared.applicationState == .active },
         language: @escaping @MainActor () -> String = { OnDeviceLanguage.app }) {
        self.model = model
        self.enabled = enabled
        self.isForeground = isForeground
        self.language = language
    }

    /// The song's rows as the shared prompt reads them.
    nonisolated static func entries(_ rows: [SongAboutStore.Entry]) -> [AboutFollowUpEntry] {
        rows.map {
            YoinOnDeviceAi.shared.aboutEntry(kind: $0.kind, entryKey: $0.entryKey, titleDisplay: $0.titleDisplay,
                                             artistDisplay: $0.artistDisplay, titleText: $0.titleText,
                                             promptText: $0.promptText, answerText: $0.answerText)
        }
    }

    private func prepare(_ rows: [SongAboutStore.Entry], _ asked: [String]) -> AboutFollowUpPrompt.Prepared? {
        guard !rows.isEmpty else { return nil }
        return YoinOnDeviceAi.shared.prepareFollowUps(entries: Self.entries(rows), asked: asked, localeTag: language())
    }

    /// The questions already made for this About snapshot, if any (no model call).
    func peekCached(_ rows: [SongAboutStore.Entry], asked: [String]) -> [String]? {
        guard let p = prepare(rows, asked) else { return nil }
        return cache[p.fingerprint]
    }

    /// What identifies this About snapshot (nil when there is too little to ask about).
    func key(_ rows: [SongAboutStore.Entry], asked: [String]) -> String? { prepare(rows, asked)?.fingerprint }

    /// Two or three questions grounded in [rows], or none. Never throws, never blocks the caller's UI.
    func suggest(_ rows: [SongAboutStore.Entry], asked: [String]) async -> [String] {
        guard allowed(), let prepared = prepare(rows, asked) else { return [] }
        let key = prepared.fingerprint
        if let hit = cache[key] {
            touch(key)
            return hit
        }
        if let running = inFlight[key] { return await running.value }
        let model = model
        let ai = YoinOnDeviceAi.shared
        let lang = prepared.localeTag
        let askedNow = prepared.asked
        let instructions = ai.followUpInstructions(localeTag: lang)
        let userPrompt = ai.followUpUserPrompt(prepared: prepared)
        let plainPrompt = ai.followUpPlainPrompt(prepared: prepared)
        let temperature = Double(ai.followUpTemperature)
        let maxTokens = Int(ai.followUpMaxOutputTokens)
        let task = Task { @MainActor () -> [String] in
            switch await model.followUps(instructions: instructions, prompt: userPrompt, temperature: temperature,
                                         maxTokens: maxTokens) {
            case .value(let raw):
                return ai.validateFollowUps(questions: raw, asked: askedNow, localeTag: lang)
            case .unsupported:
                guard case .text(let answer) = await model.text(plainPrompt, temperature: temperature,
                                                                maxTokens: maxTokens) else { return [] }
                return ai.parseFollowUps(answer: answer, asked: askedNow, localeTag: lang)
            case .busy, .failed:
                return []
            }
        }
        inFlight[key] = task
        let out = await task.value
        inFlight[key] = nil
        if !out.isEmpty { store(key, out) }
        return out
    }

    /// Android's gate: the Labs switch, the model ready, Yoin in front, and a language the model writes.
    private func allowed() -> Bool {
        enabled() && isForeground() && model.status() == .available && model.supportsLanguage(language())
    }

    private func store(_ key: String, _ value: [String]) {
        cache[key] = value
        touch(key)
        while order.count > Self.cacheSize { cache[order.removeFirst()] = nil }
    }

    private func touch(_ key: String) {
        order.removeAll { $0 == key }
        order.append(key)
    }
}
