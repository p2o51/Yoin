import SwiftUI
import YoinShared

/// Android's approved copy for the backup and transfer pages, by its resource name (values/ and
/// values-zh-rCN/ → Localizable.xcstrings, manual keys). A key with arguments carries them after the name, in
/// Android's order ("settings_xfer_step %1$lld %2$lld %3$@"), so translations can move them; plurals are
/// "<name> %lld" and read "%lld unit", so a stat shows the number, then the unit.
@MainActor
enum SyncCopy {
    static func s(_ name: String, _ args: CVarArg...) -> String {
        guard !args.isEmpty else { return Bundle.main.localizedString(forKey: name, value: nil, table: nil) }
        let format = Bundle.main.localizedString(forKey: key(name, args), value: nil, table: nil)
        return String(format: format, locale: Locale.current, arguments: args)
    }

    /// The catalog key of [name] taking [args]: a String is "%n$@", a number "%n$lld".
    nonisolated static func key(_ name: String, _ args: [CVarArg]) -> String {
        ([name] + args.enumerated().map { "%\($0.offset + 1)$" + ($0.element is String ? "@" : "lld") }).joined(separator: " ")
    }

    /// "3 accounts" for an Android plurals resource.
    static func plural(_ name: String, _ count: Int) -> String {
        String.localizedStringWithFormat(Bundle.main.localizedString(forKey: name + " %lld", value: nil, table: nil), count)
    }

    /// A plural stat split for a meta line: the number, then the unit ("3", "accounts").
    static func stat(_ key: String, _ count: Int) -> (String, String?) {
        let phrase = plural(key, count)
        guard let space = phrase.firstIndex(of: " ") else { return (phrase, nil) }
        return (String(phrase[..<space]), String(phrase[phrase.index(after: space)...]))
    }

    /// "a, b and c" in the UI language (Android joinedList).
    static func joined(_ parts: [String]) -> String {
        guard parts.count > 1 else { return parts.first ?? "" }
        return s("settings_sync_list_last", parts.dropLast().joined(separator: s("settings_sync_list_separator")), parts.last!)
    }

    /// A size as value + unit (KB under a megabyte, else MB with one decimal; Android sizeStat).
    static func size(_ bytes: Int) -> (String, String?) {
        let kb = Double(bytes) / 1024
        if kb < 1024 {
            return ("\(max(bytes > 0 ? 1 : 0, Int(kb.rounded(.up))))", s("settings_sync_unit_kb"))
        }
        return (String(format: "%.1f", locale: Locale.current, kb / 1024), s("settings_sync_unit_mb"))
    }

    /// The service's name ("Subsonic", "Spotify", "Apple Music") for a provider token.
    static func service(_ provider: String?) -> String {
        guard let provider else { return "" }
        switch provider {
        case AppleMusicMapping.provider, "spotify", "subsonic": return BackupTexts.service(provider).name
        default: return provider
        }
    }

    /// Kinds as the person knows them ("Notes, Ratings"; Android kindLabels).
    static func kinds(_ kinds: [String]) -> String {
        var labels: [String] = []
        for kind in kinds {
            let key: String = switch kind {
            case "song_note", "album_note": "settings_sync_kind_notes"
            case "track_rating", "album_rating": "settings_sync_kind_ratings"
            case "album_review": "settings_sync_kind_reviews"
            case "memory_title", "album_title", "memory_copy": "settings_sync_kind_titles"
            default: "settings_sync_kind_other"
            }
            let label = s(key)
            if !labels.contains(label) { labels.append(label) }
        }
        return joined(labels)
    }
}

/// A Cloud sync sub-page (Android SyncPage): the back (or close) button, the column, and the pinned footer
/// with the page's main action.
struct SyncFlowPage<Content: View, Footer: View>: View {
    var app: CoverScheme
    var close = false
    var onBack: () -> Void
    @ViewBuilder var content: () -> Content
    @ViewBuilder var footer: () -> Footer

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) { content() }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaBar(edge: .top) { topBar.topEdgeBlur(app.surfaceContainer) }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack(spacing: 8) { footer() }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 16 + ShellBarMetrics.clearance)
                .background {
                    // Rows scroll away under the pinned action instead of showing through it.
                    LinearGradient(stops: [.init(color: app.surfaceContainer.opacity(0), location: 0),
                                           .init(color: app.surfaceContainer, location: 0.18)],
                                   startPoint: .top, endPoint: .bottom)
                        .padding(.top, -16)
                        .ignoresSafeArea(edges: .bottom)
                }
        }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }

    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                YoinIcon(close ? "close" : "back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .yGlassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel(close ? SyncCopy.s("settings_xfer_close") : String(localized: "Back"))
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }
}

