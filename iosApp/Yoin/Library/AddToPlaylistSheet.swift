import SwiftUI

/// Android `component/AddToPlaylistSheet`: "Add to playlist" (titleMedium),
/// then Create new playlist… (a 40 primaryContainer tile with +, the label
/// in primary) and the playlists the user can write (40 Thumb cover, name
/// bodyMedium over "N songs" bodySmall). Opens at half height, pulls to
/// full; the system's glass sheet. Picking a row closes the sheet and adds;
/// Create asks for a name first.
struct AddToPlaylistSheet: View {
    var entries: [PlaylistEntry]

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var naming = false
    @State private var name = ""
    @State private var picked = 0

    private var actions: PlaylistActions { .shared }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Text("Add to playlist")
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 8)
                createRow
                if let picks = actions.picks {
                    ForEach(picks) { pick in row(pick) }
                } else {
                    ForEach(0..<3, id: \.self) { _ in placeholderRow }
                }
            }
            .padding(.top, 20)
            .animation(Yoin.spatial, value: actions.picks)
        }
        .contentMargins(.bottom, 16, for: .scrollContent)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationContentInteraction(.scrolls)
        .sensoryFeedback(.selection, trigger: picked)
        .task { await actions.loadPicks(model.store, library: model.library) }
        .alert("New playlist", isPresented: $naming) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Create") {
                let n = name
                dismiss()
                actions.create(n, with: entries, store: model.store)
            }
            .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private var createRow: some View {
        Button {
            picked += 1
            name = ""
            naming = true
        } label: {
            HStack(spacing: 12) {
                YoinIcon("add", size: 24)
                    .foregroundStyle(app.onPrimaryContainer)
                    .frame(width: 40, height: 40)
                    .background(app.primaryContainer, in: .rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                Text("Create new playlist…")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.primary)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
    }

    private func row(_ pick: PlaylistActions.Pick) -> some View {
        Button {
            picked += 1
            dismiss()
            actions.add(entries, to: pick, store: model.store)
        } label: {
            HStack(spacing: 12) {
                CoverArt(palette: pick.palette, cornerRadius: 0)
                    .frame(width: 40, height: 40)
                    .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    Text(pick.name).lineLimit(1)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurface)
                    if let n = pick.songCount {
                        Text(n == 1 ? String(localized: "1 song") : String(localized: "\(n) songs")).monospacedDigit()
                            .yoinRole(Tokens.type.bodySmall)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 8)
            .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .transition(.opacity)
    }

    private var placeholderRow: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous)
                .fill(app.surfaceVariant.opacity(0.6))
                .frame(width: 40, height: 40)
            Capsule().fill(app.surfaceVariant.opacity(0.6)).frame(width: 140, height: 12)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 8)
    }
}
