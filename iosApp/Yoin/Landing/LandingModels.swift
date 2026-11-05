import Foundation

// The first-run landing (Android ui/landing, docs/design.md › 首次引导): one
// surface above the shell with an in-page state machine. This file is the
// logic only (steps, the first-launch gate, what the pill's button says), so
// it can be tested without a screen.

/// A service the landing can connect. Spotify is listed the way Android lists a
/// service it can't add yet: shown, dimmed, not pickable (no Spotify SDK on iOS).
enum LandingService: String, CaseIterable, Sendable {
    case subsonic, spotify, appleMusic

    var availableOnIOS: Bool { self != .spotify }

    var name: String {
        switch self {
        case .subsonic: "Subsonic"
        case .spotify: "Spotify"
        case .appleMusic: "Apple Music"
        }
    }
}

/// Android LandingStep, minus the steps iOS has nothing behind yet: Spotoolfy
/// and Spotify (no Spotify on iOS), Home (iOS Home has no section layout to
/// write) and Edge (no scroll-edge styles on iOS).
enum LandingStep: String, CaseIterable, Sendable, Hashable {
    case hello, pick, about, subsonic, appleMusic, ready

    var service: LandingService? {
        switch self {
        case .subsonic: .subsonic
        case .appleMusic: .appleMusic
        default: nil
        }
    }

    static func connect(_ service: LandingService) -> LandingStep? {
        switch service {
        case .subsonic: .subsonic
        case .appleMusic: .appleMusic
        case .spotify: nil
        }
    }

    /// Position in the catalog: forward vs. back for the window's slide.
    var order: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// First run (no account yet) or re-run from Settings › About › Welcome guide.
enum LandingMode: Sendable, Equatable {
    case firstRun, rerun
}

/// The step list for a selection: one connect step per picked service, in the
/// catalog's order.
func landingSteps(picked: Set<LandingService>) -> [LandingStep] {
    var steps: [LandingStep] = [.hello, .pick, .about]
    steps += LandingService.allCases.filter { picked.contains($0) }.compactMap(LandingStep.connect)
    steps.append(.ready)
    return steps
}

/// The pill's main button.
struct LandingPrimary: Equatable, Sendable {
    enum Style: Sendable { case filled, tonal, unavailable }
    var label: String
    var style: Style
    var arrow = false
}

/// The landing's state machine (Android LandingViewModel's step logic).
struct LandingFlow: Equatable, Sendable {
    var mode: LandingMode
    private(set) var steps: [LandingStep] = landingSteps(picked: [])
    private(set) var index = 0
    private(set) var picked: Set<LandingService> = []
    /// Services an account was added for during this landing.
    private(set) var connected: Set<LandingService> = []

    init(mode: LandingMode) { self.mode = mode }

    var step: LandingStep { steps[min(max(index, 0), steps.count - 1)] }
    var canGoBack: Bool { index > 0 }
    /// Back has somewhere to go: an earlier step, or (a re-run) out of the landing.
    var backEnabled: Bool { canGoBack || mode == .rerun }

    mutating func next() { if index < steps.count - 1 { index += 1 } }
    mutating func back() { if index > 0 { index -= 1 } }

    mutating func goTo(_ step: LandingStep) {
        if let i = steps.firstIndex(of: step) { index = i }
    }

    /// Unavailable services can't be picked.
    mutating func toggle(_ service: LandingService) {
        guard service.availableOnIOS else { return }
        if picked.contains(service) { picked.remove(service) } else { picked.insert(service) }
        rebuild()
    }

    mutating func markConnected(_ service: LandingService) { connected.insert(service) }

    /// The step on screen stays where it is when the list changes around it.
    private mutating func rebuild() {
        let current = step
        steps = landingSteps(picked: picked)
        index = steps.firstIndex(of: current) ?? min(index, steps.count - 1)
    }

