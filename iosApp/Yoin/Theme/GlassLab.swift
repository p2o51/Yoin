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
    /// Yoin's own layered world (2026-11-04, iosApp/docs/glass-layers.md):
    /// glass only where content moves underneath, one accent per layer,
    /// depth = blur, one light source, NP is the mini player unfolded. The
    /// default: Gemini's colours outside Now Playing, Balanced-or-stronger
    /// cover colour inside it.
    case world = "World"
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
        Surface(id: "npFloating", title: "Now Playing · floating chrome (expanded tabs, Ask Gemini)",
                defaults: SurfaceStyle(variant: .regular, role: .primary, tintOpacity: 0.55),
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
    var preset: GlassPreset = .world { didSet { save() } }
    /// Motion: false = the current iOS springs (default until 51 decides),
    /// true = Android's springs from the shared YoinTokens.Motion.
    var sharedMotion = UserDefaults.standard.bool(forKey: "glassLab.sharedMotion") {
        didSet { UserDefaults.standard.set(sharedMotion, forKey: "glassLab.sharedMotion") }
    }
    /// Solid surfaces (prototype): every glass surface becomes Android's
    /// opaque tonal fill. `-solidSurfaces YES` or Settings › Labs.
    var solidSurfaces = UserDefaults.standard.bool(forKey: "solidSurfaces") {
        didSet { UserDefaults.standard.set(solidSurfaces, forKey: "solidSurfaces") }
    }
    var panelOpen = false
    var panelAtTop = false
    var panelCollapsed = false

    /// v2: Balanced became the default (51, 2026-11-03), so older saved
    /// values (Current) don't carry over.
    /// v3: 51's 11-03 night decisions (tinted bar selection, glass rating
    /// track and NP pills) change Balanced again; saved v2 values would hide them.
    /// v4: World became the default (2026-11-04).
    private static let key = "glassLab.v4"

    private init() {
        suppressSave = true
        apply(.world)
        suppressSave = false
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode(Saved.self, from: data) {
            appearance = saved.appearance
            preset = saved.preset ?? .world
            styles.merge(saved.styles) { _, new in new }
            extras.merge(saved.extras) { _, new in new }
            // 51, 2026-11-06: the album's top bar became the safe-area strip;
            // a top bar saved before that doesn't carry over (once).
            if saved.extras["albumStripExtend"] == nil { extras["detailTopBar"] = 4 }
        }
        panelOpen = UserDefaults.standard.bool(forKey: "glassLab")
        if let name = UserDefaults.standard.string(forKey: "preset") {
            suppressSave = true
            apply(name == "hig" ? .appleHIG : name == "balanced" ? .balanced : name == "gemini" ? .gemini
                  : name == "world" ? .world : .current)
            suppressSave = false
        }
        if UserDefaults.standard.bool(forKey: "barTinted") {
            suppressSave = true
            extras["barSelectedStyle"] = 1
            suppressSave = false
        }
        // Screenshots: `-detailTopBar 0|1|2|3`.
        if let mode = UserDefaults.standard.string(forKey: "detailTopBar").flatMap(Double.init) {
            suppressSave = true
            extras["detailTopBar"] = mode
            suppressSave = false
        }
        // Screenshots: `-libraryFilterStyle 0…4` (SegmentChipStyle).
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
    /// bar, 4 = the spectrum as a strip just past the safe area over the
    /// progressive blur (default since 2026-11-06), 0 = the cover's spectrum with a soft, progressively blurred lower
    /// edge, 1 = Liquid Glass, 2 = the system's progressive
    /// scroll-edge blur, 3 = the spectrum with Android's hard edge.
    /// Gemini preset extras (neutral in every other preset):
    /// darkTintMultiplier scales every glass tint in dark mode,
    /// sheetPresenterDim dims Now Playing under the Queue sheet,
    /// searchChromeTint tints the Library / Search search field and ✕
    /// with surfaceContainerHighest so they group with the chip row.
    /// World preset extras (neutral in every other preset):
    /// heartLikedAccent 1 = the liked heart borrows PAUSE's surface (0: it
    /// keeps its own "heart" surface); liftInFlow 1 = in-flow NP controls
    /// (rating track, ⌄ at rest) are a tonal fill at liftRestOpacity and are
    /// lifted to glass only while touched / while content moves under them;
    /// neutralShadow 1 = every Glass Lab shadow is black, straight down (one
    /// light source); recedeScale / recedeDim / recedeBlur = how far the
    /// layer behind steps back at progress 1 (NP open, a sheet up);
    /// sheetRecede* = the same under a sheet; pillAmbient = Now Playing's
    /// ambient inside the folded pill; coverContactShadow = the covers'
    /// contact-shadow opacity; darkAmbientStrength / darkNeighbourStrength
    /// (0 = as in light) and auraOnTop: Now Playing's ambient in dark mode.
    static let pageExtras: [(String, Double)] = [
        ("detailTopBar", 4), ("darkTintMultiplier", 1), ("sheetPresenterDim", 0), ("searchChromeTint", 0),
        ("heartLikedAccent", 1), ("liftInFlow", 0), ("liftRestOpacity", 0.6), ("neutralShadow", 0),
        ("recedeScale", 0), ("recedeDim", 0), ("recedeBlur", 0), ("coverContactShadow", 0),
        ("sheetRecedeScale", 0), ("sheetRecedeDim", 0), ("sheetRecedeBlur", 0), ("pillAmbient", 0),
        ("darkAmbientStrength", 0), ("darkNeighbourStrength", 0), ("auraOnTop", 0),
        ("albumPullTravel", 320), ("albumPullSnap", 0.3), ("albumStripExtend", 6), ("albumStripFeather", 56), ("albumStripBlur", 22),
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

    /// Back to the shipped defaults: the World preset.
    func reset() {
        apply(.world)
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
            // detailTopBar stays iOS's (pageExtras): the shared 0 is Android's spectrum.
            for x in Tokens.glass.balancedExtras where x.id != "detailTopBar" { e[x.id] = x.value }
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
            // detailTopBar stays iOS's (pageExtras): the shared 0 is Android's spectrum.
            for x in Tokens.glass.balancedExtras where x.id != "detailTopBar" { e[x.id] = x.value }
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
        case .world:
            applyWorld(&s, &e)
        }
        // The NP floating chrome wore the pills' glass before World split them.
        if p != .world { s["npFloating"] = s["npPills"] }
        styles = s
        extras = e
        preset = p
    }

    /// The World preset (iosApp/docs/glass-layers.md). Outside Now Playing:
    /// Gemini's colours. Inside: the cover colour at Balanced strength or
    /// more, glass only on the controls the lyrics / pages move under.
    private func applyWorld(_ s: inout [String: SurfaceStyle], _ e: inout [String: Double]) {
        apply(.gemini)
        s = styles
        e = extras
        // R4 one light source: every shadow neutral, straight down; only the
        // top floating layer casts one.
        let lift = { (o: Double) in (opacity: o, radius: 10.0, y: 5.0) }
        // L2 shell: Gemini's neutral floors; the selected tab is the one accent.
        s["barUnselected"] = SurfaceStyle(role: .surfaceContainerHighest, tintOpacity: 0.52,
                                          shadowOpacity: lift(0.14).opacity, shadowRadius: 10, shadowY: 5)
        s["barSelected"] = SurfaceStyle(tintOpacity: 0.85, shadowOpacity: 0.18, shadowRadius: 12, shadowY: 6)
        // R5: the mini player is Now Playing folded: the NP room's material,
        // colour and (pillAmbient) its ambient, already breathing in the pill.
        s["miniPlayer"] = SurfaceStyle(role: .background, tintOpacity: 0.48,
                                       shadowOpacity: 0.14, shadowRadius: 10, shadowY: 5)
        e["pillAmbient"] = 0.38
        e["washOpacity"] = 0.28
        // L3 room: frosted, cover-tinted; the cover colour back past Balanced
        // (51: Gemini's NP green was too faint).
        s["npBackground"] = SurfaceStyle(role: .background, tintOpacity: 0.46, interactive: false, cornerRadius: 44)
        e["ambientStrength"] = 0.68
        e["ambientBlur"] = 95
        e["neighbourStrength"] = 1.0
        // L3 chrome pinned over the lyrics: glass. PAUSE is the room's one
        // accent; Next / Prev / play mode / ⌄ a neutral floor.
        s["transportPrimary"] = SurfaceStyle(tintOpacity: 0.70, shadowOpacity: 0.20, shadowRadius: 12, shadowY: 6,
                                             contrastShadow: 0.12)
        s["transportSecondary"] = SurfaceStyle(role: .surfaceContainerHighest, tintOpacity: 0.28,
                                               shadowOpacity: 0.14, shadowRadius: 10, shadowY: 5)
        // Lyrics run under the PAUSE row only, then dissolve before the progress row.
        e["lyricsUnder"] = 0.55
        // L3 stage fixtures (static flow): tonal. The heart keeps Android's
        // tertiary / tertiaryContainer, so the cover's second colour shows;
        // the rating is a recessed slot that lifts to glass while held.
        s["heart"] = SurfaceStyle(variant: .none, role: .auto, tintOpacity: 1, interactive: false)
        e["heartLikedAccent"] = 0
        s["ratingTrack"] = SurfaceStyle(role: .surfaceVariant, tintOpacity: 0.28, interactive: false,
                                        shadowOpacity: 0.18, shadowRadius: 12, shadowY: 6)
        e["liftInFlow"] = 1
        e["liftRestOpacity"] = 0.45
        e["ratingFill"] = 0.78
        // Dark: this cover's aura leads, drawn over the neighbours' bleed.
        e["darkAmbientStrength"] = 0.88
        e["darkNeighbourStrength"] = 0.42
        e["auraOnTop"] = 1
        // Queue / Devices / Write: glass at all times (51, 11-04: they stay
        // glass; the rating bar is the one solid fixture). Neutral floor, as
        // Next / Prev: PAUSE stays the room's one accent (R2).
        s["npPills"] = SurfaceStyle(role: .surfaceContainerHighest, tintOpacity: 0.28,
                                    shadowOpacity: 0.14, shadowRadius: 10, shadowY: 5)
        // Expanded: tabs, lyric keys and the Ask Gemini bar float over the
        // scrolling page — glass; the selected tab the accent, the rest neutral.
        s["npFloating"] = SurfaceStyle(role: .primary, tintOpacity: 0.78,
                                       shadowOpacity: 0.14, shadowRadius: 10, shadowY: 5)
        e["neutralShadow"] = 1
        e["darkTintMultiplier"] = 1.12
        e["searchChromeTint"] = 0.48
        e["sheetPresenterDim"] = 0
        // R3 depth: behind Now Playing (1:1 with its progress) and behind a sheet.
        e["recedeScale"] = 0.06
        e["recedeDim"] = 0.22
        e["recedeBlur"] = 10
        e["sheetRecedeScale"] = 0.04
        e["sheetRecedeDim"] = 0.14
        e["sheetRecedeBlur"] = 3.5
        // R6: covers are objects with a contact shadow.
        e["coverContactShadow"] = 0.22
        // R1: the pinned chips have the list scrolling under them (51, 11-04:
        // a glass track, made refined): the HIG track, one tinted lozenge.
        e["libraryFilterStyle"] = Double(SegmentChipStyle.glassTrack.rawValue)
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

    /// World: one accent per layer (the selected / primary item); the
    /// layer's other floating controls take a neutral tint.
    var oneAccentPerLayer: Bool { preset == .world }

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
        return yGlassEffect(o > 0 ? .regular.tint(scheme.surfaceContainerHighest.opacity(o)).interactive()
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
        if lab.solidSurfaces {
            // Solid surfaces: Android's opaque tonal fill, no shadow. A
            // surface whose glass was untinted (or barely tinted) takes the
            // neutral container; an accent keeps its colour, fully opaque.
            content.background(shape.fill(lab.solidFill(id, tint: tint, opacity: opacity, scheme: scheme)))
        } else {
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
                content.yGlassEffect(st.interactive ? tinted.interactive() : tinted, in: shape)
            }
        }
        .shadow(color: lab.shadowColor(tint).opacity(st.shadowOpacity), radius: st.shadowRadius, y: st.shadowY)
        }
    }
}

