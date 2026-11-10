import SwiftUI
import YoinShared

/*
 * All Memories (Android ui/memories/ledger/MemoryLedgerScreen.kt, #186, owner 2026-11-09): the grid on
 * top (a tap on a cell shows just that stretch; a second tap clears it), then one button switching three
 * views, no filters: Timeline (by month, an album one card a month, song notes between), Cards (every album
 * and noted song once, two a row) and List (a line each, sorted Recent / Highest score / Name). Every
 * account's memories count; another account's are marked "From …" (#178). A pushed page.
 */
struct AllMemoriesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    private let source = MemoryLedgerSource.shared
    @AppStorage("memoryLedgerView") private var viewRaw = LedgerView.timeline.rawValue
    @AppStorage("memoryLedgerSort") private var sortRaw = LedgerSort.recent.rawValue
    @State private var selected: Int?

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    private var view: LedgerView { LedgerView(rawValue: viewRaw) ?? .timeline }
    private var sort: LedgerSort { LedgerSort(rawValue: sortRaw) ?? .recent }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("All Memories")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.top, 8)
                if let state = source.state, !state.isEmpty {
                    content(state)
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("No memories yet").yoinRole(Tokens.type.titleMedium).foregroundStyle(app.onSurface)
                        Text("Write a note while a song plays, or score an album, and it shows up here.")
                            .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                    }
                }
            }
            .frame(maxWidth: 720, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: viewRaw)
            .animation(Yoin.spatial, value: sortRaw)
            .animation(Yoin.spatial, value: selected)
        }
        .safeAreaBar(edge: .top) { topBar }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { source.reload() }
    }

    @ViewBuilder
    private func content(_ state: LedgerState) -> some View {
        stats(state)
        MemoryGridView(grid: state.grid, covers: state.covers, placement: .page,
                       faint: app.onSurface.opacity(0.07), outline: app.primary, label: app.onSurfaceVariant,
                       selected: selected) { i in
            withAnimation(Yoin.spatial) { selected = selected == i ? nil : i }
        }
        .padding(16)
        .background(app.surfaceContainerLow, in: .rect(cornerRadius: 28))
        controls
        let entries = filtered(state)
        if entries.isEmpty {
            Text("Nothing kept in this stretch.").yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
        } else {
            switch view {
            case .timeline: timeline(entries, state)
            case .cards: cards(MemoryLedger.items(entries), state)
            case .list: list(MemoryLedger.sorted(MemoryLedger.items(entries), by: sort), state)
            }
        }
    }

    /// The entries of the selected cell's stretch, else all.
    private func filtered(_ state: LedgerState) -> [LedgerEntry] {
        guard let i = selected, state.grid.cells.indices.contains(i) else { return state.entries }
        let cell = state.grid.cells[i]
        let end = Calendar.current.date(byAdding: .day, value: 1, to: cell.end)!
        return state.entries.filter {
            let d = Date(timeIntervalSince1970: Double($0.at) / 1000)
            return d >= cell.start && d < end
        }
    }

    private func stats(_ state: LedgerState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 18) {
                stat(state.stats.albums, "Albums")
                stat(state.stats.writings, "Written")
                stat(state.stats.characters, "Characters")
                stat(state.stats.ratedTracks, "Songs scored")
            }
            if let first = state.stats.firstAt {
                Text("Kept since \(Date(timeIntervalSince1970: Double(first) / 1000).formatted(date: .abbreviated, time: .omitted)). Every account counts as you.")
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(app.onSurfaceVariant)
            }
        }
    }

    private func stat(_ n: Int, _ label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n.formatted()).yoinRole(Tokens.type.titleLarge, weight: .semibold).foregroundStyle(app.onSurface)
                .contentTransition(.numericText())
            Text(label).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.onSurfaceVariant)
        }
    }

    private var controls: some View {
        HStack {
            if view == .list {
                Menu {
                    Picker("Sort", selection: $sortRaw) {
                        Text("Recent").tag(LedgerSort.recent.rawValue)
                        Text("Highest score").tag(LedgerSort.score.rawValue)
                        Text("Name").tag(LedgerSort.name.rawValue)
                    }
                } label: {
                    HStack(spacing: 6) {
                        YoinIcon("sort", size: 18)
                        Text(sortLabel).yoinRole(Tokens.type.labelLarge)
                    }
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 14).frame(height: 36)
                    .background(app.surfaceContainerHigh, in: .capsule)
                }
                .transition(.opacity)
            }
            Spacer()
            Picker("View", selection: $viewRaw) {
                Text("Timeline").tag(LedgerView.timeline.rawValue)
                Text("Cards").tag(LedgerView.cards.rawValue)
                Text("List").tag(LedgerView.list.rawValue)
            }
            .pickerStyle(.segmented)
            .fixedSize()
        }
    }

    private var sortLabel: LocalizedStringKey {
        switch sort {
        case .recent: "Recent"
        case .score: "Highest score"
        case .name: "Name"
        }
    }

    // MARK: Timeline

    private func timeline(_ entries: [LedgerEntry], _ state: LedgerState) -> some View {
        LazyVStack(alignment: .leading, spacing: 10) {
            ForEach(MemoryLedger.timeline(entries, today: state.today, calendar: .current)) { row in
                switch row {
                case .month(let m, let count):
                    HStack(alignment: .firstTextBaseline) {
                        Text(m.formatted(.dateTime.year().month(.wide))).yoinRole(Tokens.type.titleMedium, weight: .semibold)
                            .foregroundStyle(app.onSurface)
                        Spacer()
                        Text("\(count) entries").yoinRole(Tokens.type.labelMedium).foregroundStyle(app.onSurfaceVariant)
                    }
                    .padding(.top, 10)
                case .album(_, let item, let end):
                    albumCard(item, state)
                        .frame(maxWidth: 420)
                        .frame(maxWidth: .infinity, alignment: end ? .trailing : .leading)
                case .note(let e):
                    noteCard(e, state)
                case .quiet(let from, let to):
                    Text(quietLine(from, to)).yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant)
                        .padding(.vertical, 4)
                }
            }
        }
    }

    private func quietLine(_ from: Date, _ to: Date) -> String {
        let f = Date.FormatStyle().year().month(.abbreviated)
        if Calendar.current.isDate(from, equalTo: to, toGranularity: .month) {
            return String(localized: "Nothing written in \(from.formatted(f))")
        }
        return String(localized: "Nothing written from \(to.formatted(f)) to \(from.formatted(f))")
    }

    // MARK: Cards and List

    private func cards(_ items: [LedgerItem], _ state: LedgerState) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(items) { item in
                switch item {
                case .album: albumCard(item, state)
                case .song(_, _, _, _, _, let note, _, _):
                    if let note { noteCard(note, state) } else { songLine(item, state) }
                }
            }
        }
    }

    private func list(_ items: [LedgerItem], _ state: LedgerState) -> some View {
        LazyVStack(spacing: 2) {
            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                Button { open(item.albumRef, state) } label: { songLine(item, state) }
                    .buttonStyle(.plain)
                    .background(app.surfaceBright, in: CloudSyncView.segment(i, items.count))
            }
        }
    }

    private func songLine(_ item: LedgerItem, _ state: LedgerState) -> some View {
        HStack(spacing: 12) {
            cover(item.albumRef, state, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name.nonBlank ?? String(localized: "Song unknown")).yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface).lineLimit(1)
                Text(subtitle(item, state)).yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant).lineLimit(1)
            }
            Spacer(minLength: 0)
            if let s = item.score { scoreBadge(s) }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(.rect)
    }

    private func subtitle(_ item: LedgerItem, _ state: LedgerState) -> String {
        var parts: [String] = []
        switch item {
        case .album(let a, _, _, _, _, _): parts.append(a.artist)
        case .song(_, _, _, let a, _, _, _, _):
            parts.append(a?.name ?? String(localized: "Album unknown"))
        }
        if state.isOther(item.account), let p = state.profile(item.account) {
            parts.append(String(localized: "From \(p.name)"))
        }
        return parts.filter { !$0.isEmpty }.joined(separator: "  ")
    }

    // MARK: Cards

    private func albumCard(_ item: LedgerItem, _ state: LedgerState) -> some View {
        guard case let .album(album, title, score, review, songs, _) = item else { return AnyView(EmptyView()) }
        return AnyView(
            Button { open(album, state) } label: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 10) {
                        cover(album, state, size: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title ?? album.name).yoinRole(Tokens.type.titleSmall, weight: .semibold)
                                .foregroundStyle(app.onSurface).lineLimit(2)
                            Text(title == nil ? album.artist : "\(album.name)  \(album.artist)")
                                .yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant).lineLimit(1)
                            if state.isOther(album.account), let p = state.profile(album.account) {
                                Text("From \(p.name)").yoinRole(Tokens.type.labelSmall).foregroundStyle(app.primary)
                            }
                        }
                        Spacer(minLength: 0)
                        if let score { scoreBadge(score) }
                    }
                    if let review {
                        Text(review).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurface).lineLimit(4)
                    }
                    ForEach(Array(songs.prefix(3).enumerated()), id: \.offset) { _, s in
                        HStack {
                            Text(s.title?.nonBlank ?? String(localized: "Song unknown")).yoinRole(Tokens.type.bodySmall)
                                .foregroundStyle(app.onSurfaceVariant).lineLimit(1)
                            Spacer()
                            Text(Self.score(s.score)).yoinRole(Tokens.type.labelMedium).foregroundStyle(app.onSurface)
                        }
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(app.surfaceBright, in: .rect(cornerRadius: 20))
                .contentShape(.rect(cornerRadius: 20))
            }
            .buttonStyle(PressScale()))
    }

    private func noteCard(_ e: LedgerEntry, _ state: LedgerState) -> some View {
        Button { open(e.album, state) } label: {
            VStack(alignment: .leading, spacing: 6) {
                if let text = e.text {
                    Text(text).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurface).lineLimit(6)
                }
                HStack(spacing: 8) {
                    cover(e.album, state, size: 22)
                    Text([e.trackTitle ?? e.album?.name ?? String(localized: "Song unknown"),
                          e.score.map(Self.score) ?? ""].filter { !$0.isEmpty }.joined(separator: "  "))
                        .yoinRole(Tokens.type.labelMedium).foregroundStyle(app.onSurfaceVariant).lineLimit(1)
                    if state.isOther(e.account), let p = state.profile(e.account) {
                        Text("From \(p.name)").yoinRole(Tokens.type.labelSmall).foregroundStyle(app.primary).lineLimit(1)
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(app.surfaceContainerLow, in: .rect(cornerRadius: 16))
            .contentShape(.rect(cornerRadius: 16))
        }
        .buttonStyle(PressScale())
    }

    private func scoreBadge(_ s: Double) -> some View {
        Text(Self.score(s))
            .yoinRole(Tokens.type.labelLarge, weight: .semibold)
            .foregroundStyle(app.onPrimaryContainer)
            .padding(.horizontal, 8).frame(height: 24)
            .background(app.primaryContainer, in: .capsule)
    }

    @ViewBuilder
    private func cover(_ album: LedgerAlbum?, _ state: LedgerState, size: CGFloat) -> some View {
        let palette = album.map { state.covers[$0.key] ?? MemoryGridView.fallback($0.key) }
        CoverArt(palette: palette ?? MemoryGridView.fallback("none"), cornerRadius: 4)
            .frame(width: size, height: size)
            .opacity(album == nil ? 0.3 : 1)
    }

    /// The album's page, for an album of the account in use (another account's opens nothing here).
    private func open(_ album: LedgerAlbum?, _ state: LedgerState) {
        guard let album, !state.isOther(album.account) else { return }
        let a = model.store.albumDetails[album.albumId]
            ?? Album(id: MediaId(provider: album.account.provider, rawId: album.albumId), title: album.name,
                     artist: album.artist, year: 0, palette: state.covers[album.key] ?? MemoryGridView.fallback(album.key),
                     tracks: [])
        model.open(a)
    }

    static func score(_ s: Double) -> String { String(format: "%.1f", s) }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }
}
