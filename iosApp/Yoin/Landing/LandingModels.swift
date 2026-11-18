import Foundation

// The first-run landing (Android ui/landing, docs/design.md › 首次引导): one
// surface above the shell with an in-page state machine. This file is the
// logic only (steps, the first-launch gate, what the pill's button says), so
// it can be tested without a screen.

/// A service the landing can connect.
enum LandingService: String, CaseIterable, Sendable {
    case subsonic, spotify, appleMusic

    var availableOnIOS: Bool { true }

    var name: String {
        switch self {
        case .subsonic: String(localized: "Subsonic")
        case .spotify: String(localized: "Spotify")
        case .appleMusic: String(localized: "Apple Music")
        }
    }
}

/// Android LandingStep, minus Edge (no scroll-edge styles on iOS). Spotoolfy
/// ("Using Spotoolfy?") comes right before Spotify's connect step; Home (the
/// section toggles and order, saved per account) once an account exists.
enum LandingStep: String, CaseIterable, Sendable, Hashable {
    case hello, pick, about, subsonic, spotoolfy, spotify, appleMusic, home, ready

    var service: LandingService? {
        switch self {
        case .subsonic: .subsonic
        case .spotify: .spotify
        case .appleMusic: .appleMusic
        default: nil
        }
    }

    static func connect(_ service: LandingService) -> LandingStep? {
        switch service {
        case .subsonic: .subsonic
        case .appleMusic: .appleMusic
        case .spotify: .spotify
        }
    }

    /// Position in the catalog: forward vs. back for the window's slide.
    var order: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// First run (no account yet) or re-run from Settings › About › Welcome guide.
enum LandingMode: Sendable, Equatable {
    case firstRun, rerun
}

/// The step list for a selection (Android `landingSteps`): one connect step per picked service, in the
/// catalog's order, Spotify's preceded by the Spotoolfy question (its notes go into the Spotify account
/// connected next); Home only once an account exists to own the layout.
func landingSteps(picked: Set<LandingService>, hasAccount: Bool = false) -> [LandingStep] {
    var steps: [LandingStep] = [.hello, .pick, .about]
    for service in LandingService.allCases where picked.contains(service) {
        if service == .spotify { steps.append(.spotoolfy) }
        if let step = LandingStep.connect(service) { steps.append(step) }
    }
    if hasAccount { steps.append(.home) }
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
    /// An account exists (one was added here, or a re-run): the Home step has a layout to write.
    private(set) var hasAccount = false

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

    /// The account list changed: Home joins or leaves the steps.
    mutating func setHasAccount(_ value: Bool) {
        guard value != hasAccount else { return }
        hasAccount = value
        rebuild()
    }

    /// The step on screen stays where it is when the list changes around it.
    private mutating func rebuild() {
        let current = step
        steps = landingSteps(picked: picked, hasAccount: hasAccount)
        index = steps.firstIndex(of: current) ?? min(index, steps.count - 1)
    }

    /// Android primaryFor: what the pill's button says and how strongly.
    func primary(hasAccount: Bool, spotoolfy: SpotoolfyStepState = SpotoolfyStepState()) -> LandingPrimary {
        switch step {
        case .spotoolfy: return spotoolfyPrimary(spotoolfy)
        case .hello: return LandingPrimary(label: String(localized: "Get started"), style: .filled, arrow: true)
        case .pick:
            if !picked.isEmpty { return LandingPrimary(label: String(localized: "Next"), style: .filled) }
            // An account already exists (a re-run): nothing new to add is fine.
            if hasAccount { return LandingPrimary(label: String(localized: "Skip"), style: .tonal) }
            return LandingPrimary(label: String(localized: "Next"), style: .unavailable)
        case .subsonic, .spotify, .appleMusic:
            let done = step.service.map(connected.contains) ?? false
            return done ? LandingPrimary(label: String(localized: "Next"), style: .filled) : LandingPrimary(label: String(localized: "Skip"), style: .tonal)
        case .ready: return LandingPrimary(label: String(localized: "Open Yoin"), style: .filled, arrow: true)
        case .about, .home: return LandingPrimary(label: String(localized: "Next"), style: .filled)
        }
    }

    /// The pill's button was refused (Pick with nothing picked and no account).
    func refusesPrimary(hasAccount: Bool, spotoolfy: SpotoolfyStepState = SpotoolfyStepState()) -> Bool {
        switch step {
        case .pick: picked.isEmpty && !hasAccount
        // No answer yet, or the file is still being read.
        case .spotoolfy: spotoolfy.choice == nil || spotoolfy.file == .reading
        default: false
        }
    }
}

/// What the mascot says on a step (the one place the copy talks).
func landingBubble(_ step: LandingStep, noAccounts: Bool) -> (title: String, body: String?) {
    switch step {
    case .hello: (String(localized: "Hi, I'm Yoin."), String(localized: "I play your music library, from your server, Spotify or Apple Music."))
    case .pick: (String(localized: "What do you listen with?"), nil)
    case .about: (String(localized: "Here's my part, and what stays with your service."), nil)
    case .subsonic: (String(localized: "Where's your server?"), nil)
    case .spotoolfy: (String(localized: "Using Spotoolfy?"), nil)
    case .spotify: (String(localized: "Spotify wants an app ID from you."), String(localized: "I'll walk you through it."))
    case .appleMusic: (String(localized: "Apple Music needs your OK."), nil)
    case .home: (String(localized: "What goes on Home?"), nil)
    case .ready: noAccounts ? (String(localized: "No account yet."), String(localized: "You can add one anytime in Settings.")) : (String(localized: "All set."), nil)
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