extension GlassLab {
    /// The opaque fill a Glass Lab surface gets with solid surfaces on.
    func solidFill(_ id: String, tint: Color, opacity: Double, scheme: CoverScheme) -> Color {
        let st = style(id)
        if !st.tintOn || opacity < 0.05 { return scheme.surfaceContainerHigh }
        switch st.role {
        case .surface, .surfaceContainer, .surfaceContainerHighest, .surfaceVariant, .background:
            return tint
        default:
            // An accent (PAUSE, selected tab): Android's container colour, so
            // the glyph on it (onPrimaryContainer-ish) stays readable.
            return opacity >= 0.6 ? tint : scheme.secondaryContainer
        }
    }
}

extension GlassLab {
    /// One light source (World): every shadow is neutral, else the tint's.
    func shadowColor(_ tint: Color) -> Color { extra("neutralShadow") > 0.5 ? .black : tint }
}

extension View {
    /// An in-flow control (World `liftInFlow`): a tonal fill of the surface's
    /// tint at rest, lifted into that surface's glass while `lifted` (touched,
    /// or content moving under it). One `glassEffect` throughout, so the lift
    /// doesn't rebuild the view. Other presets: the plain Glass Lab surface.
    @ViewBuilder
    func labLift<S: Shape>(_ id: String, lifted: Bool, scheme: CoverScheme, auto: Color, in shape: S) -> some View {
        let lab = GlassLab.shared
        let st = lab.style(id)
        if lab.extra("liftInFlow") > 0.5, st.variant == .regular || st.variant == .clear {
            let tint = st.role.color(in: scheme, brand: .brand(dark: false), auto: auto)
            let base: Glass = st.variant == .clear ? .clear : .regular
            let glass = (st.tintOn ? base.tint(tint.opacity(st.tintOpacity)) : base).interactive()
            self
                .background {
                    shape.fill(tint.opacity(lab.extra("liftRestOpacity")))
                        .opacity(lifted ? 0 : 1)
                }
                .yGlassEffect(glass, in: shape, enabled: lifted)
                .shadow(color: .black.opacity(lifted ? st.shadowOpacity : 0), radius: st.shadowRadius, y: st.shadowY)
                .animation(Yoin.effects, value: lifted)
        } else {
            labGlass(id, scheme: scheme, brand: .brand(dark: false), auto: auto, in: shape)
        }
    }

