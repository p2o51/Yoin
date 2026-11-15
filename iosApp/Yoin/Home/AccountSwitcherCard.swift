import SwiftUI
import UIKit

/*
 * The account card (Android ui/home/HomeAccountSwitcher.kt, owner 2026-10-09): a short tap on the header
 * avatar (Home, Library) opens a card over the page, grown out of the avatar: the account in use (service
 * line, big avatar, name, Manage accounts, and Reconnect while it needs its owner), the other accounts to
 * switch to, Add account, then Yoin's own entries (Settings, Send feedback, and Edit Home on Home). Same
 * avatars and service line as Settings › Accounts. The hold on the avatar (AccountQuickSwitch.swift) is
 * unchanged.
 *
 * Motion: one spatial spring p (0 = the avatar, 1 = the card) drives the surface from the avatar's bounds
 * and roundness to the card's, the scrim, and, late, the card's contents (Android smoothStep 0.45…0.95).
 * Closing runs the same p back into the avatar; a trip elsewhere (Settings, an account's page, the feedback
 * form) waits until the card is back in it. Reduce Motion: the card fades where it sits (effects spring).
 *
 * The avatars fly (Android HomeAccountSwitcherDialog, review I11): on the same p the header's avatar flies
 * from its spot to the card's big one (and back on a plain close), the big one hidden until it lands. A
 * close that is a switch reads as the pick going up: once the picked row has its colour, its avatar flies
 * from the row into the header's spot (40 → the header's 32) while the old account's big avatar goes with
 * the contents; the header shows the new account under it as the layer leaves. The header's face (its
 * letter) and the card's (glyph and badge) cross over the first third of the trip. None under Reduce Motion.
 *
 * Surface: the song sheet's (SheetSurface in Library/TrackMenuSheet.swift; owner 2026-11-13: the two
 * should be one material): the sheet's Liquid Glass and corner, the rows in the sheet's translucent
 * segments. The avatar's colour tints the glass as it grows and is gone by p 0.4. The page under it steps
 * back as under a sheet (SheetRise, on the same p): scale, dim and blur, no scrim of its own.
 *
 * Back: no system back on the shell; ✕, a tap outside and VoiceOver's escape close it.
 */

/// The card's numbers (Android dp, in points), its pure decisions and its morph. Pure (AccountSwitcherCardTests).
enum AccountCardLogic {
    static let maxWidth: CGFloat = 420
    static var corner: CGFloat { SheetSurface.corner }
    static let bigAvatar: CGFloat = 72
    static let rowAvatar: CGFloat = 40
    /// M3's disabled list-item content opacity: the accounts waiting out a switch.
    static let dimmedAlpha = 0.38
    /// The header avatar's drawn size inside its 48 target (HeaderAccountAvatar: 32 + the 1.5 ring).
    static let headerAvatarInset: CGFloat = 6.5
    /// How long the picked row's colour runs (the effects spring to 0.9, Android SWITCH_SELECTION_BEAT)
    /// before the card goes back into the avatar.
    static let selectionBeat: TimeInterval = 0.22
    /// Past this p a flight has landed: the card's own avatar shows (a spring's overshoot isn't flown).
    static let landed: CGFloat = 0.999

    /// Centred, 16 off each side, never wider than 420.
    static func cardWidth(available: CGFloat) -> CGFloat { max(0, min(available - 32, maxWidth)) }

    /// The account the card leads with (in use, else the first) and the others, in Settings' order.
    static func split(_ profiles: [Profile], active: String?) -> (active: Profile?, others: [Profile]) {
        let lead = profiles.first { $0.id == active } ?? profiles.first
        return (lead, profiles.filter { $0.id != lead?.id })
    }

    enum Pick: Equatable {
        /// Switch to it.
        case switchTo(String)
        /// It can't be used now: recovered in Settings.
        case settings
        /// Nothing: it's the account in use, or a switch is under way.
        case none
    }

    static func pick(_ p: Profile, active: String?, switching: String?, picked: String?, needsSignIn: Bool) -> Pick {
        guard p.id != active, switching == nil, picked == nil else { return .none }
        return needsSignIn ? .settings : .switchTo(p.id)
    }

    /// A picked account that isn't the one in use once its switch has run: the switch didn't land.
    static func switchFailed(picked: String, activeAfter: String?) -> Bool { activeAfter != picked }

