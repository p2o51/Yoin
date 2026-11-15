import SwiftUI

/*
 * Library on the demo phone (Android ui/tips/TipLibraryScenes.kt): Library's header (its search pill and the
 * account), its own filter row and grid cells over made-up albums and artists, under the bar. Search opens over
 * it the way it does from Library (the field stays where the pill is, the surface fades in, the chips a beat
 * behind): Search is a page of the app's own window, so the phone draws its look from Search's numbers and
 * Library's chips (Android does the same with M3's full-screen search, a window of its own).
 */

// MARK: - Quick search

/// Long-press Library in the bar: straight into Library's search; the ✕ closes it.
struct LibrarySearchDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var searching = false

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            searching = false
            try await DemoTiming.delay(300)
            try await finger.moveTo("library")
            try await finger.hold { searching = true }
            try await finger.lift()
            try await DemoTiming.delay(1_800)
            try await finger.moveTo("searchClose")
            try await finger.tap { searching = false }
            try await DemoTiming.delay(500)
        }) {
            ZStack {
                DemoLibraryPage(scheme: scheme, searching: searching, onLibraryHold: { searching = true })
                DemoLibrarySearch(scheme: scheme, visible: searching) { searching = false }
            }
        }
    }
}

// MARK: - Refresh Library

/// Pull Library down from the top (Android LibraryRefreshDemo): the grid follows the finger, the spinner shows
/// under the chips, and on letting go the view reads again for a moment. Nothing is behind the phone, so a pull
/// (the dot's, or the user's) reads for a moment and is done.
struct LibraryRefreshDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @Environment(\.tipContainer) private var container
    @State private var pull: CGFloat = 0
    @State private var refreshing = false
    /// A pull by hand is under way.
    @State private var pulling = false
    /// One more per refresh begun, so an earlier one's end doesn't cut a later one short.
    @State private var turn = 0

    /// How long the view reads (Android DemoRefreshMs).
    static let refreshMs = 1_200
    /// How far the dot pulls the grid (screen points); a pull past `DemoLibraryPage.refreshRoom` refreshes.
    static let pullTravel: CGFloat = 110
    /// A pull reads half the finger's travel, as a scroll view's rubber band does.
    static let resistance: CGFloat = 0.5

    private func letGo() {
        let refreshes = pull >= DemoLibraryPage.refreshRoom
        withAnimation(DemoTiming.springAnimation) {
            if refreshes { refreshing = true }
            pull = 0
        }
        guard refreshes else { return }
        turn += 1
        let mine = turn
        Task {
            try? await DemoTiming.delay(Self.refreshMs)
            if mine == turn { withAnimation(DemoTiming.springAnimation) { refreshing = false } }
        }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            turn += 1
            refreshing = false
            pull = 0
            try await DemoTiming.delay(300)
            try await finger.moveTo("libraryGrid", fy: 0.12)
            let from = finger.at("libraryGrid", fy: 0.12)
            let screen = DemoPhoneMetrics(container: container).screen.height
            try await finger.drag(toX: from.x, toY: from.y + Self.pullTravel / max(screen, 1) / Self.resistance, ms: 700) {
                pull = Self.pullTravel
            }
            letGo()
            try await finger.lift()
            try await DemoTiming.delay(Self.refreshMs + 1_200)
        }) {
            DemoLibraryPage(scheme: scheme, pull: pull, refreshing: refreshing)
                .gesture(DemoVerticalPull(
                    onChange: { dy in
                        pulling = true
                        // The pan reads the page's points; the phone is drawn scaled down on it.
                        let scale = DemoPhoneMetrics(container: container).scale
                        pull = max(dy / max(scale, 0.01), 0) * Self.resistance
                    },
                    onEnd: {
                        guard pulling else { return }
                        pulling = false
                        letGo()
                    }))
        }
    }
}

// MARK: - Keyboard shortcuts

