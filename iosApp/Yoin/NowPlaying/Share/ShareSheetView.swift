import SwiftUI
import UIKit
import YoinShared

/*
 * The share sheet (Android ShareSheet.kt, owner 2026-11-25, prototype round 2): 歌词 / 关于 / 笔记 on top (the
 * Now Playing tabs' words and look; it opens on the tab it came from), then 版式 — a live thumbnail of each
 * layout — then the preview in a fixed box (the sheet never changes height on a switch), 卡片 / Story, the four
 * tones of the tab's role, 保存 / 分享. Each tab remembers its last layout across opens (ShareLayoutStore). On a
 * wide window (iPad, ≥ 840 × 480) the sheet is two panes: the preview large on the left (up to 1.5×), the
 * controls on the right with larger thumbnails in two rows and Save / Share at the bottom.
 */

private enum SaveStatus { case idle, saving, saved, failed }

struct ShareSheetView: View {
    var item: ShareSheetItem
    var scheme: CoverScheme
    var store = ShareLayoutStore()
    /// The sheet's window, to choose one column or two panes (nil: the key window's).
    var window: CGSize? = nil
    /// DEBUG launch hooks: the format and tone slot it opens on.
    var initialFormat: ShareFormat = .card
    var initialToneSlot = 0
    var onShared: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @State private var render: ShareRender
    @State private var tab: ShareTab
    @State private var layouts: [ShareTab: ShareLayout] = [:]
    @State private var format: ShareFormat = .card
    @State private var toneSlot = 0
    @State private var saveStatus = SaveStatus.idle
    @State private var sharing = false
    /// Where the Share button sits (window coordinates), read when it is tapped: the iPad popover's anchor.
    @State private var anchorBox = AnchorBox()
    @State private var ticks = 0
    @State private var saved = 0
    @State private var failed = 0

    init(item: ShareSheetItem, scheme: CoverScheme, store: ShareLayoutStore = ShareLayoutStore(),
         window: CGSize? = nil, initialFormat: ShareFormat = .card, initialToneSlot: Int = 0,
         onShared: @escaping () -> Void = {}) {
        self.item = item
        self.scheme = scheme
        self.store = store
        self.window = window
        self.initialFormat = initialFormat
        self.initialToneSlot = initialToneSlot
        self.onShared = onShared
        let tabs = item.render.tabs
        _render = State(initialValue: item.render)
        _tab = State(initialValue: tabs.contains(item.initialTab) ? item.initialTab : (tabs.first ?? item.initialTab))
        // Each tab's layout: what it last used (when this song offers it), else its first.
        var picked: [ShareTab: ShareLayout] = [:]
        for t in tabs {
            if let l = YoinShareCards.shared.resolveLayout(content: item.render.content, tab: t,
                                                           remembered: store.layout(for: t)) { picked[t] = l }
        }
        _layouts = State(initialValue: picked)
        _format = State(initialValue: initialFormat)
        _toneSlot = State(initialValue: min(max(initialToneSlot, 0), 3))
    }

    private var tabs: [ShareTab] { render.tabs }
    private var layout: ShareLayout? { layouts[tab] }
    /// The tone is a slot (0–3) kept across tabs: the lyrics' fourth is Mist, About and Note's Paper.
    private var tone: ShareTone { YoinShareCards.shared.tone(role: tab.role, slot: Int32(toneSlot)) }

    private var windowSize: CGSize { window ?? Self.keyWindowSize() }
    private var wide: Bool { windowSize.width >= Self.wideMinWidth && windowSize.height >= Self.wideMinHeight }

