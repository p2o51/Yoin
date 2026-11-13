import SwiftUI

/*
 * What others say on NeoDB (Android ui/detail/NeoDbVoicesScreen.kt, #192; owner 2026-11-09, a test entry).
 * Top to bottom: the score spread (five columns, one per star count), a small line with the average and how
 * many rated, other catalogues' scores, then what people wrote (long words across the width, short ones two
 * by two), and pinned at the bottom the ways out: NeoDB, Douban, RYM, AOTY. Nothing here compares anyone
 * with the user. A pushed page (PushPage): the ← and the edge swipe both pop the path. It wears the album's
 * colours. Works signed out (neodb.social, read-only).
 */
struct NeoDBVoicesView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.openURL) private var openURL
    let album: Album
    @State private var model: NeoDBVoicesModel
    @State private var unfolded: Set<String> = []
    @State private var started = false

    init(album: Album, service: any NeoDBVoicesReading = NeoDBVoicesView.defaultService()) {
        self.album = album
        _model = State(initialValue: NeoDBVoicesModel(albumName: album.title, artistName: album.artist,
                                                      service: service))
    }

    private var scheme: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }

    var body: some View {
        ZStack {
            switch model.state {
            case .loading:
                NeoDBVoicesSkeleton(scheme: scheme).transition(.opacity)
            case .failed:
                message(String(localized: "Couldn't reach NeoDB."), action: String(localized: "Retry")) { model.load() }
                    .transition(.opacity)
            case .notFound(let url):
                message(String(localized: "NeoDB doesn't have this album."), action: String(localized: "Search NeoDB")) {
                    open(url)
                }
                .transition(.opacity)
            case .content(let c):
                content(c).transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: model.state.kind)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaBar(edge: .top) { topBar }
        .safeAreaBar(edge: .bottom) {
            NeoDBVoicesWaysOut(scheme: scheme, neoDBUrl: model.content?.album.itemUrl,
                               doubanUrl: model.content?.album.doubanUrl,
                               rymUrl: NeoDBVoicesParsing.rymSearchUrl(albumName: model.albumName, artist: model.artistName),
                               aotyUrl: NeoDBVoicesParsing.aotySearchUrl(albumName: model.albumName, artist: model.artistName),
                               open: open)
                .frame(maxWidth: Self.maxWidth)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                // The phone's shell bar floats over the page's bottom edge without taking safe area (as on
                // All Memories): the ways out sit above it. iPad's dock is cleared by the shell.
                .padding(.bottom, 12 + (app.isPad ? 0 : ShellBarMetrics.clearance))
        }
        .background(scheme.surface.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task {
            guard !started else { return }
            started = true
            model.load()
        }
    }

    /// A tablet doesn't stretch the cards across the window: the page's column, centred (Android
    /// `yoinPageContentWidth`, YoinPageWidths.Feed).
    static let maxWidth: CGFloat = 720

    static func defaultService() -> any NeoDBVoicesReading {
        #if DEBUG
        if let fake = NeoDBVoicesDebug.service() { return fake }
        #endif
        return NeoDBVoicesService()
    }

    private func open(_ url: String) {
        // Only a web address is handed on: post links come from any server NeoDB federates with.
        guard NeoDBVoicesParsing.isWebLink(url), let u = URL(string: url) else { return }
        openURL(u)
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack(spacing: 12) {
            Button { app.back() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(scheme.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            VStack(alignment: .leading, spacing: 0) {
                Text("What others say on NeoDB")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                Text(model.albumName)
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.vertical, 4)
    }

    // MARK: Messages

    private func message(_ text: String, action: String, perform: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Text(text)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Button(action: perform) {
                Text(action)
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.primary)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Content

    private func content(_ c: NeoDBVoicesState.Content) -> some View {
        let rows = NeoDBVoiceRows.rows(c.voices)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                NeoDBVoicesSpread(album: c.album, scheme: scheme)
                    .padding(.vertical, 8)
                if !c.references.isEmpty {
                    NeoDBVoicesReferences(references: c.references, scheme: scheme, open: open)
                        .transition(.opacity)
                }
                if c.voices.isEmpty {
                    Text("No one on NeoDB has written about this album yet.")
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .padding(.vertical, 24)
                        .transition(.opacity)
                }
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                    rowView(row, voices: c.voices)
                        .transition(.opacity)
                        .onAppear {
                            // Near the end, the next page (Android LOAD_MORE_AHEAD, counted in rows here).
                            if i >= rows.count - 2 { model.loadMore() }
                        }
                }
                tail(c)
            }
            .frame(maxWidth: Self.maxWidth)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 16)
            // What comes in fades in (effects), and what's under it moves over on a spring.
            .animation(Yoin.spatial, value: c.voices.count)
            .animation(Yoin.spatial, value: c.references.count)
            .animation(Yoin.spatial, value: unfolded)
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder
    private func rowView(_ row: NeoDBVoiceRows.Row, voices: [NeoDBVoice]) -> some View {
        switch row.kind {
        case .full(let i):
            card(voices[i], index: i)
        case .lanes(let left, let right):
            HStack(alignment: .top, spacing: 8) {
                VStack(spacing: 8) { ForEach(left, id: \.self) { card(voices[$0], index: $0) } }
                    .frame(maxWidth: .infinity, alignment: .top)
                VStack(spacing: 8) { ForEach(right, id: \.self) { card(voices[$0], index: $0) } }
                    .frame(maxWidth: .infinity, alignment: .top)
            }
        }
    }

    private func card(_ voice: NeoDBVoice, index: Int) -> some View {
        NeoDBVoiceCard(voice: voice, scheme: scheme, lifted: index % 3 == 0, unfolded: unfolded.contains(voice.id)) {
            if voice.folded && !unfolded.contains(voice.id) {
                unfolded.insert(voice.id)
            } else if let url = voice.url {
                open(url)
            }
        }
        .transition(.opacity)
    }

    private func tail(_ c: NeoDBVoicesState.Content) -> some View {
        ZStack {
            if c.loadingMore {
                SkeletonBlock(color: scheme.surfaceContainerHigh, cornerRadius: 12)
                    .frame(width: 72, height: 8)
                    .transition(.opacity)
            } else if c.moreFailed {
                Button { model.loadMore() } label: {
                    Text("Retry")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 40)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .transition(.opacity)
            } else if !c.voices.isEmpty {
                Text("\(c.count) comments in all")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 40)
        .padding(.vertical, 8)
        .animation(Yoin.effects, value: c.loadingMore)
        .animation(Yoin.effects, value: c.moreFailed)
        .onAppear { model.loadMore() }
    }
}

/// The voices in rows: a long one takes the whole width; a run of short ones between them is laid out in
/// two lanes, each card going to the lane that is shorter so far (Android's staggered grid, whose lanes a
/// full-width item closes). Greedy in order, so a page appended later never moves the cards above it.
enum NeoDBVoiceRows {
    struct Row: Identifiable, Equatable {
        enum Kind: Equatable {
            case full(Int)
            case lanes([Int], [Int])
        }
        var id: String
        var kind: Kind
    }

    static func rows(_ voices: [NeoDBVoice]) -> [Row] {
        var out: [Row] = []
        var left: [Int] = [], right: [Int] = []
        var leftHeight = 0, rightHeight = 0
        var runID: String?
        func close() {
            if let id = runID, !(left.isEmpty && right.isEmpty) { out.append(Row(id: "run:" + id, kind: .lanes(left, right))) }
            left = []; right = []; leftHeight = 0; rightHeight = 0; runID = nil
        }
        for (i, v) in voices.enumerated() {
            if v.isLong {
                close()
                out.append(Row(id: "voice:" + v.id, kind: .full(i)))
            } else {
                if runID == nil { runID = v.id }
                let h = weight(v)
                if leftHeight <= rightHeight {
                    left.append(i); leftHeight += h
                } else {
                    right.append(i); rightHeight += h
                }
            }
        }
        close()
        return out
    }

    /// A card's height, roughly: its byline and padding, and a line per dozen characters.
    static func weight(_ v: NeoDBVoice) -> Int {
        let chars = v.folded ? 20 : v.text.count
        return 3 + (chars + 11) / 12
    }
}

private extension CoverScheme {
    /// The spread's other bars and the avatar dots: secondaryContainer in light; in dark it sits a few tones
    /// off the surface and all but disappears, so outline, which reads there and still stays under the accent.
    func voicesQuietFill(dark: Bool) -> Color { dark ? outline : secondaryContainer }
}

/// Five columns, one per star count; the tallest in the accent. Then the average, small.
private struct NeoDBVoicesSpread: View {
    var album: NeoDBAlbumVoices
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme
    @State private var shown = false

    private static let barMax: CGFloat = 92
    private static let barShape = UnevenRoundedRectangle(topLeadingRadius: 12, bottomLeadingRadius: 4,
                                                         bottomTrailingRadius: 4, topTrailingRadius: 12,
                                                         style: .continuous)

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !album.distribution.isEmpty {
                let top = max(album.distribution.max() ?? 1, 1)
                HStack(alignment: .top, spacing: 8) {
                    ForEach(Array(album.distribution.enumerated()), id: \.offset) { i, percent in
                        VStack(spacing: 6) {
                            // The bar stands in a slot of its own height, so the labels under it sit on one
                            // line in every column at any text size.
                            Self.barShape
                                .fill(percent == top ? scheme.primary : scheme.voicesQuietFill(dark: colorScheme == .dark))
                                .frame(height: max(Self.barMax * (shown ? CGFloat(percent) / CGFloat(top) : 0), 3))
                                .frame(height: Self.barMax, alignment: .bottom)
                            NeoDBGrade(rating: (i + 1) * 2, color: scheme.tertiary, size: 10)
                            Text(verbatim: "\(percent)%")
                                .yoinRole(Tokens.type.labelSmall)
                                .monospacedDigit()
                                .foregroundStyle(scheme.onSurfaceVariant)
                        }
                        .frame(maxWidth: .infinity)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text("\(i + 1) stars: \(percent)%"))
                    }
                }
            }
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                if let rating = album.rating {
                    Text(String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), rating))
                        .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                        .monospacedDigit()
                        .foregroundStyle(scheme.onSurface)
                }
                Text("\(album.ratingCount) ratings")
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear { withAnimation(Yoin.slowSpatial) { shown = true } }
    }
}