    /// An account row's content alpha: while one is picked, the others wait dimmed.
    static func rowAlpha(_ id: String, picked: String?) -> Double {
        guard let picked, picked != id else { return 1 }
        return dimmedAlpha
    }

    /// How a Reconnect ends: fixed (the card goes back into the avatar) or the account's own page.
    enum ReconnectOutcome: Equatable { case fixed, openPage(String) }

    static func reconnect(active: Profile?, stillNeeds: Bool) -> ReconnectOutcome? {
        guard let active else { return nil }
        return stillNeeds ? .openPage(active.id) : .fixed
    }

    static func smoothstep(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
        let t = min(max((x - e0) / (e1 - e0), 0), 1)
        return t * t * (3 - 2 * t)
    }

    /// The contents come in late, inside the growing surface.
    static func contentAlpha(_ p: Double) -> Double { smoothstep(0.45, 0.95, p) }

    static func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

    static func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t),
               width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
    }

    /// The surface at p: from the avatar's bounds and roundness (a circle) to the card's (the sheet's).
    static func surface(from: CGRect, to: CGRect, p: CGFloat) -> (rect: CGRect, corner: CGFloat) {
        (lerp(from, to, p), lerp(from.width / 2, corner, p))
    }
}

/// The card's state, read by the root's layer. One at a time.
@MainActor @Observable
final class AccountSwitcher {
    static let shared = AccountSwitcher()

    /// The layer is up (from the tap until the card is back in the avatar). The header avatar steps aside
    /// meanwhile: the card is it.
    private(set) var shown = false
    /// 0 = the avatar, 1 = the card. Set inside a spring: the layer's morph reads it per frame.
    private(set) var progress: CGFloat = 0
    /// The header avatar's drawn bounds, in the window.
    private(set) var anchor: CGRect = .zero
    private(set) var reduced = false
    /// The account a switch begun here goes to: its row takes its service's colour, the others wait.
    private(set) var picked: String?
    private(set) var closing = false
    /// Home's own entry; nil where the card opens elsewhere (Library), which leaves it out.
    /// A switch's flight (I11): the account whose row avatar flies up into the header as the card closes.
    private(set) var flight: String?
    /// Where the card's avatars are, in the layer's space: the big one (the header's flies to it) and each
    /// account row's (a switch's flight takes off from it).
    private(set) var bigAvatar: CGRect = .zero
    private(set) var rowAvatars: [String: CGRect] = [:]
    /// A switch's account in use when it was picked: the card keeps showing it while it goes (the old
    /// account's big avatar leaves with the contents), though the switch may land before the card is back.
    private(set) var heldActive: String?
    @ObservationIgnored private(set) var editHome: (() -> Void)?
    @ObservationIgnored private var afterClose: (() -> Void)?
    @ObservationIgnored private var opening = false

    /// Opens the card out of the avatar at [avatarTarget] (its 48 target, in the window).
    func open(avatarTarget: CGRect, reduced: Bool, editHome: (() -> Void)?) {
        guard !shown else { return }
        anchor = avatarTarget.insetBy(dx: AccountCardLogic.headerAvatarInset, dy: AccountCardLogic.headerAvatarInset)
        self.reduced = reduced
        self.editHome = editHome
        picked = nil
        flight = nil
        heldActive = nil
        closing = false
        progress = 0
        opening = true
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        shown = true
    }

    /// The card is laid out: it grows (the layer calls this once it knows where the card goes).
    func cardLaidOut() {
        guard opening, shown, !closing else { return }
        opening = false
        withAnimation(reduced ? Yoin.effects : Yoin.spatial) { progress = 1 }
    }

    /// Where the card's big avatar is laid out (the layer's space).
    func bigAvatarLaidOut(_ r: CGRect) { if r != bigAvatar { bigAvatar = r } }

    /// Where an account row's avatar is laid out (the layer's space).
    func rowAvatarLaidOut(_ id: String, _ r: CGRect) { if rowAvatars[id] != r { rowAvatars[id] = r } }

    /// Back into the avatar; [then] runs once it is there. [flying]: a switch's close, that account's row
    /// avatar flying up into the header.
    func close(flying: String? = nil, then action: (() -> Void)? = nil) {
        guard shown, !closing else { return }
        closing = true
        opening = false
        flight = reduced ? nil : flying.flatMap { rowAvatars[$0] == nil ? nil : $0 }
        afterClose = action
        withAnimation(reduced ? Yoin.effects : Yoin.spatial, completionCriteria: .logicallyComplete) {
            progress = 0
        } completion: { [self] in
            shown = false
            closing = false
            picked = nil
            flight = nil
            heldActive = nil
            bigAvatar = .zero
            rowAvatars = [:]
            editHome = nil
            let action = afterClose
            afterClose = nil
            action?()
        }
    }