    var body: some View {
        Group {
            if let layout {
                if wide { wideBody(layout) } else { phoneBody(layout) }
            }
        }
        .background(scheme.surfaceContainer.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Share as image"))
        .sensoryFeedback(.selection, trigger: ticks)
        .sensoryFeedback(.success, trigger: saved)
        .sensoryFeedback(.error, trigger: failed)
        .task { await loadCovers() }
        #if DEBUG
        .task { await tour() }
        #endif
    }

    // MARK: Layouts

    private func phoneBody(_ layout: ShareLayout) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                tabRow
                layoutLabel.padding(.top, 12)
                thumbs(thumbWidth: Self.thumbWidth, rows: 1)
                preview(layout, box: CGSize(width: max(windowSize.width - 48, 1),
                                            height: windowSize.height * Self.previewHeightFraction), maxScale: 1)
                    .padding(.top, 16)
                formatToggle.padding(.top, 16)
                swatches.padding(.top, 12)
                actions.padding(.top, 16)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private func wideBody(_ layout: ShareLayout) -> some View {
        let height = windowSize.height * Self.widePaneHeightFraction
        let width = min(Self.wideMaxWidth, windowSize.width - 64)
        let paneWidth = (width - 64 - 40) / 2
        return HStack(alignment: .top, spacing: 40) {
            preview(layout, box: CGSize(width: paneWidth, height: height - 24), maxScale: Self.widePreviewMaxScale)
                .frame(width: paneWidth, height: height)
            VStack(alignment: .leading, spacing: 0) {
                tabRow
                layoutLabel.padding(.top, 16)
                thumbs(thumbWidth: Self.wideThumbWidth, rows: 2)
                formatToggle.padding(.top, 20)
                swatches.padding(.top, 12)
                Spacer(minLength: 16)
                actions
            }
            .frame(width: paneWidth, height: height, alignment: .topLeading)
        }
        .padding(.horizontal, 32)
        .padding(.top, 28)
        .padding(.bottom, 12)
        .frame(width: width)
    }

    // MARK: Tabs

    /// 歌词 / 关于 / 笔记 as the Now Playing text tabs: the picked one bold and stretched, the others at half.
    private var tabRow: some View {
        HStack(spacing: 16) {
            ForEach(tabs, id: \.self) { t in
                let on = t == tab
                Button { pick(tab: t) } label: {
                    Text(verbatim: ShareStrings.tab(t))
                        .font(.gsf(Tokens.type.titleMedium.size.cg, on ? .bold : .medium))
                        .foregroundStyle(scheme.onSurface)
                        .opacity(on ? 1 : 0.5)
                        .scaleEffect(x: on ? 1.08 : 1, y: 1, anchor: .leading)
                        .padding(.vertical, 6)
                        .contentShape(.rect)
                }
                .buttonStyle(PressScale())
                .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
                .accessibilityIdentifier("shareTab:\(t.name)")
                .animation(Yoin.spatial, value: on)
            }
        }
    }

    private var layoutLabel: some View {
        Text("Layout")
            .yoinRole(Tokens.type.labelMedium)
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.bottom, 8)
    }

    // MARK: Thumbnails

