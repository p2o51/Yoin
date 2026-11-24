import SwiftUI
import UniformTypeIdentifiers
import YoinShared

/// "Download all data" and "Import from backup", one step at a time (Android BackupViewModel / BackupPage):
/// export → exporting → (the system's save sheet) → exported | failed;
/// import → reading → review (where each account goes) → importing → imported | failed.
@MainActor
@Observable
final class BackupFlowModel {
    struct Review: Equatable {
        var fileName: String
        var plan: YoinImportPlan
        /// scope → where it goes, for every account (defaults filled in).
        var choices: [String: YoinImportChoice]
        /// Cloud sync is on here: "Merge into iCloud / Google Drive", else "into this device".
        var intoCloud: Bool

        var canImport: Bool { Portability.canImport(plan, choices: choices) }

        static func == (a: Review, b: Review) -> Bool {
            a.fileName == b.fileName && a.plan === b.plan && a.choices == b.choices && a.intoCloud == b.intoCloud
        }
    }

    enum Step: Equatable {
        case idle
        case exporting(fromCloud: Bool)
        /// The zip is ready for the save sheet.
        case saving(Portability.Exported)
        case exported(fileName: String, export: YoinBackupExport, bytes: Int)
        case exportFailed
        case reading(fileName: String)
        case readFailed(fileName: String, Portability.ReadFailure)
        case review(Review)
        case importing(Review)
        case imported(Review, YoinImportResult)
        case importFailed(Review)

        static func == (a: Step, b: Step) -> Bool {
            if case .review(let x) = a, case .review(let y) = b { return x == y }
            return a.key == b.key
        }

        /// The step's kind (what the page animates between).
        var key: String {
            switch self {
            case .idle: "idle"
            case .exporting: "exporting"
            case .saving: "saving"
            case .exported: "exported"
            case .exportFailed: "exportFailed"
            case .reading: "reading"
            case .readFailed: "readFailed"
            case .review: "review"
            case .importing: "importing"
            case .imported: "imported"
            case .importFailed: "importFailed"
            }
        }
    }

    private(set) var step: Step = .idle
    private let sync: CloudSync

    init(sync: CloudSync = .shared) { self.sync = sync }

    var busy: Bool {
        switch step {
        case .exporting, .reading, .importing: true
        default: false
        }
    }

    func export() {
        guard step == .idle else { return }
        step = .exporting(fromCloud: sync.state.phase.enabled)
        Task {
            do {
                step = .saving(try await Portability.export(sync: sync))
            } catch {
                step = .exportFailed
            }
        }
    }

    /// The save sheet closed: saved as [url], failed, or cancelled (nil: the page goes away).
    func saved(_ result: Result<URL, Error>?) -> Bool {
        guard case .saving(let exported) = step else { return true }
        switch result {
        case .success(let url): step = .exported(fileName: url.lastPathComponent, export: exported.export, bytes: exported.zip.count)
        case .failure: step = .exportFailed
        case nil: return false
        }
        return true
    }

    func read(_ url: URL) {
        guard step == .idle else { return }
        let name = url.lastPathComponent
        step = .reading(fileName: name)
        Task {
            switch await Portability.plan(fileAt: url, sync: sync) {
            case .failure(let reason): step = .readFailed(fileName: name, reason)
            case .success(let plan):
                step = .review(Review(fileName: name, plan: plan,
                                      choices: Dictionary(plan.accounts.map { ($0.account.scope, $0.target) }, uniquingKeysWith: { a, _ in a }),
                                      intoCloud: sync.state.phase.enabled))
            }
        }
    }

    /// Where [scope] goes; ignored when it isn't one of its options.
    func choose(_ choice: YoinImportChoice) {
        guard case .review(var review) = step,
              let account = review.plan.accounts.first(where: { $0.account.scope == choice.scope }),
              Portability.targetOptions(account).contains(choice) || choice == account.target else { return }
        review.choices[choice.scope] = choice
        step = .review(review)
    }

    func confirmImport() {
        guard case .review(let review) = step, review.canImport else { return }
        step = .importing(review)
        Task {
            do {
                step = .imported(review, try await Portability.apply(review.plan, choices: review.choices, sync: sync))
            } catch {
                step = .importFailed(review)
            }
        }
    }

    #if DEBUG
    /// Screenshots: a step set by hand.
    func show(_ step: Step) { self.step = step }
    #endif
}

