import Foundation
import FoundationModels
import YoinShared

/// What a structured (guided) call gave (Android NanoTypedAnswer).
enum OnDeviceTyped<Value: Sendable>: Sendable {
    case value(Value)
    /// The guided answer couldn't be decoded: ask again in plain text (Android's Unsupported).
    case unsupported
    /// Rate limited or busy: back off.
    case busy
    case failed
}

/// The three greetings as the model wrote them, before Yoin checks them.
struct OnDeviceGreetingSlots: Sendable, Equatable {
    var morning: String
    var afternoon: String
    var evening: String
}

/// The model seam for Home's greetings and About's follow-up questions (Android LyricHighlightModel's
/// `answerTyped` / `answer`), so the store and the generator are testable without Apple Intelligence.
protocol OnDeviceTextModel: Sendable {
    func status() -> OnDeviceModelStatus
    /// Whether the model writes [language] (the app's language, "en" or "zh-Hans").
    func supportsLanguage(_ language: String) -> Bool
    func greetings(instructions: String, prompt: String, temperature: Double, maxTokens: Int) async
        -> OnDeviceTyped<OnDeviceGreetingSlots>
    func followUps(instructions: String, prompt: String, temperature: Double, maxTokens: Int) async
        -> OnDeviceTyped<[String]>
    /// One plain-text answer to a whole prompt (the fallback when the guided answer can't be decoded).
    func text(_ prompt: String, temperature: Double, maxTokens: Int) async -> OnDeviceAnswer
}

extension OnDeviceModelStatus {
    /// What the system says about its on-device model now (no inference).
    static func current() -> OnDeviceModelStatus {
        switch SystemLanguageModel.default.availability {
        case .available: return .available
        case .unavailable(.appleIntelligenceNotEnabled): return .notEnabled
        case .unavailable(.modelNotReady): return .notReady
        case .unavailable: return .unsupported
        }
    }
}

/// The app's language as the prompts see it: Yoin's resolved localization ("en", "zh-Hans"), as Android reads
/// `Locale.getDefault()` (the per-app language).
enum OnDeviceLanguage {
    static var app: String { Bundle.main.preferredLocalizations.first ?? "en" }
}

/// Android HomeGreetingSchema (ML Kit GenAI structured output), as FoundationModels guided generation.
@Generable(description: "Three short daily greetings for morning, afternoon, and evening on the Home header")
struct HomeGreetingSchema {
    @Guide(description: "Short morning greeting (single line, no emojis)")
    var morning: String
    @Guide(description: "Short afternoon greeting (single line, no emojis)")
    var afternoon: String
    @Guide(description: "Short evening greeting (single line, no emojis)")
    var evening: String
}

/// Android AboutFollowUpSchema.
@Generable(description: "Two to three short follow-up questions grounded in the song About notes")
struct AboutFollowUpSchema {
    @Guide(description: "2 to 3 concise follow-up questions in the requested language, ending with a question mark",
           .count(2...3))
    var questions: [String]
}

/// Apple Intelligence's on-device model (FoundationModels), one fresh session per question. Every call is
/// bounded by [timeout] so nothing waits on a model that hangs; the caller shows the static text meanwhile.
struct AppleOnDeviceTextModel: OnDeviceTextModel {
    var timeout: Duration = .seconds(30)

    func status() -> OnDeviceModelStatus { .current() }

    func supportsLanguage(_ language: String) -> Bool {
        SystemLanguageModel.default.supportsLocale(Locale(identifier: language))
    }

    func greetings(instructions: String, prompt: String, temperature: Double, maxTokens: Int) async
        -> OnDeviceTyped<OnDeviceGreetingSlots> {
        await OnDeviceTimeout.run(timeout, fallback: .failed) {
            let session = LanguageModelSession(instructions: instructions)
            do {
                let r = try await session.respond(to: prompt, generating: HomeGreetingSchema.self,
                                                  options: GenerationOptions(temperature: temperature,
                                                                             maximumResponseTokens: maxTokens))
                return .value(OnDeviceGreetingSlots(morning: r.content.morning, afternoon: r.content.afternoon,
                                                    evening: r.content.evening))
            } catch {
                return Self.typedFailure(error)
            }
        }
    }

    func followUps(instructions: String, prompt: String, temperature: Double, maxTokens: Int) async
        -> OnDeviceTyped<[String]> {
        await OnDeviceTimeout.run(timeout, fallback: .failed) {
            let session = LanguageModelSession(instructions: instructions)
            do {
                let r = try await session.respond(to: prompt, generating: AboutFollowUpSchema.self,
                                                  options: GenerationOptions(temperature: temperature,
                                                                             maximumResponseTokens: maxTokens))
                return .value(r.content.questions)
            } catch {
                return Self.typedFailure(error)
            }
        }
    }

    func text(_ prompt: String, temperature: Double, maxTokens: Int) async -> OnDeviceAnswer {
        await OnDeviceTimeout.run(timeout, fallback: .failed) {
            let session = LanguageModelSession()
            do {
                let r = try await session.respond(to: prompt, options: GenerationOptions(
                    temperature: temperature, maximumResponseTokens: maxTokens))
                let text = r.content.trimmingCharacters(in: .whitespacesAndNewlines)
                return text.isEmpty ? .failed : .text(text)
            } catch let e as LanguageModelSession.GenerationError {
                switch e {
                case .rateLimited, .concurrentRequests: return .busy
                default: return .failed
                }
            } catch {
                return .failed
            }
        }
    }

    private static func typedFailure<T>(_ error: Error) -> OnDeviceTyped<T> {
        guard let e = error as? LanguageModelSession.GenerationError else { return .failed }
        switch e {
        case .rateLimited, .concurrentRequests: return .busy
        // The guided answer didn't decode: the plain-text prompt and parser get a turn.
        case .decodingFailure, .unsupportedGuide: return .unsupported
        default: return .failed
        }
    }
}

/// A model call raced against a deadline: the first to finish wins, the other is cancelled.
enum OnDeviceTimeout {
    static func run<T: Sendable>(_ limit: Duration, fallback: T,
                                 _ work: @escaping @Sendable () async -> T) async -> T {
        await withTaskGroup(of: T?.self) { group in
            group.addTask { await work() }
            group.addTask {
                try? await Task.sleep(for: limit)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? fallback
        }
    }
}
