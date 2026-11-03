import SwiftUI
import UIKit

// MARK: - Model

/// Glass variants SwiftUI exposes. `none` = no glass: a plain fill of the tint.
enum GlassVariant: String, Codable, CaseIterable, Sendable {
    case regular, clear, none
}

/// Color roles a surface can be tinted with. `auto` = the role Android uses
/// for that element (e.g. primary for selected Home, tertiary for Library).
enum ToneRole: String, Codable, CaseIterable, Sendable {
    case auto
    case primary, primaryContainer, secondary, secondaryContainer, tertiary, tertiaryContainer
    case surface, surfaceContainer, surfaceContainerHighest, surfaceVariant, background
    case brandPrimary, brandTertiary

    func color(in s: CoverScheme, brand: CoverScheme, auto: Color) -> Color {
        switch self {
        case .auto: auto
        case .primary: s.primary
        case .primaryContainer: s.primaryContainer
        case .secondary: s.secondary
        case .secondaryContainer: s.secondaryContainer
        case .tertiary: s.tertiary
        case .tertiaryContainer: s.tertiaryContainer
        case .surface: s.surface
        case .surfaceContainer: s.surfaceContainer
        case .surfaceContainerHighest: s.surfaceContainerHighest
        case .surfaceVariant: s.surfaceVariant
        case .background: s.background
        case .brandPrimary: brand.primary
        case .brandTertiary: brand.tertiary
        }
    }
}

/// One glass surface's look.
struct SurfaceStyle: Codable, Equatable, Sendable {
    var variant: GlassVariant = .regular
    var tintOn = true
    var role: ToneRole = .auto
    var tintOpacity: Double
    var interactive = true
    var cornerRadius: Double? = nil
    var shadowOpacity: Double = 0
    var shadowRadius: Double = 12
    var shadowY: Double = 6
    /// Shadow under the icon/label on this surface, for contrast.
    var contrastShadow: Double = 0
}

/// Glass Lab (debug): live, persisted glass parameters per surface. Defaults
/// are the shipped values, so nothing changes until a control is touched.
/// Open with `-glassLab 1` or a three-finger long-press.
@MainActor
@Observable
final class GlassLab {
    static let shared = GlassLab()

    struct Surface: Identifiable {
        let id: String
        let title: String
        let defaults: SurfaceStyle
        /// Our own (non-glass) layer parameters for this surface: id, label, range, default.
        let extras: [(String, String, ClosedRange<Double>, Double)]
    }

    static let surfaces: [Surface] = [
        Surface(id: "barUnselected", title: "Bottom bar · unselected buttons",
                defaults: SurfaceStyle(tintOn: false, tintOpacity: 0), extras: []),
        Surface(id: "barSelected", title: "Bottom bar · selected button",
                defaults: SurfaceStyle(tintOn: false, tintOpacity: 0.85, contrastShadow: 0),
                extras: [("barSelectedStyle", "Style: 0 accent icon (Apple) · 1 tinted glass", 0...1, 0),
                         ("barSelectionPill", "Accent style: selection pill strength", 0...1, 0.55)]),
        Surface(id: "miniPlayer", title: "Mini player capsule",
                defaults: SurfaceStyle(tintOn: false, tintOpacity: 0),
                extras: [("washOpacity", "Progress wash opacity", 0...1, 0.25),
                         ("washAmplitude", "Wash wave amplitude (pt)", 0...10, 4)]),
        Surface(id: "transport", title: "Now Playing · play buttons",
                defaults: SurfaceStyle(tintOpacity: 0.55, shadowOpacity: 0.18, shadowRadius: 12, shadowY: 6,
                                       contrastShadow: 0.22),
                extras: [("containerSpacing", "GlassEffectContainer spacing", 0...16, 3),
                         ("lyricsUnder", "Lyrics strength under the buttons", 0...1, 0.7)]),
        Surface(id: "heart", title: "Now Playing · heart",
                defaults: SurfaceStyle(tintOpacity: 0.55, shadowOpacity: 0.18, shadowRadius: 10, shadowY: 5),
                extras: []),
        Surface(id: "ratingTrack", title: "Now Playing · rating track",
                defaults: SurfaceStyle(role: .surfaceVariant, tintOpacity: 0.3, interactive: false),
                extras: [("ratingFill", "Rating fill opacity", 0...1, 1)]),
        Surface(id: "npPills", title: "Now Playing · Queue / Devices / Write",
                defaults: SurfaceStyle(variant: .none, role: .primary, tintOpacity: 1, interactive: false),
                extras: []),
        Surface(id: "npBackground", title: "Now Playing · background (v4)",
                defaults: SurfaceStyle(role: .background, tintOpacity: 0.5, interactive: false, cornerRadius: 44),
                extras: [("ambientStrength", "This album's color", 0...1, 0.55),
                         ("ambientBlur", "Color blur", 0...160, 90),
                         ("neighbourStrength", "Previous / next album colors", 0...1, 1)]),
        Surface(id: "homeCards", title: "Home · cover-color cards",
                defaults: SurfaceStyle(role: .auto, tintOpacity: 0.6, interactive: false, cornerRadius: 16),
                extras: [("coverBlur", "Cover blur behind the card", 0...60, 26),
                         ("coverStrength", "Cover strength behind the card", 0...1, 0.85)]),
    ]

