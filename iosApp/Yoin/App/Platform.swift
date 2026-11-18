import Foundation

/// What the app is running on. One target covers iPhone, iPad and the iPad app on a Mac ("Designed for
/// iPad"), so a rule that differs on a Mac asks `Platform.current.isMac` and never ProcessInfo itself:
/// a test, or the iPad simulator, can then stand in for a Mac.
///
///     Platform.$standIn.withValue(.mac) { … }      // a test's scope
///     -platformMac YES                             // a debug launch, the whole run
///
/// A rule that is worth testing takes the platform as a parameter (`on platform: Platform = .current`).
struct Platform: Equatable, Sendable {
    /// The iPad app on a Mac (`ProcessInfo.isiOSAppOnMac`). The native YoinMac target is another app.
    var isMac: Bool

    static let mac = Platform(isMac: true)
    static let device = Platform(isMac: false)

    /// What the process reports.
    static let system = Platform(isMac: ProcessInfo.processInfo.isiOSAppOnMac)

    /// The platform every rule asks.
    static var current: Platform { standIn ?? launch }

    /// A stand-in for the scope of `withValue`; nil outside one.
    @TaskLocal static var standIn: Platform?

    private static let launch = atLaunch(system: system, defaults: .standard)

    /// `-platformMac YES` (debug builds): the run behaves as on a Mac, so the iPad simulator shows the Mac
    /// rules. Release builds only ever report the system's answer.
    static func atLaunch(system: Platform, defaults: UserDefaults) -> Platform {
        #if DEBUG
        if defaults.bool(forKey: "platformMac") { return .mac }
        #endif
        return system
    }
}
