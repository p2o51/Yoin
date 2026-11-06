import SwiftUI

/// Signed out: the sign-in page. Signed in: the window shell.
struct MacGate: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        Group {
            if model.session.isSignedIn && !model.addingAccount {
                MacShell()
            } else {
                MacSignInView()
            }
        }
        .frame(minWidth: 640, minHeight: 600)
        .animation(Yoin.spatial, value: model.session.isSignedIn)
        .onChange(of: model.session.accountKey) { model.accountDidChange() }
    }
}

/// One window grown by Yoin's adaptive rules: no sidebar, one floating bar, the detail page as a right
/// column (45 : 55, 24 pt handle) from 840 wide, Now Playing's chain on top.
struct MacShell: View {
    @Environment(MacModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let tint = MacTint.of(model.player.currentTrack?.coverURL, dark: scheme == .dark)
        GeometryReader { geo in
            let W = geo.size.width
            let contentW = model.contentWidth
            ZStack(alignment: .topLeading) {
                Yoin.surface
                LinearGradient(colors: [tint.primary.opacity(0.07), .clear], startPoint: .top, endPoint: .center)
                content(width: contentW)
                    .frame(width: contentW, height: geo.size.height)
                edgeFades(width: contentW, height: geo.size.height)
                if model.npMode != .full {
                    let wide = model.detailAsColumn
                    let pill = model.npMode != .panel
                    if let f = model.player.failure {
                        MacPlaybackFailureBanner(failure: f)
                            .frame(width: min(520, contentW - 48))
                            .frame(width: contentW, height: geo.size.height, alignment: .bottom)
                            .padding(.bottom, 22 + 76 + 12)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    MacBottomBar(wide: wide, showsPill: pill)
                        .frame(width: pill ? min(wide ? 820 : 640, contentW - 48) : nil)
                        .fixedSize(horizontal: !pill, vertical: false)
                        .frame(width: contentW, height: geo.size.height, alignment: .bottom)
                        .padding(.bottom, 22)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    searchButton(room: contentW - Self.trafficLightsWidth - 14)
                        .frame(width: contentW, alignment: .trailing)
                        .padding(.top, 10)
                        .offset(x: -14)
                        .transition(.opacity)
                }
                if model.npMode != .closed {
                    MacNowPlaying(full: model.npMode == .full)
                        .frame(width: model.npMode == .full ? W : MacModel.panelWidth, height: geo.size.height)
                        .frame(width: W, alignment: .trailing)
                        .transition(.move(edge: .trailing))
                        .zIndex(2)
                }
                if model.searchOpen {
                    MacSearchOverlay()
                        .frame(width: W, height: geo.size.height)
                        .transition(.opacity)
                        .zIndex(3)
                }
            }
            .onChange(of: W, initial: true) { _, w in model.windowWidth = w }
        }
        .ignoresSafeArea()
        .environment(\.macTint, tint)
        .animation(Yoin.effects, value: tint)
        .task { MacQA.applyScreen(model) }
    }

    @ViewBuilder private func content(width: CGFloat) -> some View {
        if model.detailAsColumn, let d = model.detail {
            let leftW = (width - MacModel.handleWidth) * model.splitFraction
            HStack(spacing: 0) {
                root.frame(width: leftW)
                SplitHandle(total: width)
                MacDetailPage(detail: d, asColumn: true)
                    .id(d)
                    .frame(width: width - leftW - MacModel.handleWidth)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        } else if let d = model.detail {
            MacDetailPage(detail: d, asColumn: false)
                .id(d)
                .transition(.move(edge: .trailing).combined(with: .opacity))
        } else {
            root
        }
    }

    @ViewBuilder private var root: some View {
        switch model.tab {
        case .home: MacHomeView()
        case .library: MacLibraryView()
        }
    }

    /// Content fades under the traffic lights and behind the bar instead of a hard cut.
    private func edgeFades(width: CGFloat, height: CGFloat) -> some View {
        VStack(spacing: 0) {
            LinearGradient(colors: [Yoin.surface.opacity(0.85), Yoin.surface.opacity(0)], startPoint: .top, endPoint: .bottom)
                .frame(height: 44)
            Spacer()
            LinearGradient(colors: [Yoin.surface.opacity(0), Yoin.surface.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                .frame(height: 110)
        }
        .frame(width: width, height: height)
        .allowsHitTesting(false)
    }

    /// The traffic lights and the gap after them: nothing in the shell's top bar may sit there.
    static let trafficLightsWidth: CGFloat = 78

    /// The search field, 200 wide when there's room right of the traffic lights; narrower it shrinks, and
    /// below 120 it's a round icon button.
    private func searchButton(room: CGFloat) -> some View {
        let compact = room < 120
        return Button { withAnimation(Yoin.effects) { model.searchOpen = true } } label: {
            HStack(spacing: 8) {
                YoinIcon("search", size: 16)
                if !compact {
                    Text("Search").font(.gsf(13)).lineLimit(1)
                    Spacer(minLength: 0)
                    Text("⌘K").font(.gsf(11)).foregroundStyle(Yoin.ink3)
                }
            }
            .foregroundStyle(Yoin.inkVariant)
            .padding(.leading, compact ? 0 : 12).padding(.trailing, compact ? 0 : 14)
            .frame(width: compact ? 34 : min(200, room), height: 34)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .capsule)
        .help("Search (⌘K)")
    }
}

/// The 24 pt drag handle between the root and the detail column.
private struct SplitHandle: View {
    var total: CGFloat
    @Environment(MacModel.self) private var model
    @State private var start: CGFloat?
    @State private var hover = false

    var body: some View {
        Capsule().fill(Yoin.ink3.opacity(hover || start != nil ? 0.7 : 0.45))
            .frame(width: 4, height: 44)
            .frame(width: MacModel.handleWidth)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .onHover { h in
                hover = h
                if h { NSCursor.resizeLeftRight.push() } else { NSCursor.pop() }
            }
            .gesture(
                DragGesture(minimumDistance: 1)
                    .onChanged { g in
                        let s = start ?? model.splitFraction
                        if start == nil { start = s }
                        let usable = total - MacModel.handleWidth
                        model.splitFraction = min(0.62, max(0.3, s + g.translation.width / usable))
                    }
                    .onEnded { _ in start = nil }
            )
    }
}
