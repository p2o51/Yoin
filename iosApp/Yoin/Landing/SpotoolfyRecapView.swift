import SwiftUI

// The Spotoolfy recap (Android ui/spotoolfy/SpotoolfyRecapScreen.kt, docs/spotoolfy-migration.md §6.2): two
// full-screen pages, swiped up. The cover wall with the note count, then the keyword burst with up to two lyric
// snippets and the card that leads on: "Join Yoin" from the welcome guide (it goes on to Spotify's connect step),
// "Done" from Settings. Each burst plays when its page settles, and again each time it comes back. Every move is
// a spring; Reduce Motion fades the finished layout in instead.

struct SpotoolfyRecapView: View {
    var recap: SpotoolfyRecap
    var scheme: CoverScheme
    /// Reopened from Settings: the last page ends with "Done" instead of joining.
    var fromSettings: Bool = false
    var onClose: () -> Void
    /// "Join Yoin" on the last page (the welcome guide's recap).
    var onJoin: () -> Void = {}
    /// Screenshots (`SpotoolfyRecapDemoHost`): the page it opens on, and a detail card shown once the burst has played.
    var startPage = 0
    var startDetail: RecapDetail?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var position: Int? = 0
    /// The page the scroll came to rest on: its burst plays.
    @State private var settled = 0
    @State private var detail: RecapDetail?

    static let pages = 2
    static let teal = Color(red: 0x71 / 255, green: 0xAC / 255, blue: 0xB3 / 255)
    static let topBar: CGFloat = 56

    var body: some View {
        GeometryReader { geo in
            let insets = geo.safeAreaInsets
            let full = CGSize(width: geo.size.width + insets.leading + insets.trailing,
                              height: geo.size.height + insets.top + insets.bottom)
            let content = EdgeInsets(top: insets.top + Self.topBar, leading: insets.leading,
                                     bottom: insets.bottom, trailing: insets.trailing)
            ZStack(alignment: .top) {
                ScrollView(.vertical) {
                    VStack(spacing: 0) {
                        RecapIntroPage(recap: recap, scheme: scheme, active: settled == 0, reduced: reduceMotion)
                            .padding(content)
                            .frame(width: full.width, height: full.height)
                            .id(0)
                        RecapMinePage(recap: recap, scheme: scheme, active: settled == 1, reduced: reduceMotion,
                                      fromSettings: fromSettings, onJoin: onJoin, onDone: onClose,
                                      onDetail: { d in withAnimation(Yoin.spatial) { detail = d } })
                            .padding(content)
                            .frame(width: full.width, height: full.height)
                            .id(1)
                    }
                    .scrollTargetLayout()
                }
                .scrollTargetBehavior(.paging)
                .scrollPosition(id: $position)
                .scrollIndicators(.hidden)
                .scrollDisabled(detail != nil)
                .onScrollPhaseChange { _, phase in
                    if phase == .idle { settled = position ?? 0 }
                }
                .onChange(of: settled) { _, page in if page != 1 { detail = nil } }

                RecapPageDots(current: position ?? 0, scheme: scheme)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                    .padding(.trailing, 6 + insets.trailing)

                RecapDetailLayer(detail: detail, scheme: scheme, bottom: insets.bottom) {
                    withAnimation(Yoin.spatialFast) { detail = nil }
                }

                topBar(page: (position ?? 0) + 1)
                    .padding(.top, insets.top)
                    .padding(.horizontal, max(insets.leading, insets.trailing))
            }
            .frame(width: full.width, height: full.height)
            .ignoresSafeArea()
        }
        .background(scheme.surface.ignoresSafeArea())
        .task {
            guard startPage != 0 || startDetail != nil else { return }
            position = startPage
            settled = startPage
            guard let startDetail else { return }
            try? await Task.sleep(for: RecapMotion.ms(2600))
            withAnimation(Yoin.spatial) { detail = startDetail }
        }
    }

