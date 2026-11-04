import SwiftUI
import UIKit
import YoinShared

// MARK: - Model

/// Glass variants SwiftUI exposes. `none` = no glass: a plain fill of the
/// tint. `material` = a standard material (`.regularMaterial`) with the tint
/// over it, the HIG's choice for the content layer.
enum GlassVariant: String, Codable, CaseIterable, Sendable {
    case regular, clear, material, none
}

/// Glass Lab presets (Apple's HIG: glass only for the floating controls
/// layer, no glass in the content layer, avoid tinting several controls,
/// avoid content resting under glass).
enum GlassPreset: String, Codable, CaseIterable, Sendable {
    case current = "Current"
    case balanced = "Balanced"
    case appleHIG = "Apple HIG"
    /// Gemini 4 Argon's hierarchy proposal (2026-11-04, iosApp/docs/glass-hierarchy-gemini.md):
    /// one chromatic accent per plane, neutral surface-tinted floors on the
    /// other floating controls, calmer stages under them.
    case gemini = "Gemini"
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

extension SurfaceStyle {
    /// A surface of the shared Balanced preset (YoinTokens.Glass).
    init(_ t: YoinTokens.GlassSurface) {
        self.init(variant: GlassVariant(rawValue: t.variant) ?? .regular, tintOn: t.tintOn,
                  role: ToneRole(rawValue: t.role) ?? .auto, tintOpacity: t.tintOpacity,
                  interactive: t.interactive, cornerRadius: t.cornerRadius?.doubleValue,
                  shadowOpacity: t.shadowOpacity, shadowRadius: t.shadowRadius, shadowY: t.shadowY,
                  contrastShadow: t.contrastShadow)
    }
}

/// Glass Lab (debug): live, persisted glass parameters per surface. The
/// default is the Balanced preset; Current and Apple HIG stay for comparison.
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
        Surface(id: "transportPrimary", title: "Now Playing · PAUSE",
                defaults: SurfaceStyle(tintOpacity: 0.55, shadowOpacity: 0.18, shadowRadius: 12, shadowY: 6,
                                       contrastShadow: 0.22),
                extras: [("containerSpacing", "GlassEffectContainer spacing", 0...16, 3),
                         ("lyricsUnder", "Lyrics under the buttons (0 = fade out above them)", 0...1, 0.7)]),
        Surface(id: "transportSecondary", title: "Now Playing · Next / Prev / Repeat",
                defaults: SurfaceStyle(tintOpacity: 0.55, shadowOpacity: 0.18, shadowRadius: 12, shadowY: 6,
                                       contrastShadow: 0.22),
                extras: []),
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
    var preset: GlassPreset = .balanced { didSet { save() } }
    /// Motion: false = the current iOS springs (default until 51 decides),
    /// true = Android's springs from the shared YoinTokens.Motion.
    var sharedMotion = UserDefaults.standard.bool(forKey: "glassLab.sharedMotion") {
        didSet { UserDefaults.standard.set(sharedMotion, forKey: "glassLab.sharedMotion") }
    }
    var panelOpen = false
    var panelAtTop = false
    var panelCollapsed = false

    /// v2: Balanced became the default (51, 2026-11-03), so older saved
    /// values (Current) don't carry over.
    /// v3: 51's 11-03 night decisions (tinted bar selection, glass rating
    /// track and NP pills) change Balanced again; saved v2 values would hide them.
    private static let key = "glassLab.v3"

