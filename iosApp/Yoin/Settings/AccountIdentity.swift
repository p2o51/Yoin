import SwiftUI

// Who an account is, at a glance (Android ui/settings/AccountIdentity.kt).
// The service is its colour (the Landing tones: Subsonic blue, Apple Music
// rose) and its Yoin Symbol on the avatar; the avatar's shape and letter say
// which account, so two logins on one service never look alike. No brand
// logos are redrawn.

enum AccountIdentity {
    /// How many avatar shapes there are; ≥ ProfileStore.maxProfiles.
    static let shapeCount = 8

    /// Gives every account its own avatar shape (Android assignAvatarShapes): oldest first, ties by id;
    /// each starts at a slot hashed from its id and takes the first one nobody before it has. Ids are
    /// stable, so an account keeps its shape across launches.
    static func assignShapes(_ profiles: [(id: String, createdAt: Int64)]) -> [String: Int] {
        var taken = [Bool](repeating: false, count: shapeCount)
        var out: [String: Int] = [:]
        for p in profiles.sorted(by: { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }) where out[p.id] == nil {
            let start = Int(javaHash(p.id) % Int32(shapeCount) + Int32(shapeCount)) % shapeCount
            let slot = (0..<shapeCount).map { (start + $0) % shapeCount }.first { !taken[$0] } ?? start
            taken[slot] = true
            out[p.id] = slot
        }
        return out
    }

    /// Java's String.hashCode, so a profile gets the slot Android would give the same id.
    static func javaHash(_ s: String) -> Int32 {
        s.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
    }

    /// The avatar letter (Android monogramOf): the first letter or digit of [title], uppercased; a
    /// leading service name is skipped so unnamed accounts don't all read its initial. Empty when there
    /// is none: the avatar shows the service glyph alone.
    static func monogram(_ title: String, service: String? = nil) -> String {
        var name = title
        if let service, title.lowercased().hasPrefix(service.lowercased()), title.count > service.count {
            let rest = String(title.dropFirst(service.count))
            if rest.contains(where: { $0.isLetter || $0.isNumber }) { name = rest }
        }
        guard let c = name.first(where: { $0.isLetter || $0.isNumber }) else { return "" }
        return String(c).uppercased()
    }
}

extension Profile.Service {
    /// The Landing's face for the service: its tone and glyph.
    var landing: LandingService {
        switch self {
        case .subsonic: .subsonic
        case .appleMusic: .appleMusic
        case .spotify: .spotify
        }
    }

    func tone(dark: Bool) -> LandingTone { LandingTone(landing, dark: dark) }

    var glyph: String { landing.glyph }
}

/// The eight avatar silhouettes as one animatable shape (a scalloped circle by
/// lobe count and depth), so an account whose shape changes morphs on the
/// spatial spring instead of snapping.
struct AvatarShape: Shape {
    var lobes: CGFloat
    var depth: CGFloat

    init(index: Int) {
        (lobes, depth) = switch ((index % AccountIdentity.shapeCount) + AccountIdentity.shapeCount) % AccountIdentity.shapeCount {
        case 0: (6, 0.16)
        case 1: (4, 0.30)
        case 2: (5, 0.22)
        case 3: (9, 0.14)
        case 4: (3, 0.24)
        case 5: (8, 0.12)
        case 6: (12, 0.09)
        default: (0, 0)
        }
    }

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(lobes, depth) }
        set { (lobes, depth) = (newValue.first, newValue.second) }
    }

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        let steps = 240
        for i in 0...steps {
            let t = Double(i) / Double(steps) * 2 * .pi
            let r = radius * (1 - depth / 2 + depth / 2 * CGFloat(cos(Double(lobes) * t)))
            let p = CGPoint(x: center.x + r * CGFloat(cos(t - .pi / 2)), y: center.y + r * CGFloat(sin(t - .pi / 2)))
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        path.closeSubpath()
        return path
    }
}

/// An account's avatar, two layers (Android AccountAvatar): behind and large,
/// the SERVICE (the account's shape in the service's accent, its glyph on it);
/// in front and small, bottom-trailing, the PERSON (the first letter of the
/// name), ring-cut from the shape by [ring]. Decorative for accessibility:
/// the name and service are text beside it.
struct AccountAvatar: View {
    var profile: Profile
    var shape: Int
    var ring: Color
    var size: CGFloat = 48
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tone = profile.service.tone(dark: colorScheme == .dark)
        let monogram = AccountIdentity.monogram(profile.title, service: profile.service.name)
        let badge = max(size * 0.46, 18)
        let ringWidth = min(max(size / 24, 1.5), 3)
        ZStack(alignment: .bottomTrailing) {
            YoinIcon(profile.service.glyph, size: size * 0.54)
                .foregroundStyle(tone.onAccent)
                .offset(x: monogram.isEmpty ? 0 : -size * 0.07, y: monogram.isEmpty ? 0 : -size * 0.07)
                .frame(width: size, height: size)
                .background(tone.accent, in: AvatarShape(index: shape))
            if !monogram.isEmpty {
                Text(monogram)
                    .font(.gsf(badge * 0.5, .semibold))
                    .foregroundStyle(tone.iconContent)
                    .frame(width: badge, height: badge)
                    .background(tone.iconContainer, in: .circle)
                    .padding(ringWidth)
                    .background(ring, in: .circle)
                    .offset(x: ringWidth, y: ringWidth)
            }
        }
        .frame(width: size, height: size)
        .animation(Yoin.spatial, value: shape)
        .accessibilityHidden(true)
    }
}

/// The header's account button face (Home, Library): the account in use as a
/// 32 avatar in its shape and service accent, its letter on it; with no
/// account, the mock's "5" (the screenshots).
struct HeaderAccountAvatar: View {
    var profile: Profile?
    var shape: Int
    /// The mock's fill, when there is no account.
    var fallback: Color
    var onFallback: Color
    var ring: Color
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tone = profile.map { $0.service.tone(dark: colorScheme == .dark) }
        let letter = profile.map { AccountIdentity.monogram($0.title, service: $0.service.name) } ?? "5"
        ZStack {
            if letter.isEmpty, let profile {
                YoinIcon(profile.service.glyph, size: 18)
            } else {
                Text(letter).font(.gsf(14, .semibold))
            }
        }
        .foregroundStyle(tone?.onAccent ?? onFallback)
        .contentTransition(.opacity)
        .frame(width: 32, height: 32)
        .background(tone?.accent ?? fallback, in: AvatarShape(index: profile == nil ? 0 : shape))
        .padding(1.5)
        .background(ring, in: AvatarShape(index: profile == nil ? 0 : shape))
        .frame(width: 48, height: 48)
        .animation(Yoin.spatial, value: shape)
        .animation(Yoin.effects, value: profile?.id)
    }
}