    private func topBar(page: Int) -> some View {
        HStack(spacing: 0) {
            Button(action: onClose) {
                YoinIcon("close", size: 24)
                    .foregroundStyle(scheme.onSurface)
                    .frame(width: 48, height: 48)
                    .contentShape(.circle)
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("Close"))
            Circle().fill(Self.teal).frame(width: 8, height: 8)
            Text("Spotoolfy recap")
                .yoinRole(Tokens.type.titleSmall, weight: .semibold)
                .foregroundStyle(scheme.onSurface)
                .padding(.leading, 8)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            Text(verbatim: "\(page) / \(Self.pages)")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
                .monospacedDigit()
                .contentTransition(.numericText())
                .animation(Yoin.effects, value: page)
        }
        .padding(.leading, 4)
        .padding(.trailing, 16)
        .frame(height: Self.topBar)
    }
}

// MARK: Page 1 · the cover wall and the count

private struct RecapIntroPage: View {
    var recap: SpotoolfyRecap
    var scheme: CoverScheme
    var active: Bool
    var reduced: Bool

    /// Covers that have flown out to their places.
    @State private var launched = 0
    @State private var count: Double = 0
    @State private var ticks = 0

    private var covers: [String?] { SpotoolfyRecapLayout.wallCovers(recap.coverUrls) }
    private var shownCount: Int { recap.noteCount > 0 ? recap.noteCount : recap.ratedTrackCount }

    var body: some View {
        RecapWideOrTall { wall } text: { text }
            .sensoryFeedback(.impact(weight: .light), trigger: ticks)
            .task(id: active) { if active { await play() } }
    }

    private func play() async {
        let all = covers.count
        var quiet = Transaction()
        quiet.disablesAnimations = true
        withTransaction(quiet) {
            launched = 0
            count = reduced ? Double(shownCount) : 0
        }
        if reduced {
            withAnimation(Yoin.effects) { launched = all }
            return
        }
        withAnimation(RecapMotion.count) { count = Double(shownCount) }
        if all > 0 { ticks += 1 }
        let start = ContinuousClock.now
        for i in 0..<all {
            try? await Task.sleep(until: start + RecapMotion.ms(RecapMotion.coverStart + i * RecapMotion.coverStagger))
            if Task.isCancelled { return }
            withAnimation(Yoin.spatial) { launched = i + 1 }
        }
    }

    private var wall: some View {
        GeometryReader { geo in
            let tile: CGFloat = 58
            let w = geo.size.width, h = geo.size.height
            let spots = SpotoolfyRecapLayout.wall(count: covers.count, tile: tile, width: w, height: h)
            let tones = [scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer,
                         SpotoolfyRecapView.teal.opacity(0.55), scheme.surfaceContainerHighest]
            ZStack(alignment: .topLeading) {
                ForEach(Array(covers.enumerated()), id: \.offset) { i, url in
                    let out = reduced || i < launched
                    let spin: Double = i % 2 == 0 ? -30 : 30
                    coverTile(url, tone: tones[i % tones.count])
                        .frame(width: tile, height: tile)
                        .clipShape(.rect(cornerRadius: 12))
                        .scaleEffect(out ? 1 : RecapMotion.startScale)
                        .rotationEffect(.degrees(spots[i].tilt + (out ? 0 : spin)))
                        .position(out ? spots[i].center : CGPoint(x: w / 2, y: h / 2))
                        .animation(Yoin.effects) { $0.opacity(out ? 1 : 0) }
                }
            }
            .frame(width: w, height: h)
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func coverTile(_ url: String?, tone: Color) -> some View {
        if let url, let u = URL(string: url) {
            RemoteCover(ref: CoverRef(url: u), placeholder: tone)
        } else {
            Rectangle().fill(tone)
        }
    }

    private var text: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Circle().fill(SpotoolfyRecapView.teal).frame(width: 8, height: 8)
                Text("From Spotoolfy")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(scheme.onSurface)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(SpotoolfyRecapView.teal.opacity(0.18), in: .rect(cornerRadius: 14))
            Text("In Spotoolfy you wrote")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.primary)
                .padding(.top, 12)
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                RecapCountText(value: count)
                    .font(.gsf(88, .bold))
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .accessibilityLabel(Text(shownCount.formatted()))
                Text(unit)
                    .yoinRole(Tokens.type.headlineSmall, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                    .fixedSize()
            }
            Text(verbatim: SpotoolfyRecapView.statLine(recap))
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(scheme.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            if let range = SpotoolfyRecapView.range(recap) {
                Text(verbatim: range)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.top, 4)
            }
            HStack(spacing: 2) {
                YoinIcon("chevron_up", size: 18)
                Text("Swipe up").yoinRole(Tokens.type.labelMedium)
            }
            .foregroundStyle(scheme.onSurfaceVariant)
            .frame(maxWidth: .infinity)
            .padding(.top, 16)
            .padding(.bottom, 12)
            .accessibilityHidden(true)
        }
        .padding(.horizontal, 24)
    }

    private var unit: String {
        let one = shownCount == 1
        if recap.noteCount > 0 { return one ? String(localized: "note") : String(localized: "notes") }
        return one ? String(localized: "rating") : String(localized: "ratings")
    }
}