/// Other catalogues' scores (Discogs, MusicBrainz), each a tonal chip that opens its page.
private struct NeoDBVoicesReferences: View {
    var references: [NeoDBReferenceScore]
    var scheme: CoverScheme
    var open: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach(references, id: \.source) { ref in
                Button { open(ref.url) } label: {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(alignment: .lastTextBaseline, spacing: 0) {
                            Text(String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), ref.value))
                                .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                                .monospacedDigit()
                                .foregroundStyle(scheme.onSurface)
                            Text(verbatim: " /\(ref.scale)")
                                .yoinRole(Tokens.type.labelSmall)
                                .foregroundStyle(scheme.onSurfaceVariant)
                        }
                        Text(verbatim: ref.source)
                            .yoinRole(Tokens.type.labelSmall)
                            .foregroundStyle(scheme.onSurfaceVariant)
                    }
                    .frame(minWidth: 72, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 18, style: .continuous))
                    .contentShape(.rect(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(PressScale())
            }
        }
    }
}

/// One listener's words. Folded ones show their note (or "Folded by the writer") until tapped.
private struct NeoDBVoiceCard: View {
    var voice: NeoDBVoice
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme
    var lifted: Bool
    var unfolded: Bool
    var onTap: () -> Void

    var body: some View {
        let folded = voice.folded && !unfolded
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 10) {
                if folded {
                    Text(voice.foldNote ?? String(localized: "Folded by the writer. Tap to read"))
                        .yoinRole(Tokens.type.bodyMedium)
                        .lineSpacing(YoinType.lineSpacing(Tokens.type.bodyMedium))
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .transition(.opacity)
                } else {
                    if let title = voice.reviewTitle {
                        Text(title)
                            .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                            .foregroundStyle(scheme.onSurface)
                    }
                    if !voice.text.isEmpty {
                        let role = voice.isLong ? Tokens.type.bodyLarge : Tokens.type.bodyMedium
                        Text(voice.text)
                            .yoinRole(role)
                            .lineSpacing(YoinType.lineSpacing(role))
                            .foregroundStyle(scheme.onSurface)
                            .transition(.opacity)
                    }
                }
                byline
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 12)
            .background(lifted ? scheme.surfaceContainerHigh : scheme.surfaceContainer,
                        in: .rect(cornerRadius: 24, style: .continuous))
            .contentShape(.rect(cornerRadius: 24, style: .continuous))
        }
        .buttonStyle(PressScale())
        .sensoryFeedback(.selection, trigger: unfolded)
    }

    private var byline: some View {
        HStack(spacing: 8) {
            AsyncImage(url: voice.avatarUrl.flatMap(URL.init(string:)),
                       transaction: Transaction(animation: Yoin.effects)) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill().transition(.opacity)
                } else {
                    Color.clear
                }
            }
            .frame(width: 20, height: 20)
            .background(scheme.voicesQuietFill(dark: colorScheme == .dark))
            .clipShape(.circle)
            .accessibilityHidden(true)
            HStack(spacing: 6) {
                Text(voice.author)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .lineLimit(1)
                    .layoutPriority(1)
                if let host = voice.host {
                    Text(host)
                        .foregroundStyle(scheme.onSurfaceVariant.opacity(0.6))
                        .lineLimit(1)
                }
            }
            .yoinRole(Tokens.type.labelSmall)
            .frame(maxWidth: .infinity, alignment: .leading)
            if voice.reviewTitle != nil {
                Text(String(localized: "neoDBVoices.review", defaultValue: "Review"))
                    .yoinRole(Tokens.type.labelSmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            if let rating = voice.rating {
                NeoDBGrade(rating: rating, color: scheme.tertiary, size: 10)
            }
        }
    }
}

