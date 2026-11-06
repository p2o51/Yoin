import SwiftUI
import YoinShared

/// A backup's restore, every step in one sheet (Android `PlaylistRestoreSheet`,
/// docs/playlist-transfer.md §7): Restore to (the accounts), matching with
/// live progress (a segment per song, coloured by what it matched to, as
/// Android's RestoreLiveUpdate), the review (matched rows checked, unsure
/// rows to check with their candidates, a search for songs not found),
/// creating, and what came of it. Closing the sheet leaves matching running
/// or the review waiting; Restore reopens it there.
struct PlaylistRestoreSheet: View {
    @Bindable var model: PlaylistRestoreModel
    var targets: [RestoreTarget]

    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var colorScheme
    @State private var filter: Filter = .all
    @State private var expanded: Int32?
    @State private var tick = 0

    private enum Filter: Hashable { case all, toCheck, notFound }

    private var s: CoverScheme { BackupTexts.scheme(app, colorScheme) }

    var body: some View {
        ZStack {
            switch model.phase {
            case .idle, .choosing: targetList.transition(.opacity)
            case .matching(let t, let done, let total, let outcomes):
                matching(t, done: done, total: total, outcomes: outcomes).transition(.opacity)
            case .review(let t, let rows): review(t, rows: rows, writing: false).transition(.opacity)
            case .writing(let t, let rows): review(t, rows: rows, writing: true).transition(.opacity)
            case .done(let t, let record): outcome(t, record).transition(.opacity.combined(with: .scale(scale: 0.96)))
            case .failed(let t): failure(t).transition(.opacity)
            }
        }
        .animation(Yoin.spatial, value: phaseKey)
        .presentationDetents(detents)
        .presentationDragIndicator(.visible)
        .presentationContentInteraction(.scrolls)
        .sensoryFeedback(.selection, trigger: tick)
        .sheet(isPresented: Binding(get: { model.search != nil }, set: { if !$0 { model.closeSearch() } })) {
            if case .review(let t, _) = model.phase { RestoreSearchSheet(model: model, target: t) }
        }
    }

    private var phaseKey: Int {
        switch model.phase {
        case .idle, .choosing: 0
        case .matching: 1
        case .review, .writing: 2
        case .done: 3
        case .failed: 4
        }
    }

    private var detents: Set<PresentationDetent> {
        switch model.phase {
        case .review, .writing: [.large]
        default: [.medium, .large]
        }
    }