/// The big number counting up: an animatable view, so only the digits redraw while it counts.
private struct RecapCountText: View, Animatable {
    var value: Double
    var animatableData: Double {
        get { value }
        set { value = newValue }
    }

    var body: some View { Text(verbatim: Int(value.rounded()).formatted()) }
}

// MARK: Page 2 · keywords, lyrics, and what comes next

/// What the detail card shows.
enum RecapDetail: Equatable {
    case word(RecapKeyword)
    case lyric(RecapLyric)
    case note(RecapNote)
}

private struct RecapMinePage: View {
    var recap: SpotoolfyRecap
    var scheme: CoverScheme
    var active: Bool
    var reduced: Bool
    var fromSettings: Bool
    var onJoin: () -> Void
    var onDone: () -> Void
    var onDetail: (RecapDetail) -> Void

    @State private var launched: Set<Int> = []
    @State private var joinUp = false
    @State private var ticks = 0

    private var words: [RecapWord] { SpotoolfyRecapLayout.words(recap.keywords, emoji: recap.emoji) }
    private var lyrics: [RecapLyric] { recap.lyricSnippets }
    private var notes: [RecapNote] { recap.fallbackNotes }
    /// Too few words for a burst: the notes themselves are laid out.
    private var sparse: Bool { !notes.isEmpty || words.isEmpty }
    private var restCount: Int { sparse ? notes.count : words.count }

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width > geo.size.height && geo.size.width >= 600
            if wide {
                // Wide: the burst on the left, the words about it on the right.
                HStack(spacing: 0) {
                    area.padding(.vertical, 16).frame(maxWidth: .infinity, maxHeight: .infinity).layoutPriority(1.2)
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) { header; join }
                            .frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .leading)
                    }
                    .scrollIndicators(.hidden)
                    .frame(width: geo.size.width / 2.2)
                }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    area.padding(.top, 8).frame(maxWidth: .infinity, maxHeight: .infinity)
                    join
                }
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: ticks)
        .task(id: active) { if active { await play() } }
    }

    // The burst's rhythm (ms): when the first item leaves, the gap between items, when the lyric cards follow
    // the words, and when the join card rises after them. Capped so a full burst never makes the card wait.
    private var stagger: Int { sparse ? RecapMotion.noteStagger : RecapMotion.wordStagger }
    private var burstEnd: Int { RecapMotion.wordStart + Int(Double(min(restCount, 30) * stagger) * 0.6) }

    private func delay(_ i: Int) -> Int {
        i < lyrics.count ? burstEnd + i * RecapMotion.lyricStagger
            : RecapMotion.wordStart + (i - lyrics.count) * stagger
    }

    private func play() async {
        let n = lyrics.count + restCount
        var quiet = Transaction()
        quiet.disablesAnimations = true
        withTransaction(quiet) {
            launched = []
            joinUp = reduced
        }
        if reduced {
            withAnimation(Yoin.effects) { launched = Set(0..<n) }
            return
        }
        if n > 0 { ticks += 1 }
        let start = ContinuousClock.now
        for i in (0..<n).sorted(by: { delay($0) < delay($1) }) {
            try? await Task.sleep(until: start + RecapMotion.ms(delay(i)))
            if Task.isCancelled { return }
            withAnimation(Yoin.spatial) { _ = launched.insert(i) }
        }
        try? await Task.sleep(until: start + RecapMotion.ms(burstEnd + lyrics.count * RecapMotion.lyricStagger
                                                             + RecapMotion.joinAfter))
        if Task.isCancelled { return }
        withAnimation(Yoin.spatial) { joinUp = true }
    }

    // MARK: Header

    private var header: some View {
        let hasLyrics = !lyrics.isEmpty
        let eyebrow: String = switch (hasLyrics, sparse) {
        case (true, true): String(localized: "Notes and lyrics")
        case (true, false): String(localized: "Keywords and lyrics")
        case (false, true): String(localized: "Notes")
        case (false, false): String(localized: "Keywords")
        }
        let title: String = hasLyrics ? String(localized: "What you wrote, and what you kept")
            : sparse ? String(localized: "What you wrote") : String(localized: "Words you wrote most")
        let n = recap.noteCount
        let sub: String = if n == 0 {
            String(localized: "No notes, only ratings. They come along too.")
        } else if sparse || words.isEmpty {
            n == 1 ? String(localized: "1 note, too few for keywords, so here it is.")
                : String(localized: "\(n) notes, too few for keywords, so here they are.")
        } else {
            n == 1 ? String(localized: "Words from your 1 note. Tap one to see where you wrote it.")
                : String(localized: "Words from your \(n) notes. Tap one to see where you wrote it.")
        }
        return VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: eyebrow).yoinRole(Tokens.type.labelLarge).foregroundStyle(scheme.primary)
            Text(verbatim: title)
                .yoinRole(Tokens.type.headlineMedium, weight: .bold)
                .foregroundStyle(scheme.onSurface)
                .padding(.top, 2)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(verbatim: sub)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Burst

    private var area: some View {
        let words = sparse ? [] : self.words
        let notes = sparse ? self.notes : []
        return RecapBurstLayout(lyricCount: lyrics.count, notesMode: sparse, launched: launched) {
            ForEach(Array(lyrics.enumerated()), id: \.offset) { i, lyric in
                let out = launched.contains(i)
                let tilt: Double = i == 0 ? -2 : 2
                let spin: Double = i == 0 ? -18 : 18
                RecapLyricCard(lyric: lyric, scheme: scheme) { onDetail(.lyric(lyric)) }
                    .burstItem(out: out, rest: tilt, spin: spin)
            }
            ForEach(Array(words.enumerated()), id: \.offset) { i, word in
                RecapWordChip(word: word, scheme: scheme) {
                    if let k = word.keyword { onDetail(.word(k)) }
                }
                .burstItem(out: launched.contains(lyrics.count + i), rest: 0, spin: SpotoolfyRecapLayout.spin(i))
            }
            ForEach(Array(notes.enumerated()), id: \.offset) { i, note in
                RecapNoteCard(note: note, scheme: scheme) { onDetail(.note(note)) }
                    .burstItem(out: launched.contains(lyrics.count + i), rest: SpotoolfyRecapLayout.spin(i) / 8,
                               spin: SpotoolfyRecapLayout.spin(i) / 3)
            }
        }
        .padding(.horizontal, 16)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: summary))
    }

    private var summary: String {
        var parts: [String] = []
        if notes.isEmpty, !words.isEmpty {
            let top = ListFormatter.localizedString(byJoining: words.prefix(8).map(\.text))
            parts.append(String(localized: "Words you wrote most: \(top)."))
        } else {
            parts += notes.map(\.text)
        }
        if !lyrics.isEmpty {
            let kept = ListFormatter.localizedString(byJoining: lyrics.map(\.lyric))
            parts.append(String(localized: "Lyrics you kept: \(kept)."))
        }
        return parts.joined(separator: " ")
    }

    // MARK: Join

    private var join: some View {
        VStack(alignment: .leading, spacing: 0) {
            if fromSettings {
                Text("They're all in your Yoin: Now Playing's notes, the album scrapbook, Memories and Rediscover.")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                RecapButton(title: String(localized: "Done"), filled: false, scheme: scheme, action: onDone)
                    .padding(.top, 12)
            } else {
                Text("Bring them into Yoin")
                    .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                Text("Once Spotify is connected, these notes and ratings go into your Yoin and turn up in Now Playing, the album scrapbook, Memories and Rediscover.")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
                RecapButton(title: String(localized: "Join Yoin"), filled: true, scheme: scheme, action: onJoin)
                    .padding(.top, 12)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.surfaceContainer, in: .rect(cornerRadius: 28))
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .offset(y: joinUp ? 0 : 44)
        .opacity(joinUp ? 1 : 0)
    }
}

