import SwiftUI

/*
 * Another account's memory in the deck (Android ui/memories/MemoryOtherProfiles.kt,
 * showcase/MemoryOtherProfileLine.kt, #178): Memories shows every account's memories (owner 2026-11-08),
 * not only the account in use's. One from another account says so beside its Go to album ("From …", owner
 * 2026-11-04: just the account, no "this switches accounts"), and is read-only here: its title, review and
 * NeoDB line belong to that account. Go to album, or playing one of its songs, switches to that account
 * first (as Home's other-accounts shelf does); the first time ever the one-off notice asks.
 */

/// Beside Go to album on another account's memory: that account's mark (its avatar shape in its service's
/// hue, as its avatar shows it) and "From …". Off the cover, so nothing sits on the art. Reads as one line.
struct MemoryOtherAccountLine: View {
    var owner: MemoryCard.Account
    var tones: MemoryTones

    static let maxWidth: CGFloat = 160
    static let height: CGFloat = 20
    /// Between Go to album and the line beside it (the diary's foot, the spread).
    static let goGap: CGFloat = 12
    /// Above the line when it sits under the card's buttons.
    static let cardGap: CGFloat = 8
    static let alpha: CGFloat = 0.72

    var body: some View {
        HStack(spacing: 6) {
            AccountMark(service: owner.service, shape: owner.shape, size: 16)
            Text("From \(owner.name)")
                .font(.gsf(13, .medium))
                .foregroundStyle(tones.ink.opacity(Self.alpha))
                .lineLimit(1)
        }
        .frame(maxWidth: Self.maxWidth)
        .fixedSize(horizontal: true, vertical: false)
        .frame(height: Self.height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("From another account, \(owner.name)")
    }
}

/// Where a switch was asked from: the notice comes out of that button or row
/// (`accountSwitchNotice(_:anchor:)`). A card's Go to album is on its card, its diary and the spread.
enum MemorySwitchAnchor {
    static func album(_ surface: MemoryTitleSurface, _ card: MemoryCard) -> String { "album:\(surface.rawValue):\(card.id)" }
    static func track(_ card: MemoryCard, _ track: MemoryCard.DiaryTrack) -> String { "track:\(card.id):\(track.id)" }
    static func note(_ card: MemoryCard, _ note: SongNote) -> String { "note:\(card.id):\(note.id)" }
}