    /// A row picked for a switch: its colour first (the effects spring), then the card goes, the row's avatar
    /// flying up into the header while [stillSwitching] (a switch that failed meanwhile closes plainly).
    func beginSwitch(to id: String, from active: String?, stillSwitching: @escaping @MainActor () -> Bool = { true },
                     run: @escaping () -> Void) {
        heldActive = active
        withAnimation(Yoin.effects) { picked = id }
        run()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(AccountCardLogic.selectionBeat / max(LaunchConfig.animationSpeed, 0.01)))
            close(flying: stillSwitching() ? id : nil)
        }
    }

    /// The account the card shows as in use: the held one through a switch, else [live].
    func cardActive(_ live: String?) -> String? { heldActive ?? live }

    /// Takes no more input: a switch was picked, or the card is going.
    var takesInput: Bool { shown && !closing && picked == nil }
}

extension AppModel {
    /// The account in use needs its owner (Android ProfileManager.activeNeedsReconnect): its sign-in can't
    /// be read, or Spotify revoked it. The header avatar wears a dot and the card offers Reconnect.
    var activeNeedsReconnect: Bool {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "accountCardForceReconnect"), profiles.active != nil { return true }
        #endif
        return profiles.active.map(profiles.needsSignIn) ?? false
    }

    /// The account the header avatar shows: a switch's target while it runs (a switch's flight lands there
    /// as the new account, Android closeInto), else the one in use. A switch that fails gives way back.
    var headerProfile: Profile? {
        profiles.switchingTo.flatMap(profiles.profile) ?? profiles.active
    }

    var headerProfileShape: Int { headerProfile.flatMap { profiles.avatarShapes[$0.id] } ?? 0 }

    /// What VoiceOver says for the header avatar: the account, and that it needs reconnecting (the dot).
    var headerAccountValue: String {
        let account = profiles.active?.spokenAccount ?? ""
        guard activeNeedsReconnect else { return account }
        return [account, String(localized: "Account needs reconnecting")].filter { !$0.isEmpty }.joined(separator: ", ")
    }

    /// Another account's row on the card: switch to it (its colour first, then the card goes back into
    /// the avatar), or, for one that needs signing in again, Settings once the card is back.
    func accountCardPick(_ p: Profile) {
        let switcher = AccountSwitcher.shared
        switch AccountCardLogic.pick(p, active: profiles.activeId, switching: profiles.switchingTo,
                                     picked: switcher.picked, needsSignIn: profiles.needsSignIn(p)) {
        case .switchTo(let id):
            guard switcher.takesInput else { return }
            UISelectionFeedbackGenerator().selectionChanged()
            let profiles = profiles
            // A switch that doesn't land (the server doesn't answer, another switch is running) says so, as
            // Android's switchFailures snackbar does; the card has gone back into the avatar by then.
            switcher.beginSwitch(to: id, from: profiles.activeId, stillSwitching: {
                // Under way or landed: the pick goes up (Android stillSwitching).
                profiles.switchingTo == id || profiles.activeId == id
            }) {
                Task {
                    await profiles.switchTo(id)
                    if AccountCardLogic.switchFailed(picked: id, activeAfter: profiles.activeId) {
                        PlaylistActions.shared.show(String(localized: "Couldn't switch account"))
                    }
                }
            }
        case .settings:
            if switcher.takesInput { switcher.close { [self] in openSettings() } }
        case .none: break
        }
    }

    /// A short tap on the header avatar: the card, or Settings when there is no account to show.
    func openAccountCard(avatarTarget: CGRect, reduced: Bool, editHome: (() -> Void)?) {
        guard !profiles.profiles.isEmpty else { openSettings(); return }
        AccountSwitcher.shared.open(avatarTarget: avatarTarget, reduced: reduced, editHome: editHome)
    }
}