private struct RecapButton: View {
    var title: String
    var filled: Bool
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(verbatim: title)
                .yoinRole(Tokens.type.titleSmall, weight: .semibold)
                .foregroundStyle(filled ? scheme.onPrimary : scheme.onSecondaryContainer)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(filled ? scheme.primary : scheme.secondaryContainer, in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
    }
}

private extension View {
    /// A burst item: out at its place, upright at its resting tilt; or at the area's centre (the layout puts it
    /// there), small and turned, until it flies. The fade runs on the effects spring.
    func burstItem(out: Bool, rest: Double, spin: Double) -> some View {
        scaleEffect(out ? 1 : RecapMotion.startScale)
            .rotationEffect(.degrees(rest + (out ? 0 : spin)))
            .animation(Yoin.effects) { $0.opacity(out ? 1 : 0) }
    }
}

/// The page's area: lyric cards in the top-left and bottom-right corners, words spiralling out from the centre
/// around them, or note cards stacked left and right when there are too few words. An item not launched yet
/// sits at the centre; one with no room left is put out of sight.
private struct RecapBurstLayout: Layout {
    var lyricCount: Int
    var notesMode: Bool
    var launched: Set<Int>

    static let gap: CGFloat = 6
    static let step: CGFloat = 4.2
    static let noteWidth: CGFloat = 230

