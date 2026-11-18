import SwiftUI

/// Home's Memories grid (Android ui/home/HomeMemoryLedger.kt, #186 "B3"): how much you've written, and the
/// calendar wall of it, a cell a month, week or day as you write more. A tap opens All Memories. Hidden while
/// there's nothing.
struct HomeMemoryLedgerSection: View {
    @Environment(AppModel.self) private var model
    @Environment(\.homeEdit) private var edit
    @Environment(\.homeScheme) private var scheme
    var state: LedgerState
    /// In place of opening All Memories (the tip page's demo phone).
    var onOpen: (() -> Void)? = nil

    var body: some View {
        let s = scheme ?? .brand(dark: false)
        VStack(alignment: .leading, spacing: 12) {
            HomeSectionTitle(HomeSection.memoryGrid.title)
            Button { homeTap(edit) { if let onOpen { onOpen() } else { model.path.append(.allMemories) } } } label: {
                VStack(alignment: .leading, spacing: 12) {
                    // What you wrote leads; with only scores so far, the scores.
                    let wrote = state.stats.writings > 0
                    let count = wrote ? state.stats.writings : state.entries.count
                    HStack(alignment: .lastTextBaseline, spacing: 6) {
                        Text(count.formatted())
                            .homeType(Tokens.type.titleLarge, .semibold, size: 24)
                            .foregroundStyle(s.onSurface)
                            .contentTransition(.numericText())
                        Text(wrote ? "memories written" : "scores")
                            .homeType(Tokens.type.labelMedium)
                            .foregroundStyle(s.onSurfaceVariant)
                    }
                    MemoryGridView(grid: state.grid, covers: state.covers, placement: .home,
                                   faint: s.onSurface.opacity(0.07), outline: s.primary, label: s.onSurfaceVariant)
                        .allowsHitTesting(false)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                // A tablet's wide feed would blow a month's quads up past a cover; the card stays phone-wide.
                .frame(maxWidth: 560, alignment: .leading)
                .background(s.surfaceContainerLow, in: .rect(cornerRadius: 28))
                .contentShape(.rect(cornerRadius: 28))
            }
            .buttonStyle(PressScale())
            .accessibilityLabel(Text("Open All Memories"))
        }
        // The card stays phone-wide; the section keeps to the feed's leading edge like the others.
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