/// Keyboard shortcuts (Android KeyboardShortcutsDemo), the iPad's and the Mac's keys (YoinCommands): Space
/// pauses and plays the mini player, ⌘→ moves it to the next song, ⌘K opens search, a keyboard's keys as
/// keycaps on a tray of their own that the bar sits on, pressed by the dot (or the user).
struct KeyboardShortcutsDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var song = 0
    @State private var paused = false
    @State private var searching = false
    @State private var pressed: DemoKey?
    @State private var songs: [NowPlayingDemo] = (0..<3).map { TipDemo.nowPlaying(song: $0) }

    private var demo: NowPlayingDemo { songs[song % songs.count] }

    private func press(_ key: DemoKey) {
        switch key {
        case .space: paused.toggle()
        case .next: song += 1
        case .search: searching = true
        }
    }

    private func keyDown(_ key: DemoKey) async throws {
        pressed = key
        press(key)
        try await DemoTiming.delay(DemoTiming.tapDownMs + 80)
        pressed = nil
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            song = 0
            paused = false
            searching = false
            pressed = nil
            try await DemoTiming.delay(300)
            for key in DemoKey.allCases {
                try await finger.moveTo("key-\(key.rawValue)")
                try await finger.tap { pressed = key; press(key) }
                pressed = nil
                try await DemoTiming.delay(key == .search ? 1_600 : 900)
            }
            try await finger.moveTo("searchClose")
            try await finger.tap { searching = false }
            try await DemoTiming.delay(600)
            // Space again: the bar plays on.
            try await finger.moveTo("key-\(DemoKey.space.rawValue)")
            try await finger.tap { pressed = .space; press(.space) }
            pressed = nil
            try await finger.lift()
            try await DemoTiming.delay(500)
        }) {
            ZStack {
                DemoLibraryPage(scheme: scheme, searching: searching, nowPlaying: demo,
                                keys: AnyView(DemoKeycaps(scheme: scheme, pressed: pressed) { key in
                                    Task { try? await keyDown(key) }
                                }))
                DemoLibrarySearch(scheme: scheme, visible: searching) { searching = false }
            }
            .onChange(of: playing && !paused, initial: true) { _, on in
                for s in songs { s.isPlaying = on }
            }
            .task(id: playing && !paused) {
                guard playing && !paused else { return }
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(DemoNowPlaying.tickMs))
                    demo.advance(by: Double(DemoNowPlaying.tickMs) / 1000)
                }
            }
        }
    }
}

/// The keys the keyboard demo presses: what each is called on its keycap, and what it does (YoinCommands).
enum DemoKey: String, CaseIterable {
    case space, next, search

    /// The keycap's face: Space's name, or the modifier and the key.
    var face: String {
        switch self {
        case .space: String(localized: "tip.key.space", defaultValue: "space")
        case .next: "⌘ →"
        case .search: "⌘ K"
        }
    }
}

/// A row of keycaps: Space wide, the two ⌘ chords beside it. A pressed key sinks and takes the accent.
struct DemoKeycaps: View {
    var scheme: CoverScheme
    var pressed: DemoKey?
    var onPress: (DemoKey) -> Void

    static let height: CGFloat = 52

    var body: some View {
        HStack(spacing: 10) {
            ForEach(DemoKey.allCases, id: \.self) { key in
                let down = pressed == key
                Button { onPress(key) } label: {
                    Text(key.face)
                        .font(.gsf(17, .medium))
                        .foregroundStyle(down ? scheme.onPrimaryContainer : scheme.onSurface)
                        .frame(maxWidth: .infinity)
                        .frame(height: Self.height)
                        .background(down ? scheme.primaryContainer : scheme.surfaceBright,
                                    in: .rect(cornerRadius: 12, style: .continuous))
                        // The key's edge under its face, gone while it is down.
                        .shadow(color: Color.black.opacity(down ? 0 : 0.18), radius: 0, y: down ? 0 : 3)
                        .offset(y: down ? 2 : 0)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .frame(maxWidth: key == .space ? .infinity : 96)
                .layoutPriority(key == .space ? 1 : 0)
                .demoTarget("key-\(key.rawValue)")
                .animation(Yoin.spatialFast, value: down)
            }
        }
    }
}

// MARK: - Mini player options

/// Long-press the mini player in the bar: the playing song's sheet, the one Now Playing's cover gives
/// (Favorite / Add to playlist / Share, Go to album, Keep screen on); a tap above it sends it down.
struct MiniPlayerMenuDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var demo = TipDemo.nowPlaying()
    @State private var open = false
    @State private var awake = false

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            open = false
            try await DemoTiming.delay(300)
            try await finger.moveTo("pill", fx: 0.5)
            try await finger.hold { open = true }
            // Gone the moment the sheet starts up, so it never sits on a row.
            finger.hide()
            try await DemoTiming.delay(1_800)
            try await finger.moveTo(x: 0.5, y: 0.12)
            try await finger.tap { open = false }
            try await finger.lift()
            try await DemoTiming.delay(500)
        }) {
            DemoSheetStage(open: open, onDismiss: { open = false }) {
                DemoLibraryPage(scheme: scheme, nowPlaying: demo, onPillHold: { open = true })
            } sheet: {
                DemoSongSheet(track: PlaylistEntry(album: demo.album, track: demo.track), scheme: scheme, nowPlaying: true,
                              onDismiss: { open = false }) {
                    TrackMenuToggleRow(label: String(localized: "Keep screen on"), icon: "light_mode", scheme: scheme,
                                       isOn: Binding(get: { awake }, set: { on in withAnimation(Yoin.spatialFast) { awake = on } }))
                }
            }
            .onChange(of: playing, initial: true) { _, on in demo.isPlaying = on }
            .task(id: playing) {
                guard playing else { return }
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(DemoNowPlaying.tickMs))
                    demo.advance(by: Double(DemoNowPlaying.tickMs) / 1000)
                }
            }
        }
    }
}