/// The card over everything (YoinApp's overlay). Only there while it is up.
struct AccountSwitcherLayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var cardRect: CGRect = .zero
    private let space = "accountCard"

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        let s = AccountSwitcher.shared
        if s.shown {
            GeometryReader { geo in
                let origin = geo.frame(in: .global).origin
                let from = s.anchor.offsetBy(dx: -origin.x, dy: -origin.y)
                let p = min(max(s.progress, 0), 1)
                let active = s.cardActive(model.profiles.activeId).flatMap(model.profiles.profile)
                ZStack {
                    // A tap outside closes it; the page itself steps back (SheetRise), as under a sheet.
                    Color.clear
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture { if s.takesInput { s.close() } }
                        .accessibilityHidden(true)
                    AccountCardContent(app: app, switcher: s, maxHeight: max(0, geo.size.height - 48))
                        .frame(width: AccountCardLogic.cardWidth(available: geo.size.width))
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: {
                            cardRect = $0
                            if !$0.isEmpty { DispatchQueue.main.async { s.cardLaidOut() } }
                        }
                        .modifier(AccountCardMorph(
                            p: s.progress, from: from, to: cardRect, reduced: s.reduced,
                            seed: active.map { $0.service.tone(dark: colorScheme == .dark).accent } ?? app.primary))
                    flight(s, from: from)
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .coordinateSpace(.named(space))
            }
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { s.close() }
        }
    }
}

extension AccountSwitcherLayer {
    /// The avatar in flight (I11), over the card: a switch's (the picked row's, into the header), else the
    /// header's (to the big avatar and back). Nothing under Reduce Motion or before the card is laid out.
    @ViewBuilder
    fileprivate func flight(_ s: AccountSwitcher, from: CGRect) -> some View {
        let dark = colorScheme == .dark
        let shapes = model.profiles.avatarShapes
        let ring = app.surfaceContainer
        if !s.reduced, !cardRect.isEmpty, !from.isEmpty {
            if let id = s.flight, let p = model.profiles.profile(id), let row = s.rowAvatars[id] {
                AccountAvatarFlight(p: s.progress, header: from, card: row, profile: p, shape: shapes[id] ?? 0,
                                    size: AccountCardLogic.rowAvatar, headerRing: ring,
                                    cardRing: p.service.tone(dark: dark).container)
            } else if s.flight == nil, let active = AccountCardLogic.split(model.profiles.profiles,
                                                                           active: s.cardActive(model.profiles.activeId)).active,
                      !s.bigAvatar.isEmpty {
                AccountAvatarFlight(p: s.progress, header: from, card: s.bigAvatar, profile: active,
                                    shape: shapes[active.id] ?? 0, size: AccountCardLogic.bigAvatar,
                                    headerRing: ring, cardRing: app.surfaceContainerHigh)
            }
        }
    }
}

/// An avatar between the header's spot (p 0) and its place on the card (p 1), in the layer's space. The
/// header's face (its letter on the service colour) gives way to the card's (glyph and badge) over the
/// first third of the trip; at rest (p 1) it's gone and the card's own avatar shows.
private struct AccountAvatarFlight: View, @preconcurrency Animatable {
    var p: CGFloat
    /// The header avatar's drawn bounds (its 32 and ring).
    var header: CGRect
    /// The card avatar's bounds.
    var card: CGRect
    var profile: Profile
    var shape: Int
    /// The card avatar's own size (AccountAvatar's).
    var size: CGFloat
    var headerRing: Color
    var cardRing: Color

    var animatableData: CGFloat {
        get { p }
        set { p = newValue }
    }

