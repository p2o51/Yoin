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

    func begin(anchor: CGRect, accounts: [Profile], shapes: [String: Int], onPick: @escaping (Profile) -> Void) {
        guard !accounts.isEmpty else { return }
        self.anchor = anchor
        self.accounts = accounts
        self.shapes = shapes
        self.onPick = onPick
        hover = -1
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        withAnimation(Yoin.spatial) { holding = true }
    }

    /// The finger at [location] (in the window).
    func move(to location: CGPoint) {
        guard holding else { return }
        let p = CGPoint(x: location.x - anchor.minX, y: location.y - anchor.minY)
        let hit = AccountFanGeometry.hit(p, anchor: anchor.size, count: accounts.count)
        guard hit != hover else { return }
        withAnimation(Yoin.spatialFast) { hover = hit }
        if hit >= 0 { UISelectionFeedbackGenerator().selectionChanged() }
    }

    /// Let go: on an item, its account; elsewhere, nothing.
    func end() {
        guard holding else { return }
        let chosen = accounts.indices.contains(hover) ? accounts[hover] : nil
        close()
        if let chosen {
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
            onPick(chosen)
        }
    }

    /// The gesture went away (cancelled): the column goes back in, nothing picked.
    func close() {
        guard holding else { return }
        withAnimation(Yoin.spatial) {
            holding = false
            hover = -1
        }
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
    /// The hold on a header avatar. [accounts] empty: the tap alone.
    func accountQuickSwitch(accounts: [Profile], shapes: [String: Int],
                            onPick: @escaping (Profile) -> Void) -> some View {
        modifier(AccountQuickSwitchModifier(accounts: accounts, shapes: shapes, onPick: onPick))
    }
}

private struct AccountQuickSwitchModifier: ViewModifier {
    var accounts: [Profile]
    var shapes: [String: Int]
    var onPick: (Profile) -> Void
    @State private var frame: CGRect = .zero
    @GestureState private var pressing = false

    func body(content: Content) -> some View {
        let fan = AccountFan.shared
        content
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame = $0 }
            // Ahead of the tap: once the hold is long enough, it's the fan's, the let-go included. Moving
            // before then fails the press (the scroll's), and a short tap still reaches the button.
            .highPriorityGesture(
                LongPressGesture(minimumDuration: 0.45)
                    .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .global))
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
    @Environment(\.glassFlavor) private var glassFlavor

    var body: some View {
        let fan = AccountFan.shared
        let scheme = CoverScheme(seed: model.accent, dark: colorScheme == .dark)
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
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
                            .opacity(model.profiles.needsSignIn(p) ? 0.5 : 1)
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
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
