import SwiftUI
import YoinShared

/*
 * Home on the demo phone (Android ui/tips/TipHomeScenes.kt): the header as Home lays it out (the greeting,
 * the real "Home" title, the account in use), Jump Back In's covers, and the idle bar's Home | Library
 * halves. A scene puts its own part in the header's corner or in place of the shelf.
 */

/// Home's page: [action] in the header's corner (the account in use, on Home), [feed] under the header.
struct DemoHomePage<Action: View, Feed: View>: View {
    var scheme: CoverScheme
    @ViewBuilder var action: () -> Action
    @ViewBuilder var feed: () -> Feed
    /// The title's own controller, never entered: the demo's Home is not being edited.
    @State private var edit = HomeEditController()

    init(scheme: CoverScheme, @ViewBuilder action: @escaping () -> Action, @ViewBuilder feed: @escaping () -> Feed) {
        self.scheme = scheme
        self.action = action
        self.feed = feed
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            // HomeView.feedScroll: 18 between the header and the sections, the feed's 16 margin.
            VStack(alignment: .leading, spacing: 18) {
                header
                // As tall as it is on Home's scrolling feed, not stretched to the screen's foot.
                feed().fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, DemoHomeMetrics.margin)
            .padding(.top, DemoPhoneMetrics.statusBar)
            // The screen's size whatever the feed holds: a shelf longer than the phone runs off its foot.
            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .topLeading)
            DemoShellBar(scheme: scheme)
                .padding(.horizontal, 14)
                .padding(.bottom, DemoHomeMetrics.barBottom)
                .demoTarget("bar")
        }
        .background(LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                                   startPoint: .top, endPoint: .bottom))
        .environment(\.homeScheme, scheme)
        .environment(\.homeMargin, DemoHomeMetrics.margin)
    }

    /// HomeView.header's row.
    private var header: some View {
        HStack(alignment: .center, spacing: 2) {
            VStack(alignment: .leading, spacing: 0) {
                Text(HomeView.greeting(at: .now))
                    .font(YoinFont.rounded(16, relativeTo: .subheadline))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(height: 24)
                EditTitleSwap(edit: edit, scheme: scheme)
                    .frame(height: 36)
            }
            .lineLimit(1)
            .padding(.trailing, Tokens.home.HEADER_TITLE_BREATHING.cg)
            Spacer(minLength: 0)
            action()
        }
        .frame(minHeight: 48)
        .padding(.top, 12)
        .demoTarget("header")
    }
}

extension DemoHomePage where Feed == DemoJumpBackIn {
    init(scheme: CoverScheme, @ViewBuilder action: @escaping () -> Action) {
        self.init(scheme: scheme, action: action) { DemoJumpBackIn() }
    }
}

enum DemoHomeMetrics {
    static let margin: CGFloat = 16
    /// Under the bar: the phone's home indicator band, cut down to the demo's size.
    static let barBottom: CGFloat = 14
}

/// Jump Back In's covers: every album, a 1×1 each, three to a row as the phone's shelf lays them
/// (HomeView.serverJumpBackIn).
struct DemoJumpBackIn: View {
    private let albums = TipDemo.albums + TipDemo.albums.reversed()

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
            HomeSectionTitle(String(localized: "Jump Back In"))
            ForEach(Array(stride(from: 0, to: albums.count, by: 3)), id: \.self) { row in
                HStack(alignment: .top, spacing: Tokens.home.JBI_COLUMN_GAP.cg) {
                    ForEach(row..<min(row + 3, albums.count), id: \.self) { i in
                        AlbumItem(album: albums[i], size: Tokens.home.JBI_COVER.cg)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .demoTarget("jbi")
    }
}

/// The phone's idle bar (ShellBar with nothing playing): Home | Library, labelled, and the Search circle,
/// in the bar's own faces and glass.
struct DemoShellBar: View {
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let brand = CoverScheme.brand(dark: colorScheme == .dark)
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 6) {
                half(.home, selected: true, brand: brand)
                half(.library, selected: false, brand: brand)
                YoinIcon("search", size: 24)
                    .foregroundStyle(Yoin.ink)
                    .labContrast("barUnselected")
                    .frame(width: 60, height: 60)
                    .labGlass("barUnselected", scheme: scheme, brand: brand, auto: scheme.tertiary, in: Circle())
                    .padding(.leading, 6)
            }
        }
        .frame(height: 60)
    }

    /// One half in the bar's glass (ShellBar.navButton: the accent tinted into the selected one's glass, or
    /// the Glass Lab surface).
    @ViewBuilder
    private func half(_ section: ShellSection, selected: Bool, brand: CoverScheme) -> some View {
        let lab = GlassLab.shared
        let accent = section == .home ? scheme.primary : scheme.secondary
        let face = ShellNavFace(section: section, selected: selected, labelled: true, scheme: scheme, fills: true)
        if selected && lab.extra("barSelectedStyle") >= 0.5 {
            face.glassEffect(.regular.tint(accent.opacity(lab.style("barSelected").tintOpacity)).interactive(), in: .capsule)
        } else {
            face.labGlass(selected ? "barSelected" : "barUnselected", scheme: scheme, brand: brand, auto: accent, in: Capsule())
        }
    }
}

