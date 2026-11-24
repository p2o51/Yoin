import SwiftUI
import YoinShared

/// The write bar's formatting row beside Save (Android NoteFormatToolbar, prototype: @, B, I, H, list, to-do).
/// Each button reads as pressed where the caret is (bold words, a list line). Bold / italic / heading are
/// letters, as on Android; @ and the to-do box are Yoin Symbols; the list is a bullet (Yoin Symbols has no
/// list glyph yet).
struct NoteFormatRow: View {
    var model: NoteEditorModel
    var scheme: CoverScheme

    static let order: [NoteTool] = [.mention, .bold, .italic, .heading, .bullet, .checklist]

    var body: some View {
        let active = model.activeTools
        HStack(spacing: 2) {
            ForEach(Self.order, id: \.self) { tool in
                let on = active.contains(tool)
                Button {
                    UISelectionFeedbackGenerator().selectionChanged()
                    model.apply(tool)
                } label: {
                    glyph(tool)
                        .foregroundStyle(on ? scheme.onSecondaryContainer : scheme.onSurfaceVariant)
                        .frame(width: 36, height: 36)
                        .background(on ? scheme.secondaryContainer : .clear,
                                    in: RoundedRectangle(cornerRadius: on ? 10 : 18, style: .continuous))
                        .contentShape(.rect)
                }
                .buttonStyle(PressScale())
                .animation(Yoin.spatialFast, value: on)
                .accessibilityLabel(Self.label(tool))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    @ViewBuilder private func glyph(_ tool: NoteTool) -> some View {
        switch tool {
        case .mention: Image("yoin.at").font(.system(size: 18))
        case .checklist: Image("yoin.check_box").font(.system(size: 18))
        case .bold: Text(verbatim: "B").font(.gsf(17, .black))
        case .italic: Text(verbatim: "I").font(NoteMarkupRender.font(size: 17, weight: .semibold, italic: true))
        case .heading: Text(verbatim: "H").font(.gsf(17, .bold))
        default: Text(verbatim: "•").font(.gsf(22, .black))
        }
    }

    static func label(_ tool: NoteTool) -> String {
        switch tool {
        case .bold: String(localized: "Bold")
        case .italic: String(localized: "Italic")
        case .heading: String(localized: "Heading")
        case .bullet: String(localized: "List")
        case .checklist: String(localized: "To-do")
        default: String(localized: "Mention a song, album, or artist")
        }
    }
}