    /// Android primaryFor: what the pill's button says and how strongly.
    func primary(hasAccount: Bool) -> LandingPrimary {
        switch step {
        case .hello: return LandingPrimary(label: "Get started", style: .filled, arrow: true)
        case .pick:
            if !picked.isEmpty { return LandingPrimary(label: "Next", style: .filled) }
            // An account already exists (a re-run): nothing new to add is fine.
            if hasAccount { return LandingPrimary(label: "Skip", style: .tonal) }
            return LandingPrimary(label: "Next", style: .unavailable)
        case .subsonic, .appleMusic:
            let done = step.service.map(connected.contains) ?? false
            return done ? LandingPrimary(label: "Next", style: .filled) : LandingPrimary(label: "Skip", style: .tonal)
        case .ready: return LandingPrimary(label: "Open Yoin", style: .filled, arrow: true)
        case .about: return LandingPrimary(label: "Next", style: .filled)
        }
    }

    /// The pill's button was refused (Pick with nothing picked and no account).
    func refusesPrimary(hasAccount: Bool) -> Bool {
        step == .pick && picked.isEmpty && !hasAccount
    }
}

/// What the mascot says on a step (the one place the copy talks).
func landingBubble(_ step: LandingStep, noAccounts: Bool) -> (title: String, body: String?) {
    switch step {
    case .hello: ("Hi, I'm Yoin.", "I play your music library, from your server or Apple Music.")
    case .pick: ("What do you listen with?", nil)
    case .about: ("Here's my part, and what stays with your service.", nil)
    case .subsonic: ("Where's your server?", nil)
    case .appleMusic: ("Apple Music needs your OK.", nil)
    case .ready: noAccounts ? ("No account yet.", "You can add one anytime in Settings.") : ("All set.", nil)
    }
}

// MARK: First-launch gate

/// Whether this install has been through the landing. Set when the landing
/// finishes, and at the first launch that finds an account (an existing user
/// never sees it), so signing out later does not bring it back — Settings ›
/// About › Welcome guide re-runs it instead. Device-local (not synced).
@MainActor
protocol LandingStore: AnyObject {
    var isDone: Bool { get }
    func markDone()
}

@MainActor
final class DefaultsLandingStore: LandingStore {
    static let key = "landing_done_v1"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    var isDone: Bool { defaults.bool(forKey: Self.key) }
    func markDone() { if !isDone { defaults.set(true, forKey: Self.key) } }
    func reset() { defaults.removeObject(forKey: Self.key) }
}

@MainActor
final class MemoryLandingStore: LandingStore {
    private(set) var isDone: Bool
    init(done: Bool = false) { isDone = done }
    func markDone() { isDone = true }
}

/// How this launch was started.
enum LandingLaunch: Sendable, Equatable {
    /// A real launch: the gate decides.
    case normal
    /// Screenshot / QA arguments (`-screen …`, `-demo`, `-subsonicURL …`): no landing, nothing recorded.
    case demo
    /// `-screen landing`: the first-run landing every time, nothing recorded.
    case forced
}

/// One landing on screen.
struct LandingSession: Identifiable, Equatable, Sendable {
    let id = UUID()
    var mode: LandingMode
    /// Finishing records the landing as seen (not for `-screen landing`).
    var persists: Bool
    /// Over a visible shell (a re-run) it fades in; at launch it is simply there.
    var fadesIn: Bool
}

@MainActor
enum LandingGate {
    /// The first-launch decision (Android LandingHost's check): a landing to show, or none.
    static func atLaunch(store: LandingStore, hasAccount: Bool, launch: LandingLaunch) -> LandingSession? {
        switch launch {
        case .forced: return LandingSession(mode: .firstRun, persists: false, fadesIn: false)
        case .demo: return nil
        case .normal:
            if store.isDone { return nil }
            if hasAccount {
                // An existing install: it never sees the landing, even after signing out.
                store.markDone()
                return nil
            }
            return LandingSession(mode: .firstRun, persists: true, fadesIn: false)
        }
    }

    /// Settings › About › Welcome guide.
    static func rerun() -> LandingSession {
        LandingSession(mode: .rerun, persists: true, fadesIn: true)
    }
}

extension LaunchConfig {
    private static func has(_ key: String) -> Bool { UserDefaults.standard.object(forKey: key) != nil }

    /// The mock library shows only for screenshots and demos: `-screen <anything>`
    /// or `-demo YES`. A real launch with no account shows an empty shell.
    static var demoContent: Bool { has("screen") || UserDefaults.standard.bool(forKey: "demo") }

    static var landingLaunch: LandingLaunch {
        if has("screen"), baseScreen == "landing" { return .forced }
        if demoContent || has("subsonicURL") { return .demo }
        return .normal
    }
}
