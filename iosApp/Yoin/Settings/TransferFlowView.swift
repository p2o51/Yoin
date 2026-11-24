import SwiftUI
import YoinShared

/// "Transfer Memories", one step at a time (Android TransferViewModel, prototype s5–s7):
/// source → target → summary → matching → review → copy → done. Copy, never move: nothing is written before
/// the copy step, and a copy that started finishes even if the page closes.
@MainActor
@Observable
final class TransferFlowModel {
    enum Step: Int, Comparable {
        case loading, source, target, summary, matching, review, copying, done, failed
        static func < (a: Step, b: Step) -> Bool { a.rawValue < b.rawValue }
    }

    enum Failure { case readFailed, targetUnreachable, runFailed }

    /// How a pair copies (Android TransferKind): same ids, mostly the same, or matched.
    enum Kind { case direct, partial, match }

    struct Account: Identifiable, Equatable {
        var id: String
        var source: YoinTransferSource
        var account: TransferAccount
        var cloudOnly: Bool
        var fromDevice: String?
        var hint: String?

        static func == (a: Account, b: Account) -> Bool { a.id == b.id }
    }

    /// Where a transfer starts (Android TransferArgs): a source or target the entry already knows.
    struct Args: Hashable {
        var sourceProfileId: String?
        var sourceCloudScope: String?
        var targetProfileId: String?
    }

    private(set) var step: Step = .loading
    /// The step Back closes the flow from (the first one the entry didn't preset).
    private(set) var firstStep: Step = .source
    private(set) var accounts: [Account] = []
    private(set) var sourceKey: String?
    private(set) var targetKey: String?
    /// The plan before matching: what would be copied, what was copied before, what needs matching.
    private(set) var draft: YoinTransferPlan?
    private(set) var draftLoading = false
    private(set) var tracks: [TransferTrackMatch] = []
    private(set) var albums: [TransferAlbumMatch] = []
    private(set) var rows: [ReviewRow] = []
    private(set) var progressDone = 0
    private(set) var progressTotal = 0
    private(set) var result: TransferResult?
    var removeCard = false
    private(set) var cleaned = false
    private(set) var cleaning = false
    private(set) var finishing = false
    private(set) var failure: Failure?
    private(set) var cloudSyncOn = false
    /// A passing line (Android's snackbar): busy, cleaned, couldn't clean.
    private(set) var notice: String?

    private let args: Args
    private let sync: CloudSync
    private var job: Task<Void, Never>?

    init(args: Args = Args(), sync: CloudSync = .shared) {
        self.args = args
        self.sync = sync
    }

    var source: Account? { accounts.first { $0.id == sourceKey } }
    var target: Account? { accounts.first { $0.id == targetKey } }
    /// Profiles on this device other than the source.
    var targets: [Account] { accounts.filter { $0.account.localProfileId != nil && $0.id != sourceKey } }

    var needsMatching: Bool {
        guard let draft else { return true }
        return !draft.tracksToMatch.isEmpty || !draft.albumsToMatch.isEmpty || !draft.reusedTracks.isEmpty || !draft.reusedAlbums.isEmpty
    }

    var totalItems: Int { draft.map { Int(TransferRules.shared.countItems(items: $0.items)) } ?? 0 }
    var remainingItems: Int { draft.map { max(0, totalItems - Int($0.previouslyCopied)) } ?? 0 }
    var directSongs: Int { draft.map { Int($0.directSongs) } ?? 0 }
    /// Songs whose data the run will copy: the direct ones plus the checked rows.
    var songsToCopy: Int { directSongs + rows.filter(\.checked).count }
    var canCopyReviewed: Bool { songsToCopy > 0 || albums.contains { $0.status == .matched || $0.status == .picked } }
    var stepCount: Int { draft != nil && !needsMatching ? 4 : 5 }

    func stepIndex(_ step: Step) -> Int {
        switch step {
        case .loading, .source: 0
        case .target: 1
        case .summary: 2
        case .matching, .review: 3
        case .copying, .done, .failed: stepCount - 1
        }
    }

    static func localKey(_ profileId: String) -> String { "local:\(profileId)" }
    static func cloudKey(_ scope: String) -> String { "cloud:\(scope)" }

    /// The pair's kind from the providers alone (Android transferKindOf), for the target list's badge.
    static func kind(_ source: TransferAccount, _ target: TransferAccount) -> Kind {
        if source.provider != target.provider { return .match }
        if source.provider == "spotify" { return .direct }
        if source.provider == AppleMusicMapping.provider { return .partial }
        if source.provider == "subsonic", let a = source.serverKey, let b = target.serverKey,
           a.caseInsensitiveCompare(b) == .orderedSame { return .direct }
        return .match
    }