    /// The layer behind steps back (World depth cue): scale, dim and blur by
    /// `depth` (0…1), all off the same progress as what sits on top.
    func labRecede(_ depth: CGFloat, sheet: Bool = false) -> some View {
        let lab = GlassLab.shared
        let d = min(max(depth, 0), 1)
        let k = sheet ? "sheetRecede" : "recede"
        let insets = WindowInsets.current
        // Hosted in a full-screen frame (the layer still lays out in the same
        // safe area): a blur draws only inside its view's frame, and the
        // layer's own frame stops at the safe area, so any blur cut the
        // status-bar band off.
        return Color.clear
            .ignoresSafeArea()
            .overlay { self }
            // Blur inside the corner mask, so the plane's silhouette stays crisp.
            .blur(radius: lab.solidSurfaces ? 0 : lab.extra(k + "Blur") * d)
            // The world behind keeps the display's corners as it steps back,
            // and gives up the status-bar and home-indicator bands (the look
            // behind a sheet) by the same depth: they slide away with what
            // rises over it.
            .mask {
                RoundedRectangle(cornerRadius: d > 0.001 ? 44 : 0, style: .continuous)
                    .padding(.top, insets.top * d)
                    .padding(.bottom, insets.bottom * d)
                    .ignoresSafeArea()
            }
            // Drawn scaled, laid out as is. A layout scale (scaleEffect) moved
            // the layer off the screen's edges, and then nothing in it could
            // reach under the status bar (ignoresSafeArea): Now Playing's dark
            // top went to the surface on the first frame a sheet rose.
            .modifier(RenderScale(scale: 1 - lab.extra(k + "Scale") * d).ignoredByLayout())
            // What the shrinking plane uncovers is the app's surface, not the
            // window's black (a black rim at the corners read as a flash),
            // and the dim lies over both, so the rim dims with the plane.
            .background {
                if d > 0.001 { Yoin.surface.ignoresSafeArea() }
            }
            .overlay {
                Color.black.opacity(lab.extra(k + "Dim") * d)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
    }

    /// A cover as a physical object: a soft contact shadow straight down.
    func labCoverShadow() -> some View {
        shadow(color: .black.opacity(GlassLab.shared.extra("coverContactShadow")), radius: 10, y: 6)
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
            .yGlassEffect(.regular, in: .rect(cornerRadius: 24, style: .continuous))
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
                    Text("Strip").tag(4); Text("Spectrum").tag(0); Text("Hard edge").tag(3)
                    Text("Glass").tag(1); Text("Blur").tag(2)
                }
                .pickerStyle(.segmented)
                if Int(lab.extra("detailTopBar").rounded()) == 4 {
                    labSlider("Strip below the safe area (pt)", "albumStripExtend", 2...40)
                    labSlider("Strip feather (pt)", "albumStripFeather", 8...120)
                    labSlider("Strip blur at the end (pt)", "albumStripBlur", 2...48)
                }
                labSlider("Pull-up travel (pt)", "albumPullTravel", 160...520)
                labSlider("Pull-up snap point", "albumPullSnap", 0.15...0.5)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Filter chips (Library, Search)").font(.gsf(13)).foregroundStyle(.secondary)
                Picker("Library filter", selection: Binding(
                    get: { Int((lab.extras["libraryFilterStyle"] ?? 1).rounded()) },
                    set: { v in withAnimation(Yoin.spatial) { lab.extras["libraryFilterStyle"] = Double(v) } })) {
                    Text("Android").tag(1); Text("Sliding").tag(2); Text("Track").tag(0)
                    Text("Pills").tag(3); Text("Lit on scroll").tag(4)
                }
                .pickerStyle(.segmented)
            }
            if lab.preset == .world {
                VStack(alignment: .leading, spacing: 4) {
                    Text("World extras").font(.gsf(13)).foregroundStyle(.secondary)
                    labSlider("Behind: scale back", "recedeScale", 0...0.15)
                    labSlider("Behind: dim", "recedeDim", 0...0.6)
                    labSlider("Behind: blur", "recedeBlur", 0...20)
                    labSlider("Under a sheet: scale back", "sheetRecedeScale", 0...0.15)
                    labSlider("Under a sheet: dim", "sheetRecedeDim", 0...0.6)
                    labSlider("Under a sheet: blur", "sheetRecedeBlur", 0...20)
                    labSlider("Ambient in the folded pill", "pillAmbient", 0...1)
                    labSlider("In-flow rest fill", "liftRestOpacity", 0...1)
                    labSlider("Cover contact shadow", "coverContactShadow", 0...0.6)
                }
            }
            if lab.preset == .gemini || lab.preset == .world {
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
                        .buttonStyle(.plain).pointerHighlight()
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
            .buttonStyle(.plain).pointerHighlight()
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

/// The key window's safe-area insets (status bar, home indicator).
private enum WindowInsets {
    @MainActor static var current: UIEdgeInsets {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.lazy.compactMap(\.keyWindow).first?.safeAreaInsets ?? .zero
    }
}

/// A scale about the centre, for `.ignoredByLayout()`: applied only while drawing.
struct RenderScale: GeometryEffect {
    var scale: CGFloat
    var animatableData: CGFloat {
        get { scale }
        set { scale = newValue }
    }
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: size.width * (1 - scale) / 2, y: size.height * (1 - scale) / 2)
            .scaledBy(x: scale, y: scale))
    }
}