// MARK: - The page

/// Library on the phone: the header, the filter row, the grid, the bar (Library selected). [searching]: Search
/// is up over it, so Library's chips step out (LibraryView). [nowPlaying]: the bar's mini player. [pull]: how far
/// the grid is pulled down (screen points); [refreshing]: the pull let go and the view reads again, the spinner
/// under the chips as `.refreshable`'s is. [keys]: keycaps on a tray of their own that the bar sits on.
struct DemoLibraryPage: View {
    var scheme: CoverScheme
    var searching = false
    var nowPlaying: NowPlayingDemo? = nil
    var onLibraryHold: (() -> Void)? = nil
    var onPillHold: (() -> Void)? = nil
    var pull: CGFloat = 0
    var refreshing = false
    var keys: AnyView? = nil
    @State private var filter: LibraryKind?

    /// The phone's grid: three to a row, as Library lays a phone out (LibraryGrid).
    static let columns = 3
    /// The room the refresh spinner holds open above the grid while it reads (UIRefreshControl's), and how
    /// far a pull goes before the spinner is all there.
    static let refreshRoom: CGFloat = 60

    var body: some View {
        let cell = LibraryGrid.cell(width: DemoPhoneMetrics.screenWidth, columns: Self.columns)
        let gutter = Tokens.library.GRID_GUTTER_H.cg
        let drop = refreshing ? max(pull, Self.refreshRoom) : pull
        ZStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: LibraryFilterRow.Style.current.isTrack ? 0 : -4) {
                    header
                    LibraryFilterRow(filter: $filter, scheme: scheme, style: .current, overContent: false)
                        .opacity(searching ? 0 : 1)
                        .animation(searching ? nil : Yoin.effects, value: searching)
                }
                .zIndex(1)
                ZStack(alignment: .top) {
                    if refreshing || pull > 0 {
                        ProgressView()
                            .controlSize(.regular)
                            .tint(scheme.onSurfaceVariant)
                            .frame(height: Self.refreshRoom)
                            .opacity(refreshing ? 1 : Double(min(pull / Self.refreshRoom, 1)))
                            .transition(.opacity)
                    }
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: gutter, alignment: .top), count: Self.columns),
                              alignment: .leading, spacing: Tokens.library.GRID_GUTTER_V.cg) {
                        ForEach(TipDemo.libraryItems, id: \.id) { item in
                            LibraryGridCellFace(item: item, side: cell, scheme: scheme)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, Tokens.library.GRID_TOP_PADDING.cg)
                    .offset(y: drop)
                    .demoTarget("libraryGrid")
                }
            }
            .padding(.top, DemoPhoneMetrics.statusBar)
            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .topLeading)
            VStack(spacing: 14) {
                if let keys { keys }
                DemoShellBar(scheme: scheme, selected: .library, nowPlaying: nowPlaying,
                             onLibraryHold: onLibraryHold, onPillHold: onPillHold)
            }
            .padding(.top, keys == nil ? 0 : 16)
            .padding(.horizontal, 14)
            .padding(.bottom, DemoHomeMetrics.barBottom)
            .background {
                if keys != nil {
                    UnevenRoundedRectangle(topLeadingRadius: 28, topTrailingRadius: 28, style: .continuous)
                        .fill(scheme.surfaceContainer)
                }
            }
        }
        .background(LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                                   startPoint: .top, endPoint: .bottom))
        .clipped()
    }

    /// LibraryView.header: the search pill and the account.
    private var header: some View {
        HStack(spacing: 8) {
            LibrarySearchPillLabel()
                .labSearchGlass(scheme: scheme, in: .capsule)
            DemoAccountAvatar(profile: TipDemo.accounts[0], scheme: scheme)
        }
        .padding(.horizontal, 16)
        .padding(.top, SearchView.headerTop)
        .padding(.bottom, 4)
    }
}