    func load() {
        guard step == .loading, job == nil else { return }
        job = Task {
            defer { job = nil }
            let sources = (try? await sync.withBackup { try await $0.transferSources() }) ?? []
            let cloudRows = Dictionary(sync.state.cloudOnlyAccounts.map { ($0.syncProfileId, $0) }, uniquingKeysWith: { a, _ in a })
            var list = sources.map { s -> Account in
                let cloud = s.kind == .cloudAccount ? cloudRows[s.account.scope] : nil
                let key = s.kind == .localProfile ? Self.localKey(s.account.localProfileId ?? "") : Self.cloudKey(s.account.scope)
                return Account(id: key, source: s, account: s.account, cloudOnly: s.kind == .cloudAccount,
                               fromDevice: cloud?.fromDeviceName, hint: cloud?.hint)
            }
            if let scope = args.sourceCloudScope, !list.contains(where: { $0.id == Self.cloudKey(scope) }) {
                let row = cloudRows[scope]
                let account = TransferAccount(scope: scope, provider: row?.provider ?? "unknown", displayName: row?.displayName ?? scope,
                                              serverKey: nil, localProfileId: nil)
                list.append(Account(id: Self.cloudKey(scope), source: YoinTransferSource(kind: .cloudAccount, account: account),
                                    account: account, cloudOnly: true, fromDevice: nil, hint: nil))
            }
            accounts = list
            let preset = args.sourceProfileId.map(Self.localKey) ?? args.sourceCloudScope.map(Self.cloudKey)
            let validSource = preset.flatMap { key in list.contains { $0.id == key } ? key : nil }
            let presetTarget = args.targetProfileId.map(Self.localKey)
            let validTarget = presetTarget.flatMap { key in key != validSource && list.contains { $0.id == key } ? key : nil }
            let first: Step = validSource == nil ? .source : validTarget == nil ? .target : .summary
            sourceKey = validSource
            targetKey = validTarget
            cloudSyncOn = sync.state.phase.enabled
            firstStep = first
            step = first
            if first == .summary { loadDraft() }
        }
    }

    /// An account was added meanwhile (the target step's "Add account…"): list it, keep what was chosen.
    func reloadAccounts() {
        guard step == .source || step == .target else { return }
        Task {
            guard let sources = try? await sync.withBackup({ try await $0.transferSources() }) else { return }
            let known = Set(accounts.map(\.id))
            for s in sources where s.kind == .localProfile {
                let key = Self.localKey(s.account.localProfileId ?? "")
                if !known.contains(key) {
                    accounts.append(Account(id: key, source: s, account: s.account, cloudOnly: false, fromDevice: nil, hint: nil))
                }
            }
        }
    }

    func pickSource(_ key: String) {
        guard step == .source else { return }
        sourceKey = key
        if targetKey == key { targetKey = nil }
        draft = nil
    }

    func pickTarget(_ key: String) {
        guard step == .target, targets.contains(where: { $0.id == key }) else { return }
        targetKey = key
        draft = nil
    }

    func next() {
        switch step {
        case .source where sourceKey != nil: step = .target
        case .target where targetKey != nil:
            step = .summary
            loadDraft()
        default: break
        }
    }

    /// From the target step: choose the source again.
    func changeSource() {
        step = .source
        firstStep = .source
    }

    /// Back inside the flow; false = close the flow.
    func back() -> Bool {
        switch step {
        case .loading, .failed: return false
        case .matching, .copying:
            say(SyncCopy.s("settings_sync_busy"))
            return true
        case .done:
            return false
        default:
            if step == firstStep { return false }
            let previous: Step = switch step {
            case .target: .source
            case .summary: .target
            case .review: .summary
            default: firstStep
            }
            job?.cancel()
            job = nil
            draftLoading = false
            step = previous
            return true
        }
    }

    private func loadDraft() {
        guard let source, let targetId = target?.account.localProfileId else { return }
        job?.cancel()
        draft = nil
        draftLoading = true
        job = Task {
            do {
                let plan = try await sync.withBackup { facade -> YoinTransferPlan in
                    let items = try await facade.memoryItems(source: source.source, backup: nil)
                    let to = try await facade.transferAccountOf(profileId: targetId)
                    return try await facade.planTransfer(source: source.account, items: items, target: to)
                }
                guard !Task.isCancelled else { return }
                draft = plan
                draftLoading = false
            } catch {
                guard !Task.isCancelled else { return }
                draftLoading = false
                failure = .readFailed
                step = .failed
            }
        }
    }

    /// The summary's button: match (when needed) or copy straight away.
    func start() {
        guard step == .summary, let draft, remainingItems > 0 else { return }
        guard needsMatching else {
            copy(tracks: draft.reusedTracks, albums: draft.reusedAlbums)
            return
        }
        guard let targetId = target?.account.localProfileId else { return }
        let needsCatalog = !draft.tracksToMatch.isEmpty || !draft.albumsToMatch.isEmpty
        let catalog = MemoryCatalogs.make(target: targetId, source: source?.account.localProfileId)
        if needsCatalog, catalog == nil {
            failure = .targetUnreachable
            step = .failed
            return
        }
        progressDone = 0
        progressTotal = draft.tracksToMatch.count + draft.albumsToMatch.count
        step = .matching
        job = Task {
            defer { catalog?.close() }
            let matched = await TransferMatching.match(draft, catalog: catalog) { [weak self] done, total in
                guard let self, self.step == .matching else { return }
                self.progressDone = done
                self.progressTotal = total
            }
            guard !Task.isCancelled, step == .matching else { return }
            tracks = matched.tracks
            albums = matched.albums
            rows = TransferMatching.reviewRows(matched.tracks)
            step = .review
        }
    }

