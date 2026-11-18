import SwiftUI
import UIKit

/*
 * Quick switch (Android ui/home/HomeAccountQuickSwitch.kt, PR #162; owner 2026-11-06): press and hold the
 * header avatar (Home, Library) and the other accounts drop out of it in a column, one after another, each
 * with its name beside it. Slide down onto one (it swells and ticks) and let go to switch to it; let go
 * anywhere else and nothing happens. A short tap still does what it did; a drag before the hold is the
 * page's scroll.
 *
 * The column is drawn by the root (RootView's overlay, AccountFanLayer) at the avatar's place in the window,
 * so the page below the header can't cover it.
 */

/// The column's geometry (Android's dp, in points) and the finger's hit test. Pure (AccountQuickSwitchTests).
enum AccountFanGeometry {
    static let item: CGFloat = 48
    static let avatar: CGFloat = 36
    static let step: CGFloat = 60
    static let firstGap: CGFloat = 12
    /// How far sideways (from the avatar's centre) the finger may stray and still be on an item: the labels too.
    static let reach: CGFloat = 200
    static let width: CGFloat = 260
    /// Each item comes out this long after the one before it.
    static let stagger: Double = 0.035

    /// Item [index]'s centre, down from the avatar box's top.
    static func itemCenterY(anchorHeight: CGFloat, index: Int) -> CGFloat {
        anchorHeight + firstGap + item / 2 + CGFloat(index) * step
    }

    /// The item under [point] (in the avatar box's space), or -1.
    static func hit(_ point: CGPoint, anchor: CGSize, count: Int) -> Int {
        guard count > 0, abs(point.x - anchor.width / 2) < reach else { return -1 }
        let near = (0..<count).min { abs(itemCenterY(anchorHeight: anchor.height, index: $0) - point.y)
            < abs(itemCenterY(anchorHeight: anchor.height, index: $1) - point.y) }!
        return abs(itemCenterY(anchorHeight: anchor.height, index: near) - point.y) < step / 2 ? near : -1
    }
}

/// What the hold offers: the other accounts, in Settings' order, without the one in use. None while a
/// switch runs (Android: not while the card is up, a switch or a Reconnect runs).
enum AccountQuickSwitchCandidates {
    static func of(_ profiles: [Profile], active: String?, switching: String?) -> [Profile] {
        guard switching == nil else { return [] }
        return profiles.filter { $0.id != active }
    }
}

/// The hold under way, read by the root's layer. One at a time (one finger, one avatar).
@MainActor @Observable
final class AccountFan {
    static let shared = AccountFan()

    private(set) var holding = false
    private(set) var hover = -1
    /// The avatar box, in the window.
    private(set) var anchor: CGRect = .zero
    /// The accounts as they were when the hold began: the column doesn't reshuffle under the finger.
    private(set) var accounts: [Profile] = []
    private(set) var shapes: [String: Int] = [:]
    @ObservationIgnored private var onPick: (Profile) -> Void = { _ in }
    /// Off on the tip page's demo phone, whose touch is acted out.
    @ObservationIgnored private let haptics: Bool
    /// When the last hold ended: the release that ends a hold is no tap on the avatar.
    @ObservationIgnored private(set) var holdEndedAt = Date.distantPast

    /// The avatar's tap is the hold's own release (a hold runs, or just ended): it opens nothing.
    var swallowsTap: Bool { Self.swallows(holding: holding, endedAgo: Date().timeIntervalSince(holdEndedAt)) }

    nonisolated static let releaseWindow: TimeInterval = 0.5

    nonisolated static func swallows(holding: Bool, endedAgo: TimeInterval) -> Bool {
        holding || endedAgo < releaseWindow
    }

    init(haptics: Bool = true) { self.haptics = haptics }

    func begin(anchor: CGRect, accounts: [Profile], shapes: [String: Int], onPick: @escaping (Profile) -> Void) {
        guard !accounts.isEmpty else { return }
        self.anchor = anchor
        self.accounts = accounts
        self.shapes = shapes
        self.onPick = onPick
        hover = -1
        if haptics { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        withAnimation(Yoin.spatial) { holding = true }
    }

    /// The finger at [location] (in the space the anchor was given in: the window's, in the app).
    func move(to location: CGPoint) {
        guard holding else { return }
        let p = CGPoint(x: location.x - anchor.minX, y: location.y - anchor.minY)
        let hit = AccountFanGeometry.hit(p, anchor: anchor.size, count: accounts.count)
        guard hit != hover else { return }
        withAnimation(Yoin.spatialFast) { hover = hit }
        if hit >= 0, haptics { UISelectionFeedbackGenerator().selectionChanged() }
    }

    /// Let go: on an item, its account; elsewhere, nothing.
    func end() {
        guard holding else { return }
        let chosen = accounts.indices.contains(hover) ? accounts[hover] : nil
        close()
        if let chosen {
            if haptics { UIImpactFeedbackGenerator(style: .rigid).impactOccurred() }
            onPick(chosen)
        }
    }

    /// The gesture went away (cancelled): the column goes back in, nothing picked.
    func close() {
        guard holding else { return }
        holdEndedAt = Date()
        withAnimation(Yoin.spatial) {
            holding = false
            hover = -1
        }
    }
}

extension Profile {
    /// What VoiceOver says for the account in use: its name, and its server when it has one (two accounts
    /// may share a name).
    var spokenAccount: String {
        guard let host = baseUrl.flatMap(URL.init(string:)).flatMap({ u in u.host.map { h in u.port.map { "\(h):\($0)" } ?? h } })
        else { return title }
        return "\(title), \(host)"
    }
}

extension AppModel {
    /// The header avatar's hold (Home, Library): the other accounts, and what letting go on one does —
    /// switch, or, for one that needs signing in again, Settings (as its card there would).
    var quickSwitchAccounts: [Profile] {
        AccountQuickSwitchCandidates.of(profiles.profiles, active: profiles.activeId, switching: profiles.switchingTo)
    }

