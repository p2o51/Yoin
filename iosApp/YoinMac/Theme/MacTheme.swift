import AppKit
import CoreText
import SwiftUI
import YoinShared

/// Yoin tokens for macOS: the iOS `Yoin` roles (Theme/Theme.swift) with
/// NSColor's appearance-aware provider, and the iOS platform springs
/// (51, 2026-11-03: Apple platforms keep their own springs).
enum Yoin {
    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? ns(dark) : ns(light)
        })
    }

    private static func ns(_ v: UInt32) -> NSColor {
        NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }

    static let surface = dyn(0xFCF8FF, 0x141218)
    static let surfaceContainer = dyn(0xF1ECF6, 0x211F26)
    static let surfaceContainerHigh = dyn(0xEAE5F0, 0x2B2930)
    static let ink = dyn(0x1C1B20, 0xE6E0EA)
    static let inkVariant = dyn(0x49454F, 0xCAC4D0)
    static let ink3 = dyn(0x8D879A, 0x857F91)
    static let outline = dyn(0xCAC4D0, 0x49454F)
    /// Yoin violet, used before anything is playing.
    static let violet = dyn(0x6B4FD8, 0xCFBDFF)

    /// MD3 Expressive spatial spring (position, size).
    static var spatial: Animation { .spring(response: 0.42, dampingFraction: 0.78) }
    /// Fast spatial spring for small components.
    static var spatialFast: Animation { .spring(response: 0.3, dampingFraction: 0.72) }
    /// Effects spring (color, alpha). No overshoot.
    static var effects: Animation { .spring(response: 0.35, dampingFraction: 1) }
    static var slowEffects: Animation { shared(Tokens.motion.expressiveSlowEffects) }
    static var standardFastSpatial: Animation { shared(Tokens.motion.standardFastSpatial) }

    private static func shared(_ s: YoinTokens.MotionSpring) -> Animation {
        let k = s.stiffness
        return .interpolatingSpring(mass: 1, stiffness: k, damping: s.damping * 2 * k.squareRoot())
    }
}

extension Font {
    /// Google Sans Flex, bundled from the Android app's res/font (registered by `MacFonts`).
    static func gsf(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        let name: String
        if weight == .medium {
            name = "GoogleSansFlex-Medium"
        } else if weight == .semibold {
            name = "GoogleSansFlex-SemiBold"
        } else if weight == .bold || weight == .heavy || weight == .black {
            name = "GoogleSansFlex-Bold"
        } else {
            name = "GoogleSansFlex-Regular"
        }
        return .custom(name, size: size, relativeTo: style)
    }
}

/// The four static Google Sans Flex cuts, registered for the process at launch
/// (macOS has no UIAppFonts; ATSApplicationFontsPath would also pick up the
/// variable file, which shares the 400 cut's PostScript name).
enum MacFonts {
    static func register() {
        for w in [400, 500, 600, 700] {
            guard let url = Bundle.main.url(forResource: "google_sans_flex_\(w)", withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

/// Yoin Symbols as custom symbols. Never Apple's SF Symbols.
struct YoinIcon: View {
    var name: String
    var size: CGFloat = 24

    init(_ name: String, size: CGFloat = 24) {
        self.name = name
        self.size = size
    }

    var body: some View {
        Image("yoin.\(name)")
            .font(.system(size: size * 0.82))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// Launch arguments: `-silentAudio YES` (muted QA runs), `-macScreen home|library|album|np-panel|np-full|np-focus`.
enum MacLaunch {
    static var screen: String? { UserDefaults.standard.string(forKey: "macScreen") }
    static var silentAudio: Bool { UserDefaults.standard.bool(forKey: "silentAudio") }
}

func formatTime(_ sec: Int) -> String {
    String(format: "%d:%02d", sec / 60, sec % 60)
}

// MARK: - Stand-ins for iOS-only Home types that Models.swift names

/// iOS keeps these in Data/History/HomeHistory.swift (Memories, out of the Mac P0).
enum AlbumMemoryMark: Hashable, Sendable { case solid, outline, dashed }

struct JBISignal: Hashable, Sendable {
    enum Kind: Hashable, Sendable { case ratedAlbum, notedTrack }
    var kind: Kind
    var album: Album
    var title: String
    var subtitle: String
    var score: String
    var basis: String?
    var comment: String?
    var song: PlaylistEntry?
}
