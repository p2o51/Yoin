import SwiftUI

/// Now Playing's Queue pill: the current song, then what plays next. Tap a
/// row to play it, long-press and drag to reorder, swipe to remove (the
/// current song stays, Android rule). Rows are the Library's song rows.
struct QueueSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    private var engine: PlayerEngine { model.player }
    private var scheme: CoverScheme { CoverScheme(album: model.playback.album, dark: colorScheme == .dark) }

    var body: some View {
        let start = (engine.currentIndex ?? -1) + 1
        let upcoming = Array(engine.upcoming)
        List {
            if let current = engine.currentItem {
                Section {
                    row(current, index: engine.currentIndex ?? 0, current: true)
                } header: {
                    header("Now playing")
                }
            }
            Section {
                ForEach(Array(upcoming.enumerated()), id: \.element.id) { offset, item in
                    row(item, index: start + offset, current: false)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                withAnimation(Yoin.spatial) { engine.remove(id: item.id) }
                            } label: {
                                Label { Text("Remove") } icon: { YoinIcon("close", size: 20) }
                            }
                        }
                }
                .onMove { offsets, destination in
                    engine.move(fromOffsets: IndexSet(offsets.map { $0 + start }), toOffset: destination + start)
                }
            } header: {
                header(upcoming.isEmpty ? "Nothing up next" : "Up next")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(scheme.surfaceContainer)
        .safeAreaInset(edge: .top, spacing: 0) { title }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(scheme.surfaceContainer)
    }

    private var title: some View {
        HStack {
            Text("Queue")
                .yoinRole(Tokens.type.titleLarge)
                .foregroundStyle(scheme.onSurface)
            Spacer()
            Text("\(engine.queue.count) songs")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSurfaceVariant)
        }
        .padding(.horizontal, Tokens.library.ROW_PADDING_H.cg)
        .padding(.top, 20)
        .padding(.bottom, 4)
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(scheme.onSurfaceVariant)
            .textCase(nil)
    }

    private func row(_ item: QueueItem, index: Int, current: Bool) -> some View {
        Button {
            if !current { engine.jump(to: index) }
        } label: {
            LibrarySongRow(album: AppModel.album(for: item.track, library: model.library),
                           track: AppModel.track(for: item.track), current: current, scheme: scheme,
                           horizontalPadding: 0)
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: Tokens.library.ROW_PADDING_H.cg,
                                  bottom: 0, trailing: Tokens.library.ROW_PADDING_H.cg))
        .moveDisabled(current)
        .accessibilityHint(current ? "Playing" : "Plays this song")
    }
}