    private func title(_ text: String) -> some View {
        Text(text)
            .yoinRole(Tokens.type.titleLarge)
            .foregroundStyle(s.onSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }

    // MARK: Targets

    private var targetList: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                title(String(localized: "Restore to"))
                if let since = model.versionSince {
                    Text("Version of \(BackupTexts.time(since))")
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(s.onSurfaceVariant)
                }
                if targets.isEmpty {
                    Text("No accounts")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(s.onSurfaceVariant)
                        .padding(.vertical, 16)
                } else {
                    VStack(spacing: 2) {
                        ForEach(Array(targets.enumerated()), id: \.element.id) { i, t in
                            targetRow(t).background(s.surfaceContainerHigh, in: CloudSyncView.segment(i, targets.count))
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 28)
            .padding(.bottom, 24)
        }
    }

    private func targetRow(_ t: RestoreTarget) -> some View {
        let tone = BackupTexts.tone(t.provider, dark: colorScheme == .dark)
        return Button {
            tick += 1
            model.restore(to: t)
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle().fill(tone.container)
                    YoinIcon(BackupTexts.service(t.provider).glyph, size: 22).foregroundStyle(tone.onContainer)
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.label).yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface).lineLimit(1)
                    Text(t.note ?? BackupTexts.service(t.provider).name)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(s.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if t.available { YoinIcon("chevron_right", size: 20).foregroundStyle(s.onSurfaceVariant) }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .disabled(!t.available)
        .opacity(t.available ? 1 : 0.5)
    }

    // MARK: Matching

    private func matching(_ t: RestoreTarget, done: Int, total: Int, outcomes: [ReviewStatus]) -> some View {
        let fraction = total > 0 ? Double(done) / Double(total) : 0
        let tone = BackupTexts.tone(t.provider, dark: colorScheme == .dark)
        return VStack(spacing: 20) {
            ZStack {
                Circle().fill(s.primaryContainer)
                Circle()
                    .trim(from: 0, to: fraction)
                    .stroke(s.onPrimaryContainer, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(24)
                    .animation(Yoin.spatial, value: fraction)
            }
            .frame(width: 120, height: 120)
            Text("\(done) / \(total)")
                .yoinRole(Tokens.type.displaySmall)
                .foregroundStyle(s.onSurface)
                .contentTransition(.numericText())
                .animation(Yoin.effects, value: done)
            RestoreSegments(total: total, outcomes: outcomes, scheme: s)
                .frame(height: 10)
            HStack(spacing: 10) {
                ZStack {
                    Circle().fill(tone.container)
                    YoinIcon(BackupTexts.service(t.provider).glyph, size: 18).foregroundStyle(tone.onContainer)
                }
                .frame(width: 32, height: 32)
                Text("Matching on \(t.label)")
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(s.onSurfaceVariant)
                    .lineLimit(1)
            }
            Button("Cancel") { model.cancel() }
                .font(.gsf(15, .medium))
                .foregroundStyle(s.primary)
                .frame(minHeight: 40)
        }
        .padding(.horizontal, 24)
        .padding(.top, 32)
        .padding(.bottom, 24)
        .frame(maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .combine)
    }

    // MARK: Review

    private func review(_ t: RestoreTarget, rows: [ReviewRow], writing: Bool) -> some View {
        let summary = RestoreReview.shared.summary(rows: rows)
        let tracks = model.backup?.content.tracks ?? []
        let shown = rows.filter { r in
            switch filter {
            case .all: true
            case .toCheck: r.status == .uncertain
            case .notFound: r.status == .notfound
            }
        }
        let count = RestoreReview.shared.trackIdsToWrite(rows: rows).count
        return VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                title(String(localized: "Restore to \(t.label)"))
                SummaryBar(summary: summary, scheme: s)
                HStack(spacing: 8) {
                    filterChip(.all, "All", rows.count)
                    filterChip(.toCheck, "To check", Int(summary.toCheck))
                    filterChip(.notFound, "Not found", Int(summary.notFound))
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 28)
            .padding(.bottom, 12)
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(Array(shown.enumerated()), id: \.element.position) { i, row in
                        if Int(row.position) < tracks.count {
                            reviewRow(row, track: tracks[Int(row.position)], enabled: !writing, t: t)
                                .background(s.surfaceContainerHigh, in: CloudSyncView.segment(i, shown.count))
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
                .animation(Yoin.spatial, value: shown.map(\.position))
            }
            HStack(spacing: 8) {
                Button("Cancel") { model.cancel() }
                    .font(.gsf(16, .medium))
                    .foregroundStyle(s.primary)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 56)
                    .disabled(writing)
                Button {
                    tick += 1
                    model.create()
                } label: {
                    HStack(spacing: 8) {
                        if writing { ProgressView().tint(s.onPrimary) }
                        Text(writing ? String(localized: "Creating") : String(localized: "Create playlist")).yoinRole(Tokens.type.titleMedium)
                        if !writing {
                            Text("\(count)")
                                .yoinRole(Tokens.type.labelLarge)
                                .foregroundStyle(s.primary)
                                .padding(.horizontal, 8)
                                .frame(minWidth: 24, minHeight: 24)
                                .background(s.onPrimary, in: .capsule)
                                .contentTransition(.numericText())
                        }
                    }
                    .foregroundStyle(s.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(s.primary, in: .capsule)
                    .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .disabled(writing || count == 0)
                .opacity(count == 0 && !writing ? 0.38 : 1)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 16)
            .animation(Yoin.effects, value: count)
            .animation(Yoin.effects, value: writing)
        }
    }

    private func filterChip(_ f: Filter, _ label: String, _ n: Int) -> some View {
        let on = filter == f
        return Button {
            withAnimation(Yoin.spatial) { filter = f }
        } label: {
            HStack(spacing: 6) {
                if on { YoinIcon("check", size: 16).transition(.opacity.combined(with: .scale)) }
                Text(label).yoinRole(Tokens.type.labelLarge)
                Text("\(n)").yoinRole(Tokens.type.labelLarge).opacity(0.7).contentTransition(.numericText())
            }
            .foregroundStyle(on ? s.onSecondaryContainer : s.onSurfaceVariant)
            .padding(.horizontal, 12)
            .frame(minHeight: 32)
            .background(on ? s.secondaryContainer : s.surfaceContainerHigh, in: .rect(cornerRadius: 8, style: .continuous))
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .disabled(n == 0 && f != .all)
        .opacity(n == 0 && f != .all ? 0.5 : 1)
    }

    /// Android ReviewRowItem: the check mark, the backed-up song (and, when it isn't plainly the same, what
    /// would be written), its status; an unsure row opens its candidates, a row not found offers Search.
    private func reviewRow(_ row: ReviewRow, track: BackupTrack, enabled: Bool, t: RestoreTarget) -> some View {
        let uncertain = row.status == .uncertain, notFound = row.status == .notfound
        let open = uncertain && expanded == row.position
        let artists = track.artists.joined(separator: ", ")
        return VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button {
                    tick += 1
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
                .buttonStyle(.plain)
                .disabled(!enabled || !row.checkable)
                .opacity(row.checkable ? 1 : 0.3)
                .accessibilityLabel("Add to playlist")
                .accessibilityValue(row.checked ? String(localized: "On") : String(localized: "Off"))
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface).lineLimit(1)
                    if !artists.isEmpty {
                        Text(artists).yoinRole(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant).lineLimit(1)
                    }
                    if uncertain || row.status == .picked, let choice = row.choice {
                        Text(choice.title ?? "").yoinRole(Tokens.type.bodyMedium).foregroundStyle(s.primary).lineLimit(1)
                            .contentTransition(.opacity)
                    }
                }
                .opacity(notFound ? 0.6 : 1)
                Spacer(minLength: 8)
                switch row.status {
                case .uncertain: pill("To check", s.tertiaryContainer, s.onTertiaryContainer)
                case .picked: pill("Picked", s.secondaryContainer, s.onSecondaryContainer)
                case .notfound:
                    Button { model.openSearch(row.position, query: Self.query(track)) } label: {
                        HStack(spacing: 6) {
                            YoinIcon("search", size: 18)
                            Text("Search").yoinRole(Tokens.type.labelLarge)
                        }
                        .foregroundStyle(s.onSecondaryContainer)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 40)
                        .background(s.secondaryContainer, in: .capsule)
                        .contentShape(.capsule)
                    }
                    .buttonStyle(PressScale())
                    .disabled(!enabled)
                    .accessibilityLabel("Search \(t.label)")
                default:
                    if let d = track.durationSec?.intValue {
                        Text(formatTime(d)).yoinRole(Tokens.type.labelLarge).foregroundStyle(s.onSurfaceVariant)
                    }
                }
            }
            .padding(.leading, 4)
            .padding(.trailing, 16)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
            .onTapGesture {
                guard enabled, !notFound else { return }
                if uncertain {
                    withAnimation(Yoin.spatial) { expanded = open ? nil : row.position }
                } else {
                    tick += 1
                    model.setChecked(row.position, !row.checked)
                }
            }
            if open {
                candidates(row, enabled: enabled, track: track)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .clipped()
    }

    private func pill(_ text: String, _ bg: Color, _ fg: Color) -> some View {
        Text(text)
            .yoinRole(Tokens.type.labelMedium)
            .foregroundStyle(fg)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(bg, in: .capsule)
    }

    /// The candidates of an unsure row: picking one checks the row; Search looks for another.
    private func candidates(_ row: ReviewRow, enabled: Bool, track: BackupTrack) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Candidates")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(s.primary)
                .padding(.horizontal, 20)
                .padding(.bottom, 4)
            ForEach(Array(row.candidates.enumerated()), id: \.offset) { i, c in
                let chosen = Int32(i) == row.chosen && row.checked
                Button {
                    tick += 1
                    model.choose(row.position, Int32(i))
                } label: {
                    HStack(spacing: 14) {
                        ZStack {
                            Circle().strokeBorder(chosen ? s.primary : s.onSurfaceVariant, lineWidth: 2)
                            Circle().fill(s.primary).padding(5).scaleEffect(chosen ? 1 : 0.01).opacity(chosen ? 1 : 0)
                        }
                        .frame(width: 20, height: 20)
                        candidateLines(c)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 20)
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .animation(Yoin.effects, value: chosen)
            }
            Button { model.openSearch(row.position, query: Self.query(track)) } label: {
                HStack(spacing: 14) {
                    YoinIcon("search", size: 20).frame(width: 20)
                    Text("Search").yoinRole(Tokens.type.bodyLarge)
                }
                .foregroundStyle(s.primary)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
        }
        .padding(.bottom, 8)
    }

    @ViewBuilder private func candidateLines(_ c: MatchTrack) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(c.title ?? "").yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface).lineLimit(1)
            Text([c.artist, c.album, c.durationSec.map { formatTime(Int($0.intValue)) }].compactMap { $0 }.joined(separator: "   "))
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(s.onSurfaceVariant)
                .lineLimit(1)
        }
    }

    /// The search starts from the song's title and first artist (Android `openSearch`).
    static func query(_ t: BackupTrack) -> String {
        [t.title, t.artists.first].compactMap { $0 }.joined(separator: " ")
    }

    // MARK: Outcome

    private func outcome(_ t: RestoreTarget, _ record: PlaylistRestoreRecord) -> some View {
        VStack(spacing: 20) {
            OutcomeBadge(failed: record.failed, scheme: s)
            Text(record.failed ? String(localized: "Not created on \(t.label)") : String(localized: "Created on \(t.label)"))
                .yoinRole(Tokens.type.headlineSmall)
                .foregroundStyle(s.onSurface)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 10) {
                countTile(Int(record.added), "Added", s.primaryContainer, s.onPrimaryContainer)
                countTile(Int(record.skipped), "Skipped", s.surfaceContainerHigh, s.onSurface)
                countTile(Int(record.notFound), "Not found", s.surfaceContainerHigh, s.onSurface)
            }
            Button {
                model.sheetClosed()
            } label: {
                Text("Done")
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(s.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(s.primary, in: .capsule)
                    .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
        }
        .padding(.horizontal, 24)
        .padding(.top, 32)
        .padding(.bottom, 24)
        .frame(maxHeight: .infinity, alignment: .top)
        .sensoryFeedback(record.failed ? .error : .success, trigger: record.at)
    }

    private func countTile(_ n: Int, _ label: String, _ bg: Color, _ fg: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(n)").yoinRole(Tokens.type.headlineSmall)
            Text(label).yoinRole(Tokens.type.labelMedium)
        }
        .foregroundStyle(fg)
        .frame(maxWidth: .infinity, minHeight: 72)
        .background(bg, in: .rect(cornerRadius: 20, style: .continuous))
    }

    private func failure(_ t: RestoreTarget) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            title(String(localized: "Couldn't reach \(t.label)"))
            HStack {
                Spacer()
                Button("Done") { model.sheetClosed() }
                    .font(.gsf(16, .medium))
                    .foregroundStyle(s.primary)
                    .frame(minHeight: 48)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 24)
        .frame(maxHeight: .infinity, alignment: .top)
    }
}