    /// 0 = system, 1 = light, 2 = dark.
    var appearance = 0 { didSet { save() } }
    var styles: [String: SurfaceStyle] = [:] { didSet { save() } }
    var extras: [String: Double] = [:] { didSet { save() } }
    var panelOpen = false
    var panelAtTop = false
    var panelCollapsed = false

    private static let key = "glassLab.v1"

    private init() {
        resetValues()
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            appearance = saved.appearance
            styles.merge(saved.styles) { _, new in new }
            extras.merge(saved.extras) { _, new in new }
        }
        panelOpen = UserDefaults.standard.bool(forKey: "glassLab")
        if UserDefaults.standard.bool(forKey: "barTinted") {
            suppressSave = true
            extras["barSelectedStyle"] = 1
            suppressSave = false
        }
    }

    private struct Saved: Codable {
        var appearance: Int
        var styles: [String: SurfaceStyle]
        var extras: [String: Double]
    }

    private func resetValues() {
        var s: [String: SurfaceStyle] = [:]
        var e: [String: Double] = [:]
        for surface in Self.surfaces {
            s[surface.id] = surface.defaults
            for x in surface.extras { e[x.0] = x.3 }
        }
        styles = s
        extras = e
    }

    func reset() {
        resetValues()
        appearance = 0
    }

    /// Launch-arg overrides (screenshots) are applied without saving.
    private var suppressSave = false

    private func save() {
        guard !suppressSave else { return }
        guard let data = try? JSONEncoder().encode(Saved(appearance: appearance, styles: styles, extras: extras))
        else { return }
        UserDefaults.standard.set(data, forKey: Self.key)
    }

    func style(_ id: String) -> SurfaceStyle { styles[id] ?? SurfaceStyle(tintOpacity: 0) }
    func extra(_ id: String) -> Double { extras[id] ?? 0 }

    var colorScheme: ColorScheme? {
        switch appearance {
        case 1: .light
        case 2: .dark
        default: nil
        }
    }

    /// Full JSON; also written to Documents/glass-lab.json.
    func export() -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        let json = (try? enc.encode(Saved(appearance: appearance, styles: styles, extras: extras)))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? json.write(to: dir.appendingPathComponent("glass-lab.json"), atomically: true, encoding: .utf8)
        }
        UIPasteboard.general.string = json
        return json
    }
}

/// Yoin's own scheme (Android DefaultColorScheme: YoinSeedArgb 0xFF7B3FE4;
/// iOS has no wallpaper seed).
extension CoverScheme {
    static func brand(dark: Bool) -> CoverScheme { CoverScheme(seedArgb: 0xFF7B_3FE4, dark: dark) }
}

// MARK: - Applying a style