/// A NeoDB grade (1–10) as stars out of five: a filled star from Yoin Symbols beside the number.
struct NeoDBGrade: View {
    var rating: Int
    var color: Color
    var size: CGFloat

    var body: some View {
        HStack(spacing: 2) {
            YoinIcon("star_filled", size: size)
                .frame(width: size, height: size)
            Text(verbatim: Self.text(rating))
                .yoinRole(Tokens.type.labelSmall)
                .monospacedDigit()
        }
        .foregroundStyle(color)
    }

    /// 8 → "4", 7 → "3.5".
    static func text(_ rating: Int) -> String {
        rating % 2 == 0 ? "\(rating / 2)" : String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), Double(rating) / 2)
    }
}

/// The ways out, pinned over the scrolling words (glass, R1): NeoDB in the page's accent (the one final
/// action, R2), the other catalogues neutral beside each other. NeoDB's and Douban's links come with the
/// item, after the page is up: their buttons open on a spring and the rest gives way with them.
private struct NeoDBVoicesWaysOut: View {
    var scheme: CoverScheme
    var neoDBUrl: String?
    var doubanUrl: String?
    var rymUrl: String
    var aotyUrl: String
    var open: (String) -> Void

    private static let gap: CGFloat = 8

    var body: some View {
        GlassEffectContainer(spacing: Self.gap) {
            VStack(spacing: Self.gap) {
                if let neoDBUrl {
                    Button { open(neoDBUrl) } label: {
                        Text("See all on NeoDB")
                            .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(scheme.onPrimary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(.plain).pointerHighlight()
                    .glassEffect(.regular.tint(scheme.primary.opacity(0.85)).interactive(), in: .capsule)
                    .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .bottom)))
                }
                HStack(spacing: Self.gap) {
                    if let doubanUrl {
                        other(String(localized: "Douban")) { open(doubanUrl) }
                            .transition(.opacity.combined(with: .scale(scale: 0.6, anchor: .leading)))
                    }
                    other("RYM") { open(rymUrl) }
                    other("AOTY") { open(aotyUrl) }
                }
            }
        }
        .animation(Yoin.spatial, value: neoDBUrl != nil)
        .animation(Yoin.spatial, value: doubanUrl != nil)
    }

    private func other(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: label)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurface)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .contentShape(.capsule)
        }
        .buttonStyle(.plain).pointerHighlight()
        .glassEffect(.regular.tint(scheme.surfaceContainerHighest.opacity(0.52)).interactive(), in: .capsule)
    }
}