    private init() {
        suppressSave = true
        apply(.balanced)
        suppressSave = false
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            appearance = saved.appearance
            preset = saved.preset ?? .balanced
            styles.merge(saved.styles) { _, new in new }
            extras.merge(saved.extras) { _, new in new }
        }
        panelOpen = UserDefaults.standard.bool(forKey: "glassLab")
        if let name = UserDefaults.standard.string(forKey: "preset") {
            suppressSave = true
            apply(name == "hig" ? .appleHIG : name == "balanced" ? .balanced : name == "gemini" ? .gemini : .current)
            suppressSave = false
        }
        if UserDefaults.standard.bool(forKey: "barTinted") {
            suppressSave = true
            extras["barSelectedStyle"] = 1
            suppressSave = false
        }
        // Screenshots: `-detailTopBar 0|1|2`.
        if let mode = UserDefaults.standard.string(forKey: "detailTopBar").flatMap(Double.init) {
            suppressSave = true
            extras["detailTopBar"] = mode
            suppressSave = false
        }
        // Screenshots: `-libraryFilterStyle 0|1|2` (LibraryFilterRow.Style).
        if let mode = UserDefaults.standard.string(forKey: "libraryFilterStyle").flatMap(Double.init) {
            suppressSave = true
            extras["libraryFilterStyle"] = mode
            suppressSave = false
        }
    }

    private struct Saved: Codable {
        var appearance: Int
        var preset: GlassPreset?
        var styles: [String: SurfaceStyle]
        var extras: [String: Double]
    }

    /// Extras that aren't a glass surface's: the album page's pulled-up top
    /// bar, 0 = the cover's spectrum (Android, default), 1 = Liquid Glass,
    /// 2 = the system's progressive scroll-edge blur.
    /// Gemini preset extras (neutral in every other preset):
    /// darkTintMultiplier scales every glass tint in dark mode,
    /// sheetPresenterDim dims Now Playing under the Queue sheet,
    /// searchChromeTint tints the Library / Search search field and ✕
    /// with surfaceContainerHighest so they group with the chip row.
    static let pageExtras: [(String, Double)] = [
        ("detailTopBar", 0), ("darkTintMultiplier", 1), ("sheetPresenterDim", 0), ("searchChromeTint", 0),
    ]

    private func resetValues() {
        var s: [String: SurfaceStyle] = [:]
        var e: [String: Double] = [:]
        for surface in Self.surfaces {
            s[surface.id] = surface.defaults
            for x in surface.extras { e[x.0] = x.3 }
        }
        for x in Self.pageExtras { e[x.0] = x.1 }
        styles = s
        extras = e
    }

    /// Back to the shipped defaults: the Balanced preset.
    func reset() {
        apply(.balanced)
        appearance = 0
    }

    /// Loads a preset's values; they stay editable afterwards.
    func apply(_ p: GlassPreset) {
        resetValues()
        var s = styles
        var e = extras
        switch p {
        case .current:
            break
        case .balanced:
            // The shipped look: data in the shared YoinTokens.Glass (Kotlin).
            for t in Tokens.glass.balanced { s[t.id] = SurfaceStyle(t) }
            for x in Tokens.glass.balancedExtras { e[x.id] = x.value }
        case .appleHIG:
            // Home cards: blurred cover + a standard material, not glass.
            s["homeCards"] = SurfaceStyle(variant: .material, role: .auto, tintOpacity: 0.45,
                                          interactive: false, cornerRadius: 16)
            // Rating track: solid container color.
            s["ratingTrack"] = SurfaceStyle(variant: .none, role: .surfaceVariant, tintOpacity: 0.7, interactive: false)
            // Lyrics fade out above the transport row.
            e["lyricsUnder"] = 0
            // No glass in Now Playing: plain glyphs, PAUSE a solid primary pill (Android).
            s["transportSecondary"] = SurfaceStyle(variant: .none, tintOn: false, tintOpacity: 0, interactive: false)
            s["transportPrimary"] = SurfaceStyle(variant: .none, role: .primary, tintOpacity: 1, interactive: false)
            s["heart"] = SurfaceStyle(variant: .none, tintOn: false, tintOpacity: 0, interactive: false)
            s["npPills"] = SurfaceStyle(variant: .none, role: .primary, tintOpacity: 1, interactive: false)
        case .gemini:
            // Starts from Balanced (51's decisions stay: tinted bar
            // selection, glass rating track and NP pills, plain glass).
            for t in Tokens.glass.balanced { s[t.id] = SurfaceStyle(t) }
            for x in Tokens.glass.balancedExtras { e[x.id] = x.value }
            // One chromatic accent per plane: the selected bar button and
            // PAUSE (and a liked heart, which borrows PAUSE's surface).
            // Every other floating control gets a neutral floor so covers
            // and text scrolling under it don't dye it (round 3: 0.24 / 0.28
            // were invisible over the album grid, raised to 0.52).
            let floor = { (o: Double) in SurfaceStyle(role: .surfaceContainerHighest, tintOpacity: o) }
            s["barUnselected"] = floor(0.52)
            s["barSelected"] = SurfaceStyle(tintOpacity: 0.85, shadowOpacity: 0.12, shadowRadius: 10, shadowY: 4)
            s["miniPlayer"] = floor(0.52)
            s["transportPrimary"] = SurfaceStyle(tintOpacity: 0.82, shadowOpacity: 0.18, shadowRadius: 12, shadowY: 5)
            s["transportSecondary"] = floor(0.22)
            s["heart"] = floor(0.22)
            s["ratingTrack"] = SurfaceStyle(role: .surfaceVariant, tintOpacity: 0.38, interactive: false)
            s["npPills"] = floor(0.35)
            s["npBackground"] = SurfaceStyle(role: .background, tintOpacity: 0.54, interactive: false, cornerRadius: 44)
            // Scrolling content: a calmer, denser tonal wash; the cover
            // underneath is a soft gradient, not a second artwork.
            s["homeCards"] = SurfaceStyle(variant: .material, tintOpacity: 0.72, interactive: false, cornerRadius: 16)
            e["washOpacity"] = 0.22
            e["ambientStrength"] = 0.75
            e["ambientBlur"] = 110
            e["neighbourStrength"] = 0.48
            e["coverBlur"] = 42
            e["coverStrength"] = 0.5
            e["darkTintMultiplier"] = 1.15
            e["sheetPresenterDim"] = 0.14
            e["searchChromeTint"] = 0.38
        }
        styles = s
        extras = e
        preset = p
    }

    /// Launch-arg overrides (screenshots) are applied without saving.
    private var suppressSave = false

    private func save() {
        guard !suppressSave else { return }
        guard let data = try? JSONEncoder().encode(Saved(appearance: appearance, preset: preset, styles: styles, extras: extras))
        else { return }
        UserDefaults.standard.set(data, forKey: Self.key)
    }

    func style(_ id: String) -> SurfaceStyle { styles[id] ?? SurfaceStyle(tintOpacity: 0) }
    func extra(_ id: String) -> Double { extras[id] ?? 0 }

    /// A surface's tint opacity in this appearance (Gemini's dark multiplier).
    func tintOpacity(_ id: String, dark: Bool) -> Double {
        let o = style(id).tintOpacity
        return dark ? min(1, o * (extras["darkTintMultiplier"] ?? 1)) : o
    }

    /// The accent glass is dense enough (Gemini: PAUSE 0.82, × 1.15 in dark)
    /// that the accent's own on-color reads on it in dark mode too; thinner
    /// accent glass (Balanced 0.55) keeps white ink there.
    func denseAccent(_ id: String, dark: Bool) -> Bool {
        style(id).tintOn && !neutralTint(id) && tintOpacity(id, dark: dark) >= 0.75
    }

    /// The surface is tinted with a neutral surface role (Gemini's floors):
    /// its ink is on-surface, not the accent's on-color.
    func neutralTint(_ id: String) -> Bool {
        let st = style(id)
        guard st.tintOn else { return false }
        switch st.role {
        case .surface, .surfaceContainer, .surfaceContainerHighest, .surfaceVariant, .background: return true
        default: return false
        }
    }

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
        let json = (try? enc.encode(Saved(appearance: appearance, preset: preset, styles: styles, extras: extras)))
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
    func labGlass<S: Shape>(_ id: String, scheme: CoverScheme, brand: CoverScheme,
                            auto: Color, in shape: S) -> some View {
        modifier(LabGlass(id: id, scheme: scheme, brand: brand, auto: auto, shape: shape))
    }

    /// The Library / Search search field and ✕: plain glass, or (Gemini)
    /// glass tinted surfaceContainerHighest so it groups with the chip row.
    func labSearchGlass<S: Shape>(scheme: CoverScheme, in shape: S) -> some View {
        let o = GlassLab.shared.extra("searchChromeTint")
        return glassEffect(o > 0 ? .regular.tint(scheme.surfaceContainerHighest.opacity(o)).interactive()
                                 : .regular.interactive(), in: shape)
    }

    /// The contrast shadow under an icon or label on a Glass Lab surface.
    func labContrast(_ id: String) -> some View {
        shadow(color: .black.opacity(GlassLab.shared.style(id).contrastShadow), radius: 1.5, y: 0.5)
    }
}

