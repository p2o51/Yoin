import SwiftUI
import YoinShared

/*
 * Home's "On Your Other Accounts" (Android ui/home/HomeOtherProfiles.kt, #127 #156, owner 2026-11-04): the
 * albums and playlists the listener's other accounts play most, from Yoin's own play log, so it needs no
 * network and shows at once. A tap switches to that account and plays it; the first tap ever says so first,
 * since switching stops what is playing.
 */

/// One album or playlist another account plays often.
struct OtherAccountItem: Identifiable, Hashable, Sendable {
    var profileId: String
    var profileName: String
    var service: Profile.Service
    var provider: String
    var entity: ActivityEntity
    var entityId: String
    var title: String
    var subtitle: String
    var palette: CoverPalette

    var id: String { "other-profile:\(profileId):\(entity.rawValue):\(entityId)" }
}

enum HomeOtherAccounts {
    static let perProfile = 6
    static let total = 12
    static let windowMs: Int64 = 90 * 86_400_000

    /// One account's most played albums and playlists since [since], by play count then latest play
    /// (visits don't count); the newest record of each stands for it.
    static func frequent(_ records: [ActivityRecord], scope: String, provider: String, since: Int64,
                         limit: Int = perProfile) -> [ActivityRecord] {
        let rows = records.filter {
            $0.scope == scope && $0.provider == provider && $0.action == .played
                && ($0.entity == .album || $0.entity == .playlist) && $0.timestamp >= since
                && !$0.title.trimmingCharacters(in: .whitespaces).isEmpty && !$0.entityId.isEmpty
        }
        let ranked = Dictionary(grouping: rows) { "\($0.entity.rawValue):\($0.entityId)" }.values.sorted {
            if $0.count != $1.count { return $0.count > $1.count }
            let l = $0.map(\.timestamp).max() ?? 0, r = $1.map(\.timestamp).max() ?? 0
            if l != r { return l > r }
            return ($0.first?.entityId ?? "") < ($1.first?.entityId ?? "")
        }
        return ranked.prefix(limit).compactMap { $0.max { $0.timestamp < $1.timestamp } }
    }

    /// The accounts take turns, the first account's first item, then the next account's, up to [total].
    static func interleave<T>(_ groups: [[T]], total: Int = total) -> [T] {
        var out: [T] = []
        var i = 0
        while out.count < total, groups.contains(where: { i < $0.count }) {
            for g in groups where i < g.count && out.count < total { out.append(g[i]) }
            i += 1
        }
        return out
    }

    /// The shelf for the account in use: every other account that can be switched to.
    /// The last shelf and what it was built from: Home asks several times a frame.
    @MainActor private static var memo: (key: String, items: [OtherAccountItem])?

    @MainActor
    static func items(model: AppModel, history: HistoryStore = .shared) -> [OtherAccountItem] {
        let profiles = model.profiles
        guard let active = profiles.active else { return [] }
        let key = "\(active.id)|\(profiles.profiles.map(\.id))|\(history.activities.count)|\(history.activities.last?.id ?? "")"
        if let memo, memo.key == key { return memo.items }
        let built = build(model: model, history: history, active: active)
        memo = (key, built)
        return built
    }

    @MainActor
    private static func build(model: AppModel, history: HistoryStore, active: Profile) -> [OtherAccountItem] {
        let profiles = model.profiles
        let since = history.nowMs - windowMs
        var groups: [[OtherAccountItem]] = []
        for p in profiles.profiles where p.id != active.id && !profiles.needsSignIn(p) {
            guard let account = MemoryLedgerSource.account(of: p) else { continue }
            let scope = MemoryLedgerSource.historyScope(account)
            let mapping: SubsonicMapping? = p.service == .subsonic
                ? profiles.signIn(p.id).map { SubsonicMapping(client: SubsonicClient(baseUrl: $0.baseUrl, username: $0.username, password: $0.password)) }
                : nil
            let group = frequent(history.activities, scope: scope, provider: account.provider, since: since).map { r in
                let palette = mapping?.palette(coverId: r.coverKey ?? r.entityId)
                    ?? AppleMusicMapping.palette(r.coverKey.flatMap(URL.init(string:)), key: r.entityId)
                return OtherAccountItem(profileId: p.id, profileName: p.title, service: p.service, provider: account.provider,
                                        entity: r.entity, entityId: r.entityId, title: r.title, subtitle: r.subtitle,
                                        palette: palette)
            }
            if !group.isEmpty { groups.append(group) }
        }
        return interleave(groups)
    }
}

extension AppModel {
    /// Switches to [item]'s account, then plays it (Android OtherProfilesViewModel.play).
    func play(otherAccount item: OtherAccountItem) {
        Task {
            await profiles.switchTo(item.profileId)
            guard profiles.active?.id == item.profileId else { return }
            let store = store
            switch item.entity {
            case .album:
                play(loading: {
                    await store.loadAlbum(item.entityId)
                    guard let album = store.albumDetails[item.entityId] else { return [] }
                    return album.tracks.map { PlaylistEntry(album: album, track: $0) }
                })
            case .playlist:
                play(loading: { [weak self] in
                    await store.loadPlaylist(item.entityId)
                    return self?.playlistInfo(item.entityId).entries ?? []
                })
            default: break
            }
        }
    }
}

/// The shelf: cover, name, and the account's name under it.
struct OtherAccountsShelf: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    @Environment(\.homeMargin) private var margin
    var items: [OtherAccountItem]
    /// The first tap ever asks before switching (the notice All Memories shares).
    @State private var switchGate = AccountSwitchGate()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle(HomeSection.otherProfiles.title)
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 14) {
                    ForEach(items) { item in
                        Button {
                            homeTap(edit) {
                                switchGate.request(from: item.id, account: item.profileName, subject: item.title,
                                                   opensAlbum: false) {
                                    model.play(otherAccount: item)
                                }
                            }
                        } label: { card(item) }
                        .buttonStyle(PressScale())
                        .accountSwitchNotice(switchGate, anchor: item.id)
                    }
                }
                .padding(.horizontal, margin)
            }
            .scrollIndicators(.hidden)
            .padding(.horizontal, -margin)
            .shelfEdgeFade()
        }
    }

    private func card(_ item: OtherAccountItem) -> some View {
        let size = Tokens.home.RECENTLY_ADDED_ALBUM_COVER.cg
        return VStack(alignment: .leading, spacing: 6) {
            EntityBackdrop(entity: item.entity == .playlist ? .playlist : .album, palette: item.palette,
                           seed: item.entityId.count, size: size, stroke: scheme?.primary ?? Yoin.ink)
            VStack(alignment: .leading, spacing: 0) {
                Text(item.title)
                    .font(YoinFont.font(size: 12, YoinFont.Axes(weight: 600, round: 100)))
                    .homeLine(12, 16)
                    .foregroundStyle(scheme?.onSurface ?? Yoin.ink)
                    .lineLimit(1)
                Text(item.profileName).homeType(Tokens.type.labelSmall).lineLimit(1)
                    .foregroundStyle(scheme?.onSurfaceVariant ?? Yoin.inkVariant)
            }
            .frame(width: size, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint(Text("Switch and play"))
    }
}
