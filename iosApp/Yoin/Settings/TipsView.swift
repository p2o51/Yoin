import SwiftUI

/// Settings › About › Tips (Android TipsPage): every tip, read ones checked; a tap opens it on its own page
/// (Tips/TipView.swift), where it is marked read. Once something is read, one more row marks them all unread,
/// so Home's bubble brings them up again. [focus] is a tip to open the list on: scrolled to, lit for a moment,
/// and marked read.
struct TipsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    var focus: String?
    private let store = TipReadStore.shared
    @State private var lit: String?

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("Tips")
                        .yoinRole(Tokens.type.displaySmall)
                        .foregroundStyle(app.onSurface)
                        .padding(.horizontal, 8)
                        .padding(.top, 8)
                    Text(Tips.readSummary(read: store.read.count, total: Tips.all.count))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .padding(.horizontal, 8)
                        .contentTransition(.numericText())
                    VStack(spacing: 2) {
                        ForEach(Array(Tips.all.enumerated()), id: \.element.id) { i, tip in
                            row(tip)
                                .background(lit == tip.id ? app.primaryContainer : app.surfaceBright,
                                            in: CloudSyncView.segment(i, Tips.all.count))
                                .id(tip.id)
                        }
                    }
                    if !store.read.isEmpty {
                        Button { store.setAllUnread() } label: {
                            HStack(spacing: 16) {
                                YoinIcon("refresh", size: 22).foregroundStyle(app.onSurfaceVariant)
                                Text("Mark all as unread")
                                    .yoinRole(Tokens.type.bodyLarge)
                                    .foregroundStyle(app.onSurface)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain).pointerHighlight()
                        .background(app.surfaceBright, in: CloudSyncView.segment(0, 1))
                        .transition(.opacity)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 40 + ShellBarMetrics.clearance)
                .animation(Yoin.effects, value: store.read)
            }
            .safeAreaBar(edge: .top) { topBar }
            .background(app.surfaceContainer.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .task {
                guard let focus, let tip = Tips.tip(focus) else { return }
                try? await Task.sleep(for: .milliseconds(250))
                withAnimation(Yoin.spatial) { proxy.scrollTo(tip.id, anchor: .center) }
                withAnimation(Yoin.effects) { lit = tip.id }
                store.setRead(tip, true)
                try? await Task.sleep(for: .seconds(1.6))
                withAnimation(Yoin.effects) { lit = nil }
            }
        }
    }

    private func row(_ tip: Tip) -> some View {
        let read = store.isRead(tip)
        return NavigationLink { TipView(start: tip.id) } label: {
            HStack(alignment: .top, spacing: 16) {
                YoinIcon(tip.icon, size: 22)
                    .foregroundStyle(app.onSecondaryContainer)
                    .frame(width: 40, height: 40)
                    .background(app.secondaryContainer, in: .circle)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tip.shownTitle)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(tip.shownText)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(Tips.updatedLine(tip))
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .padding(.top, 2)
                }
                Spacer(minLength: 8)
                if read {
                    YoinIcon("check", size: 22).foregroundStyle(app.primary)
                        .frame(width: 40, height: 40)
                        .transition(.opacity)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain).pointerHighlight()
        .accessibilityElement(children: .combine)
        .accessibilityValue(read ? String(localized: "Read") : String(localized: "Unread"))
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .yGlassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            .settingsPaneBack()
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }
}

/// Settings › About › Tips: the row that opens the page, its summary the read count.
struct TipsEntryRow: View {
    var app: CoverScheme
    private let store = TipReadStore.shared
    /// The two-pane Settings' list: the row picks Tips beside it.
    @Environment(\.settingsList) private var list

    var body: some View {
        if let list {
            Button { list.pick(.tips) } label: { label }
                .buttonStyle(.plain).pointerHighlight()
        } else {
            NavigationLink { TipsView(focus: nil) } label: { label }
                .buttonStyle(.plain).pointerHighlight()
        }
    }

    private var label: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Tips")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Text(Tips.readSummary(read: store.read.count, total: Tips.all.count))
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
            }
            Spacer(minLength: 16)
            YoinIcon("chevron_right", size: 20).foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(.rect)
    }
}