    func quickSwitch(to p: Profile) {
        if profiles.needsSignIn(p) { openSettings(); return }
        Task { await profiles.switchTo(p.id) }
    }
}

extension View {
    /// The hold on a header avatar. [accounts] empty: the tap alone. [fan] and [space]: the app's one column,
    /// in the window; the tip page's demo phone has its own, in its screen's space.
    func accountQuickSwitch(accounts: [Profile], shapes: [String: Int], fan: AccountFan = .shared,
                            space: CoordinateSpace = .global, onPick: @escaping (Profile) -> Void) -> some View {
        modifier(AccountQuickSwitchModifier(accounts: accounts, shapes: shapes, fan: fan, space: space, onPick: onPick))
    }
}

private struct AccountQuickSwitchModifier: ViewModifier {
    var accounts: [Profile]
    var shapes: [String: Int]
    var fan: AccountFan
    var space: CoordinateSpace
    var onPick: (Profile) -> Void
    @State private var frame: CGRect = .zero
    @GestureState private var pressing = false

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGRect.self) { $0.frame(in: space) } action: { frame = $0 }
            // Beside the button's own tap (a high-priority gesture here swallowed every tap, 51 2026-11-07):
            // a short tap opens what it opened; once the hold is long enough it's the fan's, and the
            // button ignores the release that ends it (AccountFan.swallowsTap). Moving before then fails
            // the press (the scroll's).
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.45)
                    .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: space))
                    .updating($pressing) { value, state, _ in
                        if case .second(true, _) = value { state = true }
                    }
                    .onChanged { value in
                        guard case .second(true, let drag) = value else { return }
                        if !fan.holding {
                            fan.begin(anchor: frame, accounts: accounts, shapes: shapes, onPick: onPick)
                        }
                        if let drag { fan.move(to: drag.location) }
                    }
                    .onEnded { _ in fan.end() },
                including: accounts.isEmpty ? .subviews : .all
            )
            .onChange(of: pressing) { _, now in if !now { fan.close() } }
            // VoiceOver: each account as an action on the avatar.
            .accessibilityActions {
                ForEach(accounts) { p in
                    Button(String(localized: "Switch to \(p.title)")) { onPick(p) }
                }
            }
    }
}

/// The column, at the avatar's place in the window (RootView's overlay). Only there while a hold runs.
struct AccountFanLayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        AccountFanColumn(fan: .shared, scheme: CoverScheme(seed: model.accent, dark: colorScheme == .dark),
                         space: .global) { model.profiles.needsSignIn($0) }
            .ignoresSafeArea()
    }
}

/// The accounts dropping out of the avatar: [fan]'s, its anchor read in [space] (the window in the app; the
/// tip page's demo phone lays its own over its screen). [dimmed]: an account that needs signing in again.
struct AccountFanColumn: View {
    var fan: AccountFan
    var scheme: CoverScheme
    var space: CoordinateSpace
    var dimmed: (Profile) -> Bool = { _ in false }
    @Environment(\.glassFlavor) private var glassFlavor

    var body: some View {
        GeometryReader { geo in
            let origin = geo.frame(in: space).origin
            let a = fan.anchor
            ZStack(alignment: .topLeading) {
                ForEach(Array(fan.accounts.enumerated()), id: \.element.id) { i, p in
                    let lifted = fan.hover == i
                    let restY = AccountFanGeometry.itemCenterY(anchorHeight: a.height, index: i)
                        - AccountFanGeometry.item / 2
                    let fromY = a.height / 2 - AccountFanGeometry.item / 2
                    HStack(spacing: 8) {
                        Text(p.title)
                            .font(.gsf(14, .medium))
                            .lineLimit(1)
                            .foregroundStyle(lifted ? scheme.onSecondaryContainer : scheme.onSurface)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(lifted ? scheme.secondaryContainer : .clear, in: .capsule)
                            .yoinGlass(.capsule, tint: scheme.primary, flavor: glassFlavor, interactive: false)
                            .frame(maxWidth: AccountFanGeometry.width - AccountFanGeometry.item - 16, alignment: .trailing)
                            .opacity(fan.holding ? (lifted ? 1 : 0.85) : 0)
                            // Toward the page, a little further while under the finger.
                            .offset(x: fan.holding ? (lifted ? -10 : -4) : 8)
                        AccountAvatar(profile: p, shape: fan.shapes[p.id] ?? 0, ring: scheme.surfaceContainerHigh,
                                      size: AccountFanGeometry.avatar)
                            .opacity(dimmed(p) ? 0.5 : 1)
                            .frame(width: AccountFanGeometry.item, height: AccountFanGeometry.item)
                            .yoinGlass(.circle, tint: scheme.primary, flavor: glassFlavor, interactive: false)
                            .scaleEffect((fan.holding ? 1 : 0.4) * (lifted ? 1.22 : 1))
                            .opacity(fan.holding ? 1 : 0)
                    }
                    .frame(width: AccountFanGeometry.width, alignment: .trailing)
                    // Its item over the avatar's centre line: out of the avatar's centre, down to its spot.
                    .offset(x: a.midX + AccountFanGeometry.item / 2 - AccountFanGeometry.width - origin.x,
                            y: a.minY - origin.y + (fan.holding ? restY : fromY))
                    .animation(Yoin.spatial.delay(fan.holding ? Double(i) * AccountFanGeometry.stagger : 0),
                               value: fan.holding)
                    .animation(Yoin.spatialFast, value: lifted)
                    .zIndex(lifted ? 1 : 0)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
