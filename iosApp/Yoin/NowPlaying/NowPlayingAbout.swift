import SwiftUI
import YoinShared

/// The song About asks Gemini about (title, artist, album as playing).
struct AboutSong: Equatable {
    var title: String
    var artist: String
    var album: String

    init(_ t: PlayableTrack) {
        title = t.title
        artist = t.artist
        album = t.album
    }

    var key: SongAboutStore.Entry.SongKey { SongAboutStore.key(title: title, artist: artist, album: album) }
    /// Android asks only with a title and an artist.
    var askable: Bool {
        !title.trimmingCharacters(in: .whitespaces).isEmpty && !artist.trimmingCharacters(in: .whitespaces).isEmpty
    }
}

extension NowPlayingData {
    /// The About page's state (Android `AboutUiState`, plus the server's facts).
    enum AboutState: Equatable {
        /// The server's facts are on the way.
        case loading
        /// The server's facts (no Gemini for this song).
        case ready(SongFacts)
        /// Gemini is being asked (Android `Loading`).
        case searching
        /// A key is saved and nothing is cached yet: ask now.
        case pending(AboutSong)
        /// Gemini's rows for the song (Android `Ready`).
        case gemini([SongAboutStore.Entry])
        /// No Gemini key (Android `ApiKeyMissing`), over the server's facts.
        case noKey(SongFacts?)
        /// Android `Error`: the message and a retry.
        case failed(String, AboutSong)

        /// Gemini's answers where there are any, else Android's order of
        /// error → rows → loading, with the server's facts standing in.
        @MainActor
        static func compose(server: AboutState, song: AboutSong,
                            loader: SongAboutLoader = .shared) -> AboutState {
            guard song.askable else { return server }
            let key = song.key
            let entries = loader.store.entries(key).filter {
                !$0.answerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            let facts: SongFacts? = if case .ready(let f) = server { f } else { nil }
            switch loader.phase(key) {
            case .failed(let message):
                return .failed(message, song)
            case _ where !entries.isEmpty:
                return .gemini(entries)
            case .loading:
                return .searching
            case .noKey:
                return .noKey(facts)
            case .idle:
                if loader.settings.hasKey, !loader.store.hasCanonical(key) { return .pending(song) }
                // Asked, and Gemini found nothing: the server's facts.
                return server
            }
        }
    }
}

/// The About page in both sizes (Android `SongInfoDisplay` in the small
/// window, `AboutFullscreenPane` expanded): label in primary over the value
/// in onSurface. Small: labelMedium / bodyMedium, 3 apart, the foot fading
/// out. Expanded: titleMedium / bodyLarge, 8 apart, scrolling.
///
/// Expanded, Gemini's rows and the listener's own answer a long-press
/// (owner-approved prototype v3, 2026-11-02): `AboutEntrySheet` offers the
/// row in its album's scrapbook and, for Gemini's, a fresh answer; a row
/// being written again shows thinking dots in place of its text.
struct NowPlayingAboutView: View {
    var state: NowPlayingData.AboutState
    var expanded: Bool
    var scheme: CoverScheme
    var bottomPadding: CGFloat = 56
    @Environment(\.colorScheme) private var colorScheme
    /// The long-pressed row whose sheet is up.
    @State private var menuFor: AboutEntrySheet.Target?
    @State private var pressed = 0

    private var loader: SongAboutLoader { .shared }

    /// The state as shown (DEBUG `-aboutDemo`: the mock song's demo rows).
    private var shown: NowPlayingData.AboutState {
        #if DEBUG
        if case .gemini = state { return state }
        if let rows = SongAboutDemo.rows(), !rows.isEmpty { return .gemini(rows) }
        #endif
        return state
    }