    /// Matching or the review dropped: nothing was written.
    func cancel() {
        guard step != .copying, step != .done else { return }
        job?.cancel()
        job = nil
    }

    func setChecked(_ position: Int32, _ checked: Bool) {
        guard step == .review else { return }
        rows = rows.map { $0.position == position && $0.checkable ? ReviewRow(position: $0.position, status: $0.status, candidates: $0.candidates,
                                                                              chosen: $0.chosen, checked: checked) : $0 }
    }

    /// Picking a candidate is consent: the row is checked.
    func choose(_ position: Int32, _ index: Int32) {
        guard step == .review else { return }
        rows = rows.map { r in
            r.position == position && index >= 0 && Int(index) < r.candidates.count
                ? ReviewRow(position: r.position, status: r.status, candidates: r.candidates, chosen: index, checked: true) : r
        }
    }

    /// The review's button.
    func copyReviewed() {
        guard step == .review, let draft else { return }
        copy(tracks: TransferMatching.trackPicks(tracks, rows: rows), albums: TransferMatching.albumPicks(albums, plan: draft))
    }

    private func copy(tracks: [TrackPick], albums: [AlbumPick]) {
        guard let draft else { return }
        step = .copying
        let sync = sync
        // Once copying starts it finishes, page or not.
        Task {
            do {
                let result = try await sync.withBackup {
                    try await $0.applyTransfer(plan: draft, tracks: tracks, albums: albums,
                                               nowMs: Int64(Date().timeIntervalSince1970 * 1000))
                }
                if result.copied.total + result.updated.total > 0 { sync.localDataImported() }
                self.result = result
                step = .done
            } catch {
                failure = .runFailed
                step = .failed
            }
        }
    }

    /// The done page's "remove the original's cloud data", after its confirmation.
    func cleanSource() {
        guard let source, !cleaned, !cleaning, BackupFormat.shared.isSyncScope(scope: source.account.scope) else { return }
        cleaning = true
        Task {
            let ok = await sync.removeAccountFromCloud(source.account.scope)
            cleaning = false
            cleaned = cleaned || ok
            say(SyncCopy.s(ok ? "settings_xfer_cleaned" : "settings_sync_remove_failed", source.account.displayName))
        }
    }

    /// Done: removes the source's card if asked; the line to show once the flow closed (Android Finished).
    func finish() -> String? {
        guard !finishing else { return nil }
        finishing = true
        let name = source?.account.displayName ?? ""
        guard step == .done else { return nil }
        if removeCard, let profileId = source?.account.localProfileId {
            AppModel.shared.profiles.remove(profileId)
            TrackAlbumLinkStore.shared.removeAll(profileId: profileId)
            return SyncCopy.s("settings_xfer_card_removed", name)
        }
        return SyncCopy.s("settings_xfer_finished", name)
    }

    private func say(_ text: String) {
        notice = text
        Task {
            try? await Task.sleep(for: .seconds(3))
            if notice == text { notice = nil }
        }
    }

    #if DEBUG
    /// Screenshots: a flow set by hand.
    func show(step: Step, accounts: [Account], source: String?, target: String?, draft: YoinTransferPlan? = nil,
              tracks: [TransferTrackMatch] = [], albums: [TransferAlbumMatch] = [], result: TransferResult? = nil,
              progress: (Int, Int) = (0, 0)) {
        self.accounts = accounts
        sourceKey = source
        targetKey = target
        self.draft = draft
        self.tracks = tracks
        self.albums = albums
        rows = TransferMatching.reviewRows(tracks)
        self.result = result
        (progressDone, progressTotal) = progress
        firstStep = .source
        self.step = step
    }
    #endif
}

/// The transfer flow's page (Android TransferFlow).
struct TransferFlowView: View {
    var model: TransferFlowModel
    /// The flow closed; the line to show where it was opened from.
    var onFinished: (String?) -> Void = { _ in }

    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var confirmCancel = false
    @State private var confirmClean = false
    @State private var addAccount = false
    @State private var filter = 0
    @State private var expanded: Int32?

    private var s: CoverScheme { BackupTexts.scheme(app, colorScheme) }
    private var kit: SyncFlowKit { SyncFlowKit(app: s, dark: colorScheme == .dark) }
    private var sourceName: String { model.source?.account.displayName ?? "" }
    private var targetName: String { model.target?.account.displayName ?? "" }

