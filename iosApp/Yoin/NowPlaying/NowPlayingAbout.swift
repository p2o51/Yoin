import SwiftUI
import YoinShared

/// What the server says about a song: labelled facts in a fixed order.
///
/// Android fills About from Gemini only (creation time, place, lyricist,
/// composer, producer, a review: `SongAboutEntry.CANONICAL_ORDER`). iOS does
/// too once a key is saved (`SongAboutLoader`); without one, or for a song
/// Gemini isn't asked about, the page reads the server's own record of the
/// song (`getSong`) in Android's About language: label over value.
struct SongFacts: Equatable {
    struct Fact: Equatable, Identifiable {
        var label: String
        var value: String
        var id: String { label }
    }

    var facts: [Fact]
    /// False when the server couldn't be asked: ask again next time.
    var complete = true
    /// What the Now Playing spine reads from the server's record (Subsonic only).
    var spine: SpineSourceFacts?

    /// From the queue entry alone (Apple Music, or the server unreachable): its album, artist and length.
    init(_ t: PlayableTrack, complete: Bool = true) {
        var f: [Fact] = []
        Self.add(&f, String(localized: "Album"), t.album)
        Self.add(&f, String(localized: "Artist"), t.artist)
        if t.durationSec > 0 { Self.add(&f, String(localized: "Length"), Self.duration(Int(t.durationSec.rounded()))) }
        facts = f
        self.complete = complete
    }

    /// The mock snapshot (screenshots without a server).
    init(album: Album, track: Track) {
        var f: [Fact] = []
        Self.add(&f, String(localized: "Album"), album.title)
        Self.add(&f, String(localized: "Artist"), album.artist)
        if album.year > 0 { Self.add(&f, String(localized: "Year"), String(album.year)) }
        Self.add(&f, String(localized: "Length"), Self.duration(track.durationSec))
        facts = f
    }

    /// From the server's record (Subsonic `getSong`): Android's receipt, everything the server says about
    /// the song (`SpineSourceFacts.receiptRows`), the queue entry's length when the server gives none.
    @MainActor
    init(_ s: SongInfo, fallback t: PlayableTrack) {
        var spine = SpineSourceFacts(s)
        if spine.durationSec == nil, t.durationSec > 0 { spine.durationSec = Int(t.durationSec.rounded()) }
        facts = spine.receiptRows(.localized)
        self.spine = spine
    }

