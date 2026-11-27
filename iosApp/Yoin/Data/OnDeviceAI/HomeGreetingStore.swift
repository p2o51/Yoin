import Foundation
import Observation
import YoinShared

/// One account's greetings for one day and language (Android DailyHomeGreetings), as stored.
struct StoredHomeGreetings: Codable, Equatable, Sendable {
    var day: Int64
    var localeTag: String
    var profileId: String
    var morning: String?
    var afternoon: String?
    var evening: String?
    var cuesFingerprint: String
    var refreshCount: Int

    init(day: Int64, localeTag: String, profileId: String, morning: String?, afternoon: String?, evening: String?,
         cuesFingerprint: String = "", refreshCount: Int = 0) {
        self.day = day
        self.localeTag = localeTag
        self.profileId = profileId
        self.morning = morning
        self.afternoon = afternoon
        self.evening = evening
        self.cuesFingerprint = cuesFingerprint
        self.refreshCount = refreshCount
    }

    init(_ k: DailyHomeGreetings) {
        self.init(day: k.day, localeTag: k.localeTag, profileId: k.profileId, morning: k.morning,
                  afternoon: k.afternoon, evening: k.evening, cuesFingerprint: k.cuesFingerprint,
                  refreshCount: Int(k.refreshCount))
    }

    var kotlin: DailyHomeGreetings {
        YoinOnDeviceAi.shared.greetings(day: day, localeTag: localeTag, profileId: profileId, morning: morning,
                                        afternoon: afternoon, evening: evening, cuesFingerprint: cuesFingerprint,
                                        refreshCount: Int32(refreshCount))
    }

    var hasAny: Bool { morning != nil || afternoon != nil || evening != nil }
}

/// Today's three on-device greetings for Home's header, per account (Android HomeGreetingStore): generated
/// once a day in one call while Yoin is open, so the header shows its line at once on the next launch with no
/// model wait. The rules (slots, when to generate, the upcoming-slot merge, the 7-day phrase history, the
/// prompt and the validator) are `:shared`'s `YoinOnDeviceAi`, the same as Android's; this keeps them in
/// UserDefaults. Anything missing, stale or in another language leaves Home on its static greeting.
@MainActor
@Observable
final class HomeGreetingStore {
    static let shared = HomeGreetingStore()

    /// Android GenerationOutcome (no AICore quota or foreground refusals on iOS).
    enum Outcome: Equatable { case generated, alreadyReady, unusable, busy, failed }

    /// Every account's cached day, by profile id.
    private(set) var greetings: [String: StoredHomeGreetings]
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let timeZone: () -> TimeZone
    @ObservationIgnored private let language: () -> String

    private static let greetingsKey = "onDeviceHomeGreetings"
    private static let attemptsKey = "onDeviceHomeGreetingsAttempted"
    private static func historyKey(_ lang: String) -> String { "onDeviceHomeGreetingsHistory_\(lang)" }