    var body: some View {
        let close = model.step == model.firstStep || model.step == .done || model.step == .failed || model.step == .loading
        SyncFlowPage(app: s, close: close, onBack: back) {
            VStack(alignment: .leading, spacing: 0) {
                if ![.done, .failed, .loading].contains(model.step) { stepBar }
                stepContent
            }
            .id(model.step)
            .transition(.opacity)
            if let notice = model.notice {
                Text(notice)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(s.inverseOnSurface)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(s.inverseSurface, in: .rect(cornerRadius: 12, style: .continuous))
                    .padding(.top, 16)
                    .transition(.opacity)
            }
        } footer: {
            footer
        }
        .animation(Yoin.spatial, value: model.step)
        .animation(Yoin.effects, value: model.notice)
        .task { model.load() }
        .navigationDestination(isPresented: $addAccount) { ServiceSetupView(request: ServiceSetupRequest()) }
        .onChange(of: app.profiles.profiles.map(\.id)) { _, _ in model.reloadAccounts() }
        .alert(SyncCopy.s("settings_xfer_cancel_title"), isPresented: $confirmCancel) {
            Button(SyncCopy.s("settings_xfer_cancel_keep"), role: .cancel) {}
            Button(SyncCopy.s("settings_xfer_cancel_confirm"), role: .destructive) {
                model.cancel()
                dismiss()
            }
        } message: {
            Text(SyncCopy.s("settings_xfer_cancel_body"))
        }
        .alert(SyncCopy.s("settings_xfer_clean_title", sourceName), isPresented: $confirmClean) {
            Button(SyncCopy.s("settings_xfer_clean_later"), role: .cancel) {}
            Button(SyncCopy.s("settings_xfer_clean_now"), role: .destructive) { model.cleanSource() }
        } message: {
            Text(SyncCopy.s("settings_xfer_clean_body", sourceName, SyncCopy.s("settings_sync_remove_all_data"), targetName))
        }
    }

    private func back() {
        if model.step == .done {
            finish()
        } else if !model.back() {
            model.cancel()
            dismiss()
        }
    }

    private func finish() {
        let line = model.finish()
        dismiss()
        onFinished(line)
    }

    // MARK: Footer

    @ViewBuilder private var footer: some View {
        switch model.step {
        case .source:
            kit.flowButtons(SyncCopy.s("settings_xfer_next"), enabled: model.sourceKey != nil) { model.next() }
        case .target:
            kit.flowButtons(SyncCopy.s("settings_xfer_next"), enabled: model.targetKey != nil) { model.next() }
        case .summary:
            let ready = model.draft != nil && !model.draftLoading
            if ready && model.remainingItems == 0 {
                kit.flowButtons(SyncCopy.s("settings_xfer_nothing_new"), enabled: false) {}
            } else {
                kit.flowButtons(SyncCopy.s(model.needsMatching ? "settings_xfer_start_match" : "settings_xfer_start_copy"),
                                enabled: ready, badge: ready ? model.remainingItems : nil) { model.start() }
            }
        case .matching:
            Spacer()
            Button(SyncCopy.s("settings_xfer_cancel")) { confirmCancel = true }
                .font(.gsf(16, .medium))
                .foregroundStyle(s.primary)
                .frame(minHeight: 56)
        case .review:
            kit.flowButtons(SyncCopy.s("settings_xfer_start_copy"), enabled: model.canCopyReviewed,
                            badge: model.songsToCopy > 0 ? model.songsToCopy : nil,
                            secondary: SyncCopy.s("settings_xfer_cancel"), onSecondary: { confirmCancel = true }) {
                model.copyReviewed()
            }
        case .done:
            kit.flowButtons(SyncCopy.s("settings_sync_done"), enabled: !model.finishing) { finish() }
        case .failed:
            kit.flowButtons(SyncCopy.s("settings_sync_done")) { dismiss() }
        default:
            EmptyView()
        }
    }

    // MARK: Steps

    /// Progress segments and "Step n of m: name".
    private var stepBar: some View {
        let count = model.stepCount
        let index = model.stepIndex(model.step)
        var names = ["settings_xfer_step_source", "settings_xfer_step_target", "settings_xfer_step_summary"]
        if count == 5 { names.append("settings_xfer_step_review") }
        names.append("settings_xfer_step_copy")
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 4) {
                ForEach(0..<count, id: \.self) { i in
                    Capsule().fill(i <= index ? s.primary : s.surfaceContainerHighest).frame(height: 4)
                }
            }
            Text(SyncCopy.s("settings_xfer_step", index + 1, count, SyncCopy.s(names[min(index, names.count - 1)])))
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(s.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
    }

    @ViewBuilder private var stepContent: some View {
        switch model.step {
        case .loading: SyncProgressHero(app: s)
        case .source: sourceStep
        case .target: targetStep
        case .summary: summaryStep
        case .matching:
            SyncProgressHero(app: s, progress: model.progressTotal > 0 ? Double(model.progressDone) / Double(model.progressTotal) : 0,
                             count: SyncCopy.s("settings_xfer_progress", model.progressDone, model.progressTotal)) {
                serviceLine(model.target?.account.provider ?? "",
                            SyncCopy.s("settings_xfer_matching_on", SyncCopy.service(model.target?.account.provider)))
            }
        case .review: reviewStep
        case .copying:
            SyncProgressHero(app: s) {
                serviceLine(model.target?.account.provider ?? "", SyncCopy.s("settings_xfer_copying", targetName))
            }
        case .done: doneStep
        case .failed:
            SyncOutcomeHero(app: s, icon: "close", title: failureTitle, bodyText: nil, failed: true)
        }
    }

    private var failureTitle: String {
        switch model.failure {
        case .readFailed: SyncCopy.s("settings_xfer_read_failed", sourceName)
        case .targetUnreachable: SyncCopy.s("settings_xfer_target_unreachable", targetName)
        default: SyncCopy.s("settings_xfer_run_failed")
        }
    }