    var body: some View {
        let t = min(max(p, 0), 1)
        let r = AccountCardLogic.lerp(header, card, t)
        let cardFace = AccountCardLogic.smoothstep(0, 0.35, Double(t))
        let headerSize = header.width
        ZStack(alignment: .topLeading) {
            if t < AccountCardLogic.landed {
                AccountAvatar(profile: profile, shape: shape, ring: cardRing, size: size)
                    .scaleEffect(r.width / size, anchor: .topLeading)
                    .offset(x: r.minX, y: r.minY)
                    .opacity(cardFace)
                HeaderAccountAvatar(profile: profile, shape: shape, fallback: .clear, onFallback: .clear, ring: headerRing)
                    .scaleEffect(headerSize > 0 ? r.width / headerSize : 1)
                    .offset(x: r.midX - 24, y: r.midY - 24)
                    .opacity(1 - cardFace)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The surface from the avatar to the card on p, its contents fading in late and clipped to it.
private struct AccountCardMorph: ViewModifier, @preconcurrency Animatable {
    var p: CGFloat
    var from: CGRect
    var to: CGRect
    var reduced: Bool
    /// The avatar's own colour, which the surface starts in: the avatar grows into the card.
    var seed: Color
    /// The world behind steps back on this card's p, as it does under a sheet.
    @State private var riseSource = RiseSource()

    var animatableData: CGFloat {
        get { p }
        set { p = newValue }
    }

    func body(content: Content) -> some View {
        let measured = !to.isEmpty
        let t = reduced ? 1 : p
        let (r, corner) = AccountCardLogic.surface(from: measured ? from : to, to: to, p: t)
        // In the card's own space.
        let local = r.offsetBy(dx: -to.minX, dy: -to.minY)
        let shape = RoundedRectangle(cornerRadius: max(corner, 0), style: .continuous)
        // The avatar's colour over the glass, gone by p 0.4.
        let seedAlpha = reduced ? 0 : 1 - Double(min(max(p * 2.5, 0), 1))
        content
            .environment(\.accountCardP, measured ? p : 0)
            .opacity(measured ? (reduced ? Double(p) : AccountCardLogic.contentAlpha(Double(p))) : 0)
            .background(alignment: .topLeading) {
                shape.fill(seed.opacity(seedAlpha))
                    .glassEffect(SheetSurface.glass, in: shape)
                    .frame(width: max(local.width, 0), height: max(local.height, 0))
                    .offset(x: local.minX, y: local.minY)
                    .opacity(measured ? (reduced ? Double(p) : 1) : 0)
            }
            .clipShape(InsetRoundedShape(rect: local, corner: corner))
            .onChange(of: measured ? p : 0, initial: true) { _, v in
                SheetRise.shared.report(riseSource.id, min(max(v, 0), 1))
            }
            .onDisappear { SheetRise.shared.report(riseSource.id, nil) }
    }
}

extension EnvironmentValues {
    /// The card's p as drawn (per frame, from its morph): its avatars hide while their twins fly.
    @Entry var accountCardP: CGFloat = 1
}

/// One identity per card for SheetRise's sources.
private final class RiseSource {
    var id: ObjectIdentifier { ObjectIdentifier(self) }
}

/// A rounded rect at [rect] inside the view (the clip of a growing card).
private struct InsetRoundedShape: Shape {
    var rect: CGRect
    var corner: CGFloat

    func path(in _: CGRect) -> Path {
        Path(roundedRect: rect, cornerRadius: max(min(corner, min(rect.width, rect.height) / 2), 0), style: .continuous)
    }
}

/// What the card shows (Android HomeAccountCard).
private struct AccountCardContent: View {
    var app: CoverScheme
    var switcher: AccountSwitcher
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL

    private var profiles: ProfileManager { model.profiles }
    private let space = "accountCard"

    /// The most the card may take (the screen less 48): past it, it scrolls inside.
    var maxHeight: CGFloat
    @State private var contentHeight: CGFloat = 0
    /// A Reconnect is under way: the button waits with a spinner (Android HomeAccountSwitcher.retrying).
    @State private var reconnecting = false

    var body: some View {
        ScrollView {
            column
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDisabled(contentHeight <= maxHeight)
        .frame(height: min(contentHeight > 0 ? contentHeight : maxHeight, maxHeight))
    }

    private var column: some View {
        let (active, others) = AccountCardLogic.split(profiles.profiles, active: switcher.cardActive(profiles.activeId))
        let shapes = profiles.avatarShapes
        let needsReconnect = model.activeNeedsReconnect
        return VStack(spacing: 0) {
            HStack(spacing: 0) {
                Color.clear.frame(width: 48, height: 48)
                Group {
                    if let active { ServiceLine(profile: active, ink: app.onSurfaceVariant) }
                }
                .frame(maxWidth: .infinity)
                Button { if switcher.takesInput { switcher.close() } } label: {
                    YoinIcon("close", size: 24)
                        .foregroundStyle(app.onSurfaceVariant)
                        .frame(width: 48, height: 48)
                        .contentShape(.circle)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Close")
            }
            if let active {
                CardBigAvatar(profile: active, shape: shapes[active.id] ?? 0, ring: app.surfaceContainerHigh,
                              switcher: switcher, space: space)
                    .padding(.top, 4)
                    .accessibilityHidden(true)
                Text(active.title)
                    .yoinRole(Tokens.type.headlineSmall)
                    .foregroundStyle(app.onSurface)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.top, 12)
                    .padding(.horizontal, 16)
                    .accessibilityAddTraits(.isHeader)
            }
            // Manage accounts, with Reconnect ahead of it while the account in use needs its owner (it opens
            // its width on the spatial spring and fades on the effects spring). Stacks under a large font.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { reconnect(needsReconnect, active: active); manage }
                VStack(spacing: 8) { reconnect(needsReconnect, active: active); manage }
            }
            .padding(.top, 12)
            .padding(.bottom, 16)
            .animation(Yoin.spatial, value: needsReconnect)
            segments(others.map { p in AnyView(accountRow(p, shape: shapes[p.id] ?? 0)) }
                     + (profiles.canAdd ? [AnyView(item("add", String(localized: "Add account")) { model.openSettings() })] : []),
                     ids: others.map(\.id) + (profiles.canAdd ? ["add"] : []))
            Color.clear.frame(height: 12)
            segments(yoinItems.map(\.view), ids: yoinItems.map(\.id))
        }
        // The song sheet's margins (TrackMenuBody: 16 in, 16 under).
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 16)
    }

    private var yoinItems: [(id: String, view: AnyView)] {
        var out: [(id: String, view: AnyView)] = [
            ("settings", AnyView(item("settings", String(localized: "Settings")) { model.openSettings() })),
            // No "send" symbol in Yoin Symbols yet: share is the nearest.
            ("feedback", AnyView(item("share", String(localized: "Send feedback")) { openURL(AboutLinks.feedback) })),
        ]
        if let editHome = switcher.editHome {
            out.append(("edit", AnyView(Button {
                // Not while a switch runs: the feed edit mode would freeze is on its way out.
                guard switcher.takesInput, profiles.switchingTo == nil else { return }
                switcher.close()
                editHome()
            } label: { itemLabel("edit", String(localized: "Edit Home")) }.buttonStyle(.plain).pointerHighlight())))
        }
        return out
    }

    @ViewBuilder
    private func reconnect(_ needed: Bool, active: Profile?) -> some View {
        if needed {
            Button {
                guard switcher.takesInput, !reconnecting, let id = active?.id else { return }
                reconnecting = true
                Task {
                    // One more try in place first (Android HomeAccountSwitcher.reconnect): a sign-in the
                    // Keychain couldn't give at launch may be there now.
                    _ = await profiles.retryActive()
                    reconnecting = false
                    // Another account in use by now (switched in Settings): not this one's page.
                    guard profiles.activeId == id else { return }
                    switch AccountCardLogic.reconnect(active: profiles.active, stillNeeds: model.activeNeedsReconnect) {
                    case .fixed: switcher.close()
                    case .openPage(let id): switcher.close { model.openSettings(.account(id)) }
                    case nil: break
                    }
                }
            } label: {
                Text("Reconnect")
                    .yoinRole(Tokens.type.labelLarge)
                    .opacity(reconnecting ? 0 : 1)
                    .overlay { if reconnecting { ProgressView().controlSize(.small).tint(app.onSecondaryContainer) } }
                    .foregroundStyle(app.onSecondaryContainer)
                    .padding(.horizontal, 24)
                    .frame(minHeight: 40)
                    .background(app.secondaryContainer, in: .capsule)
                    .contentShape(.capsule)
            }
            .disabled(reconnecting)
            .buttonStyle(PressScale())
            .transition(.scale(scale: 0.6).combined(with: .opacity))
        }
    }

    private var manage: some View {
        Button { if switcher.takesInput { switcher.close { model.openSettings() } } } label: {
            Text("Manage accounts")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 24)
                .frame(minHeight: 40)
                .overlay(Capsule().strokeBorder(app.outline, lineWidth: 1))
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
    }

    /// Rows as the song sheet's segments: 20 outer / 4 inner corners, 2 apart, in its translucent fill. A picked account
    /// detaches (all corners round) and fills with its service's colour.
    private func segments(_ rows: [AnyView], ids: [String]) -> some View {
        VStack(spacing: 2) {
            ForEach(rows.indices, id: \.self) { i in
                let id = ids[i]
                let picked = switcher.picked == id
                let fill = picked ? (profiles.profile(id)?.service.tone(dark: colorScheme == .dark).container ?? rowFill)
                                  : rowFill
                rows[i]
                    .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                    .background(fill, in: SheetSurface.segment(i, rows.count, detached: picked))
                    .animation(Yoin.effects, value: picked)
            }
        }
    }

    /// The song sheet's row fill (SheetSurface).
    private var rowFill: Color { SheetSurface.rowFill(app, dark: colorScheme == .dark) }

    /// Another account: avatar, name, service line (or why it can't be used now).
    private func accountRow(_ p: Profile, shape: Int) -> some View {
        let picked = switcher.picked == p.id
        let tone = p.service.tone(dark: colorScheme == .dark)
        let needsSignIn = profiles.needsSignIn(p)
        return Button { model.accountCardPick(p) } label: {
            HStack(spacing: 12) {
                AccountAvatar(profile: p, shape: shape, ring: picked ? tone.container : app.surfaceContainerHigh,
                              size: AccountCardLogic.rowAvatar)
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { switcher.rowAvatarLaidOut(p.id, $0) }
                    // Up in the air on a switch's close: the row it left is empty under it.
                    .opacity(switcher.flight == p.id ? 0 : 1)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.title)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(picked ? tone.onContainer : app.onSurface)
                        .lineLimit(1)
                    if needsSignIn {
                        Text("Sign in again")
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.error)
                            .lineLimit(1)
                    } else {
                        ServiceLine(profile: p, ink: picked ? tone.onContainer : app.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(Rectangle())
            .opacity(AccountCardLogic.rowAlpha(p.id, picked: switcher.picked))
            .animation(Yoin.effects, value: switcher.picked)
        }
        .buttonStyle(.plain).pointerHighlight()
        .accessibilityElement(children: .combine)
        .accessibilityHint(String(localized: "Switch to this account"))
    }

    /// One of the card's own entries: closes the card, then [action].
    private func item(_ icon: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button { if switcher.takesInput { switcher.close(then: action) } } label: { itemLabel(icon, title) }
            .buttonStyle(.plain).pointerHighlight()
    }

    private func itemLabel(_ icon: String, _ title: String) -> some View {
        HStack(spacing: 12) {
            YoinIcon(icon, size: 24)
                .foregroundStyle(app.onSurfaceVariant)
                .frame(width: 40, height: 40)
            Text(title)
                .yoinRole(Tokens.type.titleMedium)
                .foregroundStyle(app.onSurface)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// The card's big avatar. It reads the card's drawn p (per frame) on its own, so the rest of the card isn't
/// redrawn with it: while the header's avatar flies to it (opening, or a plain close) it waits hidden.
private struct CardBigAvatar: View {
    var profile: Profile
    var shape: Int
    var ring: Color
    var switcher: AccountSwitcher
    var space: String
    @Environment(\.accountCardP) private var drawnP

    var body: some View {
        let flying = !switcher.reduced && switcher.flight == nil && drawnP < AccountCardLogic.landed
        AccountAvatar(profile: profile, shape: shape, ring: ring, size: AccountCardLogic.bigAvatar)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { switcher.bigAvatarLaidOut($0) }
            .opacity(flying ? 0 : 1)
    }
}

/// Service, then host (the service drops out when the title already says it; Android serviceLineGroups).
private struct ServiceLine: View {
    var profile: Profile
    var ink: Color

    var body: some View {
        HStack(spacing: 6) {
            if !profile.title.localizedCaseInsensitiveContains(profile.service.name) {
                Text(profile.service.name).foregroundStyle(ink)
            }
            if let host = profile.host, !profile.title.localizedCaseInsensitiveContains(host) {
                Text(host).foregroundStyle(ink.opacity(0.8))
            }
        }
        .yoinRole(Tokens.type.bodyMedium)
        .lineLimit(1)
    }
}

/// The account in use needs its owner (Android HomeAccountReconnectDot): a 6 pt error dot on a 1 pt ring of
/// the header's colour, at the avatar's top-trailing corner. Scale on the spatial spring, alpha on the
/// effects spring.
struct AccountReconnectDot: View {
    var visible: Bool
    var ring: Color
    var color: Color

    var body: some View {
        Circle().fill(color)
            .frame(width: 6, height: 6)
            .padding(1)
            .background(ring, in: .circle)
            .scaleEffect(visible ? 1 : 0.01)
            .animation(Yoin.spatial, value: visible)
            .opacity(visible ? 1 : 0)
            .animation(Yoin.effects, value: visible)
            .accessibilityHidden(true)
    }
}