    struct Cache {
        var key: [CGFloat] = []
        var boxes: [RecapBox?] = []
    }

    func makeCache(subviews: Subviews) -> Cache { Cache() }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        let w = bounds.width, h = bounds.height
        let lyricCount = min(self.lyricCount, subviews.count)
        let noteWidth = min(Self.noteWidth, w)
        let sizes: [CGSize] = subviews.indices.map { i in
            if i < lyricCount {
                return subviews[i].sizeThatFits(ProposedViewSize(width: w * 0.8, height: h / 2))
            }
            if notesMode {
                return CGSize(width: noteWidth, height: subviews[i].sizeThatFits(ProposedViewSize(width: noteWidth, height: nil)).height)
            }
            let s = subviews[i].sizeThatFits(.unspecified)
            return CGSize(width: min(s.width, w), height: s.height)
        }
        let key = [w, h] + sizes.flatMap { [$0.width, $0.height] }
        if cache.key != key {
            cache.key = key
            cache.boxes = Self.boxes(sizes, lyricCount: lyricCount, notesMode: notesMode, width: w, height: h)
        }
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        for i in subviews.indices {
            let size = ProposedViewSize(sizes[i])
            guard i < cache.boxes.count, let box = cache.boxes[i] else {
                subviews[i].place(at: CGPoint(x: -10_000, y: -10_000), anchor: .center, proposal: size)
                continue
            }
            let at = launched.contains(i) ? CGPoint(x: bounds.minX + box.midX, y: bounds.minY + box.midY) : center
            subviews[i].place(at: at, anchor: .center, proposal: size)
        }
    }

    static func boxes(_ sizes: [CGSize], lyricCount: Int, notesMode: Bool, width w: CGFloat, height h: CGFloat) -> [RecapBox?] {
        let lyricBoxes = sizes.prefix(lyricCount).enumerated().map { i, s in
            RecapBox(x: i == 0 ? 0 : w - s.width, y: i == 0 ? 0 : h - s.height, width: s.width, height: s.height)
        }
        let rest = Array(sizes.dropFirst(lyricCount))
        if !notesMode {
            return lyricBoxes + SpotoolfyRecapLayout.spiral(rest, width: w, height: h, obstacles: lyricBoxes,
                                                           gap: gap, step: step)
        }
        var y = lyricBoxes.first.map { $0.height + gap * 2 } ?? 0
        let limit = h - (lyricBoxes.count > 1 ? lyricBoxes[1].height + gap * 2 : 0)
        let notes: [RecapBox?] = rest.enumerated().map { i, s in
            if y + s.height > limit { return nil }
            let box = RecapBox(x: i % 2 == 0 ? 0 : w - s.width, y: y, width: s.width, height: s.height)
            y += s.height * 0.8 + gap
            return box
        }
        return lyricBoxes + notes
    }
}