extension View {
    /// Applies a Glass Lab surface: glass variant / plain fill, tint role and
    /// opacity, interactive, colored shadow.
    @ViewBuilder
    func labGlass<S: Shape>(_ id: String, scheme: CoverScheme, brand: CoverScheme,
                            auto: Color, in shape: S) -> some View {
        let st = GlassLab.shared.style(id)
        let tint = st.role.color(in: scheme, brand: brand, auto: auto)
        let shadowColor = tint.opacity(st.shadowOpacity)
        Group {
            switch st.variant {
            case .none:
                self.background(shape.fill(tint.opacity(st.tintOn ? st.tintOpacity : 0)))
            case .regular, .clear:
                let base: Glass = st.variant == .clear ? .clear : .regular
                let tinted = st.tintOn ? base.tint(tint.opacity(st.tintOpacity)) : base
                self.glassEffect(st.interactive ? tinted.interactive() : tinted, in: shape)
            }
        }
        .shadow(color: shadowColor, radius: st.shadowRadius, y: st.shadowY)
    }

    /// The contrast shadow under an icon or label on a Glass Lab surface.
    func labContrast(_ id: String) -> some View {
        shadow(color: .black.opacity(GlassLab.shared.style(id).contrastShadow), radius: 1.5, y: 0.5)
    }
}

// MARK: - Panel

/// Floating, draggable, collapsible Glass Lab panel. Dock it at the top while
/// tuning the bottom bar.
struct GlassLabPanel: View {
    @Environment(AppModel.self) private var model
    @State private var drag: CGSize = .zero
    @State private var settled: CGSize = .zero
    @State private var expandedSurface: String? = "transport"
    private var lab: GlassLab { GlassLab.shared }