/// The account in use, as Home's header shows it.
private struct DemoAccountAvatar: View {
    var profile: Profile
    var scheme: CoverScheme

    var body: some View {
        HeaderAccountAvatar(profile: profile, shape: TipDemo.shape(of: profile), fallback: scheme.primary,
                            onFallback: scheme.onPrimary, ring: scheme.surfaceContainer)
    }
}

// MARK: - Switch to Spotify

/// Opened in a non-Spotify account while the signed-in Spotify one plays: the header's pill (Home's own
/// SpotifyElsewherePill) shows the song beside the avatar; the finger taps it and Spotify's account is the
/// one in use.
struct SpotifyElsewhereDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var activeId = TipDemo.accounts[0].id
    @State private var shown = false

    private var accounts: [Profile] { TipDemo.accounts }
    private var spotify: Profile { accounts.first { $0.service == .spotify } ?? accounts[0] }
    private var active: Profile { accounts.first { $0.id == activeId } ?? accounts[0] }

    private var elsewhere: SpotifyElsewhere {
        SpotifyElsewhere(profileId: spotify.id, title: TipDemo.trackTitles[0], artist: TipDemo.albums[3].artist,
                         coverURL: nil, isPlaying: true)
    }

    private func switchToSpotify() {
        withAnimation(Yoin.spatial) {
            shown = false
            activeId = spotify.id
        }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.spatial) {
                activeId = accounts[0].id
                shown = false
            }
            try await DemoTiming.delay(700)
            // The app comes to the front: Spotify's song shows up top.
            withAnimation(Yoin.spatial) { shown = true }
            try await DemoTiming.delay(1_600)
            try await finger.moveTo("spotify-pill")
            try await finger.tap { switchToSpotify() }
            try await finger.lift()
            try await DemoTiming.delay(2_000)
        }) {
            DemoHomePage(scheme: scheme) {
                HStack(spacing: 2) {
                    if shown {
                        SpotifyElsewherePill(elsewhere: elsewhere, scheme: scheme, demoCover: TipDemo.albums[3].palette) {
                            switchToSpotify()
                        }
                        .demoTarget("spotify-pill")
                    }
                    DemoAccountAvatar(profile: active, scheme: scheme)
                }
            }
        }
    }
}

// MARK: - Lyric Cards

/// Lyric Cards: Home's own shelf under the header. The finger swipes on to the next song's line and back,
/// then taps a card to play from it.
struct LyricHighlightDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var position: String? = TipDemo.lyricCards[0].id

    private var cards: [LyricHighlightRow] { TipDemo.lyricCards }

    /// The pages the finger turns to, in order: on, on again, and one back.
    static let turns = [1, 2, 1]

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.spatial) { position = cards[0].id }
            try await DemoTiming.delay(900)
            var page = 0
            for target in Self.turns {
                // The finger goes the way the cards do: leftwards on to the next, rightwards back.
                let forward = target > page
                try await finger.moveTo("lyric", fx: forward ? 0.8 : 0.3, fy: 0.6)
                try await DemoTiming.delay(200)
                let end = finger.at("lyric", fx: forward ? 0.3 : 0.8, fy: 0.6)
                withAnimation(Yoin.spatial) { position = cards[target].id }
                try await finger.drag(toX: end.x, toY: end.y, ms: 420)
                page = target
                try await DemoTiming.delay(900)
            }
            try await finger.moveTo("lyric", fx: 0.4, fy: 0.5)
            try await DemoTiming.delay(250)
            try await finger.tap {}
            try await DemoTiming.delay(900)
            try await finger.lift()
            try await DemoTiming.delay(600)
        }) {
            DemoHomePage(scheme: scheme) {
                DemoAccountAvatar(profile: TipDemo.accounts[0], scheme: scheme)
            } feed: {
                HomeLyricCardsSection(cards: cards,
                                      demo: LyricCardsDemo(palettes: TipDemo.lyricPalettes, position: $position) { _ in })
                    .demoTarget("lyric")
            }
        }
    }
}
