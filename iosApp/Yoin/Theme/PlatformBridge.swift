import SwiftUI
#if canImport(UIKit)
import UIKit

/// The image type covers decode into: UIImage on iOS, NSImage on macOS (YoinMac).
typealias PlatformImage = UIImage

extension Image {
    init(platformImage: PlatformImage) { self.init(uiImage: platformImage) }
}

/// Extended-sRGB components of a SwiftUI colour (UIColor on iOS, NSColor on macOS).
func rgbaComponents(_ color: Color) -> (r: CGFloat, g: CGFloat, b: CGFloat, a: CGFloat) {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
    return (r, g, b, a)
}
#else
import AppKit

typealias PlatformImage = NSImage

extension NSImage {
    convenience init(cgImage: CGImage) {
        self.init(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
    }

    var cgImage: CGImage? { cgImage(forProposedRect: nil, context: nil, hints: nil) }
}

extension Image {
    init(platformImage: PlatformImage) { self.init(nsImage: platformImage) }
}

func rgbaComponents(_ color: Color) -> (r: CGFloat, g: CGFloat, b: CGFloat, a: CGFloat) {
    let c = NSColor(color).usingColorSpace(.extendedSRGB) ?? .black
    return (c.redComponent, c.greenComponent, c.blueComponent, c.alphaComponent)
}
#endif