    private static func add(_ f: inout [Fact], _ label: String, _ value: String?) {
        guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return }
        f.append(Fact(label: label, value: v))
    }

    /// Android `formatDuration`: 245 → "4:05", an hour or more → "1:02:05".
    static func duration(_ sec: Int) -> String {
        sec >= 3600 ? String(format: "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
            : String(format: "%d:%02d", sec / 60, sec % 60)
    }
}

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
        /// Gemini is being asked (Android `Loading`), carrying any already-available server facts.
        case searching(SongFacts? = nil)
        /// A key is saved and nothing is cached yet: ask now, carrying any already-available server facts.
        case pending(AboutSong, SongFacts? = nil)
        /// Gemini's rows for the song (Android `Ready`), with the server's
        /// facts beside them when it has any (Android always shows both).
        case gemini([SongAboutStore.Entry], SongFacts? = nil)
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
                return .gemini(entries, facts)
            case .loading:
                return .searching(facts)
            case .noKey:
                return .noKey(facts)
            case .idle:
                if loader.settings.hasKey, !loader.store.hasCanonical(key) { return .pending(song, facts) }
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
    /// The credits the lyrics source wrote into the lyrics (Android `credits`): they answer lyricist,
    /// composer and producer before Gemini, with or without it; expanded, the rest sit behind "N more".
    var credits: SongCredits? = nil
    @Environment(\.colorScheme) private var colorScheme
    /// The long-pressed row whose sheet is up.
    @State private var menuFor: AboutEntrySheet.Target?
    @State private var pressed = 0
    /// The lyrics' other credits are open (Android AboutReceipt `expanded`, per credits).
    @State private var allCredits = false
    @State private var compactBoxHeight: CGFloat = 0
    @State private var compactContentHeight: CGFloat = 0

    /// Bottom fade of a compact About page taller than its window (`add8a63f5`).
    static let compactOverflowFade: CGFloat = 40

    /// Whether lyric credits or server facts already yield displayable rows before AI answers (`5145074a9`).
    static func hasSourceRows(facts: SongFacts?, credits: SongCredits?) -> Bool {
        !rows(entries: [], facts: facts, expanded: false, credits: credits).isEmpty
    }

    static func compactOverflows(containerHeight: CGFloat, contentHeight: CGFloat) -> Bool {
        containerHeight > 0 && contentHeight > containerHeight + 0.5
    }

    private var loader: SongAboutLoader { .shared }

    /// The state as shown (DEBUG `-aboutDemo`: the mock song's demo rows).
    private var shown: NowPlayingData.AboutState {
        #if DEBUG
        if case .gemini = state { return state }
        if let rows = SongAboutDemo.rows(), !rows.isEmpty { return .gemini(rows, nil) }
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
                list(Self.rows(entries: [], facts: facts, expanded: expanded, credits: credits))
                    .transition(.opacity)
            case .searching(let facts):
                let sourceRows = Self.rows(entries: [], facts: facts, expanded: expanded, credits: credits)
                if !sourceRows.isEmpty {
                    list(sourceRows).transition(.opacity)
                } else {
                    searching.transition(.opacity)
                }
            case .pending(let song, let facts):
                let sourceRows = Self.rows(entries: [], facts: facts, expanded: expanded, credits: credits)
                Group {
                    if !sourceRows.isEmpty {
                        list(sourceRows)
                    } else {
                        searching
                    }
                }
                .task(id: song.key) { await SongAboutLoader.shared.ensure(title: song.title, artist: song.artist, album: song.album) }
                .transition(.opacity)
            case .gemini(let entries, let facts):
                list(Self.rows(entries: entries, facts: facts, expanded: expanded, credits: credits))
                    .transition(.opacity)
            case .noKey(let facts):
                list(Self.rows(entries: [], facts: facts, expanded: expanded, credits: credits), header: AnyView(noKey))
                    .transition(.opacity)
            case .failed(let message, let song):
                failed(message, song).transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: state)
        .sheet(item: $menuFor) { target in
            AboutEntrySheet(target: target, scheme: scheme)
                .reportsSheetRise()
                .ySolidSheet(scheme.surfaceContainerLow)
        }
        // The song can move on while a row's sheet is up: the sheet goes with
        // its row, so Manage never pairs one song's row with the next song's album.
        .onChange(of: shown) { _, now in
            guard let open = menuFor else { return }
            guard case .gemini(let rows, _) = now, rows.contains(where: { $0.ref == open.entry.ref }) else {
                menuFor = nil
                return
            }
        }
        .sensoryFeedback(.impact(weight: .medium), trigger: pressed)
        .onChange(of: credits) { allCredits = false }
        #if DEBUG
        .task {
            // `-aboutDemo sheet`: the question's sheet, as a long-press opens it.
            guard expanded, SongAboutDemo.mode == "sheet", case .gemini(let rows, _) = shown,
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
            textButton(String(localized: "Settings")) {
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
            textButton(String(localized: "Retry")) {
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

    struct Item: Identifiable {
        var label: String
        var value: String
        /// Android's small window sets the review 8 lower with its own heading gap.
        var gapAbove: CGFloat = 0
        /// Gemini's or the listener's row (nil: the server's facts, or the lyrics' credits).
        var entry: SongAboutStore.Entry? = nil
        /// Part of the receipt (who wrote it, when and where, the server's facts); false for the review
        /// and the asks under it. The lyrics' other credits and notices close the receipt.
        var receipt = true
        var id: String { entry.map { "\($0.kind)/\($0.entryKey)" } ?? label + "\u{1}" + value }
    }

    /// Android's receipt order (`aboutReceiptRows`): who wrote it (the
    /// lyrics' credits first, then Gemini's canonical rows, blank ones
    /// skipped; the arranger only from the lyrics) and when and where
    /// (Gemini), then everything the server says, then the review, then the
    /// asks and the listener's own entries (every one expanded under a 20
    /// gap, the latest only in the small window under a 12 gap). The
    /// server's facts show whenever there are any, whatever else is on the page.
    static func rows(entries: [SongAboutStore.Entry], facts: SongFacts?, expanded: Bool,
                     credits: SongCredits? = nil) -> [Item] {
        let canonical = entries.filter { $0.kind == SongAboutStore.Kind.canonical }
        func row(_ key: String) -> Item? {
            canonical.first { $0.entryKey == key }.map {
                Item(label: label(key, expanded: expanded), value: $0.answerText, entry: $0)
            }
        }
        /// Android `credited`: the lyrics' names for the role (the server's composer after them), else
        /// Gemini's row.
        func credited(_ key: String) -> Item? {
            let server = key == SongAboutStore.Canon.composer ? facts?.spine?.composer : nil
            if let names = credits?.names(forAboutKey: key) ?? server {
                return Item(label: label(key, expanded: expanded), value: names)
            }
            return row(key)
        }
        var out = [credited(SongAboutStore.Canon.lyricist), credited(SongAboutStore.Canon.composer)]
            .compactMap { $0 }
        if let arranger = credits?.arranger { out.append(Item(label: String(localized: "Arranger"), value: arranger)) }
        out += [credited(SongAboutStore.Canon.producer), row(SongAboutStore.Canon.creationTime),
                row(SongAboutStore.Canon.creationLocation)].compactMap { $0 }
        out += (facts?.facts ?? []).map { Item(label: $0.label, value: $0.value) }
        if var review = row(SongAboutStore.Canon.review) {
            review.gapAbove = expanded ? 0 : 8
            review.receipt = false
            out.append(review)
        }
        let asks = entries.filter { $0.kind != SongAboutStore.Kind.canonical }
        for (i, ask) in (expanded ? asks : Array(asks.prefix(1))).enumerated() {
            out.append(Item(label: ask.heading ?? "", value: ask.answerText,
                            gapAbove: i == 0 ? (expanded ? 20 : 12) : 0, entry: ask, receipt: false))
        }
        return out
    }

    /// Android `np_about_*` (expanded) and `cmp_song_info_*` (small) labels.
    static func label(_ key: String, expanded: Bool) -> String {
        switch key {
        case SongAboutStore.Canon.creationTime: expanded ? String(localized: "Creation Time") : String(localized: "Created")
        case SongAboutStore.Canon.creationLocation: expanded ? String(localized: "Creation Location") : String(localized: "Location")
        case SongAboutStore.Canon.lyricist: String(localized: "Lyricist")
        case SongAboutStore.Canon.composer: String(localized: "Composer")
        case SongAboutStore.Canon.producer: String(localized: "Producer")
        case SongAboutStore.Canon.review: String(localized: "About")
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
        // The receipt ends where the review starts: the lyrics' other credits and notices go there.
        let cut = items.firstIndex { !$0.receipt } ?? items.count
        let inner = VStack(alignment: .leading, spacing: 0) {
            if let header { header }
            ForEach(items[..<cut]) { item in itemView(item) }
            if expanded, let credits { creditsFoot(credits) }
            ForEach(items[cut...]) { item in itemView(item) }
        }
        if expanded {
            let column = VStack(alignment: .leading, spacing: 0) {
                inner
                Spacer().frame(height: bottomPadding)
            }
            // A scroll view takes exactly the height it is given (the small
            // window holds still; the page scrolls).
            ScrollView(.vertical, showsIndicators: false) { column }
                .mask(Self.fade(top: 0, bottom: 64))
        } else {
            let overflows = Self.compactOverflows(containerHeight: compactBoxHeight, contentHeight: compactContentHeight)
            let column = inner
                .fixedSize(horizontal: false, vertical: true)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { compactContentHeight = $0 }
                .frame(maxWidth: .infinity, minHeight: overflows || compactBoxHeight <= 0 ? nil : compactBoxHeight,
                       alignment: overflows ? .topLeading : .center)
            ScrollView(.vertical, showsIndicators: false) { column }
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { compactBoxHeight = $0 }
                .scrollDisabled(true)
                .mask {
                    if overflows {
                        Self.fade(top: 0, bottom: Self.compactOverflowFade)
                    } else {
                        Color.white
                    }
                }
        }
    }

    /// One row: the label in primary over the value.
    private func itemView(_ item: Item) -> some View {
        let thinking = item.entry.map { loader.regenerating.contains($0.ref) } ?? false
        return VStack(alignment: .leading, spacing: expanded ? 2 : 0) {
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

    /// Android AboutReceipt's foot, expanded: the lyrics' other credits behind "N more" (the first
    /// roles quieter beside it, a free-form line as "Note"), then the copyright notices, small and
    /// centred.
    @ViewBuilder
    private func creditsFoot(_ credits: SongCredits) -> some View {
        if !credits.others.isEmpty {
            if allCredits {
                ForEach(Array(credits.others.enumerated()), id: \.offset) { _, credit in
                    if credit.role.isEmpty {
                        Text(credit.names)
                            .yoinRole(Tokens.type.bodySmall)
                            .foregroundStyle(scheme.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 8)
                    } else {
                        itemView(Item(label: credit.role, value: credit.names))
                    }
                }
            }
            Button {
                withAnimation(Yoin.spatial) { allCredits.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Text(allCredits ? String(localized: "Show less") : String(localized: "\(credits.others.count) more"))
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(scheme.primary)
                        .lineLimit(1)
                    if !allCredits {
                        let note = String(localized: "lyricCredits.note", defaultValue: "Note")
                        Text(credits.others.prefix(3).map { $0.role.isEmpty ? note : $0.role }.joined(separator: "、") + "…")
                            .yoinRole(Tokens.type.labelMedium)
                            .foregroundStyle(scheme.onSurfaceVariant)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                }
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
        if !credits.notices.isEmpty {
            VStack(spacing: 2) {
                ForEach(Array(credits.notices.enumerated()), id: \.offset) { _, notice in
                    Text(notice)
                        .yoinRole(Tokens.type.labelSmall)
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.vertical, 10)
        }
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
                label(title.isEmpty ? String(localized: "Ask Gemini") : title, color: onGlass)
            case .error(let message):
                AskGeminiBar.dot(scheme)
                label(message, color: scheme.error)
            case .idle, .focused:
                AskGeminiBar.dot(scheme)
                label(String(localized: "Ask Gemini"), color: onGlass)
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
        .pointerHighlight()
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
/// While the field is empty the quick questions not yet asked about the song
/// ([prompts]) sit at its foot, scrolling out to the card's edges; one fills
/// the field (owner 2026-11-02).
struct AskGeminiField: View {
    var scheme: CoverScheme
    @Binding var text: String
    var focus: FocusState<Bool>.Binding
    var prompts: [String] = []
    var onSubmit: (String) -> Void
    @Environment(\.colorScheme) private var colorScheme

    static let height: CGFloat = 252

    /// As on the bar: white in dark.
    private var onGlass: Color { colorScheme == .dark ? .white : scheme.onPrimaryContainer }
    private var showsPrompts: Bool { text.isEmpty && !prompts.isEmpty }

    var body: some View {
        let card = RoundedRectangle(cornerRadius: AskGeminiBar.corner, style: .continuous)
        // Only the top and bottom are padded, so the quick questions can scroll edge to edge (Android).
        VStack(alignment: .leading, spacing: 0) {
            AskGeminiBar.dot(scheme)
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
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
            .padding(.horizontal, 20)
            .frame(maxHeight: .infinity, alignment: .topLeading)
            if showsPrompts {
                QuickPromptsRow(prompts: prompts, container: scheme.surface, content: scheme.onSurface,
                                inset: 20) { prompt in
                    text = prompt
                    focus.wrappedValue = true
                }
                .padding(.top, 8)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, minHeight: Self.height, maxHeight: Self.height, alignment: .topLeading)
        .animation(Yoin.spatial, value: showsPrompts)
        // The card clips what scrolls in it, as Android's Surface does.
        .clipShape(card)
        .npPillGlass("npFloating", tint: scheme.primaryContainer, in: card)
    }

    private func submit() {
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        focus.wrappedValue = false
        onSubmit(q)
    }
}

/// An About row's long-press, or a right-click: opens its sheet (only Gemini's rows and the
/// listener's own, and only on the expanded page).
private struct RowMenu: ViewModifier {
    var entry: SongAboutStore.Entry?
    var label: String
    var open: (AboutEntrySheet.Target) -> Void

    func body(content: Content) -> some View {
        if let entry {
            content
                .onSecondaryClick { open(.init(entry: entry, label: label)) }
                .onLongPressGesture(minimumDuration: 0.4) { open(.init(entry: entry, label: label)) }
                .accessibilityAction(named: "Options for this entry") { open(.init(entry: entry, label: label)) }
        } else {
            content
        }
    }
}
