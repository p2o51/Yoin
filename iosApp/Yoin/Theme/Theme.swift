import SwiftUI
import UIKit

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
    static let spatial: Animation = .spring(response: 0.42, dampingFraction: 0.78)
    /// Fast spatial spring for small components.
    static let spatialFast: Animation = .spring(response: 0.3, dampingFraction: 0.72)
    /// Effects spring (color, alpha). No overshoot.
    static let effects: Animation = .spring(response: 0.35, dampingFraction: 1)
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

/// Launch arguments, e.g. `-screen np-v2 -glass yoin`.
enum LaunchConfig {
    static var screen: String { UserDefaults.standard.string(forKey: "screen") ?? "home" }
    /// `-screen np-v2` or `-npVariant v2`; `np-frost` is the old name for v2.
    static var npVariant: String {
        if screen == "np-frost" { return "v2" }
        if screen.hasPrefix("np-v") { return String(screen.dropFirst(3)) }
        return UserDefaults.standard.string(forKey: "npVariant") ?? "v3"
    }
    static var glass: String {
        if screen.hasSuffix("-yoinglass") { return "yoin" }
        return UserDefaults.standard.string(forKey: "glass") ?? "system"
    }
    static var baseScreen: String {
        screen.replacingOccurrences(of: "-yoinglass", with: "")
    }
}

func formatTime(_ sec: Int) -> String {
    String(format: "%d:%02d", sec / 60, sec % 60)
}