/// The matching progress as one segment per song (Android RestoreLiveUpdate's
/// segments): matched in primary, unsure in tertiary, not found in
/// outlineVariant, still to look at in surfaceContainerHighest. Many songs
/// draw as runs without gaps.
private struct RestoreSegments: View {
    var total: Int
    var outcomes: [ReviewStatus]
    var scheme: CoverScheme

    var body: some View {
        Canvas { ctx, size in
            guard total > 0 else { return }
            let w = size.width / CGFloat(total)
            let gap: CGFloat = w >= 6 ? 2 : 0
            for i in 0..<total {
                let color: Color = i < outcomes.count ? Self.color(outcomes[i], scheme) : scheme.surfaceContainerHighest
                let rect = CGRect(x: CGFloat(i) * w, y: 0, width: max(1, w - gap), height: size.height)
                ctx.fill(Path(roundedRect: rect, cornerRadius: gap > 0 ? min(rect.width, size.height) / 2 : 0), with: .color(color))
            }
        }
        .clipShape(.capsule)
        .animation(Yoin.effects, value: outcomes.count)
        .accessibilityHidden(true)
    }

    static func color(_ s: ReviewStatus, _ scheme: CoverScheme) -> Color {
        switch s {
        case .matched: scheme.primary
        case .uncertain: scheme.tertiary
        case .picked: scheme.secondary
        default: scheme.outlineVariant
        }
    }
}