/// While NeoDB answers: quiet blocks in the page's tones where the spread and the first cards will be
/// (iOS load states: no spinner, no copy).
private struct NeoDBVoicesSkeleton: View {
    var scheme: CoverScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .bottom, spacing: 8) {
                ForEach([0.15, 0.3, 0.55, 1.0, 0.7], id: \.self) { f in
                    SkeletonBlock(color: scheme.surfaceContainerHigh, cornerRadius: 12)
                        .frame(height: 92 * f)
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 92, alignment: .bottom)
            .padding(.top, 8)
            SkeletonBlock(color: scheme.surfaceContainerHigh, cornerRadius: 8)
                .frame(width: 120, height: 24)
                .padding(.top, 34)
                .padding(.bottom, 8)
            SkeletonBlock(color: scheme.surfaceContainer, cornerRadius: 24).frame(height: 132)
            HStack(spacing: 8) {
                SkeletonBlock(color: scheme.surfaceContainer, cornerRadius: 24).frame(height: 96)
                SkeletonBlock(color: scheme.surfaceContainer, cornerRadius: 24).frame(height: 96)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: NeoDBVoicesView.maxWidth)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .accessibilityHidden(true)
    }
}

#if DEBUG
/// Debug stand-ins for screenshots without the network: `-neodbVoicesFake content|empty|notfound|failed`.
enum NeoDBVoicesDebug {
    static func service() -> (any NeoDBVoicesReading)? {
        guard let kind = UserDefaults.standard.string(forKey: "neodbVoicesFake") else { return nil }
        return Fake(kind: kind)
    }