    private var sourceStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            kit.headline(SyncCopy.s("settings_xfer_head_source"))
            kit.group(SyncCopy.s("settings_xfer_source_group"), rows: model.accounts.map { a in
                AnyView(accountPick(a, selected: model.sourceKey == a.id) { model.pickSource(a.id) })
            })
        }
    }

    @ViewBuilder private var targetStep: some View {
        if let source = model.source {
            VStack(alignment: .leading, spacing: 0) {
                kit.headline(SyncCopy.s("settings_xfer_head_target"))
                VStack(alignment: .leading, spacing: 28) {
                    kit.group(nil, rows: [AnyView(Button { model.changeSource() } label: {
                        kit.item(BackupTexts.service(source.account.provider).glyph, SyncCopy.s("settings_xfer_from", source.account.displayName),
                                 tone: BackupTexts.tone(source.account.provider, dark: colorScheme == .dark)) {
                            Text(SyncCopy.s("settings_xfer_change")).yoinRole(Tokens.type.labelLarge).foregroundStyle(s.primary)
                        }
                        .contentShape(.rect)
                    }.buttonStyle(PressScale()))])
                    VStack(alignment: .leading, spacing: 8) {
                        kit.group(SyncCopy.s("settings_xfer_target_group"), rows: model.targets.map { a in
                            AnyView(accountPick(a, selected: model.targetKey == a.id, badge: TransferFlowModel.kind(source.account, a.account)) {
                                model.pickTarget(a.id)
                            })
                        } + [AnyView(Button { addAccount = true } label: {
                            kit.item("person_add", SyncCopy.s("settings_xfer_add_account"), summary: SyncCopy.s("settings_xfer_add_account_summary"))
                                .contentShape(.rect)
                        }.buttonStyle(PressScale()))])
                        if model.targets.isEmpty { kit.note("info", SyncCopy.s("settings_xfer_no_targets")) }
                        if source.cloudOnly { kit.note("info", SyncCopy.s("settings_xfer_cloud_note", source.account.displayName)) }
                    }
                }
            }
        }
    }

    /// A radio row for an account: glyph, name (Cloud only / the pair's badge), service line.
    private func accountPick(_ a: TransferFlowModel.Account, selected: Bool, badge: TransferFlowModel.Kind? = nil,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                kit.accountGlyph(a.account.provider)
                VStack(alignment: .leading, spacing: 2) {
                    SyncMetaFlow(spacing: 8, lineSpacing: 2) {
                        Text(a.account.displayName).yoinRole(Tokens.type.titleMedium).foregroundStyle(s.onSurface)
                        if a.cloudOnly {
                            kit.pill(SyncCopy.s("settings_sync_cloud_only"), container: s.secondaryContainer, content: s.onSecondaryContainer)
                        } else if let badge {
                            switch badge {
                            case .direct: kit.pill(SyncCopy.s("settings_xfer_badge_direct"), container: s.primaryContainer, content: s.onPrimaryContainer)
                            case .partial: kit.pill(SyncCopy.s("settings_xfer_badge_partial"), container: s.secondaryContainer, content: s.onSecondaryContainer)
                            case .match: kit.pill(SyncCopy.s("settings_xfer_badge_match"), container: s.surfaceContainerHighest, content: s.onSurfaceVariant)
                            }
                        }
                    }
                    let line = ImportReviewView.accountLine(provider: a.account.provider, name: a.account.displayName,
                                                            hint: a.hint, fromDevice: a.fromDevice)
                    if !line.isEmpty { kit.metaLine(line) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                ZStack {
                    Circle().strokeBorder(selected ? s.primary : s.outline, lineWidth: 2)
                    Circle().fill(s.primary).padding(6).scaleEffect(selected ? 1 : 0.01).opacity(selected ? 1 : 0)
                }
                .frame(width: 22, height: 22)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .background(selected ? s.secondaryContainer : Color.clear)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .animation(Yoin.effects, value: selected)
        .sensoryFeedback(.selection, trigger: selected)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private func serviceLine(_ provider: String, _ text: String) -> some View {
        HStack(spacing: 10) {
            kit.accountGlyph(provider)
            Text(text).yoinRole(Tokens.type.titleMedium).foregroundStyle(s.onSurfaceVariant).lineLimit(2)
        }
    }

    // MARK: Summary

    @ViewBuilder private var summaryStep: some View {
        kit.headline(SyncCopy.s("settings_xfer_summary_title"))
        if let draft = model.draft, let source = model.source, let target = model.target {
            let items = draft.items
            let kind: TransferFlowModel.Kind = !model.needsMatching ? .direct : model.directSongs > 0 ? .partial : .match
            let albumCount = Set(items.albumRatings.map(\.album) + items.albumReviews.map(\.album)).count
            VStack(alignment: .leading, spacing: 28) {
                pairCard(source, target)
                if draft.previouslyCopied > 0 { skipBanner(Int(draft.previouslyCopied), remaining: model.remainingItems) }
                kit.group(SyncCopy.s("settings_xfer_what"), trailing: SyncCopy.s("settings_xfer_total", "\(model.totalItems)"), rows: [
                    countRow("note", "settings_xfer_row_notes", items.songNotes.count + items.albumNotes.count, "settings_sync_unit_count"),
                    countRow("star", "settings_xfer_row_song_ratings", items.trackRatings.count, "settings_sync_unit_count"),
                    countRow("reviews", "settings_xfer_row_albums", albumCount, "settings_sync_unit_count"),
                    countRow("edit", "settings_xfer_row_custom_titles", items.albumTitles.count, "settings_sync_unit_title_count"),
                    countRow("sparkle", "settings_xfer_row_ai_titles", max(items.memoryTitles.count, items.memoryCopies.count),
                             "settings_sync_unit_title_count"),
                    countRow("album", "settings_xfer_row_links", items.tracks.count, "settings_sync_unit_songs",
                             summary: SyncCopy.s("settings_xfer_row_links_summary")),
                ])
                kit.group(SyncCopy.s("settings_xfer_matching"), rows: [AnyView(methodRow(kind, source: source))])
                VStack(alignment: .leading, spacing: 8) {
                    kit.group(SyncCopy.s("settings_xfer_duplicates"), rows: [
                        AnyView(kit.item("note", SyncCopy.s("settings_xfer_dup_notes"), summary: SyncCopy.s("settings_xfer_dup_notes_summary"))),
                        AnyView(kit.item("star", SyncCopy.s("settings_xfer_dup_ratings"))),
                        AnyView(kit.item("reviews", SyncCopy.s("settings_xfer_dup_reviews"),
                                         summary: SyncCopy.s("settings_xfer_dup_reviews_summary", source.account.displayName))),
                    ])
                    kit.note("info", SyncCopy.s("settings_xfer_copy_only", source.account.displayName))
                }
            }
        } else {
            SyncProgressHero(app: s)
        }
    }

    private func countRow(_ icon: String, _ title: String, _ count: Int, _ unit: String, summary: String? = nil) -> AnyView {
        AnyView(kit.item(icon, SyncCopy.s(title), summary: summary) {
            kit.metaLine([SyncCopy.stat(unit, count)], style: Tokens.type.bodyLarge, color: s.onSurface)
        })
    }

    @ViewBuilder private func methodRow(_ kind: TransferFlowModel.Kind, source: TransferFlowModel.Account) -> some View {
        switch kind {
        case .direct:
            kit.item("check_circle", SyncCopy.s("settings_xfer_method_direct"),
                     summary: SyncCopy.s("settings_xfer_method_direct_summary", SyncCopy.service(source.account.provider)))
        case .partial:
            kit.item("check_circle", SyncCopy.s("settings_xfer_method_partial"),
                     summary: SyncCopy.s("settings_xfer_method_partial_summary"),
                     meta: [("\(model.directSongs)", SyncCopy.s("settings_xfer_direct_songs")),
                            ("\(model.draft?.tracksToMatch.count ?? 0)", SyncCopy.s("settings_xfer_match_songs"))])
        case .match:
            kit.item("search", SyncCopy.s("settings_xfer_method_match"),
                     summary: SyncCopy.s("settings_xfer_method_match_summary", source.account.displayName))
        }
    }

    private func pairCard(_ source: TransferFlowModel.Account, _ target: TransferFlowModel.Account) -> some View {
        kit.group(nil, rows: [AnyView(HStack(spacing: 8) {
            pairSide(source)
            YoinIcon("forward", size: 28).foregroundStyle(s.primary)
            pairSide(target)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 20))])
    }

    private func pairSide(_ a: TransferFlowModel.Account) -> some View {
        let service = SyncCopy.service(a.account.provider)
        let subtitle: String? = a.cloudOnly ? SyncCopy.s("settings_sync_cloud_only")
            : a.hint ?? (a.account.displayName.caseInsensitiveCompare(service) == .orderedSame ? nil : service)
        return VStack(spacing: 6) {
            kit.accountGlyph(a.account.provider, size: 52)
            Text(a.account.displayName).yoinRole(Tokens.type.titleMedium).foregroundStyle(s.onSurface)
                .multilineTextAlignment(.center).lineLimit(2)
            if let subtitle {
                Text(subtitle).yoinRole(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func skipBanner(_ previous: Int, remaining: Int) -> some View {
        HStack(spacing: 12) {
            YoinIcon("history", size: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(SyncCopy.s("settings_xfer_skip_title", "\(previous)")).yoinRole(Tokens.type.titleMedium)
                Text(SyncCopy.s(remaining > 0 ? "settings_xfer_skip_some" : "settings_xfer_skip_all")).yoinRole(Tokens.type.bodyMedium)
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(s.onSecondaryContainer)
        .padding(16)
        .background(s.secondaryContainer, in: .rect(cornerRadius: 20, style: .continuous))
    }

    // MARK: Review

    @ViewBuilder private var reviewStep: some View {
        if let target = model.target, let source = model.source {
            let rows = model.rows
            let summary = RestoreReview.shared.summary(rows: rows)
            let shown = rows.filter { r in
                switch filter {
                case 1: r.status == .uncertain
                case 2: r.status == .notfound
                default: true
                }
            }
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    kit.accountGlyph(target.account.provider)
                    Text(target.account.displayName).yoinRole(Tokens.type.headlineSmall).foregroundStyle(s.onSurface).lineLimit(1)
                }
                .padding(.horizontal, 8)
                .padding(.top, 8)
                Text(model.directSongs > 0 && source.account.provider == target.account.provider
                     ? SyncCopy.s("settings_xfer_review_partial_lead", model.directSongs, rows.count)
                     : SyncCopy.s("settings_xfer_review_lead", source.account.displayName, rows.count, SyncCopy.service(target.account.provider)))
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(s.onSurfaceVariant)
                    .padding(.horizontal, 8)
                summaryBar(summary).padding(.horizontal, 8)
                HStack(spacing: 2) {
                    filterChip(0, SyncCopy.s("playlist_restore_all"), rows.count)
                    filterChip(1, SyncCopy.s("playlist_restore_to_check"), Int(summary.toCheck))
                    filterChip(2, SyncCopy.s("playlist_restore_not_found"), Int(summary.notFound))
                }
                .padding(.horizontal, 8)
                VStack(spacing: 2) {
                    ForEach(Array(shown.enumerated()), id: \.element.position) { i, row in
                        reviewRow(row)
                            .background(s.surfaceBright, in: CloudSyncView.segment(i, shown.count))
                    }
                }
                .animation(Yoin.spatial, value: shown.map(\.position))
                if !model.albums.isEmpty {
                    let matched = model.albums.filter { $0.status == .matched || $0.status == .picked }.count
                    kit.note("album", SyncCopy.s("settings_xfer_albums_note", model.albums.count, matched, model.albums.count - matched,
                                                 source.account.displayName))
                }
                kit.note("info", SyncCopy.s("settings_xfer_left_note", source.account.displayName))
            }
        }
    }

    /// Android SummaryBar: the review's counts as one proportional bar with its labels.
    private func summaryBar(_ summary: ReviewSummary) -> some View {
        let parts: [(Int, Color, String)] = [
            (Int(summary.matched), s.primary, SyncCopy.s("playlist_restore_matched")),
            (Int(summary.picked), s.secondary, SyncCopy.s("playlist_restore_picked")),
            (Int(summary.toCheck), s.tertiary, SyncCopy.s("playlist_restore_to_check")),
            (Int(summary.notFound), s.outlineVariant, SyncCopy.s("playlist_restore_not_found")),
        ]
        let total = max(1, parts.map(\.0).reduce(0, +))
        return VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(Array(parts.enumerated()), id: \.offset) { _, p in
                        if p.0 > 0 {
                            Capsule().fill(p.1).frame(width: max(4, (geo.size.width - 6) * CGFloat(p.0) / CGFloat(total)))
                        }
                    }
                }
            }
            .frame(height: 8)
            SyncMetaFlow(spacing: 14, lineSpacing: 4) {
                ForEach(Array(parts.enumerated()), id: \.offset) { i, p in
                    if p.0 > 0 || i == 0 {
                        HStack(spacing: 6) {
                            Circle().fill(p.1).frame(width: 8, height: 8)
                            Text("\(p.0) \(p.2)").yoinRole(Tokens.type.labelMedium).foregroundStyle(s.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    private func filterChip(_ f: Int, _ label: String, _ n: Int) -> some View {
        let on = filter == f
        return Button {
            withAnimation(Yoin.spatial) { filter = f }
        } label: {
            Text("\(label) \(n)")
                .yoinRole(Tokens.type.labelLarge)
                .lineLimit(1)
                .foregroundStyle(on ? s.onPrimary : s.onSurfaceVariant)
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(on ? s.primary : s.surfaceContainerHighest,
                            in: UnevenRoundedRectangle(topLeadingRadius: f == 0 ? 20 : 8, bottomLeadingRadius: f == 0 ? 20 : 8,
                                                       bottomTrailingRadius: f == 2 ? 20 : 8, topTrailingRadius: f == 2 ? 20 : 8))
                .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }

    /// One song of the review, as playlist restore draws its rows (mark, song, what the target offers).
    private func reviewRow(_ row: ReviewRow) -> some View {
        let match = Int(row.position) < model.tracks.count ? model.tracks[Int(row.position)] : nil
        let uncertain = row.status == .uncertain, notFound = row.status == .notfound
        let open = uncertain && expanded == row.position
        let title = match?.sourceTrack?.title.flatMap { $0.isEmpty ? nil : $0 } ?? SyncCopy.s("settings_sync_unknown_song")
        let artist = match?.sourceTrack?.artist ?? ""
        return VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button {
                    model.setChecked(row.position, !row.checked)
                } label: {
                    ZStack {
                        Circle().fill(row.checked ? s.primary : Color.clear)
                        Circle().strokeBorder(row.checked ? s.primary : s.outline, lineWidth: 2)
                        if row.checked { YoinIcon("check", size: 18).foregroundStyle(s.onPrimary).transition(.scale) }
                    }
                    .frame(width: 28, height: 28)
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
                }
                .buttonStyle(.plain).pointerHighlight()
                .disabled(!row.checkable)
                .opacity(row.checkable ? 1 : 0.3)
                .sensoryFeedback(.selection, trigger: row.checked)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface).lineLimit(1)
                    if !artist.isEmpty {
                        Text(artist).yoinRole(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant).lineLimit(1)
                    }
                    if uncertain {
                        Text(row.checked ? "→ \(row.choice?.title ?? "")" : SyncCopy.s("settings_xfer_choose_match"))
                            .yoinRole(Tokens.type.bodyMedium).foregroundStyle(s.primary).lineLimit(1)
                    }
                }
                .opacity(notFound ? 0.6 : 1)
                Spacer(minLength: 8)
                switch row.status {
                case .uncertain: kit.pill(SyncCopy.s("playlist_restore_to_check"), container: s.tertiaryContainer, content: s.onTertiaryContainer)
                case .notfound: kit.pill(SyncCopy.s("settings_xfer_stays"), container: s.surfaceContainerHighest, content: s.onSurfaceVariant)
                default:
                    if let d = row.choice?.durationSec?.intValue ?? match?.sourceTrack?.durationSec?.intValue {
                        Text(formatTime(Int(d))).yoinRole(Tokens.type.labelLarge).foregroundStyle(s.onSurfaceVariant)
                    }
                }
            }
            .padding(.leading, 4)
            .padding(.trailing, 16)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
            .onTapGesture {
                guard !notFound else { return }
                if uncertain {
                    withAnimation(Yoin.spatial) { expanded = open ? nil : row.position }
                } else {
                    model.setChecked(row.position, !row.checked)
                }
            }
            if open {
                VStack(spacing: 2) {
                    ForEach(Array(row.candidates.enumerated()), id: \.offset) { i, c in
                        let chosen = Int32(i) == row.chosen && row.checked
                        Button { model.choose(row.position, Int32(i)) } label: {
                            HStack(spacing: 14) {
                                ZStack {
                                    Circle().strokeBorder(chosen ? s.primary : s.onSurfaceVariant, lineWidth: 2)
                                    Circle().fill(s.primary).padding(5).scaleEffect(chosen ? 1 : 0.01).opacity(chosen ? 1 : 0)
                                }
                                .frame(width: 20, height: 20)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.title ?? "").yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface).lineLimit(1)
                                    Text([c.artist, c.album, c.durationSec.map { formatTime(Int($0.intValue)) }].compactMap { $0 }
                                        .joined(separator: "   "))
                                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 16)
                            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                            .background(chosen ? s.secondaryContainer : s.surfaceContainer,
                                        in: CloudSyncView.segment(i, row.candidates.count))
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain).pointerHighlight()
                        .animation(Yoin.effects, value: chosen)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .clipped()
    }

    // MARK: Done

    @ViewBuilder private var doneStep: some View {
        if let result = model.result, let source = model.source, let target = model.target {
            let copied = Int(result.copied.total + result.updated.total)
            let skipped = Int(result.skippedPreviously + result.kept.total)
            let left = Int(result.unmatched.total)
            let local = source.account.localProfileId != nil
            let cleanable = model.cloudSyncOn && !model.cleaned && !model.cleaning
                && BackupFormat.shared.isSyncScope(scope: source.account.scope)
            VStack(alignment: .leading, spacing: 28) {
                SyncOutcomeHero(app: s, icon: "check", title: SyncCopy.s("settings_xfer_done_title", target.account.displayName),
                                bodyText: SyncCopy.s("settings_xfer_done_kept", source.account.displayName)) {
                    HStack(spacing: 10) {
                        kit.countTile(copied, SyncCopy.s("settings_xfer_tile_copied"), s.primaryContainer, s.onPrimaryContainer)
                        kit.countTile(skipped, SyncCopy.s("settings_xfer_tile_skipped"), s.surfaceContainerHigh, s.onSurface)
                        kit.countTile(left, SyncCopy.s("settings_xfer_tile_left"), s.surfaceContainerHigh, s.onSurface)
                    }
                    .padding(.top, 8)
                }
                VStack(alignment: .leading, spacing: 8) {
                    if local || cleanable {
                        kit.group(nil, rows: (local ? [AnyView(kit.item("person", SyncCopy.s("settings_xfer_remove_card"),
                                                                        summary: SyncCopy.s("settings_xfer_remove_card_summary")) {
                            Toggle("", isOn: Binding(get: { model.removeCard }, set: { model.removeCard = $0 }))
                                .labelsHidden().tint(s.primary)
                        })] : []) + (cleanable ? [AnyView(Button { confirmClean = true } label: {
                            kit.item("delete_sweep", SyncCopy.s("settings_xfer_clean"),
                                     summary: SyncCopy.s("settings_xfer_clean_summary", target.account.displayName, source.account.displayName)) {
                                YoinIcon("chevron_right", size: 20).foregroundStyle(s.onSurfaceVariant)
                            }
                            .contentShape(.rect)
                        }.buttonStyle(PressScale()))] : []))
                    }
                    kit.note("info", SyncCopy.s("settings_xfer_neodb_note"))
                }
            }
        }
    }
}
