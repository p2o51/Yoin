import CoreGraphics
import YoinShared

/// Shortcuts into the shared YoinTokens (Kotlin, :shared): the numbers both
/// apps read from one place (iosApp/docs/shared-kmp.md). Doubles there; `.cg`
/// for layout.
enum Tokens {
    static var bar: YoinTokens.Bar { .shared }
    static var shape: YoinTokens.Shape { .shared }
    static var np: YoinTokens.NowPlaying { .shared }
    static var detail: YoinTokens.Detail { .shared }
    static var home: YoinTokens.Home { .shared }
    static var library: YoinTokens.Library { .shared }
    static var search: YoinTokens.Search { .shared }
    static var metaLine: YoinTokens.MetaLine { .shared }
    static var type: YoinTokens.Typography { .shared }
    static var glass: YoinTokens.Glass { .shared }
    static var motion: YoinTokens.Motion { .shared }
}

extension Double {
    /// A token as a layout length.
    var cg: CGFloat { CGFloat(self) }
}