    struct Fake: NeoDBVoicesReading {
        var kind: String

        func findAlbum(albumName: String, artist: String?) async throws -> NeoDBAlbumVoices? {
            try await Task.sleep(for: .milliseconds(600))
            switch kind {
            case "failed": throw URLError(.notConnectedToInternet)
            case "notfound": return nil
            default:
                return NeoDBAlbumVoices(itemUuid: "qa", itemUrl: "https://neodb.social/album/qa", title: albumName,
                                        rating: 8.7, ratingCount: 577, distribution: [1, 2, 10, 35, 52],
                                        doubanUrl: "https://music.douban.com/subject/1/",
                                        discogsUrl: nil, musicBrainzUrl: nil)
            }
        }

        func voices(itemUuid: String, page: Int) async throws -> NeoDBVoicePage {
            try await Task.sleep(for: .milliseconds(300))
            if kind == "empty" { return NeoDBVoicePage(voices: [], page: 1, pages: 1, count: 0) }
            let base = [
                NeoDBVoice(id: "1", author: "Everglow", handle: "everglow@neodb.social", avatarUrl: nil, rating: 9,
                           text: "很情绪，很动人", reviewTitle: nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil),
                NeoDBVoice(id: "2", author: "liz", handle: "liz@neodb.social", avatarUrl: nil, rating: 8,
                           text: "B+ 这绝对不是一张完美的专辑。但每一首都像是在凌晨三点写下的，带着一点点不肯睡去的倔强，副歌一出来就停不下来。",
                           reviewTitle: "爱人同质", folded: false, foldNote: nil, url: "https://neodb.social/review/qa",
                           createdAtMillis: nil),
                NeoDBVoice(id: "3", author: "Mori", handle: "mori@mastodon.social", avatarUrl: nil, rating: 7,
                           text: "Second half drags.", reviewTitle: nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil),
                NeoDBVoice(id: "4", author: "kai", handle: "kai@neodb.social", avatarUrl: nil, rating: nil,
                           text: "结尾那首……", reviewTitle: nil, folded: true, foldNote: "剧透", url: nil, createdAtMillis: nil),
                NeoDBVoice(id: "5", author: "Ana", handle: "ana@neodb.social", avatarUrl: nil, rating: 10,
                           text: "听了一整个夏天。", reviewTitle: nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil),
                NeoDBVoice(id: "6", author: "Theo", handle: "theo@neodb.social", avatarUrl: nil, rating: 6,
                           text: "Good, not great. The production is lovely though and the closer is worth the wait.",
                           reviewTitle: nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil),
                NeoDBVoice(id: "7", author: "阿青", handle: "aqing@neodb.social", avatarUrl: nil, rating: 9,
                           text: "鼓好听", reviewTitle: nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil),
            ]
            return NeoDBVoicePage(voices: base.map { var v = $0; v.id = "\(page)-\(v.id)"; return v },
                                  page: page, pages: 2, count: 14)
        }

        func searchUrl(albumName: String, artist: String?) async -> String {
            NeoDBVoicesParsing.neoDBSearchUrl(instance: NeoDBVoicesService.defaultInstance, albumName: albumName, artist: artist)
        }

        func referenceScores(_ album: NeoDBAlbumVoices) async -> [NeoDBReferenceScore] {
            try? await Task.sleep(for: .milliseconds(500))
            return [NeoDBReferenceScore(source: "Discogs", value: 4.31, scale: 5, votes: 900, url: "https://www.discogs.com/"),
                    NeoDBReferenceScore(source: "MusicBrainz", value: 4.5, scale: 5, votes: 12, url: "https://musicbrainz.org/")]
        }
    }
}
#endif