/// Search as it opens from Library (SearchView.header over its surface): the field where Library's pill is,
/// the ✕ beside it, the result types under it; nothing below until there is a query. The surface fades in on
/// the effects spring, the ✕ and the chips a beat behind it (fade through).
struct DemoLibrarySearch: View {
    var scheme: CoverScheme
    var visible: Bool
    var onClose: () -> Void
    @State private var filter: SearchView.Filter = .all

    var body: some View {
        ZStack(alignment: .top) {
            scheme.surfaceContainerHigh
                .opacity(visible ? 1 : 0)
                .animation(Yoin.effects, value: visible)
            if visible {
                header
                    .padding(.top, DemoPhoneMetrics.statusBar)
                    .transition(.opacity.animation(SearchView.fadeThrough))
            }
        }
        .allowsHitTesting(visible)
    }

    private var header: some View {
        VStack(spacing: Tokens.search.FILTER_ROW_PADDING_V.cg) {
            HStack(spacing: 8) {
                HStack(spacing: 12) {
                    YoinIcon("search", size: 24).foregroundStyle(Yoin.inkVariant)
                    Text("Search library").font(.gsf(16)).foregroundStyle(Yoin.inkVariant)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .frame(height: SearchView.fieldHeight)
                .labSearchGlass(scheme: scheme, in: .capsule)
                Button(action: onClose) {
                    YoinIcon("close", size: 20)
                        .foregroundStyle(Yoin.ink)
                        .frame(width: 48, height: 48)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain).pointerHighlight()
                .labSearchGlass(scheme: scheme, in: .circle)
                .demoTarget("searchClose")
            }
            // A Subsonic server searches its library alone: no scope pair, and no playlists (SearchView).
            SegmentChipRow(groups: [SearchView.Filter.allCases.filter { $0 != .playlists }], selected: [filter],
                           label: \.title, onTap: { f in withAnimation(Yoin.spatial) { filter = f } },
                           scheme: scheme, style: .current, overContent: false)
                .padding(.horizontal, -16)
                .padding(.vertical, -4)
        }
        .padding(.horizontal, 16)
        .padding(.top, SearchView.headerTop)
        .padding(.bottom, Tokens.search.FILTER_ROW_PADDING_V.cg)
    }
}

// MARK: - Yoin Labs

/// Settings › Yoin Labs on the phone: the real page on a device whose on-device model is ready, and the touch
/// turns Lyric Cards on. The switch is the phone's own: it never changes the real one.
struct LabsDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var on = false

    /// Where the switch sits across the Lyric Cards row (it ends the row).
    static let switchFx: CGFloat = 0.92

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.effects) { on = false }
            try await DemoTiming.delay(700)
            try await finger.moveTo("labs-switch", fx: Self.switchFx)
            try await DemoTiming.delay(250)
            try await finger.tap { withAnimation(Yoin.effects) { on = true } }
            try await finger.lift()
            try await DemoTiming.delay(2_400)
        }) {
            SettingsPage(title: "Yoin Labs", demoScheme: scheme) { app, row in
                LabsBody(status: .available, optedIn: Binding(get: { on }, set: { v in withAnimation(Yoin.effects) { on = v } }),
                         app: app, row: row, onOpenSettings: {}, onCheckAgain: {})
            }
            .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
            // A sideways swipe on the phone turns the tips; the page has nothing below the fold to scroll to.
            .scrollDisabled(true)
        }
    }
}