private struct RecapWordChip: View {
    var word: RecapWord
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        let (container, content): (Color, Color) = switch word.tier {
        case .largest: (scheme.primary, scheme.onPrimary)
        case .large: (scheme.primaryContainer, scheme.onPrimaryContainer)
        case .medium: (scheme.tertiaryContainer, scheme.onTertiaryContainer)
        case .small: (scheme.secondaryContainer, scheme.onSecondaryContainer)
        }
        let (size, height): (CGFloat, CGFloat) = switch word.tier {
        case .largest: (28, 50)
        case .large: (21, 40)
        case .medium: (17, 34)
        case .small: (14, 30)
        }
        Button(action: action) {
            HStack(alignment: .firstTextBaseline, spacing: 5) {
                Text(verbatim: word.text).font(.gsf(size, .semibold))
                Text(verbatim: "\(word.count)").font(.gsf(size * 0.6, .semibold)).opacity(0.75)
            }
            .lineLimit(1)
            .foregroundStyle(content)
            .padding(.horizontal, 14)
            .frame(height: height)
            .background(container, in: .capsule)
            .contentShape(.capsule)
            .fixedSize()
        }
        .buttonStyle(PressScale())
        .disabled(word.keyword == nil)
    }
}

private struct RecapLyricCard: View {
    var lyric: RecapLyric
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: SpotoolfyRecapView.quote(lyric.lyric))
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .fixedSize(horizontal: false, vertical: true)
                if let by = SpotoolfyRecapView.songLine(lyric.trackTitle, lyric.artist) {
                    Text(verbatim: by)
                        .yoinRole(Tokens.type.labelMedium)
                        .lineLimit(1)
                        .opacity(0.75)
                }
            }
            .multilineTextAlignment(.leading)
            .foregroundStyle(scheme.onTertiaryContainer)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(scheme.tertiaryContainer, in: .rect(cornerRadius: 20))
            .contentShape(.rect(cornerRadius: 20))
        }
        .buttonStyle(PressScale())
    }
}

private struct RecapNoteCard: View {
    var note: RecapNote
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: note.text)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(3)
                Text(verbatim: SpotoolfyRecapView.writtenFor(note))
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .lineLimit(1)
            }
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 20))
            .contentShape(.rect(cornerRadius: 20))
        }
        .buttonStyle(PressScale())
    }
}

// MARK: Detail card

/// A word's notes, a lyric with its note, or a note in full, over a scrim; tap outside to put it away.
private struct RecapDetailLayer: View {
    var detail: RecapDetail?
    var scheme: CoverScheme
    var bottom: CGFloat
    var onDismiss: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            if detail != nil {
                scheme.scrim.opacity(0.32)
                    .contentShape(.rect)
                    .onTapGesture(perform: onDismiss)
                    .transition(.opacity)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel(Text("Close"))
            }
            if let detail {
                card(detail)
                    .transition(.offset(y: 240).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
    }

    private func card(_ detail: RecapDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                switch detail {
                case .word(let k):
                    title(k.text, k.count == 1 ? String(localized: "Used once") : String(localized: "Used \(k.count) times"))
                    ForEach(Array(k.notes.enumerated()), id: \.offset) { _, note in
                        snippet(note.text, SpotoolfyRecapView.writtenFor(note))
                    }
                case .lyric(let l):
                    title(l.trackTitle ?? String(localized: "Unknown track"), l.artist)
                    snippet(SpotoolfyRecapView.quote(l.lyric),
                            String(localized: "Kept on \(SpotoolfyRecapView.date(l.capturedAt))"))
                    if !l.note.isEmpty { snippet(l.note, String(localized: "What you wrote with it")) }
                case .note(let n):
                    title(n.trackTitle ?? String(localized: "Unknown track"), n.artist)
                    snippet(n.text, SpotoolfyRecapView.date(n.writtenAt))
                }
            }
            .padding(20)
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(maxWidth: 560)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxHeight: 520)
        .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 28))
        .padding(12)
        .padding(.bottom, bottom)
    }

    private func title(_ text: String, _ sub: String?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: text).yoinRole(Tokens.type.titleLarge, weight: .semibold).foregroundStyle(scheme.onSurface)
            if let sub, !sub.isEmpty {
                Text(verbatim: sub).yoinRole(Tokens.type.bodyMedium).foregroundStyle(scheme.onSurfaceVariant)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private func snippet(_ text: String, _ source: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: text)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(scheme.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            Text(verbatim: source).yoinRole(Tokens.type.labelMedium).foregroundStyle(scheme.onSurfaceVariant)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.surfaceBright, in: .rect(cornerRadius: 18))
        .accessibilityElement(children: .combine)
    }
}

