import SwiftUI

struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @State private var filter: LibraryKind?

    private var items: [LibraryItem] {
        let all = model.library.libraryItems()
        switch filter {
        case .none: return all
        case .albums: return all.filter { $0.kind == .album }
        case .artists: return all.filter { $0.kind == .artist }
        case .playlists: return all.filter { $0.kind == .playlist }
        case .songs, .favorites: return all.filter { $0.kind == .album }
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Library")
                    .font(.gsf(34, .medium))
                    .foregroundStyle(Yoin.ink)
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                chips
                sortRow
                    .padding(.horizontal, 16)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 14, alignment: .top)],
                          alignment: .leading, spacing: 20) {
                    ForEach(items) { item in
                        LibraryTile(item: item)
                    }
                }
                .padding(.horizontal, 16)
                .animation(Yoin.spatial, value: filter)
            }
        }
        .contentMargins(.bottom, 24, for: .scrollContent)
        .scrollEdgeEffectStyle(.soft, for: .all)
        .background(Yoin.surface)
    }

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                if filter != nil {
                    Button { withAnimation(Yoin.spatial) { filter = nil } } label: {
                        YoinIcon("close", size: 18)
                            .foregroundStyle(Yoin.ink)
                            .frame(width: 40, height: 40)
                            .background(Yoin.surfaceContainerHigh, in: .circle)
                    }
                    .buttonStyle(.plain)
                    .transition(.scale.combined(with: .opacity))
                    .accessibilityLabel("Show all")
                }
                ForEach([LibraryKind.playlists, .artists, .albums, .songs, .favorites], id: \.self) { kind in
                    let on = filter == kind
                    Button {
                        withAnimation(Yoin.spatial) { filter = on ? nil : kind }
                    } label: {
                        Text(kind.rawValue)
                            .font(.gsf(15, .medium))
                            .foregroundStyle(on ? .white : Yoin.ink)
                            .padding(.horizontal, 16)
                            .frame(height: 40)
                            .background(on ? model.accent : Yoin.surfaceContainerHigh, in: .capsule)
                    }
                    .buttonStyle(PressScale())
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
    }

    private var sortRow: some View {
        HStack(spacing: 6) {
            YoinIcon("sort", size: 20)
            Text("Recents")
                .font(.gsf(15, .medium))
            Spacer()
            YoinIcon("filter", size: 20)
                .foregroundStyle(Yoin.inkVariant)
        }
        .foregroundStyle(Yoin.ink)
    }
}

private struct LibraryTile: View {
    @Environment(AppModel.self) private var model
    @Environment(\.zoomNamespace) private var zoomNS
    var item: LibraryItem

    var body: some View {
        Button {
            if let album = item.album { model.open(album) }
        } label: {
            VStack(alignment: item.kind == .artist ? .center : .leading, spacing: 6) {
                CoverArt(palette: item.palette, cornerRadius: item.kind == .artist ? 400 : 8,
                         seed: item.id.rawId.count)
                    .aspectRatio(1, contentMode: .fit)
                    .zoomSource(item.id, zoomNS)
                Text(item.title)
                    .font(.gsf(15, .semibold))
                    .foregroundStyle(Yoin.ink)
                    .lineLimit(1)
                Text(item.subtitle)
                    .font(.gsf(13))
                    .foregroundStyle(Yoin.inkVariant)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: item.kind == .artist ? .center : .leading)
        }
        .buttonStyle(PressScale())
    }
}