    var body: some View {
        let state = shown
        ZStack(alignment: .topLeading) {
            switch state {
            case .loading:
                Text("Loading")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .transition(.opacity)
            case .ready(let facts):
                list(facts.facts.map { Item(label: $0.label, value: $0.value) })
                    .transition(.opacity)
            case .searching:
                searching.transition(.opacity)
            case .pending(let song):
                searching
                    .task(id: song.key) { await SongAboutLoader.shared.ensure(title: song.title, artist: song.artist, album: song.album) }
                    .transition(.opacity)
            case .gemini(let entries):
                list(items(entries))
                    .transition(.opacity)
            case .noKey(let facts):
                list((facts?.facts ?? []).map { Item(label: $0.label, value: $0.value) }, header: AnyView(noKey))
                    .transition(.opacity)
            case .failed(let message, let song):
                failed(message, song).transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: state)
        .sheet(item: $menuFor) { target in
            AboutEntrySheet(target: target, scheme: scheme)
                .reportsSheetRise()
        }
        // The song can move on while a row's sheet is up: the sheet goes with
        // its row, so Manage never pairs one song's row with the next song's album.
        .onChange(of: shown) { _, now in
            guard let open = menuFor else { return }
            guard case .gemini(let rows) = now, rows.contains(where: { $0.ref == open.entry.ref }) else {
                menuFor = nil
                return
            }
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: pressed)
        #if DEBUG
        .task {
            // `-aboutDemo sheet`: the question's sheet, as a long-press opens it.
            guard expanded, SongAboutDemo.mode == "sheet", case .gemini(let rows) = shown,
                  let ask = rows.first(where: { $0.kind == SongAboutStore.Kind.ask }) else { return }
            try? await Task.sleep(for: .seconds(1.5))
            menuFor = .init(entry: ask, label: ask.heading ?? "")
        }
        #endif
    }

    // MARK: States

    /// Android: the loading indicator over "Searching for song info…";
    /// start-aligned in the small window, centred expanded.
    private var searching: some View {
        VStack(alignment: expanded ? .center : .leading, spacing: 8) {
            ProgressView()
                .tint(scheme.primary)
                .controlSize(expanded ? .large : .regular)
            Text("Searching for song info…")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, maxHeight: expanded ? .infinity : nil,
               alignment: expanded ? .center : .leading)
        .frame(minHeight: 96)
    }

    /// Android ApiKeyMissing: "No Gemini API key" and a Settings button.
    private var noKey: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("No Gemini API key")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
            textButton("Settings") {
                let model = AppModel.shared
                if model.npExpanded { model.setNowPlaying(expanded: false) }
                if !model.path.contains(.settings) { model.openSettings() }
            }
        }
        .padding(.vertical, expanded ? 12 : 3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Android Error: the message in error, then Retry.
    private func failed(_ message: String, _ song: AboutSong) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(message)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.error)
                .fixedSize(horizontal: false, vertical: true)
            textButton("Retry") {
                Task { await SongAboutLoader.shared.ensure(title: song.title, artist: song.artist, album: song.album, retry: true) }
            }
        }
        .padding(.vertical, expanded ? 12 : 0)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// M3 TextButton (labelLarge, 12 in, 40 tall) on the NP pills' primary
    /// glass (51, 2026-11-04: every Ask Gemini control is glass). With a
    /// visible capsule, the capsule (not the text) lines up with the page edge.
    private func textButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(colorScheme == .dark ? Color.white : scheme.onPrimary)
                .labContrast("transportPrimary")
                .padding(.horizontal, 12)
                .frame(minHeight: 40)
                // World: the pills went neutral glass; these keep the accent.
                .npPillGlass(GlassLab.shared.oneAccentPerLayer ? "npFloating" : "npPills",
                             tint: scheme.primary, in: Capsule())
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .padding(.top, 4)
    }

    // MARK: Rows

    private struct Item: Identifiable {
        var label: String
        var value: String
        /// Android's small window sets the review 8 lower with its own heading gap.
        var gapAbove: CGFloat = 0
        /// Gemini's or the listener's row (nil: the server's facts).
        var entry: SongAboutStore.Entry? = nil
        var id: String { entry.map { "\($0.kind)/\($0.entryKey)" } ?? label + "\u{1}" + value }
    }

    /// Android's rows: canonical in order (blank ones skipped), then the
    /// asks and the listener's own entries: every one expanded under a 20
    /// gap, the latest only in the small window under a 12 gap. The review
    /// reads "About".
    private func items(_ entries: [SongAboutStore.Entry]) -> [Item] {
        var out: [Item] = entries.filter { $0.kind == SongAboutStore.Kind.canonical }.map { row in
            Item(label: Self.label(row.entryKey, expanded: expanded), value: row.answerText,
                 gapAbove: !expanded && row.entryKey == SongAboutStore.Canon.review ? 8 : 0, entry: row)
        }
        let asks = entries.filter { $0.kind != SongAboutStore.Kind.canonical }
        for (i, row) in (expanded ? asks : Array(asks.prefix(1))).enumerated() {
            out.append(Item(label: row.heading ?? "", value: row.answerText,
                            gapAbove: i == 0 ? (expanded ? 20 : 12) : 0, entry: row))
        }
        return out
    }

    /// Android `np_about_*` (expanded) and `cmp_song_info_*` (small) labels.
    static func label(_ key: String, expanded: Bool) -> String {
        switch key {
        case SongAboutStore.Canon.creationTime: expanded ? "Creation Time" : "Created"
        case SongAboutStore.Canon.creationLocation: expanded ? "Creation Location" : "Location"
        case SongAboutStore.Canon.lyricist: "Lyricist"
        case SongAboutStore.Canon.composer: "Composer"
        case SongAboutStore.Canon.producer: "Producer"
        case SongAboutStore.Canon.review: "About"
        default: key
        }
    }

    /// Android `markdownBoldAnnotatedString`: **bold** spans, the rest as is.
    static func markdown(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(text)
    }

    @ViewBuilder
    private func list(_ items: [Item], header: AnyView? = nil) -> some View {
        let column = VStack(alignment: .leading, spacing: 0) {
            if let header { header }
            ForEach(items) { item in
                let thinking = item.entry.map { loader.regenerating.contains($0.ref) } ?? false
                VStack(alignment: .leading, spacing: expanded ? 2 : 0) {
                    Text(item.label)
                        .yoinRole(expanded ? Tokens.type.titleMedium : Tokens.type.labelMedium)
                        .foregroundStyle(scheme.primary)
                    // Gemini is writing this row again: dots in place of the old text.
                    if thinking {
                        ThinkingDots(color: scheme.primary)
                            .padding(.top, 10).padding(.bottom, 6)
                            .transition(.opacity)
                    } else {
                        Text(Self.markdown(item.value))
                            .yoinRole(expanded ? Tokens.type.bodyLarge : Tokens.type.bodyMedium)
                            .foregroundStyle(scheme.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                            .transition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, expanded ? 8 : 3)
                .padding(.top, item.gapAbove)
                .animation(Yoin.effects, value: thinking)
                .contentShape(.rect)
                .modifier(RowMenu(entry: expanded ? item.entry : nil, label: item.label) { target in
                    pressed += 1
                    menuFor = target
                })
                .accessibilityElement(children: .combine)
            }
            Spacer().frame(height: bottomPadding)
        }
        // A scroll view takes exactly the height it is given (the small
        // window holds still; the page scrolls).
        ScrollView(.vertical, showsIndicators: false) { column }
            .scrollDisabled(!expanded)
            .mask(Self.fade(top: 0, bottom: expanded ? 64 : 48))
    }

    /// A vertical mask: clear at the edges, solid between.
    static func fade(top: CGFloat, bottom: CGFloat) -> some View {
        VStack(spacing: 0) {
            if top > 0 {
                LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: top)
            }
            Color.black
            LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: bottom)
        }
    }
}