/// Android SummaryBar: the review's counts as one proportional bar with its labels.
private struct SummaryBar: View {
    var summary: ReviewSummary
    var scheme: CoverScheme

    var body: some View {
        let parts: [(Int, Color, String)] = [
            (Int(summary.matched), scheme.primary, "Matched"),
            (Int(summary.picked), scheme.secondary, "Picked"),
            (Int(summary.toCheck), scheme.tertiary, "To check"),
            (Int(summary.notFound), scheme.outlineVariant, "Not found"),
        ]
        let total = max(1, parts.map(\.0).reduce(0, +))
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(Array(parts.enumerated()), id: \.offset) { _, p in
                        if p.0 > 0 {
                            Capsule().fill(p.1)
                                .frame(width: max(4, (geo.size.width - 6) * CGFloat(p.0) / CGFloat(total)))
                        }
                    }
                }
            }
            .frame(height: 8)
            HStack(spacing: 14) {
                ForEach(Array(parts.enumerated()), id: \.offset) { i, p in
                    if p.0 > 0 || i == 0 {
                        HStack(spacing: 6) {
                            Circle().fill(p.1).frame(width: 8, height: 8)
                            Text("\(p.0) \(p.2)")
                                .yoinRole(Tokens.type.labelMedium)
                                .foregroundStyle(scheme.onSurfaceVariant)
                                .contentTransition(.numericText())
                        }
                    }
                }
            }
        }
        .animation(Yoin.spatial, value: [summary.matched, summary.picked, summary.toCheck, summary.notFound])
    }
}

