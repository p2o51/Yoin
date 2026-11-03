import SwiftUI

/// Yoin's 3-part button group in Liquid Glass:
/// Home · Now Playing pill · Library, plus a separate Search circle.
/// On a pushed album page the same glass pieces morph into
/// [Play | ▾] · mini pill.
struct ShellBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassFlavor) private var flavor
    var npNamespace: Namespace.ID
    @Namespace private var glassNS

    private let height: CGFloat = 60

    var body: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 6) {
                if model.isDetail, let album = model.detailAlbum {
                    playSplit(album)
                } else {
                    navButton(.home)
                }
                pill
                if !model.isDetail {
                    navButton(.library)
                    searchButton
                        .padding(.leading, 6)
                }
            }
        }
        .animation(Yoin.spatial, value: model.isDetail)
        .animation(Yoin.spatial, value: model.section)
        .frame(height: height)
    }

    // MARK: Nav buttons

    private func navButton(_ s: ShellSection) -> some View {
        let selected = model.section == s
        let icon = switch s {
        case .home: selected ? "home_filled" : "home"
        case .library: selected ? "library_filled" : "library"
        }
        return Button {
            model.select(s)
        } label: {
            YoinIcon(icon, size: 24)
                .foregroundStyle(selected ? Color.white : Yoin.ink)
                .frame(width: selected ? 68 : 54, height: height)
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .yoinGlass(Capsule(), tint: model.accent, prominent: selected, flavor: flavor)
        .glassEffectID(s == .home ? "home" : "library", in: glassNS)
        .accessibilityLabel(s == .home ? "Home" : "Library")
    }

    private var searchButton: some View {
        Button {} label: {
            YoinIcon("search", size: 24)
                .foregroundStyle(Yoin.ink)
                .frame(width: height, height: height)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .yoinGlass(Circle(), tint: model.accent, flavor: flavor)
        .glassEffectID("search", in: glassNS)
        .accessibilityLabel("Search")
    }

    // MARK: Now Playing pill

    private var pill: some View {
        let p = model.playback
        return Button {
            model.setNowPlaying(expanded: true)
        } label: {
            HStack(spacing: 10) {
                CoverArt(palette: p.album.palette, cornerRadius: 6, seed: p.album.seed)
                    .frame(width: 42, height: 42)
                    .matchedGeometryEffect(id: "np.cover", in: npNamespace, isSource: !model.npExpanded)
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.track.title)
                        .font(.gsf(15, .semibold))
                        .foregroundStyle(Yoin.ink)
                    Text(p.album.artist)
                        .font(.gsf(13))
                        .foregroundStyle(Yoin.inkVariant)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.leading, 9)
            .padding(.trailing, 12)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .yoinGlass(Capsule(), tint: model.accent, flavor: flavor)
        .glassEffectID("np", in: glassNS)
        .background {
            Capsule().fill(.clear)
                .matchedGeometryEffect(id: "np.surface", in: npNamespace, isSource: !model.npExpanded)
        }
        .accessibilityLabel("Now playing, \(p.track.title) by \(p.album.artist)")
    }

    // MARK: Album page form

    private func playSplit(_ album: Album) -> some View {
        HStack(spacing: 3) {
            Button {} label: {
                HStack(spacing: 8) {
                    YoinIcon("play_filled", size: 22)
                    Text("Play").font(.gsf(17, .semibold))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 20)
                .frame(height: height)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .yoinGlass(UnevenRoundedRectangle(topLeadingRadius: 30, bottomLeadingRadius: 30,
                                              bottomTrailingRadius: 8, topTrailingRadius: 8),
                       tint: album.palette.tint, prominent: true, flavor: flavor)
            .glassEffectID("home", in: glassNS)

            Button {} label: {
                YoinIcon("chevron_down", size: 22)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: height)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .yoinGlass(UnevenRoundedRectangle(topLeadingRadius: 8, bottomLeadingRadius: 8,
                                              bottomTrailingRadius: 30, topTrailingRadius: 30),
                       tint: album.palette.tint, prominent: true, flavor: flavor)
            .glassEffectID("split", in: glassNS)
            .accessibilityLabel("More play options")
        }
    }
}