    var body: some View {
        @Bindable var lab = lab
        GeometryReader { geo in
            VStack(spacing: 0) {
                header
                if !lab.panelCollapsed {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            globals
                            Text("Apple doesn't expose refraction, dispersion or glass thickness; these are all the knobs SwiftUI has, plus our own layers.")
                                .font(.gsf(12))
                                .foregroundStyle(.secondary)
                            ForEach(GlassLab.surfaces) { surface in
                                surfaceGroup(surface)
                            }
                            HStack {
                                Button("Reset to defaults", role: .destructive) { lab.reset() }
                                Spacer()
                                Button(exported ? "Exported" : "Export JSON") { _ = lab.export(); exported = true }
                            }
                            .font(.gsf(14, .semibold))
                            .padding(.vertical, 8)
                        }
                        .padding(14)
                    }
                    .frame(height: min(geo.size.height * 0.42, 420))
                }
            }
            .frame(width: min(geo.size.width - 24, 400))
            .background(.regularMaterial, in: .rect(cornerRadius: 24, style: .continuous))
            .glassEffect(.regular, in: .rect(cornerRadius: 24, style: .continuous))
            .offset(x: settled.width + drag.width, y: settled.height + drag.height)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: lab.panelAtTop ? .top : .bottom)
            .padding(.top, lab.panelAtTop ? 54 : 0)
            .padding(.bottom, lab.panelAtTop ? 0 : 90)
            .animation(Yoin.spatial, value: lab.panelAtTop)
            .animation(Yoin.spatial, value: lab.panelCollapsed)
        }
    }

    @State private var exported = false

    private var header: some View {
        @Bindable var lab = lab
        return HStack(spacing: 10) {
            Capsule().fill(.secondary).frame(width: 30, height: 4)
            Text("Glass Lab").font(.gsf(15, .semibold))
            Spacer()
            Button(lab.panelAtTop ? "Dock bottom" : "Dock top") {
                lab.panelAtTop.toggle(); settled = .zero
            }
            Button(lab.panelCollapsed ? "Open" : "Fold") { lab.panelCollapsed.toggle() }
            Button("Close") { lab.panelOpen = false }
        }
        .font(.gsf(13, .medium))
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contentShape(.rect)
        .gesture(
            DragGesture()
                .onChanged { drag = $0.translation }
                .onEnded { v in
                    settled.width += v.translation.width
                    settled.height += v.translation.height
                    drag = .zero
                }
        )
    }

    private var globals: some View {
        @Bindable var lab = lab
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: 10) {
            Picker("Appearance", selection: $lab.appearance) {
                Text("System").tag(0); Text("Light").tag(1); Text("Dark").tag(2)
            }
            .pickerStyle(.segmented)
            Picker("Page", selection: Binding(
                get: { model.npExpanded ? 1 : 0 },
                set: { model.setNowPlaying(expanded: $0 == 1) })) {
                Text("Home").tag(0); Text("Now Playing").tag(1)
            }
            .pickerStyle(.segmented)
            Toggle("Playing", isOn: $model.playback.isPlaying)
            Toggle("Nothing playing", isOn: Binding(
                get: { model.isIdle },
                set: { v in withAnimation(Yoin.spatial) { model.isIdle = v; if v { model.npExpanded = false } } }))
            Toggle("Short NP layout (Android reference phone)", isOn: $model.npForceShort)
            HStack {
                Text("Progress").font(.gsf(14))
                Slider(value: Binding(
                    get: { Double(model.playback.positionSec) / Double(model.playback.track.durationSec) },
                    set: { model.playback.positionSec = Int($0 * Double(model.playback.track.durationSec)) }))
            }
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(model.library.albums()) { album in
                        Button { model.play(album) } label: {
                            CoverArt(palette: album.palette, cornerRadius: 6, seed: album.seed)
                                .frame(width: 40, height: 40)
                                .overlay {
                                    if album.id == model.playback.album.id {
                                        RoundedRectangle(cornerRadius: 6).strokeBorder(.primary, lineWidth: 2)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
        .font(.gsf(14))
    }

    private func surfaceGroup(_ surface: GlassLab.Surface) -> some View {
        let open = expandedSurface == surface.id
        return VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation(Yoin.spatialFast) { expandedSurface = open ? nil : surface.id } } label: {
                HStack {
                    Text(surface.title).font(.gsf(14, .semibold))
                    Spacer()
                    YoinIcon(open ? "chevron_up" : "chevron_down", size: 16)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            if open {
                SurfaceControls(id: surface.id, extras: surface.extras)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct SurfaceControls: View {
    let id: String
    let extras: [(String, String, ClosedRange<Double>, Double)]
    private var lab: GlassLab { GlassLab.shared }

    private func binding<T>(_ kp: WritableKeyPath<SurfaceStyle, T>) -> Binding<T> {
        Binding(get: { lab.style(id)[keyPath: kp] },
                set: { var s = lab.style(id); s[keyPath: kp] = $0; lab.styles[id] = s })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Glass", selection: binding(\.variant)) {
                ForEach(GlassVariant.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            Toggle("Tint", isOn: binding(\.tintOn))
            Picker("Tint role", selection: binding(\.role)) {
                ForEach(ToneRole.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            slider("Tint opacity", binding(\.tintOpacity), 0...1)
            Toggle("Interactive", isOn: binding(\.interactive))
            if lab.style(id).cornerRadius != nil {
                slider("Corner radius", Binding(get: { lab.style(id).cornerRadius ?? 0 },
                                                set: { var s = lab.style(id); s.cornerRadius = $0; lab.styles[id] = s }),
                       0...60)
            }
            slider("Shadow opacity", binding(\.shadowOpacity), 0...0.8)
            slider("Shadow radius", binding(\.shadowRadius), 0...40)
            slider("Shadow y", binding(\.shadowY), -10...20)
            slider("Icon / text contrast shadow", binding(\.contrastShadow), 0...0.8)
            ForEach(extras, id: \.0) { x in
                slider(x.1, Binding(get: { lab.extra(x.0) }, set: { lab.extras[x.0] = $0 }), x.2)
            }
        }
        .font(.gsf(13))
    }

    private func slider(_ label: String, _ value: Binding<Double>, _ range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(label)
                Spacer()
                Text(String(format: range.upperBound > 2 ? "%.0f" : "%.2f", value.wrappedValue))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            Slider(value: value, in: range)
        }
    }
}

// MARK: - Three-finger long-press opener

/// Installs a three-finger long-press on the key window that toggles the panel.
struct GlassLabGestureInstaller: UIViewRepresentable {
    final class Coordinator: NSObject {
        @MainActor @objc func fire(_ g: UILongPressGestureRecognizer) {
            if g.state == .began { GlassLab.shared.panelOpen.toggle() }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.isUserInteractionEnabled = false
        DispatchQueue.main.async {
            guard let window = v.window else { return }
            let g = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.fire(_:)))
            g.numberOfTouchesRequired = 3
            g.minimumPressDuration = 0.6
            g.cancelsTouchesInView = false
            window.addGestureRecognizer(g)
        }
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