// MARK: - Volume leveling

/// Volume leveling (Android VolumeLevelingDemo): a Subsonic account's page in the little phone with its real
/// Volume leveling group, and the dot picks By track, then By album. The demo keeps the choice to itself: the
/// real one never changes.
struct VolumeLevelingDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var mode: ReplayGainMode = .off

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.effects) { mode = .off }
            try await DemoTiming.delay(700)
            try await finger.moveTo("leveling-\(ReplayGainMode.track.rawValue)")
            try await DemoTiming.delay(250)
            try await finger.tap { withAnimation(Yoin.effects) { mode = .track } }
            try await DemoTiming.delay(900)
            try await finger.moveTo("leveling-\(ReplayGainMode.album.rawValue)")
            try await DemoTiming.delay(250)
            try await finger.tap { withAnimation(Yoin.effects) { mode = .album } }
            try await finger.lift()
            try await DemoTiming.delay(2_400)
        }) {
            // The account's page is named after its service, as ServiceSetupView names it.
            SettingsPage(title: "Subsonic", demoScheme: scheme) { app, row in
                VolumeLevelingGroup(app: app, row: row, mode: mode) { m in withAnimation(Yoin.effects) { mode = m } }
            }
            .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
            // A sideways swipe on the phone turns the tips; the page has nothing below the fold to scroll to.
            .scrollDisabled(true)
        }
    }
}

// MARK: - Behind the name

/// 余韻: the Yoin mark; each tap (the demo's, or the user's) sounds a note whose rings keep spreading.
struct NameDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    /// When each note was struck.
    @State private var notes: [Date] = []

    static let markSize: CGFloat = 96
    /// A ring's life, and how far behind each of a note's three rings follows the one before (Android).
    static let noteLife: TimeInterval = 2.4
    static let noteEcho: TimeInterval = 0.22
    static let echoes = 3

    /// Ring [k] of a note struck [age] seconds ago: how far it has spread (0…1), nil before it starts or
    /// once it has faded. Pure (TipDemoTests).
    static func ringProgress(age: TimeInterval, ring k: Int) -> Double? {
        let p = (age - Double(k) * noteEcho) / noteLife
        return p > 0 && p < 1 ? p : nil
    }

    /// A note's whole life: its last ring's.
    static var ringing: TimeInterval { noteLife + Double(echoes - 1) * noteEcho }

    private func strike() {
        let now = Date()
        notes.append(now)
        // Gone once its last ring has faded, so the clock stops when all have.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.ringing / LaunchConfig.animationSpeed))
            notes.removeAll { $0 <= now }
        }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            try await finger.moveTo("mark")
            try await finger.tap { strike() }
            try await DemoTiming.delay(1_200)
            try await finger.tap { strike() }
            try await finger.lift()
            try await DemoTiming.delay(2_000)
        }) {
            ZStack {
                LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                               startPoint: .top, endPoint: .bottom)
                TimelineView(.animation(paused: notes.isEmpty)) { context in
                    Canvas { gc, size in
                        let c = CGPoint(x: size.width / 2, y: size.height / 2)
                        let reach = size.height * 0.6
                        let speed = LaunchConfig.animationSpeed
                        for start in notes {
                            let age = context.date.timeIntervalSince(start) * speed
                            for k in 0..<Self.echoes {
                                guard let p = Self.ringProgress(age: age, ring: k) else { continue }
                                let r = reach * p
                                gc.stroke(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                                          with: .color(scheme.primary.opacity((1 - p) * 0.6)),
                                          lineWidth: (1 - p) * 6 + 1.5)
                            }
                        }
                    }
                }
                // The launcher mark's arm roles: tertiary, primary, secondary.
                YoinArmsCanvas(arms: [(0, scheme.tertiary), (1, scheme.primary), (2, scheme.secondary)], lineColor: .white)
                    .frame(width: Self.markSize, height: Self.markSize)
                    .demoTarget("mark")
            }
            // A tap anywhere on the screen sounds one.
            .contentShape(.rect)
            .onTapGesture { strike() }
        }
    }
}
