import SwiftUI

/*
 * Playing something from another account, or going to one of its albums, switches accounts, which stops
 * what is playing. The first time, Yoin says so and asks (Android ui/memories/MemorySwitchNotice.kt and
 * ui/home/HomeOtherProfiles.kt AccountSwitchNoticeSheet, owner 2026-11-08). Home's other-accounts shelf and
 * All Memories share the one notice and its flag: once confirmed in either place, neither asks again.
 */

/// A switch waiting on the notice: the card that asked, whose account, what it opens or plays, and the
/// switch itself.
struct PendingAccountSwitch {
    /// The tapped card (`accountSwitchNotice(_:anchor:)`): the notice comes out of it.
    var anchor: String
    /// The account's name, for the notice's title.
    var account: String
    /// The album's or playlist's name, for the notice's body.
    var subject: String
    /// Go to an album (true) or play (false): the notice's body and button follow.
    var opensAlbum: Bool
    var proceed: @MainActor () -> Void

    var title: String { String(localized: "Switch to \(account)?") }
    var message: String {
        opensAlbum ? String(localized: "To open \(subject), Yoin switches to that account first. What's playing now stops.")
                   : String(localized: "To play \(subject), Yoin switches to that account first. What's playing now stops.")
    }
    var confirm: String { opensAlbum ? String(localized: "Switch and open") : String(localized: "Switch and play") }
}

/// Whether the "this switches accounts" notice has been shown; once per install (Android
/// OtherProfilesNoticeStore). `-homeOtherProfilesSwitchNoticeSeen NO` on a launch asks every time.
struct AccountSwitchNoticeStore {
    static let key = "homeOtherProfilesSwitchNoticeSeen"
    var defaults: UserDefaults = .standard

    var seen: Bool {
        get { defaults.bool(forKey: Self.key) }
        nonmutating set { defaults.set(newValue, forKey: Self.key) }
    }
}

/// Holds a switch back until the one-off notice is confirmed; straight through once it has been seen
/// (Android MemorySwitchGate).
@MainActor
@Observable
final class AccountSwitchGate {
    private(set) var pending: PendingAccountSwitch?
    @ObservationIgnored private let seen: () -> Bool
    @ObservationIgnored private let markSeen: () -> Void

    init(seen: @escaping () -> Bool, markSeen: @escaping () -> Void) {
        self.seen = seen
        self.markSeen = markSeen
    }

    convenience init(store: AccountSwitchNoticeStore = AccountSwitchNoticeStore()) {
        self.init(seen: { store.seen }, markSeen: { store.seen = true })
    }

    func request(from anchor: String, account: String, subject: String, opensAlbum: Bool,
                 proceed: @escaping @MainActor () -> Void) {
        if seen() {
            proceed()
        } else {
            pending = PendingAccountSwitch(anchor: anchor, account: account, subject: subject, opensAlbum: opensAlbum,
                                           proceed: proceed)
        }
    }

    /// The notice's confirm button: the flag is set and the switch goes ahead. The notice passes the switch
    /// it showed, since closing it may dismiss the gate's own copy before the button's action runs.
    func confirm(_ shown: PendingAccountSwitch? = nil) {
        guard let waiting = shown ?? pending else { return }
        pending = nil
        markSeen()
        waiting.proceed()
    }

    func dismiss() { pending = nil }
}

extension View {
    /// The notice for [gate]'s pending switch, on the card it was asked from ([anchor]): the system brings
    /// the dialog out of the view it is attached to. Nothing while none is waiting.
    func accountSwitchNotice(_ gate: AccountSwitchGate, anchor: String) -> some View {
        modifier(AccountSwitchNotice(gate: gate, anchor: anchor))
    }
}

private struct AccountSwitchNotice: ViewModifier {
    var gate: AccountSwitchGate
    var anchor: String
    /// The switch the notice was opened for: its words stay while the notice leaves.
    @State private var shown: PendingAccountSwitch?

    private var asked: PendingAccountSwitch? { gate.pending.flatMap { $0.anchor == anchor ? $0 : nil } }

    func body(content: Content) -> some View {
        let notice = asked ?? shown
        content
            .onChange(of: asked != nil, initial: true) { _, waiting in if waiting { shown = asked } }
            .confirmationDialog(Text(verbatim: notice?.title ?? ""),
                                isPresented: Binding(get: { asked != nil }, set: { if !$0, asked != nil { gate.dismiss() } }),
                                titleVisibility: .visible) {
                Button(notice?.confirm ?? "") { gate.confirm(notice) }
                Button("Cancel", role: .cancel) { gate.dismiss() }
            } message: {
                Text(verbatim: notice?.message ?? "")
            }
    }
}
