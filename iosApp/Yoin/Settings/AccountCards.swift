import SwiftUI

/// Settings › Accounts (Android SettingsScreen `AccountsSection` /
/// `AccountCardRow` / `ProfileCardTile`): every account as a card in a row
/// that runs edge to edge, the one in use filled with its service's colour.
/// A tap on another card switches to it; on the one in use, or one that
/// needs its owner, it opens the account's page. Each card's menu manages or
/// removes it (removing asks first). Add opens the service setup page.
struct AccountsSection: View {
    var app: CoverScheme
    var row: Color
    var onAdd: () -> Void
    var onManage: (Profile) -> Void
    @Environment(AppModel.self) private var model
    @State private var confirmRemove: Profile?
    /// The two-pane Settings' list: an account is a row that picks its page beside the list.
    @Environment(\.settingsList) private var list

    private var profiles: ProfileManager { model.profiles }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Accounts")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(app.primary)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if !profiles.profiles.isEmpty {
                    Text("\(profiles.profiles.count) of \(ProfileStore.maxProfiles)")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.numericText())
                }
            }
            .padding(.horizontal, 8)
            if profiles.profiles.isEmpty {
                empty.transition(.opacity)
            } else if list != nil {
                rows.transition(.opacity)
            } else {
                cards.transition(.opacity)
            }
            if let failure = profiles.failure {
                Text(failure.message)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.error)
                    .padding(.horizontal, 8)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(Yoin.spatial, value: profiles.profiles.isEmpty)
        .animation(Yoin.spatial, value: profiles.failure)
        .alert("Remove this account?", isPresented: Binding(get: { confirmRemove != nil },
                                                            set: { if !$0 { confirmRemove = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) {
                if let p = confirmRemove { profiles.remove(p.id) }
            }
        } message: {
            Text("“\(confirmRemove?.title ?? "")” will be removed from Yoin. Your ratings, notes and history stay.")
        }
    }

    // MARK: Cards

    private var cards: some View {
        let shapes = profiles.avatarShapes
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(profiles.profiles) { p in
                    AccountCard(profile: p, shape: shapes[p.id] ?? 0, app: app, row: row,
                                status: status(p), onTap: { tap(p) }, onManage: { onManage(p) },
                                onRemove: { confirmRemove = p })
                        .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
                if profiles.canAdd {
                    addTile.transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
            }
            .padding(.vertical, 6)
            .animation(Yoin.spatial, value: profiles.profiles.map(\.id))
            .animation(Yoin.spatial, value: profiles.activeId)
            .animation(Yoin.effects, value: profiles.switchingTo)
        }
        // Edge to edge, past the page's 16: cards are only ever cut by the screen edge, and at rest the
        // first one sits on the page margin.
        .contentMargins(.horizontal, 16, for: .scrollContent)
        .padding(.horizontal, -16)
        .scrollClipDisabled()
    }

    // MARK: Rows (two-pane list)

    /// Android list-detail AccountsSection `asRows`: a row per account, a tap opens its page beside the
    /// list; the ⋮ menu keeps Use this account and Remove. Add account is the last row.
    private var rows: some View {
        let shapes = profiles.avatarShapes
        let count = profiles.profiles.count + (profiles.canAdd ? 1 : 0)
        return VStack(spacing: 2) {
            ForEach(Array(profiles.profiles.enumerated()), id: \.element.id) { i, p in
                let picked = list?.picked == .account(p.id)
                AccountRow(profile: p, shape: shapes[p.id] ?? 0, app: app,
                           ring: picked ? app.surfaceContainer : row, status: status(p),
                           onOpen: { onManage(p) },
                           onUse: { Task { await profiles.switchTo(p.id) } },
                           onRemove: { confirmRemove = p })
                    .settingsSegment(i, count, row: row, app: app, picked: picked)
            }
            if profiles.canAdd {
                Button(action: onAdd) {
                    HStack(spacing: 16) {
                        YoinIcon("add", size: 24)
                            .foregroundStyle(app.primary)
                            .frame(width: 40, height: 40)
                        Text("Add account")
                            .yoinRole(Tokens.type.bodyLarge)
                            .foregroundStyle(app.onSurface)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain).pointerHighlight()
                .settingsSegment(count - 1, count, row: row, app: app, picked: list?.picked == .addAccount)
            }
        }
        .animation(Yoin.spatial, value: profiles.profiles.map(\.id))
    }

    /// The one thing a card needs to say, if any (Android AccountStatusPill): a problem beats a switch.
    private func status(_ p: Profile) -> AccountCard.Status? {
        if profiles.switchingTo == p.id { return .busy(String(localized: "Connecting")) }
        if profiles.needsSignIn(p) { return .problem(String(localized: "Sign in again")) }
        if profiles.failure?.profileId == p.id { return .problem(String(localized: "Couldn't switch")) }
        // Install Spotify / Premium required (Android SpotifyProviderStatus on a Spotify card).
        if p.service == .spotify, let issue = SpotifyIssues.shared.issue(for: p.id) { return .problem(issue.label) }
        if p.service == .appleMusic, !model.appleMusic.isAuthorized, model.appleMusic.authorization != .notDetermined {
            return .problem(String(localized: "Access off"))
        }
        if p.id == profiles.activeId, p.service == .subsonic, case .failed = model.session.state {
            return .problem(String(localized: "Can't connect"))
        }
        return nil
    }

    /// Anything that needs attention, or the account already in use, opens its page; another card switches.
    private func tap(_ p: Profile) {
        if p.id == profiles.activeId || profiles.needsSignIn(p) {
            onManage(p)
        } else {
            Task { await profiles.switchTo(p.id) }
        }
    }

    private var addTile: some View {
        Button(action: onAdd) {
            VStack(spacing: 8) {
                YoinIcon("add", size: 28)
                Text("Add")
                    .yoinRole(Tokens.type.labelLarge)
            }
            .foregroundStyle(app.primary)
            .frame(width: 112, height: AccountCard.height)
            .background(row, in: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("Add account")
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("No accounts")
                .yoinRole(Tokens.type.titleLarge)
                .foregroundStyle(app.onPrimaryContainer)
            Button(action: onAdd) {
                HStack(spacing: 8) {
                    YoinIcon("add", size: 18)
                    Text("Add account").yoinRole(Tokens.type.labelLarge)
                }
                .foregroundStyle(app.onPrimary)
                .padding(.horizontal, 20)
                .frame(minHeight: 40)
                .background(app.primary, in: .capsule)
                .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(app.primaryContainer, in: RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
    }
}

/// One account as a row of the two-pane Settings' list (Android ProfileAccountRow): avatar, name, the
/// service line and the card's status; the row opens the account's page, ⋮ has Use this account (another
/// account) and Remove.
struct AccountRow: View {
    var profile: Profile
    var shape: Int
    var app: CoverScheme
    /// The row's own colour, for the avatar's ring.
    var ring: Color
    var status: AccountCard.Status?
    var onOpen: () -> Void
    var onUse: () -> Void
    var onRemove: () -> Void
    @Environment(AppModel.self) private var model

    private var isActive: Bool { model.profiles.activeId == profile.id }

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    AccountAvatar(profile: profile, shape: shape, ring: ring, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(profile.title)
                            .yoinRole(Tokens.type.titleMedium)
                            .foregroundStyle(app.onSurface)
                            .lineLimit(1)
                        AccountCard.serviceLine(profile, ink: app.onSurfaceVariant)
                        if let status {
                            AccountCard.pill(status, app: app)
                                .padding(.top, 4)
                                .transition(.scale(scale: 0.6, anchor: .leading).combined(with: .opacity))
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 12)
                .contentShape(.rect)
            }
            .buttonStyle(.plain).pointerHighlight()
            .accessibilityAddTraits(isActive ? .isSelected : [])
            Menu {
                if !isActive { Button("Use this account", action: onUse) }
                Button("Remove", role: .destructive, action: onRemove)
            } label: {
                YoinIcon("more_vertical", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .accessibilityLabel("Options for \(profile.title)")
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .animation(Yoin.spatial, value: status)
    }
}

/// One account in the row. The service is the colour (the account in use
/// fills with it) plus the glyph on the avatar and its name under the
/// account; the avatar's shape and letter tell two accounts on one service
/// apart.
struct AccountCard: View {
    enum Status: Equatable {
        case busy(String)
        case problem(String)

        var label: String {
            switch self {
            case .busy(let s), .problem(let s): s
            }
        }
    }

    static let height: CGFloat = 176

    var profile: Profile
    var shape: Int
    var app: CoverScheme
    var row: Color
    var status: Status?
    var onTap: () -> Void
    var onManage: () -> Void
    var onRemove: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    private var isActive: Bool { model.profiles.activeId == profile.id }

    var body: some View {
        let tone = profile.service.tone(dark: colorScheme == .dark)
        let container = isActive ? tone.container : row
        let ink = isActive ? tone.onContainer : app.onSurface
        // Secondary text at full strength on the tinted card; the usual variant on a plain one.
        let secondary = isActive ? tone.onContainer : app.onSurfaceVariant
        let shapeOfCard = RoundedRectangle(cornerRadius: Tokens.shape.CARD.cg, style: .continuous)
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                AccountAvatar(profile: profile, shape: shape, ring: container)
                Spacer(minLength: 0)
                Menu {
                    Button("Manage", action: onManage)
                    Button("Remove", role: .destructive, action: onRemove)
                } label: {
                    YoinIcon("more_vertical", size: 20)
                        .foregroundStyle(secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain).pointerHighlight()
                .offset(y: -8)
                .accessibilityLabel("Options for \(profile.title)")
            }
            Spacer(minLength: 8)
            Text(profile.title)
                .yoinRole(Tokens.type.titleMedium)
                .foregroundStyle(ink)
                .lineLimit(2)
                .padding(.trailing, 12)
            serviceLine(secondary)
            if let status {
                pill(status)
                    .padding(.top, 10)
                    .transition(.scale(scale: 0.6, anchor: .topLeading).combined(with: .opacity))
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.vertical, 16)
        .frame(width: 200, height: Self.height, alignment: .topLeading)
        .background(container, in: shapeOfCard)
        .contentShape(shapeOfCard)
        .pointerHighlight()
        .onTapGesture(perform: onTap)
        .scaleEffect(isActive ? 1 : 0.96)
        .animation(Yoin.spatial, value: isActive)
        .animation(Yoin.spatial, value: status)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isActive ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint(isActive ? String(localized: "Manage account") : String(localized: "Switch to this account"))
    }

    /// Service, then host (the service drops out when the title already says it).
    private func serviceLine(_ ink: Color) -> some View { Self.serviceLine(profile, ink: ink) }

    static func serviceLine(_ profile: Profile, ink: Color) -> some View {
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
        .padding(.trailing, 12)
    }

    private func pill(_ s: Status) -> some View { Self.pill(s, app: app) }

    static func pill(_ s: Status, app: CoverScheme) -> some View {
        let problem = if case .problem = s { true } else { false }
        return Text(s.label)
            .yoinRole(Tokens.type.labelMedium)
            .foregroundStyle(problem ? app.onErrorContainer : app.onSecondaryContainer)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(problem ? app.errorContainer : app.secondaryContainer, in: .capsule)
            .contentTransition(.opacity)
    }
}