/// Android `AskGeminiBar` (the expanded About page's accessory): a 56 pt
/// primaryContainer pill, the 28 pt primary dot then "Ask Gemini" in
/// titleMedium. Tap → the open bar (`AskGeminiField`, over the keyboard).
/// Loading: Gemini's heading beside a spinner, the pill lightened 45 %
/// toward the page. Error: the message in error; a tap clears it.
struct AskGeminiBar: View {
    var state: SongAboutLoader.AskBarState
    var scheme: CoverScheme
    var onFocus: () -> Void
    var onDismissError: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    static let height: CGFloat = 56
    static let corner: CGFloat = 28

    private var loading: Bool { if case .loading = state { true } else { false } }

    var body: some View {
        HStack(spacing: 16) {
            switch state {
            case .loading(let title, _):
                ProgressView()
                    .controlSize(.regular)
                    .tint(scheme.primary)
                    .frame(width: 28, height: 28)
                label(title.isEmpty ? "Ask Gemini" : title, color: onGlass)
            case .error(let message):
                AskGeminiBar.dot(scheme)
                label(message, color: scheme.error)
            case .idle, .focused:
                AskGeminiBar.dot(scheme)
                label("Ask Gemini", color: onGlass)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, minHeight: Self.height, maxHeight: Self.height)
        // Glass (51, 2026-11-04): it floats over the page as it scrolls.
        // Loading thins the tint 45 % (Android lightens the fill toward the page).
        .npPillGlass("npFloating", tint: scheme.primaryContainer, strength: loading ? 0.55 : 1,
                     in: RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
        .onTapGesture {
            switch state {
            case .error: onDismissError()
            case .loading: break
            default: onFocus()
            }
        }
        .animation(Yoin.effects, value: state)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(loading ? [] : .isButton)
    }

    /// On the glass: onPrimaryContainer in light, white in dark (the NP
    /// pills' rule; dark onPrimaryContainer sinks into the tinted glass).
    private var onGlass: Color { colorScheme == .dark ? .white : scheme.onPrimaryContainer }

    private func label(_ text: String, color: Color) -> some View {
        Text(text)
            .yoinRole(Tokens.type.titleMedium)
            .foregroundStyle(color)
            .lineLimit(1)
            .truncationMode(.tail)
            .contentTransition(.opacity)
    }

    /// Android `StaticIndicator`: a 28 pt primary dot.
    static func dot(_ scheme: CoverScheme) -> some View {
        Circle().fill(scheme.primary).frame(width: 28, height: 28)
    }
}

/// Android `AskGeminiBar` focused: the 252 pt card (28 corners), the dot,
/// then the question field (titleMedium, "Ask Gemini" placeholder at 60 %).
/// Return sends (Android ImeAction.Send); an empty question isn't sent.
struct AskGeminiField: View {
    var scheme: CoverScheme
    @Binding var text: String
    var focus: FocusState<Bool>.Binding
    var onSubmit: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    static let height: CGFloat = 252

    /// As on the bar: white in dark.
    private var onGlass: Color { colorScheme == .dark ? .white : scheme.onPrimaryContainer }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            AskGeminiBar.dot(scheme)
            TextField(text: $text, prompt: Text("Ask Gemini")
                .foregroundStyle(onGlass.opacity(0.6)), axis: .vertical) {
                Text("Ask Gemini")
            }
            .yoinRole(Tokens.type.titleMedium)
            .foregroundStyle(onGlass)
            .tint(onGlass)
            .submitLabel(.send)
            .focused(focus)
            .onChange(of: text) { _, now in
                // A vertical field types the return key in: send instead.
                guard now.contains("\n") else { return }
                text = now.replacingOccurrences(of: "\n", with: "")
                submit()
            }
            .onSubmit(submit)
            .frame(maxHeight: .infinity, alignment: .topLeading)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, minHeight: Self.height, maxHeight: Self.height, alignment: .topLeading)
        .npPillGlass("npFloating", tint: scheme.primaryContainer,
                     in: RoundedRectangle(cornerRadius: AskGeminiBar.corner, style: .continuous))
    }

    private func submit() {
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        focus.wrappedValue = false
        onSubmit(q)
    }
}

/// An About row's long-press: opens its sheet (only Gemini's rows and the
/// listener's own, and only on the expanded page).
private struct RowMenu: ViewModifier {
    var entry: SongAboutStore.Entry?
    var label: String
    var open: (AboutEntrySheet.Target) -> Void

    func body(content: Content) -> some View {
        if let entry {
            content
                .onLongPressGesture(minimumDuration: 0.4) { open(.init(entry: entry, label: label)) }
                .accessibilityAction(named: "Options for this entry") { open(.init(entry: entry, label: label)) }
        } else {
            content
        }
    }
}
