import SwiftUI

/// All Memories (Android ui/tips/TipMemoryLedgerScene.kt, owner 2026-11-08): Home's Memories grid opens the
/// page, and a tap on a cell narrows the list to that stretch. The real Home card and the real page, over
/// made-up memories.
struct MemoryLedgerDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var state = TipDemo.ledgerState()
    @State private var open = false
    @State private var selected: Int?

    private func show(_ page: Bool) {
        withAnimation(Yoin.effects) { open = page }
    }

    var body: some View {
        let pick = TipDemo.ledgerPick(state.grid)
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            show(false)
            withAnimation(Yoin.spatial) { selected = nil }
            try await DemoTiming.delay(900)
            try await finger.moveTo("ledgerHome", fx: 0.5, fy: 0.6)
            try await finger.tap { show(true) }
            try await DemoTiming.delay(1_100)
            if let pick {
                let cell = TipDemo.weekCellFraction(pick)
                try await finger.moveTo("ledgerGrid", fx: cell.x, fy: cell.y)
                try await finger.tap { withAnimation(Yoin.spatial) { selected = pick } }
            }
            try await DemoTiming.delay(2_600)
        }) {
            ZStack {
                if open {
                    AllMemoriesView(demo: AllMemoriesDemo(state: state, selected: $selected) { show(false) })
                        .safeAreaPadding(.top, DemoPhoneMetrics.statusBar)
                        .transition(.opacity)
                } else {
                    HomeMemoryLedgerSection(state: state) { show(true) }
                        .demoTarget("ledgerHome")
                        .padding(.horizontal, DemoHomeMetrics.margin)
                        .padding(.vertical, 24)
                        .padding(.top, DemoPhoneMetrics.statusBar)
                        .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .top)
                        .transition(.opacity)
                }
            }
            .environment(\.homeScheme, scheme)
        }
    }
}
