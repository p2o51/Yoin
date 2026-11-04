import SwiftUI
import UIKit
import YoinShared

/// Yoin tokens for iOS. Names follow the Android MD3 roles loosely.
enum Yoin {
    private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? ui(dark) : ui(light) })
    }

    private static func ui(_ v: UInt32) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }

    static let surface = dyn(0xFCF8FF, 0x141218)
    static let surfaceContainer = dyn(0xF1ECF6, 0x211F26)
    static let surfaceContainerHigh = dyn(0xEAE5F0, 0x2B2930)
    static let ink = dyn(0x1C1B20, 0xE6E0EA)
    static let inkVariant = dyn(0x49454F, 0xCAC4D0)
    static let outline = dyn(0xCAC4D0, 0x49454F)
    /// Yoin violet, used before anything is playing.
    static let violet = dyn(0x6B4FD8, 0xCFBDFF)

    /// MD3 Expressive spatial spring (position, size).
    @MainActor static var spatial: Animation {
        (GlassLab.shared.sharedMotion ? shared(Tokens.motion.expressiveDefaultSpatial)
            : .spring(response: 0.42, dampingFraction: 0.78)).speed(LaunchConfig.animationSpeed)
    }
    /// Fast spatial spring for small components.
    @MainActor static var spatialFast: Animation {
        (GlassLab.shared.sharedMotion ? shared(Tokens.motion.expressiveFastSpatial)
            : .spring(response: 0.3, dampingFraction: 0.72)).speed(LaunchConfig.animationSpeed)
    }
    /// Effects spring (color, alpha). No overshoot.
    @MainActor static var effects: Animation {
        (GlassLab.shared.sharedMotion ? shared(Tokens.motion.expressiveDefaultEffects)
            : .spring(response: 0.35, dampingFraction: 1)).speed(LaunchConfig.animationSpeed)
    }

    /// M3 Standard motion scheme's fast / default spatial springs (Android
    /// YoinMotionRole.Standard: damping 0.9 at stiffness 1400 / 700). The
    /// transport's PLAY ⇄ PAUSE pill and its press run on these.
    @MainActor static var standardFastSpatial: Animation {
        shared(Tokens.motion.standardFastSpatial).speed(LaunchConfig.animationSpeed)
    }
    @MainActor static var standardDefaultSpatial: Animation {
        shared(Tokens.motion.standardDefaultSpatial).speed(LaunchConfig.animationSpeed)
    }

    /// Android's MD3 Expressive spring (damping ratio + stiffness, mass 1)
    /// from the shared YoinTokens.Motion, as a SwiftUI spring.
    private static func shared(_ s: YoinTokens.MotionSpring) -> Animation {
        let k = s.stiffness
        return .interpolatingSpring(mass: 1, stiffness: k, damping: s.damping * 2 * k.squareRoot())
    }
}

extension Font {
    /// Google Sans Flex, bundled from the Android app's res/font.
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

/// Yoin Symbols as custom symbols. Never Apple's SF Symbols.
struct YoinIcon: View {
    var name: String
    var size: CGFloat = 24

    init(_ name: String, size: CGFloat = 24) {
        self.name = name
        self.size = size
    }

    var body: some View {
        // Generated SF-Symbol templates (YoinSymbols.xcassets, tools/gen_sfsymbols.py).
        Image("yoin.\(name)")
            .font(.system(size: size * 0.82))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// Launch arguments, e.g. `-screen np-short -glass yoin`.
enum LaunchConfig {
    static var screen: String { UserDefaults.standard.string(forKey: "screen") ?? "home" }
    /// Search screenshots: `-screen search-results` etc. open with a query.
    /// `-screen search` and `search-longpress` open empty (the keyboard up);
    /// `search-empty-results` with a query nothing matches; the rest with "la".
    static var searchQuery: String {
        switch screen {
        case "search", "search-longpress": ""
        case "search-empty-results": "zzqx"
        default: screen.hasPrefix("search") ? "la" : ""
        }
    }
    static var searchFilter: SearchView.Filter {
        switch screen {
        case "search-artists": .artists
        case "search-albums": .albums
        case "search-songs": .songs
        case "search-playlists": .playlists
        default: .all
        }
    }
    /// `-screen search-scrolled`: the All results start scrolled under the chips.
    static var searchScrolled: Bool { screen == "search-scrolled" }
    static var glass: String {
        if screen.hasSuffix("-yoinglass") { return "yoin" }
        return UserDefaults.standard.string(forKey: "glass") ?? "system"
    }
    static var baseScreen: String {
        screen.replacingOccurrences(of: "-yoinglass", with: "")
    }
    /// Debug slow motion for recordings: `-animationSpeed 0.25` plays Yoin's
    /// springs (and, through the window's layer, Core Animation and the
    /// glass) at a quarter speed. Release builds ignore it.
    static let animationSpeed: Double = {
        #if DEBUG
        let v = UserDefaults.standard.double(forKey: "animationSpeed")
        return v > 0 ? v : 1
        #else
        return 1
        #endif
    }()
}

func formatTime(_ sec: Int) -> String {
    String(format: "%d:%02d", sec / 60, sec % 60)
}