/// The export or import page (Android BackupActivity's BackupPage).
struct BackupFlowView: View {
    enum Mode: Hashable {
        case export
        case importFile(URL)
    }

    var mode: Mode
    @State private var model: BackupFlowModel
    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var saving = false

    init(mode: Mode, model: BackupFlowModel? = nil) {
        self.mode = mode
        #if DEBUG
        let made = model ?? PortabilityDemo.takeBackup() ?? BackupFlowModel()
        #else
        let made = model ?? BackupFlowModel()
        #endif
        _model = State(initialValue: made)
    }

    private var s: CoverScheme { BackupTexts.scheme(app, colorScheme) }
    private var kit: SyncFlowKit { SyncFlowKit(app: s, dark: colorScheme == .dark) }

    var body: some View {
        SyncFlowPage(app: s, onBack: { dismiss() }) {
            VStack(alignment: .leading, spacing: 0) { content }
                .id(model.step.key)
                .transition(.opacity)
        } footer: {
            footer
        }
        .animation(Yoin.effects, value: model.step.key)
        .task {
            switch mode {
            case .export: model.export()
            case .importFile(let url): model.read(url)
            }
        }
        .onChange(of: model.step.key) { _, key in saving = key == "saving" }
        .fileExporter(isPresented: $saving, document: savingDocument, contentType: .zip,
                      defaultFilename: savingName) { result in
            if !model.saved(result) { dismiss() }
        }
        // The save sheet went away without saving (cancelled): nothing was exported, the page goes too.
        .onChange(of: saving) { _, open in
            guard !open else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(300))
                if model.step.key == "saving", !model.saved(nil) { dismiss() }
            }
        }
    }

    private var savingDocument: BackupZipDocument? {
        if case .saving(let exported) = model.step { return BackupZipDocument(data: exported.zip) }
        return nil
    }

    private var savingName: String? {
        if case .saving(let exported) = model.step { return exported.fileName }
        return nil
    }

    // MARK: Footer

    @ViewBuilder private var footer: some View {
        switch model.step {
        case .review(let review):
            kit.flowButtons(SyncCopy.s("settings_sync_import_confirm"), enabled: review.canImport,
                            secondary: SyncCopy.s("settings_sync_import_cancel"), onSecondary: { dismiss() }) {
                model.confirmImport()
            }
        case .exported, .exportFailed, .readFailed, .imported, .importFailed:
            kit.flowButtons(SyncCopy.s("settings_sync_done")) { dismiss() }
        default:
            EmptyView()
        }
    }

    // MARK: Steps

    @ViewBuilder private var content: some View {
        switch model.step {
        case .idle, .saving:
            SyncProgressHero(app: s)
        case .exporting(let fromCloud):
            kit.headline(SyncCopy.s("settings_sync_export_running"),
                         lead: SyncCopy.s(fromCloud ? "settings_sync_export_running_summary" : "settings_sync_export_running_summary_local"))
            SyncProgressHero(app: s)
        case .exported(let fileName, let export, let bytes):
            exported(fileName, export, bytes)
        case .exportFailed:
            SyncOutcomeHero(app: s, icon: "close", title: SyncCopy.s("settings_sync_export_failed"),
                            bodyText: SyncCopy.s("settings_sync_export_failed_summary"), failed: true)
        case .reading(let fileName):
            kit.headline(SyncCopy.s("settings_sync_import_reading"), lead: fileName)
            SyncProgressHero(app: s)
        case .readFailed(let fileName, let reason):
            SyncOutcomeHero(app: s, icon: "close", title: Self.readFailureTitle(reason), bodyText: fileName, failed: true)
        case .review(let review):
            ImportReviewView(review: review, kit: kit, onChoose: model.choose)
        case .importing(let review):
            kit.headline(SyncCopy.s("settings_sync_import_running"), lead: Self.mergeTarget(review.intoCloud))
            SyncProgressHero(app: s)
        case .imported(let review, let result):
            imported(review, result)
        case .importFailed:
            SyncOutcomeHero(app: s, icon: "close", title: SyncCopy.s("settings_sync_import_failed"),
                            bodyText: SyncCopy.s("settings_sync_import_failed_summary"), failed: true)
        }
    }

    static func readFailureTitle(_ reason: Portability.ReadFailure) -> String {
        switch reason {
        case .notABackup: SyncCopy.s("settings_sync_read_not_backup")
        case .tooNew: SyncCopy.s("settings_sync_read_too_new")
        case .checksum: SyncCopy.s("settings_sync_read_checksum")
        case .unreadable: SyncCopy.s("settings_sync_read_failed")
        }
    }

    /// The sync location's name while Cloud sync is on.
    @MainActor static var cloudName: String {
        CloudSync.shared.isICloud ? SyncCopy.s("settings_sync_icloud") : SyncCopy.s("settings_sync_google_drive")
    }

    @MainActor static func mergeTarget(_ intoCloud: Bool) -> String {
        SyncCopy.s("settings_sync_import_merge_into", intoCloud ? cloudName : SyncCopy.s("settings_sync_import_this_device_inline"))
    }

    private func exported(_ fileName: String, _ export: YoinBackupExport, _ bytes: Int) -> some View {
        let manifest = export.manifest
        let items = manifest.accounts.reduce(0) { sum, a in sum + a.counts.values.reduce(0) { $0 + $1.intValue } }
            + manifest.globalCounts.values.reduce(0) { $0 + $1.intValue }
        let dirs = manifest.accounts.compactMap(\.readableDir).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
        let readable = BackupFormat.shared.READABLE_DIR
        return VStack(alignment: .leading, spacing: 28) {
            SyncOutcomeHero(app: s, icon: "download_done", title: SyncCopy.s("settings_sync_export_saved"), bodyText: fileName) {
                kit.metaLine([SyncCopy.size(bytes), SyncCopy.stat("settings_sync_unit_accounts", manifest.accounts.count),
                              SyncCopy.stat("settings_sync_unit_items", items)], style: Tokens.type.bodyLarge)
            }
            kit.group(SyncCopy.s("settings_sync_export_contents"), rows: [
                AnyView(treeRow(BackupFormat.shared.MANIFEST_PATH, SyncCopy.s("settings_sync_export_manifest"), depth: 0)),
                AnyView(treeRow(BackupFormat.shared.SNAPSHOT_PATH, SyncCopy.s("settings_sync_export_data"), depth: 0)),
                AnyView(VStack(alignment: .leading, spacing: 0) {
                    treeRow(readable, SyncCopy.s("settings_sync_export_readable"), depth: 0, folder: true)
                    ForEach(dirs, id: \.self) { dir in
                        treeRow(String(dir.dropFirst(readable.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/",
                                nil, depth: 1, folder: true)
                    }
                }
                .padding(.bottom, 8)),
            ])
            kit.group(SyncCopy.s("settings_sync_export_about"), rows: [
                AnyView(kit.item("check_circle", SyncCopy.s("settings_sync_export_lossless"), summary: SyncCopy.s("settings_sync_export_lossless_summary"))),
                AnyView(kit.item("article", SyncCopy.s("settings_sync_export_offline"), summary: SyncCopy.s("settings_sync_export_offline_summary"))),
                AnyView(kit.item("lock", SyncCopy.s("settings_sync_export_private"), summary: SyncCopy.s("settings_sync_export_private_summary"))),
            ])
            if !export.readFailures.isEmpty {
                kit.note("info", SyncCopy.s("settings_sync_export_read_failures", SyncCopy.kinds(export.readFailures)))
            }
        }
    }

    private func treeRow(_ name: String, _ note: String?, depth: Int, folder: Bool = false) -> some View {
        HStack(spacing: 12) {
            YoinIcon(folder ? "folder" : "article", size: 20).foregroundStyle(s.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 0) {
                Text(name).font(.system(size: 16, design: .monospaced)).foregroundStyle(s.onSurface)
                if let note { Text(note).yoinRole(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant) }
            }
        }
        .padding(.leading, 16 + 24 * CGFloat(depth))
        .padding(.trailing, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func imported(_ review: BackupFlowModel.Review, _ result: YoinImportResult) -> some View {
        let all = result.byScope.map(\.counts).reduce(result.global) { sum, c in
            ImportCounts(written: sum.written + c.written, unchanged: sum.unchanged + c.unchanged, keptLocal: sum.keptLocal + c.keptLocal,
                         keptBoth: sum.keptBoth + c.keptBoth, tombstones: sum.tombstones + c.tombstones, skipped: sum.skipped + c.skipped,
                         failedKinds: sum.failedKinds.union(c.failedKinds))
        }
        let written = Int(all.written + all.keptBoth)
        let skipped = Int(all.unchanged + all.keptLocal + all.tombstones + all.skipped)
        let failed = Array(all.failedKinds).sorted()
        let refused = result.cloudOnlyRefused.map { scope in
            review.plan.accounts.first { $0.account.scope == scope }?.account.displayName ?? scope
        }
        return VStack(alignment: .leading, spacing: 28) {
            SyncOutcomeHero(app: s, icon: "check", title: SyncCopy.s("settings_sync_import_done"),
                            bodyText: review.intoCloud ? SyncCopy.s("settings_sync_import_done_summary", Self.cloudName)
                                                       : SyncCopy.s("settings_sync_import_done_summary_local")) {
                HStack(spacing: 10) {
                    kit.countTile(written, SyncCopy.s("settings_sync_import_written"), s.primaryContainer, s.onPrimaryContainer)
                    kit.countTile(skipped, SyncCopy.s("settings_sync_import_skipped"), s.surfaceContainerHigh, s.onSurface)
                    if result.cloudOnlyMerged > 0 {
                        kit.countTile(Int(result.cloudOnlyMerged), SyncCopy.s("settings_sync_import_cloud"), s.surfaceContainerHigh, s.onSurface)
                    }
                }
                .padding(.top, 8)
            }
            if !failed.isEmpty || !refused.isEmpty {
                kit.group(nil, rows: (failed.isEmpty ? [] : [AnyView(kit.item("error", SyncCopy.s("settings_sync_import_failed_kinds", SyncCopy.kinds(failed))))])
                    + (refused.isEmpty ? [] : [AnyView(kit.item("info", SyncCopy.s("settings_sync_import_refused", SyncCopy.joined(refused))))]))
            }
        }
    }
}

/// The review before an import (Android ImportReview): what the file is, where each account goes, the rules.
struct ImportReviewView: View {
    var review: BackupFlowModel.Review
    var kit: SyncFlowKit
    var onChoose: (YoinImportChoice) -> Void

    private var s: CoverScheme { kit.app }

    var body: some View {
        let manifest = review.plan.backup.manifest
        VStack(alignment: .leading, spacing: 28) {
            kit.headline(SyncCopy.s("settings_sync_import_review"), lead: review.fileName)
                .padding(.bottom, -28)
            kit.group(SyncCopy.s("settings_sync_import_info"), rows: [
                AnyView(kit.item("history", Date(timeIntervalSince1970: Double(manifest.exportedAt) / 1000)
                    .formatted(date: .long, time: .shortened), summary: SyncCopy.s("settings_sync_import_exported"))),
                AnyView(kit.item("cloud", Self.locationName(manifest.source.location), meta: [(manifest.source.deviceName, nil)])),
                AnyView(kit.item("check_circle", SyncCopy.s("settings_sync_import_verified"),
                                 summary: SyncCopy.s("settings_sync_import_verified_summary"))),
            ])
            kit.group(BackupFlowView.mergeTarget(review.intoCloud), rows: accountRows)
            kit.group(SyncCopy.s("settings_sync_import_rules"), rows: [
                AnyView(kit.item("note", SyncCopy.s("settings_sync_import_rule_add"), summary: SyncCopy.s("settings_sync_import_rule_add_summary"))),
                AnyView(kit.item("star", SyncCopy.s("settings_sync_import_rule_rating"), summary: SyncCopy.s("settings_sync_import_rule_rating_summary"))),
                AnyView(kit.item("reviews", SyncCopy.s("settings_sync_import_rule_review"), summary: SyncCopy.s("settings_sync_import_rule_review_summary"))),
            ])
        }
    }

    static func locationName(_ location: String) -> String {
        switch location {
        case BackupFormat.shared.LOCATION_GOOGLE_DRIVE: SyncCopy.s("settings_sync_google_drive")
        case BackupFormat.shared.LOCATION_ICLOUD: SyncCopy.s("settings_sync_icloud")
        default: SyncCopy.s("settings_sync_import_this_device")
        }
    }

    private var accountRows: [AnyView] {
        var rows = review.plan.accounts.map { AnyView(accountRow($0)) }
        let global = review.plan.globalCounts
        if global.values.reduce(0, { $0 + $1.intValue }) > 0 {
            func n(_ kind: String) -> Int { global[kind]?.intValue ?? 0 }
            rows.append(AnyView(kit.item("library", SyncCopy.s("settings_sync_import_global"), meta: [
                SyncCopy.stat("settings_sync_unit_backups", n("playlist_backup")),
                SyncCopy.stat("settings_sync_unit_translations", n("lyrics_translation")),
                SyncCopy.stat("settings_sync_unit_about", n("song_about")),
            ])))
        }
        return rows
    }

    /// An account's live record counts in the page's words (Android accountCountGroups / scopeStatGroups).
    static func countGroups(_ counts: [String: KotlinInt]) -> [(String, String?)] {
        func n(_ kind: String) -> Int { counts[kind]?.intValue ?? 0 }
        let notes = n("song_note") + n("album_note")
        let albums = max(n("album_rating"), n("album_review"))
        let songs = n("track_rating")
        let titles = n("memory_title") + n("album_title")
        var out: [(String, String?)] = []
        if notes > 0 { out.append(SyncCopy.stat("settings_sync_unit_notes", notes)) }
        if albums > 0 { out.append(SyncCopy.stat("settings_sync_unit_albums", albums)) }
        if songs > 0 { out.append(SyncCopy.stat("settings_sync_unit_song_ratings", songs)) }
        if titles > 0 { out.append(SyncCopy.stat("settings_sync_unit_titles", titles)) }
        return out
    }

    /// The service line under an account name: the service (unless the name says it), then the hint.
    static func accountLine(provider: String, name: String, hint: String?, fromDevice: String? = nil) -> [(String, String?)] {
        var out: [(String, String?)] = []
        let service = SyncCopy.service(provider)
        if !service.isEmpty, service.caseInsensitiveCompare(name) != .orderedSame { out.append((service, nil)) }
        if let hint, !hint.trimmingCharacters(in: .whitespaces).isEmpty { out.append((hint, nil)) }
        if let fromDevice, !fromDevice.isEmpty { out.append((SyncCopy.s("settings_sync_from_device", fromDevice), nil)) }
        return out
    }

    private func accountRow(_ plan: YoinImportAccount) -> some View {
        let account = plan.account
        let choice = review.choices[account.scope] ?? plan.target
        let counts = Self.countGroups(plan.counts)
        let options = Portability.targetOptions(plan)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                kit.accountGlyph(account.provider)
                VStack(alignment: .leading, spacing: 2) {
                    Text(account.displayName).yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface)
                    let line = Self.accountLine(provider: account.provider, name: account.displayName, hint: account.hint)
                    if !line.isEmpty { kit.metaLine(line) }
                    if counts.isEmpty {
                        Text(SyncCopy.s("settings_sync_no_data")).yoinRole(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant.opacity(0.6))
                    } else {
                        kit.metaLine(counts)
                    }
                    Text(plan.sameAccount && choice.kind != .skip ? SyncCopy.s("settings_sync_import_same_account")
                         : choice.kind == .cloudOnly ? SyncCopy.s("settings_sync_import_cloud_only_note")
                         : SyncCopy.s("settings_sync_import_choose"))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(s.onSurfaceVariant.opacity(0.6))
                        .contentTransition(.opacity)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 10)
            SyncMetaFlow(spacing: 8, lineSpacing: 8) {
                ForEach(options, id: \.self) { option in
                    chip(label(option, plan), on: option == choice) { onChoose(option) }
                }
            }
            .padding(.leading, 68)
            .padding(.trailing, 16)
            .padding(.bottom, 16)
            if plan.wasPurged {
                Text(SyncCopy.s("settings_sync_import_purged_note"))
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(s.onSurfaceVariant)
                    .padding(.leading, 68)
                    .padding(.trailing, 16)
                    .padding(.bottom, 16)
            }
        }
        .animation(Yoin.spatial, value: choice)
    }

    private func label(_ option: YoinImportChoice, _ plan: YoinImportAccount) -> String {
        switch option.kind {
        case .intoProfile:
            SyncCopy.s("settings_sync_import_into_profile",
                       plan.options.first { $0.localProfileId == option.profileId }?.displayName ?? "")
        case .cloudOnly: SyncCopy.s("settings_sync_import_keep_cloud")
        default: SyncCopy.s("settings_sync_import_skip")
        }
    }

    /// Android's ToggleButton: a pill that fills when it is the choice.
    private func chip(_ text: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if on { YoinIcon("check", size: 16).transition(.opacity.combined(with: .scale)) }
                Text(text).yoinRole(Tokens.type.labelLarge).lineLimit(1)
            }
            .foregroundStyle(on ? s.onPrimary : s.onSurfaceVariant)
            .padding(.horizontal, 16)
            .frame(minHeight: 40)
            .background(on ? s.primary : s.surfaceContainerHighest, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .sensoryFeedback(.selection, trigger: on)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}

/// The export's zip for the system's save sheet.
struct BackupZipDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.zip]
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