private struct LabGlass<S: Shape>: ViewModifier {
    let id: String
    let scheme: CoverScheme
    let brand: CoverScheme
    let auto: Color
    let shape: S
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let lab = GlassLab.shared
        let st = lab.style(id)
        let tint = st.role.color(in: scheme, brand: brand, auto: auto)
        let opacity = st.tintOn ? lab.tintOpacity(id, dark: colorScheme == .dark) : 0
        Group {
            switch st.variant {
            case .none:
                content.background(shape.fill(tint.opacity(opacity)))
            case .material:
                content.background {
                    ZStack {
                        shape.fill(.regularMaterial)
                        shape.fill(tint.opacity(opacity))
                    }
                }
            case .regular, .clear:
                let base: Glass = st.variant == .clear ? .clear : .regular
                let tinted = st.tintOn ? base.tint(tint.opacity(opacity)) : base
                content.glassEffect(st.interactive ? tinted.interactive() : tinted, in: shape)
            }
        }
        .shadow(color: tint.opacity(st.shadowOpacity), radius: st.shadowRadius, y: st.shadowY)
    }
}

// MARK: - Panel

/// Floating, draggable, collapsible Glass Lab panel. Dock it at the top while
/// tuning the bottom bar.
struct GlassLabPanel: View {
    @Environment(AppModel.self) private var model
    @State private var drag: CGSize = .zero
    @State private var settled: CGSize = .zero
    @State private var expandedSurface: String? = "transportPrimary"
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
            Picker("Preset", selection: Binding(get: { lab.preset }, set: { lab.apply($0) })) {
                ForEach(GlassPreset.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            Picker("Motion", selection: $lab.sharedMotion) {
                Text("Current iOS").tag(false); Text("Shared (Android)").tag(true)
            }
            .pickerStyle(.segmented)
            Picker("Appearance", selection: $lab.appearance) {
                Text("System").tag(0); Text("Light").tag(1); Text("Dark").tag(2)
            }
            .pickerStyle(.segmented)
            VStack(alignment: .leading, spacing: 4) {
                Text("Album page · pulled-up top bar").font(.gsf(13)).foregroundStyle(.secondary)
                Picker("Detail top bar", selection: Binding(
                    get: { Int(lab.extra("detailTopBar").rounded()) },
                    set: { lab.extras["detailTopBar"] = Double($0) })) {
                    Text("Spectrum").tag(0); Text("Glass").tag(1); Text("Blur").tag(2)
                }
                .pickerStyle(.segmented)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Filter chips (Library, Search)").font(.gsf(13)).foregroundStyle(.secondary)
                Picker("Library filter", selection: Binding(
                    get: { Int((lab.extras["libraryFilterStyle"] ?? 1).rounded()) },
                    set: { v in withAnimation(Yoin.spatial) { lab.extras["libraryFilterStyle"] = Double(v) } })) {
                    Text("Android").tag(1); Text("Sliding").tag(2); Text("Glass track").tag(0)
                }
                .pickerStyle(.segmented)
            }
            if lab.preset == .gemini {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Gemini extras").font(.gsf(13)).foregroundStyle(.secondary)
                    labSlider("Dark-mode tint ×", "darkTintMultiplier", 1...1.5)
                    labSlider("Dim under the Queue sheet", "sheetPresenterDim", 0...0.6)
                    labSlider("Search field tint", "searchChromeTint", 0...0.8)
                }
            }
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

    private func labSlider(_ label: String, _ id: String, _ range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(label)
                Spacer()
                Text(String(format: "%.2f", lab.extra(id))).monospacedDigit().foregroundStyle(.secondary)
            }
            Slider(value: Binding(get: { lab.extra(id) }, set: { lab.extras[id] = $0 }), in: range)
        }
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