/// The outcome's badge: a check on primaryContainer (or ✕ on errorContainer) that springs in turning.
private struct OutcomeBadge: View {
    var failed: Bool
    var scheme: CoverScheme
    @State private var shown = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: failed ? 36 : 44, style: .continuous)
                .fill(failed ? scheme.errorContainer : scheme.primaryContainer)
            YoinIcon(failed ? "close" : "check", size: 44)
                .foregroundStyle(failed ? scheme.onErrorContainer : scheme.onPrimaryContainer)
        }
        .frame(width: 112, height: 112)
        .scaleEffect(shown ? 1 : 0.2)
        .rotationEffect(.degrees(shown ? 0 : -60))
        .opacity(shown ? 1 : 0)
        .onAppear { withAnimation(Yoin.spatial) { shown = true } }
    }
}

/// The review's search for one song on the target (Android RestoreSearchSheet).
private struct RestoreSearchSheet: View {
    @Bindable var model: PlaylistRestoreModel
    var target: RestoreTarget

    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var colorScheme
    @State private var query = ""
    @FocusState private var focused: Bool

    private var s: CoverScheme { BackupTexts.scheme(app, colorScheme) }

    var body: some View {
        let search = model.search
        VStack(alignment: .leading, spacing: 16) {
            Text("Search \(target.label)")
                .yoinRole(Tokens.type.titleLarge)
                .foregroundStyle(s.onSurface)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                YoinIcon("search", size: 24).foregroundStyle(s.onSurfaceVariant)
                TextField("Search", text: $query)
                    .font(.gsf(16))
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit { model.runSearch(query, debounce: false) }
                if search?.loading == true { ProgressView().transition(.opacity) }
            }
            .padding(.horizontal, 16)
            .frame(height: 56)
            .background(s.surfaceContainerHigh, in: .capsule)
            ScrollView {
                LazyVStack(spacing: 2) {
                    let results = search?.results ?? []
                    if results.isEmpty, search?.loading == false, !query.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text(search?.failed == true ? String(localized: "Couldn't reach \(target.label)") : String(localized: "No results"))
                            .yoinRole(Tokens.type.bodyLarge)
                            .foregroundStyle(s.onSurfaceVariant)
                            .padding(.vertical, 24)
                            .transition(.opacity)
                    }
                    ForEach(Array(results.enumerated()), id: \.element.id) { i, r in
                        Button { model.pick(r) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.title ?? "").yoinRole(Tokens.type.bodyLarge).foregroundStyle(s.onSurface).lineLimit(1)
                                Text([r.artist, r.album, r.durationSec.map { formatTime(Int($0.intValue)) }]
                                    .compactMap { $0 }.joined(separator: "   "))
                                    .yoinRole(Tokens.type.bodyMedium)
                                    .foregroundStyle(s.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            .padding(.horizontal, 16)
                            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                            .background(s.surfaceContainerHigh, in: CloudSyncView.segment(i, results.count))
                            .contentShape(.rect)
                        }
                        .buttonStyle(PressScale())
                    }
                }
                .animation(Yoin.spatial, value: search?.results.map(\.id) ?? [])
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onAppear { query = search?.query ?? "" }
        .onChange(of: query) { _, q in if q != model.search?.query { model.runSearch(q) } }
    }
}