    private var ai: YoinOnDeviceAi { .shared }

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init,
         timeZone: @escaping () -> TimeZone = { .current }, language: @escaping () -> String = { OnDeviceLanguage.app }) {
        self.defaults = defaults
        self.now = now
        self.timeZone = timeZone
        self.language = language
        greetings = defaults.data(forKey: Self.greetingsKey)
            .flatMap { try? JSONDecoder().decode([String: StoredHomeGreetings].self, from: $0) } ?? [:]
    }

    /// The prompt language now ("zh" / "en").
    var lang: String { ai.normalizeLocale(languageTag: language()) }

    /// The local day number at [date].
    func today(_ date: Date? = nil) -> Int64 {
        let d = date ?? now()
        let ms = Int64(d.timeIntervalSince1970 * 1000)
        return ai.dayOf(epochMs: ms, utcOffsetMs: Int64(timeZone().secondsFromGMT(for: d)) * 1000)
    }

    func hour(_ date: Date? = nil) -> Int {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone()
        return cal.component(.hour, from: date ?? now())
    }

    /// Home's line at [date] for [profileId], or nil for the static greeting. At noon (11–13) an afternoon line
    /// that says "afternoon" / 下午 waits (Android cachedFor's isNoon).
    func text(at date: Date, profileId: String?) -> String? {
        guard let cached = greetings[profileId ?? ""] else { return nil }
        let h = hour(date)
        return ai.cachedText(cached: cached.kotlin, slot: ai.slotAtHour(hour: Int32(h)), today: today(date), lang: lang,
                             isNoon: ai.isNoonHour(hour: Int32(h)))
    }

    /// Whether a visit should look at [profileId]'s greetings (nothing today yet, or an upcoming slot may refresh).
    func needsGeneration(profileId: String) -> Bool {
        ai.needsGeneration(cached: greetings[profileId]?.kotlin, today: today(), lang: lang,
                           currentSlot: ai.slotAtHour(hour: Int32(hour())), lastAttempt: attempt(profileId))
    }

    /// Generates (or refreshes the upcoming slots of) today's greetings from [cues], asking [model] at most once.
    func generateIfNeeded(profileId: String, cues: HomeGreetingPrompt.SlotCues,
                          model: any OnDeviceTextModel) async -> Outcome {
        let date = now()
        let today = today(date)
        let lang = lang
        let slot = ai.slotAtHour(hour: Int32(hour(date)))
        let cached = greetings[profileId]?.kotlin
        let fp = cues.fingerprint()
        guard ai.shouldGenerate(cached: cached, today: today, lang: lang, currentSlot: slot, cuesFingerprint: fp,
                                lastAttempt: attempt(profileId)) else { return .alreadyReady }
        let avoid = ai.recentHistory(raw: defaults.string(forKey: Self.historyKey(lang)) ?? "", today: today)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone()
        // ISO weekday (Monday = 1) from the Gregorian one (Sunday = 1).
        let weekday = (cal.component(.weekday, from: date) + 5) % 7 + 1
        let month = cal.component(.month, from: date)

        let candidate: DailyHomeGreetings?
        switch await model.greetings(
            instructions: ai.greetingInstructions(hasAnyCue: cues.hasAny(), localeTag: lang),
            prompt: ai.greetingUserPrompt(cues: cues, dayOfWeek: Int32(weekday), month: Int32(month), localeTag: lang,
                                          avoidRecent: avoid),
            temperature: Double(ai.greetingTemperature), maxTokens: Int(ai.greetingMaxOutputTokens)) {
        case .value(let slots):
            candidate = ai.validateGreetings(morning: slots.morning, afternoon: slots.afternoon, evening: slots.evening,
                                             day: today, localeTag: lang, profileId: profileId, cues: cues,
                                             avoidRecent: avoid)
        case .unsupported:
            let prompt = ai.greetingPlainPrompt(cues: cues, dayOfWeek: Int32(weekday), month: Int32(month),
                                                localeTag: lang, avoidRecent: avoid)
            switch await model.text(prompt, temperature: Double(ai.greetingTemperature),
                                    maxTokens: Int(ai.greetingMaxOutputTokens)) {
            case .text(let answer):
                candidate = ai.parseGreetings(answer: answer, day: today, localeTag: lang, profileId: profileId,
                                              cues: cues, avoidRecent: avoid)
            case .busy: return .busy
            case .failed: return .failed
            }
        case .busy: return .busy
        case .failed: return .failed
        }

        var attempts = defaults.dictionary(forKey: Self.attemptsKey) as? [String: String] ?? [:]
        attempts[profileId] = ai.attemptValue(day: today, lang: lang, cuesFingerprint: fp)
        defaults.set(attempts, forKey: Self.attemptsKey)
        guard let candidate else { return .unusable }

        // The slot on screen and the ones before it never change under the listener; only the upcoming ones do.
        let merged = StoredHomeGreetings(ai.merged(existing: ai.todays(cached: cached, today: today, lang: lang),
                                                   fresh: candidate, currentSlot: slot, cuesFingerprint: fp))
        save(merged)
        if let history = ai.recordHistory(raw: defaults.string(forKey: Self.historyKey(lang)) ?? "", today: today,
                                          phrases: [merged.morning, merged.afternoon, merged.evening].compactMap { $0 }) {
            defaults.set(history, forKey: Self.historyKey(lang))
        }
        return .generated
    }

    /// Clears every cached greeting and the phrase history (tests, data reset).
    func clear() {
        greetings = [:]
        defaults.removeObject(forKey: Self.greetingsKey)
        defaults.removeObject(forKey: Self.attemptsKey)
        for l in ["en", "zh"] { defaults.removeObject(forKey: Self.historyKey(l)) }
    }

    private func attempt(_ profileId: String) -> String? {
        (defaults.dictionary(forKey: Self.attemptsKey) as? [String: String])?[profileId]
    }

    private func save(_ g: StoredHomeGreetings) {
        greetings[g.profileId] = g
        if let data = try? JSONEncoder().encode(greetings) { defaults.set(data, forKey: Self.greetingsKey) }
    }

    #if DEBUG
    /// Screenshots: a day's greetings written straight in (`-greetingDemo`).
    func demoSave(_ g: StoredHomeGreetings) { save(g) }
    #endif
}