// MARK: Shared pieces

/// The vertical page dots on the trailing edge.
private struct RecapPageDots: View {
    var current: Int
    var scheme: CoverScheme

    var body: some View {
        VStack(spacing: 6) {
            ForEach(0..<SpotoolfyRecapView.pages, id: \.self) { page in
                let on = page == current
                Capsule()
                    .fill(on ? scheme.primary : scheme.outlineVariant)
                    .frame(width: 6, height: on ? 22 : 6)
                    .animation(Yoin.spatial, value: on)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Page halves: the visual above the text on a phone, beside it on a wide window, visual on the left.
private struct RecapWideOrTall<Visual: View, Words: View>: View {
    @ViewBuilder var visual: Visual
    @ViewBuilder var text: Words

    var body: some View {
        GeometryReader { geo in
            if geo.size.width > geo.size.height && geo.size.width >= 600 {
                HStack(spacing: 0) {
                    visual.padding(.horizontal, 20).padding(.vertical, 16).frame(maxWidth: .infinity, maxHeight: .infinity)
                    ScrollView {
                        text.frame(maxWidth: .infinity, minHeight: geo.size.height, alignment: .leading)
                    }
                    .scrollIndicators(.hidden)
                    .frame(width: geo.size.width / 2.2)
                }
            } else {
                VStack(spacing: 0) {
                    visual.padding(.horizontal, 20).padding(.vertical, 16).frame(maxWidth: .infinity, maxHeight: .infinity)
                    text
                }
            }
        }
    }
}

enum RecapMotion {
    static let startScale: CGFloat = 0.3
    static let coverStart = 140
    static let coverStagger = 22
    static let wordStart = 180
    static let wordStagger = 26
    static let noteStagger = 70
    static let lyricStagger = 140
    static let joinAfter = 160

    /// The count-up: critically damped and unhurried (Android stiffness VeryLow), so the number lands as the
    /// covers do.
    @MainActor static var count: Animation {
        Animation.interpolatingSpring(mass: 1, stiffness: 50, damping: 2 * 50.0.squareRoot())
            .speed(LaunchConfig.animationSpeed)
    }

    @MainActor static func ms(_ value: Int) -> Duration { .milliseconds(Int(Double(value) / LaunchConfig.animationSpeed)) }
}

// MARK: Text

extension SpotoolfyRecapView {
    /// "for 32 songs by 10 artists, every one of them rated": one sentence, never a dotted list.
    static func statLine(_ r: SpotoolfyRecap) -> String {
        let songs = r.trackCount == 1 ? String(localized: "1 song") : String(localized: "\(r.trackCount) songs")
        let artists = r.artistCount == 1 ? String(localized: "1 artist") : String(localized: "\(r.artistCount) artists")
        let base = r.artistCount > 0 ? String(localized: "for \(songs) by \(artists)") : String(localized: "for \(songs)")
        if r.ratedTrackCount <= 0 { return base }
        if r.ratedTrackCount >= r.trackCount { return base + String(localized: ", every one of them rated") }
        return base + String(localized: ", \(r.ratedTrackCount) of them rated")
    }

    static func range(_ r: SpotoolfyRecap) -> String? {
        guard r.firstRecordAt > 0 else { return nil }
        let a = date(r.firstRecordAt), b = date(r.lastRecordAt)
        return a == b ? a : String(localized: "\(a) to \(b)")
    }

    static func date(_ ms: Int64) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .omitted)
    }

    /// A lyric in quotes: “…” in English, 「…」 in Chinese.
    static func quote(_ text: String) -> String {
        String(localized: "recap.lyricQuote \(text)", defaultValue: "“\(text)”")
    }

    static func songLine(_ title: String?, _ artist: String?) -> String? {
        let title = title.flatMap { $0.isEmpty ? nil : $0 }
        let artist = artist.flatMap { $0.isEmpty ? nil : $0 }
        if let title, let artist { return String(localized: "“\(title)” by \(artist)") }
        if let title { return "“\(title)”" }
        return artist
    }

    static func writtenFor(_ note: RecapNote) -> String {
        let title = note.trackTitle ?? String(localized: "Unknown track")
        return String(localized: "Written for “\(title)” on \(date(note.writtenAt))")
    }
}
