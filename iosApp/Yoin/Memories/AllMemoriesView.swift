import SwiftUI
import YoinShared

/*
 * All Memories (Android ui/memories/ledger/MemoryLedgerScreen.kt, #186, owner 2026-11-09): the grid on
 * top (a tap on a cell shows just that stretch; a second tap clears it), then one button switching three
 * views, no filters: Timeline (by month, an album one card a month, song notes between), Cards (every album
 * and noted song once, in lanes) and List (a line each, sorted Recent / Highest score / Name). Every
 * account's memories count; another account's are marked "From …" (#178). A pushed page. As the page scrolls
 * past the grid, its squares rise into the top bar (MemoryGridLift) and come home on the way back; the page's
 * title comes into the bar as its headline goes under it, white once the bar has formed.
 *
 * A large screen is used (#202, owner 2026-11-12, LedgerPageLayout): the stats run large above a wider,
 * centred grid, and the timeline and the cards run in as many columns as fit, a masonry. The month is the
 * page's landmark, large in the serif; album covers alternate sides on a phone with what's written under them
 * full width, song scores on dashed rules; song notes sit on soft patches, and a month's song scores whose
 * album nobody could name share one card.
 */
struct AllMemoriesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.inPadColumn) private var inPadColumn
    @Environment(\.dismiss) private var dismiss
    private let source = MemoryLedgerSource.shared
    /// The tip page's demo phone (Tips/TipMemoryLedgerScene.swift): made-up memories and a cell the demo
    /// picks; nothing is loaded, opened, lifted or remembered.
    var demo: AllMemoriesDemo? = nil
    @AppStorage("memoryLedgerView") private var savedView = LedgerView.timeline.rawValue
    @AppStorage("memoryLedgerSort") private var savedSort = LedgerSort.recent.rawValue
    @State private var demoView = LedgerView.timeline.rawValue
    @State private var demoSort = LedgerSort.recent.rawValue
    @State private var ownSelected: Int?

    /// The view and sort last chosen; the demo's are its own and forgotten.
    private var viewChoice: Binding<String> { demo == nil ? $savedView : $demoView }
    private var sortChoice: Binding<String> { demo == nil ? $savedSort : $demoSort }
    private var viewRaw: String { viewChoice.wrappedValue }
    private var sortRaw: String { sortChoice.wrappedValue }
    private var selection: Binding<Int?> { demo?.selected ?? $ownSelected }
    private var selected: Int? { selection.wrappedValue }
    /// What the page shows: the demo's memories, else the ledger's.
    private var ledger: LedgerState? { demo?.state ?? source.state }
    /// Going to another account's album asks once before switching (the notice Home's shelf shares).
    @State private var switchGate = AccountSwitchGate()
    /// Where the grid's squares are and how far the page has scrolled: the squares that rise into the top bar.
    @State private var lift = MemoryGridLift()
    @State private var scrollPosition = ScrollPosition(edge: .top)
    /// The page's width (its layout) and the headline's frame in its content (the bar's title handoff).
    @State private var pageWidth: CGFloat = 0
    @State private var headline: CGRect = .zero
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    private var view: LedgerView { LedgerView(rawValue: viewRaw) ?? .timeline }
    private var sort: LedgerSort { LedgerSort(rawValue: sortRaw) ?? .recent }
    private var layout: LedgerPageLayout { .at(width: pageWidth, compactHeight: verticalSizeClass == .compact) }
    /// What the page's content may be wide (its 16 pt sides off).
    private var contentWidth: CGFloat { max(min(pageWidth, layout.maxWidth) - 32, 0) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("All Memories")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.top, 8)
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(MemoryGridLift.space)) } action: { headline = $0 }
                if let state = ledger, !state.isEmpty {
                    content(state)
                } else {
                    // One label, no second line (Android 058f629b).
                    Text("No memories yet").yoinRole(Tokens.type.titleMedium).foregroundStyle(app.onSurface)
                }
            }
            .frame(maxWidth: layout.maxWidth - 32, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: viewRaw)
            .animation(Yoin.spatial, value: sortRaw)
            .animation(Yoin.spatial, value: selected)
            .animation(Yoin.spatial, value: layout)
            .coordinateSpace(.named(MemoryGridLift.space))
        }
        .scrollPosition($scrollPosition)
        .onScrollGeometryChange(for: CGPoint.self) { CGPoint(x: $0.contentOffset.y, y: $0.contentInsets.top) } action: { _, g in
            lift.scrolled(y: g.x, insetTop: g.y)
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { lift.viewport = $0; pageWidth = $0.width }
        .overlay {
            if demo == nil, let state = source.state, !state.isEmpty {
                MemoryGridLiftLayer(lift: lift, pixels: MemoryGridLiftMath.pixels(state.grid),
                                    paint: MemoryGridPaint(covers: state.covers, faint: app.onSurface.opacity(0.07)))
            }
        }
        .modifier(TopBar(plain: demo != nil) {
            LedgerTopBar(lift: lift, headline: headline, lifts: demo == nil && !(source.state?.isEmpty ?? true),
                         onSurface: app.onSurface) { if let demo { demo.onBack() } else { dismiss() } }
        })
        .background(app.surfaceContainer.ignoresSafeArea())
        // White icons with the bar's white back and title once the squares have formed it (Android
        // MemoryLedgerScreen drivesStatusBar), in the light scheme and a full window.
        .statusBarLight(demo == nil && !(source.state?.isEmpty ?? true) && lift.progress > MemoryGridLiftMath.formed
                        && colorScheme == .light && !inPadColumn && !model.npExpanded && !(model.isPad && model.npPanel))
        .toolbar(.hidden, for: .navigationBar)
        .task {
            guard demo == nil else { return }
            source.reload()
            // Album pages a mark needs to be named (a scored song never played here): loaded, then read again.
            if await model.store.hydrateMemoryAlbums() { source.reload() }
            // Songs nothing here names the album of: asked of their own server, each pass shown as it lands.
            await source.resolveSongs(model: model)
            #if DEBUG
            // `-allMemoriesShotScroll 260`: scrolled that far down (the grid rising into the top bar);
            // `-allMemoriesShotScrollSweep YES` scrolls down past the grid and back, slowly, for a recording.
            let d = UserDefaults.standard
            if d.object(forKey: "allMemoriesShotScroll") != nil {
                try? await Task.sleep(for: .seconds(1.5))
                scrollPosition.scrollTo(y: d.double(forKey: "allMemoriesShotScroll"))
            }
            if d.bool(forKey: "allMemoriesShotScrollSweep") {
                try? await Task.sleep(for: .seconds(2))
                for pass in [Array(stride(from: 0, through: 520, by: 4)), Array(stride(from: 520, through: 0, by: -4))] {
                    for y in pass {
                        scrollPosition.scrollTo(y: CGFloat(y))
                        try? await Task.sleep(for: .milliseconds(16))
                    }
                    try? await Task.sleep(for: .seconds(1))
                }
            }
            // `-allMemoriesShotSwitch YES`: taps the first card kept on another account (the switch notice).
            if UserDefaults.standard.bool(forKey: "allMemoriesShotSwitch"), let state = source.state,
               let card = firstOtherCard(state) {
                try? await Task.sleep(for: .seconds(1))
                open(card.album, state, from: card.anchor)
            }
            #endif
        }
    }

    /// An empty state for the moment after a switch, before the ledger reloads.
    private var state0: LedgerState {
        LedgerState(entries: [], stats: MemoryLedger.stats([]), grid: MemoryGrid(scale: .month, cells: []), covers: [:],
                    profiles: [], activeAccount: nil, today: Date())
    }

    @ViewBuilder
    private func content(_ state: LedgerState) -> some View {
        // On a large screen the figures run larger across the top and the grid grows wider, centred, so
        // nothing is left beside the grid to float alone once it has risen into the bar.
        stats(state, large: layout.wide)
            .frame(maxWidth: layout.wide ? 760 : .infinity, alignment: .leading)
            .frame(maxWidth: .infinity)
        MemoryGridView(grid: state.grid, covers: state.covers, placement: .page,
                       faint: app.onSurface.opacity(0.07), outline: app.primary, label: app.onSurfaceVariant,
                       selected: selected, onCell: { i in
            withAnimation(Yoin.spatial) { selection.wrappedValue = selected == i ? nil : i }
        }, lift: demo == nil ? lift : nil)
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(MemoryGridLift.space)) } action: { lift.grid = $0 }
        // The real grid steps aside the moment its copies take off, so no square shows twice; under Reduce
        // Motion nothing flies, so it fades out over the scroll the bar fades in over. Nothing rises on the
        // demo phone.
        .modifier(MemoryGridStepAside(lift: lift, still: demo != nil))
        .demoTarget("ledgerGrid")
        .padding(16)
        .background(app.surfaceContainerLow, in: .rect(cornerRadius: 28))
        .frame(maxWidth: layout.gridMaxWidth)
        .frame(maxWidth: .infinity, alignment: layout.wide ? .center : .leading)
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

    private func stats(_ state: LedgerState, large: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // A large screen gives the numbers room to be the headline figures they are.
            HStack(alignment: .firstTextBaseline, spacing: large ? 24 : 12) {
                stat(state.stats.albums, "Albums", large: large)
                stat(state.stats.writings, "Written", large: large)
                stat(state.stats.characters, "Characters", large: large)
                stat(state.stats.ratedTracks, "Songs scored", large: large)
            }
            if let first = state.stats.firstAt {
                Text("Kept since \(Date(timeIntervalSince1970: Double(first) / 1000).formatted(date: .abbreviated, time: .omitted)). Every account counts as you.")
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(app.onSurfaceVariant)
            }
        }
    }

    private func stat(_ n: Int, _ label: LocalizedStringKey, large: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n.formatted()).font(.gsf(large ? 40 : 24, .semibold)).frame(minHeight: large ? 46 : 30)
                .foregroundStyle(app.onSurface).lineLimit(1)
                .contentTransition(.numericText())
            Text(label).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.onSurfaceVariant).lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var controls: some View {
        HStack {
            if view == .list {
                Menu {
                    Picker("Sort", selection: sortChoice) {
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
            Picker("View", selection: viewChoice) {
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

    @ViewBuilder
    private func timeline(_ entries: [LedgerEntry], _ state: LedgerState) -> some View {
        let rows = MemoryLedger.timeline(entries, today: state.today, calendar: .current).filter { row in
            // A stretch picked on the grid: just its months, no quiet ones around it.
            if selected != nil, case .quiet = row { return false }
            return true
        }
        if layout.columns == 1 {
            // A phone: one column, album covers alternating sides.
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(rows) { row in timelineRow(row, state, masonry: false) }
            }
        } else {
            // A masonry per month, in a lazy stack: only the months near the screen are built.
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(LedgerLazy.segments(rows, isFullLine: \.isFullLine), id: \.[0].id) { segment in
                    LedgerMasonry(columns: layout.columns, spacing: layout.gutter) {
                        ForEach(segment) { row in
                            timelineRow(row, state, masonry: true).layoutValue(key: LedgerFullLine.self, value: row.isFullLine)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func timelineRow(_ row: LedgerTimelineRow, _ state: LedgerState, masonry: Bool) -> some View {
        switch row {
        case .month(let m, let count): monthHeader(m, count)
        case .album(_, let item, let end): timelineAlbumCard(item, state, end: end, masonry: masonry, anchor: row.id)
        case .note(let e): noteCard(e, state, anchor: row.id)
        case .scores(_, let entries): scoresCard(entries, state)
        case .quiet(let from, let to): quietMonths(from, to)
        }
    }

    /// The month is the page's landmark: large and in the serif, so the eye finds where it is while scrolling.
    private func monthHeader(_ m: Date, _ count: Int) -> some View {
        let size: CGFloat = layout.wide ? 40 : 30
        return HStack(alignment: .lastTextBaseline, spacing: 12) {
            Text(m.formatted(.dateTime.year().month(.wide)))
                .font(.system(size: size, weight: .semibold, design: .serif))
                .frame(minHeight: layout.wide ? 46 : 36)
                .foregroundStyle(app.onSurface)
            Text("\(count) entries").yoinRole(Tokens.type.labelLarge).foregroundStyle(app.onSurfaceVariant)
            Spacer(minLength: 0)
        }
        .padding(.top, layout.wide ? 48 : 36)
        .padding(.bottom, layout.wide ? 12 : 8)
    }

    /// Empty months: the line sits on the page itself (no patch behind it), between two dashed rules.
    private func quietMonths(_ from: Date, _ to: Date) -> some View {
        HStack(spacing: 10) {
            LedgerDashedRule(color: app.outlineVariant)
            Text(quietLine(from, to)).yoinRole(Tokens.type.labelMedium).foregroundStyle(app.onSurfaceVariant)
                .lineLimit(1).fixedSize()
            LedgerDashedRule(color: app.outlineVariant)
        }
        .padding(.vertical, 14)
    }

    private func quietLine(_ from: Date, _ to: Date) -> String {
        let f = Date.FormatStyle().year().month(.abbreviated)
        if Calendar.current.isDate(from, equalTo: to, toGranularity: .month) {
            return String(localized: "Nothing written in \(from.formatted(f))")
        }
        return String(localized: "Nothing written from \(to.formatted(f)) to \(from.formatted(f))")
    }

    // MARK: Cards and List

    /// Every album and noted song once, in as many lanes as fit, each to the shortest lane (a masonry).
    /// Each lane is a lazy stack, so only the cards near the screen are built; a card goes to the lane that is
    /// shortest by the cards' estimated heights.
    private func cards(_ items: [LedgerItem], _ state: LedgerState) -> some View {
        let n = max(layout.lanes, 1)
        let width = max((contentWidth - layout.gutter * CGFloat(n - 1)) / CGFloat(n), 0)
        let lanes = LedgerLazy.lanes(items.map { $0.estimatedCardHeight(width: width) }, count: n, spacing: 12)
        return HStack(alignment: .top, spacing: layout.gutter) {
            ForEach(lanes.indices, id: \.self) { l in
                LazyVStack(spacing: 12) {
                    ForEach(lanes[l].map { items[$0] }) { item in
                        switch item {
                        case .album: albumCard(item, state, anchor: item.id)
                        case .song(_, _, _, _, _, let note, _, _):
                            if let note { songNoteCard(note, state, anchor: item.id) } else { songLine(item, state) }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .top)
            }
        }
    }

    private func list(_ items: [LedgerItem], _ state: LedgerState) -> some View {
        LazyVStack(spacing: 2) {
            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                Button { open(item.albumRef, state, from: item.id) } label: { songLine(item, state) }
                    .buttonStyle(.plain).pointerHighlight()
                    .background(app.surfaceBright, in: CloudSyncView.segment(i, items.count))
                    .accountSwitchNotice(switchGate, anchor: item.id)
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

    /// [anchor]: this card among the page's (the same album has a card in each month of the timeline).
    private func albumCard(_ item: LedgerItem, _ state: LedgerState, anchor: String) -> some View {
        guard case let .album(album, title, score, review, songs, _) = item else { return AnyView(EmptyView()) }
        return AnyView(
            Button { open(album, state, from: anchor) } label: {
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
            .buttonStyle(PressScale())
            .accountSwitchNotice(switchGate, anchor: anchor))
    }

    /// A song note in the Cards view: what was written under the song and its cover.
    private func songNoteCard(_ e: LedgerEntry, _ state: LedgerState, anchor: String) -> some View {
        Button { open(e.album, state, from: anchor) } label: {
            VStack(alignment: .leading, spacing: 6) {
                if let text = e.text {
                    Text(text).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurface).lineLimit(6)
                }
                HStack(spacing: 8) {
                    cover(e.album, state, size: 22)
                    Text([e.trackTitle ?? e.album?.name ?? String(localized: "Song unknown"),
                          e.score.map(Self.score) ?? ""].filter { !$0.isEmpty }.joined(separator: "  "))
                        .yoinRole(Tokens.type.labelMedium).foregroundStyle(app.onSurfaceVariant).lineLimit(1)
                    fromLine(e.account, state)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(app.surfaceContainerLow, in: .rect(cornerRadius: 16))
            .contentShape(.rect(cornerRadius: 16))
        }
        .buttonStyle(PressScale())
        .accountSwitchNotice(switchGate, anchor: anchor)
    }

    /// An album's month on the timeline: the cover whole, then what was kept on it, no container. On a phone
    /// the covers take turns starting from the leading and the trailing side, and what's written under them
    /// always runs the full width; in a masonry the cover fills its column. The memory title is the loudest
    /// line, the score next.
    private func timelineAlbumCard(_ item: LedgerItem, _ state: LedgerState, end: Bool, masonry: Bool,
                                   anchor: String) -> some View {
        guard case let .album(album, title, score, review, songs, at) = item else { return AnyView(EmptyView()) }
        let side = masonry ? nil : min(contentWidth * 0.66, 300)
        return AnyView(
            Button { open(album, state, from: anchor) } label: {
                VStack(alignment: .leading, spacing: 0) {
                    coverFill(album, state, corner: 16)
                        .frame(width: side)
                        .frame(maxWidth: .infinity, alignment: end && !masonry ? .trailing : .leading)
                    VStack(alignment: .leading, spacing: 6) {
                        if let title {
                            Text(title).font(.system(size: 24, weight: .semibold, design: .serif)).homeLine(24, 32)
                                .foregroundStyle(app.onSurface).multilineTextAlignment(.leading)
                        }
                        pair(album.name, album.artist.nonBlank, Tokens.type.bodyMedium)
                        if let score { scoreLine(score, "Album score", size: 34) }
                        if let review {
                            Text(review).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface).lineLimit(6)
                                .multilineTextAlignment(.leading).padding(.top, 2)
                        }
                        if !songs.isEmpty {
                            // A dashed rule over each line, as About's receipt does.
                            VStack(spacing: 0) {
                                ForEach(Array(songs.enumerated()), id: \.offset) { _, s in
                                    LedgerDashedRule(color: app.outlineVariant)
                                    songScoreRow(s.title, s.score).padding(.vertical, 8)
                                }
                                LedgerDashedRule(color: app.outlineVariant)
                            }
                            .padding(.top, 6)
                        }
                        HStack(spacing: 10) {
                            Text(shortDay(at, state)).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.outline)
                            fromLine(album.account, state)
                        }
                        .padding(.top, 2)
                    }
                    .padding(.top, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.vertical, masonry ? 12 : 16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .accountSwitchNotice(switchGate, anchor: anchor))
    }

    private func songScoreRow(_ title: String?, _ score: Double) -> some View {
        HStack {
            Text(title?.nonBlank ?? String(localized: "Song unknown")).yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(app.onSurfaceVariant).lineLimit(1)
            Spacer(minLength: 8)
            Text(Self.score(score)).yoinRole(Tokens.type.labelLarge, weight: .semibold).foregroundStyle(app.onSurface)
        }
    }

    /// A song note on the timeline: what was written leads, large, in the serif (it's the listener's own
    /// voice); the song and where in it under it, then its album small beside a small cover. On a soft patch,
    /// so the notes read apart from the album cards that stand bare on the page.
    private func noteCard(_ e: LedgerEntry, _ state: LedgerState, anchor: String) -> some View {
        Button { open(e.album, state, from: anchor) } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(e.kind == .songNote ? "Song note" : "Song score").yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(app.onSurfaceVariant).lineLimit(1)
                    Spacer(minLength: 8)
                    Text(shortDay(e.at, state)).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.outline).lineLimit(1)
                }
                if let text = e.text?.nonBlank {
                    Text(text).font(.system(size: 19, design: .serif)).homeLine(19, 28)
                        .foregroundStyle(app.onSurface).lineLimit(10).multilineTextAlignment(.leading)
                } else if let s = e.score {
                    scoreLine(s, nil, size: 30)
                }
                if let song = e.trackTitle {
                    pair(song, e.positionMs.flatMap { $0 > 0 ? Self.position($0) : nil }, Tokens.type.labelLarge)
                }
                HStack(spacing: 10) {
                    cover(e.album, state, size: 32, corner: 6)
                    VStack(alignment: .leading, spacing: 0) {
                        pair(e.album?.name ?? String(localized: "Album unknown"), e.album?.artist.nonBlank,
                             Tokens.type.bodySmall)
                        fromLine(e.account, state)
                    }
                }
            }
            .padding(.horizontal, 18).padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(app.surfaceContainerLow, in: .rect(cornerRadius: 24))
            .contentShape(.rect(cornerRadius: 24))
        }
        .buttonStyle(PressScale())
        .accountSwitchNotice(switchGate, anchor: anchor)
        .padding(.vertical, 8)
    }

    /// A month's song scores whose album nobody could name, on one card: each song and its score, best first,
    /// instead of a run of identical rows.
    private func scoresCard(_ entries: [LedgerEntry], _ state: LedgerState) -> some View {
        let sorted = entries.sorted { ($0.score ?? 0) > ($1.score ?? 0) }
        var accounts: [LedgerAccount] = []
        for e in entries where !accounts.contains(e.account) { accounts.append(e.account) }
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Song score").yoinRole(Tokens.type.labelMedium).foregroundStyle(app.onSurfaceVariant)
                Spacer(minLength: 8)
                Text(shortDay(entries.first?.at ?? 0, state)).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.outline)
            }
            ForEach(Array(sorted.enumerated()), id: \.element.id) { i, e in
                if i > 0 { LedgerDashedRule(color: app.outlineVariant) }
                HStack {
                    Text(e.trackTitle ?? String(localized: "Song unknown")).yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurface).lineLimit(1)
                    Spacer(minLength: 8)
                    Text(Self.score(e.score ?? 0)).yoinRole(Tokens.type.titleMedium, weight: .semibold)
                        .foregroundStyle(app.onSurface)
                }
                .padding(.vertical, 4)
            }
            ForEach(accounts, id: \.self) { fromLine($0, state) }
        }
        .padding(.horizontal, 18).padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(app.surfaceContainerLow, in: .rect(cornerRadius: 24))
        .padding(.vertical, 8)
    }

    /// The big number and what it scores.
    private func scoreLine(_ s: Double, _ label: LocalizedStringKey?, size: CGFloat) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 8) {
            Text(Self.score(s)).font(.gsf(size, .semibold)).foregroundStyle(app.onSurface)
            if let label { Text(label).yoinRole(Tokens.type.labelSmall).foregroundStyle(app.onSurfaceVariant) }
        }
    }

    /// Two pieces of one line told apart by tone and a gap, not by a dot: [first] strong, [second] lighter.
    private func pair(_ first: String, _ second: String?, _ style: YoinTokens.TypographyStyle) -> some View {
        HStack(spacing: 6) {
            Text(first).yoinRole(style).foregroundStyle(app.onSurfaceVariant).lineLimit(1)
            if let second {
                Text(second).yoinRole(style).foregroundStyle(app.outline).lineLimit(1).layoutPriority(1)
            }
        }
    }

    /// "From …" for another account's memory.
    @ViewBuilder
    private func fromLine(_ account: LedgerAccount, _ state: LedgerState) -> some View {
        if state.isOther(account), let p = state.profile(account) {
            Text("From \(p.name)").yoinRole(Tokens.type.labelSmall).foregroundStyle(app.primary).lineLimit(1)
        }
    }

    /// A day as the cards show it: "Nov 8", with the year when it isn't this one.
    private func shortDay(_ at: Int64, _ state: LedgerState) -> String {
        let d = Date(timeIntervalSince1970: Double(at) / 1000)
        let thisYear = Calendar.current.isDate(d, equalTo: state.today, toGranularity: .year)
        return thisYear ? d.formatted(.dateTime.month(.abbreviated).day())
                        : d.formatted(.dateTime.year().month(.abbreviated).day())
    }

    static func position(_ ms: Int64) -> String { String(format: "%d:%02d", ms / 60_000, (ms / 1000) % 60) }

    private func scoreBadge(_ s: Double) -> some View {
        Text(Self.score(s))
            .yoinRole(Tokens.type.labelLarge, weight: .semibold)
            .foregroundStyle(app.onPrimaryContainer)
            .padding(.horizontal, 8).frame(height: 24)
            .background(app.primaryContainer, in: .capsule)
    }

    @ViewBuilder
    private func cover(_ album: LedgerAlbum?, _ state: LedgerState, size: CGFloat, corner: CGFloat = 4) -> some View {
        let palette = album.map { state.covers[$0.key] ?? MemoryGridView.fallback($0.key) }
        CoverArt(palette: palette ?? MemoryGridView.fallback("none"), cornerRadius: corner)
            .frame(width: size, height: size)
            .opacity(album == nil ? 0.3 : 1)
    }

    /// An album's cover whole, square at the width it is given.
    private func coverFill(_ album: LedgerAlbum, _ state: LedgerState, corner: CGFloat) -> some View {
        CoverArt(palette: state.covers[album.key] ?? MemoryGridView.fallback(album.key), cornerRadius: corner)
            .aspectRatio(1, contentMode: .fit)
    }

    /// The album's page. Another account's: Yoin switches to it first, as Android's Go to album does (#178),
    /// and the first time ever the notice asks before switching, on the tapped card ([anchor]), since a
    /// switch stops what is playing.
    private func open(_ album: LedgerAlbum?, _ state: LedgerState, from anchor: String) {
        guard let album, demo == nil else { return }
        if state.isOther(album.account) {
            guard let p = state.profile(album.account) else { return }
            switchGate.request(from: anchor, account: p.name, subject: album.name, opensAlbum: true) {
                Task {
                    await model.profiles.switchTo(p.id)
                    guard model.profiles.active?.id == p.id else { return }
                    source.reload(model: model)
                    open(album, source.state ?? state0, from: anchor)
                }
            }
            return
        }
        let a = model.store.albumDetails[album.albumId]
            ?? Album(id: MediaId(provider: album.account.provider, rawId: album.albumId), title: album.name,
                     artist: album.artist, year: 0, palette: state.covers[album.key] ?? MemoryGridView.fallback(album.key),
                     tracks: [])
        model.open(a)
    }

    #if DEBUG
    /// The first card of another account in the view on screen, and its anchor (`-allMemoriesShotSwitch`).
    private func firstOtherCard(_ state: LedgerState) -> (album: LedgerAlbum, anchor: String)? {
        let entries = filtered(state)
        func other(_ item: LedgerItem) -> LedgerAlbum? { item.albumRef.flatMap { state.isOther($0.account) ? $0 : nil } }
        switch view {
        case .timeline:
            for row in MemoryLedger.timeline(entries, today: state.today, calendar: .current) {
                if case .album(_, let item, _) = row, let album = other(item) { return (album, row.id) }
            }
        case .cards:
            for item in MemoryLedger.items(entries) {
                if case .album = item, let album = other(item) { return (album, item.id) }
            }
        case .list:
            for item in MemoryLedger.sorted(MemoryLedger.items(entries), by: sort) {
                if let album = other(item) { return (album, item.id) }
            }
        }
        return nil
    }
    #endif

    static func score(_ s: Double) -> String { String(format: "%.1f", s) }
}

/// The page's top bar: the back button, and the page's title coming in as the headline goes under the bar
/// (Android TopAppBar title handoff); both white once the grid's squares have formed the bar.
private struct LedgerTopBar: View {
    let lift: MemoryGridLift
    /// The headline, in the page's content.
    let headline: CGRect
    /// The grid has squares to rise.
    let lifts: Bool
    let onSurface: Color
    let onBack: () -> Void

    /// 0 = the headline below the bar, 1 = wholly gone under it.
    private var handoff: CGFloat {
        guard headline.height > 0 else { return 0 }
        return min(max((lift.insetTop - (headline.minY - lift.scrollY)) / headline.height, 0), 1)
    }

    var body: some View {
        let formed = lifts && lift.progress > MemoryGridLiftMath.formed
        let content = formed ? Color.white : onSurface
        let alpha = handoff
        HStack(spacing: 8) {
            Button(action: onBack) {
                YoinIcon("back", size: 22)
                    .foregroundStyle(content)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Text("All Memories")
                .yoinRole(Tokens.type.titleLarge)
                .foregroundStyle(content)
                .lineLimit(1)
                .opacity(alpha)
                .offset(y: (1 - alpha) * 14)
                .accessibilityHidden(alpha < 0.5)
            Spacer(minLength: 0)
        }
        .animation(Yoin.effects, value: formed)
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }
}

/// The page's grid while it rises into the bar: gone once its copies take off, or, under Reduce Motion (no
/// copies fly), faded out as the bar fades in. Its own view, so a scroll frame re-reads only this.
private struct MemoryGridStepAside: ViewModifier {
    let lift: MemoryGridLift
    var still = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.opacity(still ? 1 : MemoryGridLiftMath.realGridOpacity(progress: lift.progress, lifting: lift.lifting,
                                                           reduced: reduceMotion))
    }
}

/// The page's top bar: a safe-area bar, so the list blurs out under it. On the demo phone ([plain]) a plain
/// inset: the system draws that blur at the window's scale, over the phone's rim and the page around it.
private struct TopBar<Bar: View>: ViewModifier {
    var plain: Bool
    @ViewBuilder var bar: () -> Bar

    func body(content: Content) -> some View {
        if plain {
            content
                .scrollEdgeEffectHidden(true, for: .all)
                .safeAreaInset(edge: .top, spacing: 0) { bar() }
        } else {
            content.safeAreaBar(edge: .top) { bar() }
        }
    }
}

/// What the tip page's demo phone shows All Memories with.
struct AllMemoriesDemo {
    var state: LedgerState
    /// The cell the list is narrowed to; the demo's touch sets it.
    var selected: Binding<Int?>
    var onBack: () -> Void
}