    /// 版式: each layout the tab offers as a live thumbnail of the image (in the format and tone picked) with its
    /// name; the picked one wears a 2 pt primary ring and its name in primary. Two rows on the wide sheet; one row
    /// scrolls sideways if it must.
    private func thumbs(thumbWidth: CGFloat, rows: Int) -> some View {
        let offered = render.layouts(tab)
        let perRow = max(1, Int((Double(offered.count) / Double(rows)).rounded(.up)))
        let chunks = stride(from: 0, to: offered.count, by: perRow).map { Array(offered[$0..<min($0 + perRow, offered.count)]) }
        return VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(chunks.enumerated()), id: \.offset) { _, row in
                if rows == 1 {
                    ScrollView(.horizontal, showsIndicators: false) { thumbRow(row, width: thumbWidth) }
                        .scrollClipDisabled()
                } else {
                    thumbRow(row, width: thumbWidth)
                }
            }
        }
    }

    private func thumbRow(_ row: [ShareLayout], width: CGFloat) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ForEach(row, id: \.self) { l in thumb(l, width: width) }
        }
    }

    private func thumb(_ l: ShareLayout, width: CGFloat) -> some View {
        let on = l == layout
        let height = width * Self.thumbAspect
        return Button { pick(layout: l) } label: {
            VStack(spacing: 4) {
                ShareThumbnail(image: ShareImage(render: render, layout: l, format: format, tone: tone, scheme: scheme),
                               size: CGSize(width: width, height: height))
                    .background(scheme.surfaceContainerHigh)
                    .clipShape(RoundedRectangle(cornerRadius: Self.thumbCorner, style: .continuous))
                    .padding(Self.thumbRingInset)
                    .overlay {
                        RoundedRectangle(cornerRadius: Self.thumbCorner + Self.thumbRingInset, style: .continuous)
                            .strokeBorder(on ? scheme.primary : .clear, lineWidth: 2)
                    }
                Text(verbatim: ShareStrings.layout(l))
                    .font(.gsf(Tokens.type.labelMedium.size.cg, on ? .bold : .medium))
                    .foregroundStyle(on ? scheme.primary : scheme.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(width: width + Self.thumbRingInset * 2)
            .animation(Yoin.effects, value: on)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(Text(verbatim: ShareStrings.layout(l)))
        .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
        .accessibilityIdentifier("shareThumb:\(l.name)")
    }

    // MARK: Preview

    /// The image itself in a fixed box for every tab, layout and format (Android: the sheet's height jumped on
    /// each switch): it scales to fit inside, rounded only here (the PNG is a rectangle).
    private func preview(_ layout: ShareLayout, box: CGSize, maxScale: CGFloat) -> some View {
        ZStack {
            SharePreview(image: ShareImage(render: render, layout: layout, format: format, tone: tone, scheme: scheme),
                         box: box, maxScale: maxScale,
                         corner: format == .story ? CGFloat(Tokens.shape.CARD) : ShareMetrics.panel)
                .id("\(layout.name)/\(format.name)")
                .transition(.opacity)
        }
        .frame(width: box.width, height: box.height)
        .frame(maxWidth: .infinity)
        .animation(Yoin.effects, value: "\(layout.name)/\(format.name)")
        .accessibilityIdentifier("sharePreview")
    }

    // MARK: Format and tones

    /// Card / Story, as the Now Playing tab group's pills.
    private var formatToggle: some View {
        HStack(spacing: 8) {
            ForEach([ShareFormat.card, ShareFormat.story], id: \.self) { f in
                let on = f == format
                Button { pick(format: f) } label: {
                    Text(f == .card ? String(localized: "Card") : String(localized: "Story"))
                        .font(.gsf(Tokens.type.labelLarge.size.cg, on ? .bold : .medium))
                        .foregroundStyle(on ? scheme.onPrimary : scheme.onPrimaryContainer)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(on ? scheme.primary : scheme.primaryContainer, in: Capsule())
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
                .animation(Yoin.effects, value: on)
            }
        }
        .frame(height: 44)
    }

    /// The role's four tones as swatches of the card colour; the picked one squares off and wears a check.
    private var swatches: some View {
        HStack(spacing: 4) {
            ForEach(Array(tab.role.tones().enumerated()), id: \.offset) { slot, t in
                let c = t.colors(scheme, role: tab.role)
                let on = slot == toneSlot
                let shape = RoundedRectangle(cornerRadius: on ? Self.swatchSelectedCorner : Self.swatch / 2,
                                             style: .continuous)
                Button { pick(toneSlot: slot) } label: {
                    shape.fill(c.card)
                        // Paper is the lowest surface: a ring of its ink keeps it from vanishing into the sheet.
                        .overlay { if t == .paper { shape.strokeBorder(c.content, lineWidth: 2) } }
                        .frame(width: Self.swatch, height: Self.swatch)
                        .overlay {
                            if on {
                                YoinIcon("check", size: 20).foregroundStyle(c.content)
                                    .transition(.scale(scale: 0.6).combined(with: .opacity))
                            }
                        }
                        .frame(width: Self.swatchTouch, height: Self.swatchTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(Text(verbatim: t.label))
                .accessibilityAddTraits(on ? [.isSelected, .isButton] : .isButton)
                .animation(Yoin.spatial, value: on)
            }
        }
        .padding(.leading, -6)
    }

    // MARK: Actions

    private var actions: some View {
        HStack(spacing: 12) {
            Button(action: save) {
                HStack(spacing: 8) {
                    YoinIcon(saveStatus == .saved ? "download_done" : "download", size: 24)
                    Text(saveLabel).yoinRole(Tokens.type.labelLarge)
                        .contentTransition(.opacity)
                }
                .foregroundStyle(scheme.onSecondaryContainer)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(scheme.secondaryContainer, in: Capsule())
                .contentShape(.capsule)
                .animation(Yoin.effects, value: saveStatus)
            }
            .buttonStyle(PressScale())
            .disabled(saveStatus == .saving)
            .accessibilityIdentifier("shareSave")
            Button(action: share) {
                HStack(spacing: 8) {
                    YoinIcon("share", size: 24)
                    Text("Share").yoinRole(Tokens.type.labelLarge)
                }
                .foregroundStyle(scheme.onPrimary)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(scheme.primary, in: Capsule())
                .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
            .disabled(sharing)
            .background(AnchorReader(anchor: anchorBox))
            .accessibilityIdentifier("shareShare")
        }
    }

    private var saveLabel: String {
        switch saveStatus {
        case .idle: String(localized: "Save")
        case .saving: String(localized: "Saving…")
        case .saved: String(localized: "Saved")
        case .failed: String(localized: "Try again")
        }
    }

    // MARK: Picks

    private func pick(tab t: ShareTab) {
        guard t != tab else { return }
        ticks += 1
        withAnimation(Yoin.spatial) { tab = t }
        saveStatus = .idle
    }

    private func pick(layout l: ShareLayout) {
        guard l != layout else { return }
        ticks += 1
        layouts[l.tab] = l
        store.set(l, for: l.tab)
        saveStatus = .idle
    }

    private func pick(format f: ShareFormat) {
        guard f != format else { return }
        ticks += 1
        format = f
        saveStatus = .idle
    }

    private func pick(toneSlot slot: Int) {
        guard slot != toneSlot else { return }
        ticks += 1
        withAnimation(Yoin.effects) { toneSlot = slot }
        saveStatus = .idle
    }

    private var exportImage: ShareImage? {
        layout.map { ShareImage(render: render, layout: $0, format: format, tone: tone, scheme: scheme) }
    }

    /// Lyrics' image saved or shared: Home's 「歌词一句」 keeps the lines as the listener's pick.
    private func keep() { if tab == .lyrics { item.onKept?() } }

    private func save() {
        guard saveStatus != .saving, saveStatus != .saved, let image = exportImage else { return }
        saveStatus = .saving
        Task {
            let ok = await ShareExport.save(image, title: render.song.title)
            saveStatus = ok ? .saved : .failed
            if ok { saved += 1; keep() } else { failed += 1 }
        }
    }

    private func share() {
        guard !sharing, let image = exportImage else { return }
        sharing = true
        let caption = YoinShareCards.shared.caption(title: render.song.title, artist: render.song.artist)
        guard let file = ShareExport.file(image, title: render.song.title),
              ShareExport.present(file: file, caption: caption, anchor: anchorBox.rect, completion: { completed in
                  sharing = false
                  guard completed else { return }
                  keep()
                  onShared()
                  dismiss()
              })
        else {
            sharing = false
            PlaylistActions.shared.show(String(localized: "Couldn't share the image"))
            return
        }
    }

    #if DEBUG
    /// `-shareTour YES` (screen recordings): every tab, layout, format and tone in turn, as a listener would pick.
    private func tour() async {
        guard UserDefaults.standard.bool(forKey: "shareTour") else { return }
        func step() async { try? await Task.sleep(for: .milliseconds(1100)) }
        await step()
        for t in tabs {
            pick(tab: t)
            await step()
            for l in render.layouts(t) {
                pick(layout: l)
                await step()
            }
            pick(format: format == .card ? .story : .card)
            await step()
            pick(toneSlot: (toneSlot + 1) % 4)
            await step()
            pick(toneSlot: 3)
            await step()
            pick(format: .card)
            pick(toneSlot: 0)
            await step()
        }
    }
    #endif

    /// The covers the images draw, loaded once as images (`ImageRenderer` doesn't wait for a load).
    private func loadCovers() async {
        if render.cover == nil, let url = render.song.coverArtUrl.flatMap(URL.init(string:)) {
            render.cover = await ShareContentBuilder.cover(url: url, album: nil)
        }
        if render.mentionCovers.isEmpty {
            let covers = await ShareContentBuilder.mentionCovers(render.content.note)
            if !covers.isEmpty { render.mentionCovers = covers }
        }
    }

    // MARK: Metrics

    static func keyWindowSize() -> CGSize {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
        return window?.bounds.size ?? CGSize(width: 390, height: 844)
    }

    /// Whether a window this size gets the two-pane sheet.
    static func isWide(_ window: CGSize) -> Bool { window.width >= wideMinWidth && window.height >= wideMinHeight }

    /// The two-pane sheet's size in a window this size.
    static func wideSize(_ window: CGSize) -> CGSize {
        CGSize(width: min(wideMaxWidth, window.width - 64), height: window.height * widePaneHeightFraction + 40)
    }

    /// On a phone the preview takes at most this share of the window, so the controls stay in reach.
    static let previewHeightFraction: CGFloat = 0.45
    static let thumbWidth: CGFloat = 70
    static let wideThumbWidth: CGFloat = 112
    static let thumbAspect: CGFloat = 88 / 70
    static let thumbCorner: CGFloat = 12
    static let thumbRingInset: CGFloat = 4
    static let swatch: CGFloat = 36
    static let swatchTouch: CGFloat = 48
    static let swatchSelectedCorner: CGFloat = 10
    /// Expanded: a two-pane sheet this wide at most, this share of the window tall; the preview may grow 1.5×.
    static let wideMinWidth: CGFloat = 840
    static let wideMinHeight: CGFloat = 480
    static let wideMaxWidth: CGFloat = 1040
    static let widePaneHeightFraction: CGFloat = 0.82
    static let widePreviewMaxScale: CGFloat = 1.5
}

/// The image at its own size, scaled to fit [box] (never above [maxScale]), clipped round after the scale. One
/// layout pass ([FitLayout] sizes it, [View.fitScaled] draws it at that scale): no measuring through state.
private struct SharePreview: View {
    var image: ShareImage
    var box: CGSize
    var maxScale: CGFloat
    var corner: CGFloat

    var body: some View {
        FitLayout(box: box, maxScale: maxScale) {
            image.fixedSize().fitScaled(box: box, maxScale: maxScale)
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// As big as its child drawn at the shared fit scale; the child is laid out at its own size, centred.
private struct FitLayout: Layout {
    var box: CGSize
    var maxScale: CGFloat

    /// `YoinShareCards.fitScale` for a child [size] in [box].
    static func scale(_ size: CGSize, box: CGSize, maxScale: CGFloat) -> CGFloat {
        CGFloat(YoinShareCards.shared.fitScale(
            childWidth: Int32(size.width.rounded()), childHeight: Int32(size.height.rounded()),
            maxWidth: Int32(box.width.rounded(.down)), maxHeight: Int32(box.height.rounded(.down)),
            maxScale: Float(maxScale)))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let child = subviews.first?.sizeThatFits(.unspecified) ?? .zero
        let s = Self.scale(child, box: box, maxScale: maxScale)
        return CGSize(width: child.width * s, height: child.height * s)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let child = subviews.first else { return }
        let size = child.sizeThatFits(.unspecified)
        child.place(at: CGPoint(x: bounds.midX, y: bounds.midY), anchor: .center, proposal: ProposedViewSize(size))
    }
}

extension View {
    /// Drawn at the scale [FitLayout] sized it for.
    fileprivate func fitScaled(box: CGSize, maxScale: CGFloat) -> some View {
        visualEffect { content, proxy in
            content.scaleEffect(FitLayout.scale(proxy.size, box: box, maxScale: maxScale))
        }
    }
}

/// The image at its own size, scaled to the thumbnail's width (a card: its top shows) or height (a story: whole).
private struct ShareThumbnail: View {
    var image: ShareImage
    var size: CGSize

    var body: some View {
        let story = image.format == .story
        let scale = story ? size.height / ShareMetrics.story.height : size.width / ShareMetrics.cardWidth
        image
            .fixedSize()
            .scaleEffect(scale, anchor: story ? .top : .topLeading)
            .frame(width: size.width, height: size.height, alignment: story ? .top : .topLeading)
            .clipped()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// Holds a view's frame in its window, read only when needed (no state change per layout).
final class AnchorBox {
    weak var view: UIView?
    var rect: CGRect? { view.flatMap { v in v.window.map { v.convert(v.bounds, to: $0) } } }
}

/// A plain UIView behind a control, so [AnchorBox] can read where it is.
private struct AnchorReader: UIViewRepresentable {
    let anchor: AnchorBox

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.isUserInteractionEnabled = false
        anchor.view = v
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) { anchor.view = uiView }
}