/// The pieces the backup and transfer pages are built from, in one scheme.
@MainActor
struct SyncFlowKit {
    var app: CoverScheme
    var dark: Bool

    /// Android SyncHeadline (small): the step's title and an optional lead.
    func headline(_ title: String, lead: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .yoinRole(Tokens.type.headlineMedium)
                .foregroundStyle(app.onSurface)
                .accessibilityAddTraits(.isHeader)
            if let lead {
                Text(lead)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 8)
        .padding(.top, 16)
        .padding(.bottom, 24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A titled group of rows as segments (20 outer / 4 inner corners); [trailing] sits right of the title.
    func group(_ title: String?, trailing: String? = nil, rows: [AnyView]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                HStack {
                    Text(title)
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(app.primary)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    if let trailing {
                        Text(trailing).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.onSurfaceVariant)
                    }
                }
                .padding(.horizontal, 8)
            }
            VStack(spacing: 2) {
                ForEach(rows.indices, id: \.self) { i in
                    rows[i]
                        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                        .background(app.surfaceBright, in: CloudSyncView.segment(i, rows.count))
                        .clipShape(CloudSyncView.segment(i, rows.count))
                }
            }
        }
    }

    /// Android SettingsItem: icon, title, an optional summary (or meta line), an optional trailing view.
    func item<Trailing: View>(_ icon: String?, _ title: String, summary: String? = nil, meta: [(String, String?)] = [],
                              tone: LandingTone? = nil, @ViewBuilder trailing: () -> Trailing = { EmptyView() }) -> some View {
        HStack(spacing: 12) {
            if let icon { rowIcon(icon, tone: tone) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                if let summary {
                    Text(summary).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                }
                if !meta.isEmpty { metaLine(meta) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    /// The 40 pt tinted circle a settings row's icon sits on.
    func rowIcon(_ icon: String, tone: LandingTone? = nil) -> some View {
        YoinIcon(icon, size: 20)
            .foregroundStyle(tone?.iconContent ?? app.onSecondaryContainer)
            .frame(width: 40, height: 40)
            .background(tone?.iconContainer ?? app.secondaryContainer, in: .circle)
    }

    /// An account's service mark on its tone.
    func accountGlyph(_ provider: String, size: CGFloat = 40) -> some View {
        let tone = BackupTexts.tone(provider, dark: dark)
        return YoinIcon(BackupTexts.service(provider).glyph, size: size * 0.5)
            .foregroundStyle(tone.iconContent)
            .frame(width: size, height: size)
            .background(tone.iconContainer, in: .circle)
    }

    /// Android MetaLine / MetaFlow: groups 12 pt apart, a stat's number then its muted unit.
    func metaLine(_ groups: [(String, String?)], style: YoinTokens.TypographyStyle = Tokens.type.bodyMedium,
                  color: Color? = nil) -> some View {
        SyncMetaFlow(spacing: 12, lineSpacing: 2) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, g in
                HStack(spacing: 4) {
                    Text(g.0).yoinRole(style, weight: g.1 == nil ? nil : .medium)
                    if let unit = g.1 { Text(unit).yoinRole(style).opacity(0.6) }
                }
            }
        }
        .foregroundStyle(color ?? app.onSurfaceVariant)
    }

    /// Android SyncNote: a small icon and a muted sentence under a group.
    func note(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            YoinIcon(icon, size: 18).foregroundStyle(app.onSurfaceVariant).padding(.top, 1)
            Text(text).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    /// Android SyncPill.
    func pill(_ text: String, container: Color, content: Color) -> some View {
        Text(text)
            .yoinRole(Tokens.type.labelMedium)
            .foregroundStyle(content)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(container, in: .capsule)
    }

    /// Android CountTile.
    func countTile(_ n: Int, _ label: String, _ bg: Color, _ fg: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(n)").yoinRole(Tokens.type.headlineSmall).contentTransition(.numericText())
            Text(label).yoinRole(Tokens.type.labelMedium).multilineTextAlignment(.center)
        }
        .foregroundStyle(fg)
        .padding(.horizontal, 6)
        .frame(maxWidth: .infinity, minHeight: 72)
        .background(bg, in: .rect(cornerRadius: 20, style: .continuous))
    }

    /// Android FlowButtons: an optional text button, then the main one filling the row (with a count).
    @ViewBuilder
    func flowButtons(_ primary: String, enabled: Bool = true, badge: Int? = nil, secondary: String? = nil,
                     onSecondary: @escaping () -> Void = {}, onPrimary: @escaping () -> Void) -> some View {
        if let secondary {
            Button(secondary, action: onSecondary)
                .font(.gsf(16, .medium))
                .foregroundStyle(app.primary)
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .buttonStyle(PressScale())
        }
        Button(action: onPrimary) {
            HStack(spacing: 10) {
                Text(primary).yoinRole(Tokens.type.titleMedium).lineLimit(1)
                if let badge {
                    Text("\(badge)")
                        .yoinRole(Tokens.type.labelLarge)
                        .padding(.horizontal, 8)
                        .frame(minWidth: 24, minHeight: 24)
                        .background(app.onPrimary.opacity(0.18), in: .capsule)
                        .contentTransition(.numericText())
                }
            }
            .foregroundStyle(enabled ? app.onPrimary : app.onSurfaceVariant)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(enabled ? app.primary : app.surfaceContainerHighest, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!enabled)
    }
}

/// Android ProgressHero: a running indicator on a soft container, an optional count and a caption.
struct SyncProgressHero<Caption: View>: View {
    var app: CoverScheme
    /// nil: indeterminate.
    var progress: Double?
    var count: String?
    @ViewBuilder var caption: () -> Caption

    var body: some View {
        VStack(spacing: 20) {
            ZStack {
                RoundedRectangle(cornerRadius: 40, style: .continuous).fill(app.primaryContainer)
                ProgressView().controlSize(.large).tint(app.onPrimaryContainer)
            }
            .frame(width: 120, height: 120)
            if let count {
                Text(count).yoinRole(Tokens.type.displaySmall).foregroundStyle(app.onSurface).contentTransition(.numericText())
            }
            if let progress {
                ProgressView(value: min(1, max(0, progress))).tint(app.primary)
                    .animation(Yoin.spatial, value: progress)
            }
            caption()
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 8)
        .padding(.vertical, 24)
    }
}

extension SyncProgressHero where Caption == EmptyView {
    init(app: CoverScheme, progress: Double? = nil, count: String? = nil) {
        self.init(app: app, progress: progress, count: count) { EmptyView() }
    }
}

/// Android OutcomeHero: a badge that springs in with its icon, the title and a line under it.
struct SyncOutcomeHero<Extra: View>: View {
    var app: CoverScheme
    var icon: String
    var title: String
    var bodyText: String?
    var failed = false
    @ViewBuilder var extra: () -> Extra
    @State private var shown = false

    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: failed ? 36 : 44, style: .continuous)
                    .fill(failed ? app.errorContainer : app.primaryContainer)
                YoinIcon(icon, size: 44).foregroundStyle(failed ? app.onErrorContainer : app.onPrimaryContainer)
            }
            .frame(width: 112, height: 112)
            .scaleEffect(shown ? 1 : 0.2)
            .rotationEffect(.degrees(shown ? 0 : -60))
            .opacity(shown ? 1 : 0)
            .padding(.bottom, 4)
            Text(title)
                .yoinRole(Tokens.type.headlineSmall)
                .foregroundStyle(app.onSurface)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            if let bodyText {
                Text(bodyText)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurfaceVariant)
                    .multilineTextAlignment(.center)
            }
            extra()
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 8)
        .padding(.vertical, 16)
        .onAppear { withAnimation(Yoin.spatial) { shown = true } }
        .sensoryFeedback(failed ? .error : .success, trigger: shown)
    }
}

extension SyncOutcomeHero where Extra == EmptyView {
    init(app: CoverScheme, icon: String, title: String, bodyText: String?, failed: Bool = false) {
        self.init(app: app, icon: icon, title: title, bodyText: bodyText, failed: failed) { EmptyView() }
    }
}

/// Lays its children out in rows, wrapping when the width runs out (Android FlowRow / MetaFlow).
struct SyncMetaFlow: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width.map { min($0, width) } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for i in row.items {
                let size = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row { var items: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for i in subviews.indices {
            let size = subviews[i].sizeThatFits(.unspecified)
            let needed = row.items.isEmpty ? size.width : row.width + spacing + size.width
            if !row.items.isEmpty, needed > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.items.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.items.append(i)
        }
        if !row.items.isEmpty { rows.append(row) }
        return rows
    }
}
