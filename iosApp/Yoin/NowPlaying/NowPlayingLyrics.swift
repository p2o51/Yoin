import SwiftUI

/// The compact lyric window's lines, anchored so the active line's box sits
/// at `anchor`. Equatable on what it draws, so the 4 Hz position ticks of a
/// surrounding `PlaybackPosition` only re-render it when the line changes.
///
/// Lines wrap (Android LyricLineItem has no line limit): each row's height
/// is measured, and the list scrolls by the measured rows above the active
/// one. `wraps == false` keeps the one-line modes (short screens' one-line
/// row, focus) on one line. `solo`: focus's preview — the same list with
/// only the active line shown, so compact ⇄ focus moves ONE line from its
/// list anchor to the preview's, never two copies cross-fading.
struct LyricLinesView: View, Equatable {
    var lines: [String]
    /// -1 = no line is active yet (before the first timestamp).
    var active: Int
    var anchor: CGFloat
    var primary: Color
    var onSurfaceVariant: Color
    var wraps = true
    var solo = false

    var body: some View {
        LyricLinesBody(lines: lines, active: active, anchor: anchor, primary: primary,
                       onSurfaceVariant: onSurfaceVariant, wraps: wraps, solo: solo)
    }
}

/// LyricLinesView's rows and their measured heights.
private struct LyricLinesBody: View {
    var lines: [String]
    var active: Int
    var anchor: CGFloat
    var primary: Color
    var onSurfaceVariant: Color
    var wraps: Bool
    var solo: Bool

    @State private var heights: [Int: CGFloat] = [:]

    /// Before measuring: a one-line inactive row (bodyMedium line + pads).
    private static let nominalRow = Tokens.type.bodyMedium.lineHeight.cg + 8
    private static let spacing: CGFloat = 2

    var body: some View {
        let shown = max(active, 0)
        let above = (0..<min(shown, lines.count)).reduce(CGFloat(0)) { $0 + (heights[$1] ?? Self.nominalRow) + Self.spacing }
        VStack(alignment: .leading, spacing: Self.spacing) {
            ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                let d = abs(i - shown)
                let isActive = i == active
                // LyricLineItem: active bodyLarge (16/24) rounded SemiBold
                // in primary, else bodyMedium (14/20) onSurfaceVariant;
                // each line in its role's line box, 4 above and below.
                Text(line)
                    .font(isActive ? YoinFont.rounded(16, semibold: true) : .gsf(14))
                    .tracking(0)
                    .lineLimit(wraps ? nil : 1)
                    .lineSpacing(YoinType.lineSpacing(size: isActive ? 16 : 14,
                                                      lineHeight: isActive ? Tokens.type.bodyLarge.lineHeight.cg
                                                          : Tokens.type.bodyMedium.lineHeight.cg))
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(minHeight: isActive ? Tokens.type.bodyLarge.lineHeight.cg : Tokens.type.bodyMedium.lineHeight.cg)
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { heights[i] = $0 }
                    // Android: color and alpha on the effects spring, the
                    // scale (the line landing) on the spatial one.
                    .animation(Yoin.effects) {
                        $0.foregroundStyle(isActive ? primary : onSurfaceVariant)
                            .opacity(solo && !isActive ? 0
                                     : isActive ? 1 : (d <= 1 ? 0.55 : d == 2 ? 0.40 : 0.28))
                    }
                    .animation(Yoin.spatial) { $0.scaleEffect(isActive ? 1 : 0.94, anchor: .leading) }
            }
        }
        // Scroll so the active line's box (with its 4 pt pad) sits at
        // `anchor`; keyed on the offset itself, so a row measured a frame
        // late (a wrap) glides too.
        .offset(y: anchor - 4 - above)
        .animation(Yoin.spatial, value: anchor - 4 - above)
    }
}

/// Unsynced lyrics: every line, plain, scrollable by hand (Android hands
/// the scroll to the user when there are no timestamps).
struct UnsyncedLyricsView: View {
    var lines: [String]
    var onSurfaceVariant: Color

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                    Text(line.isEmpty ? " " : line)
                        .font(.gsf(14))
                        .tracking(0)
                        .foregroundStyle(onSurfaceVariant)
                        .padding(.vertical, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.vertical, 12)
        }
    }
}

/// Android `NoteCompactPane`: the song's notes read like lyrics — plain
/// timeline-ordered lines, a moment stamp column, the line the playhead is
/// inside lit up and held ~22 % from the top. No cards, no controls.
struct NoteLinesView: View, Equatable {
    var notes: [SongNote]
    var activeId: String?
    var primary: Color
    var onSurfaceVariant: Color

    /// Android `NoteStampColumnWidth`.
    static let stampColumn: CGFloat = 40

    private var activeIndex: Int? { activeId.flatMap { id in notes.firstIndex { $0.id == id } } }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(notes) { note in
                        line(note, distance: activeIndex.flatMap { a in notes.firstIndex(of: note).map { abs($0 - a) } })
                            .id(note.id)
                    }
                }
                .padding(.vertical, 12)
            }
            .scrollDisabled(true)
            .onAppear { anchor(proxy, animated: false) }
            .onChange(of: activeId) { anchor(proxy, animated: true) }
            .onChange(of: notes.count) { anchor(proxy, animated: true) }
        }
    }

    private func anchor(_ proxy: ScrollViewProxy, animated: Bool) {
        guard let id = activeId ?? notes.last?.id else { return }
        let target = UnitPoint(x: 0, y: 0.22)
        if animated {
            withAnimation(Yoin.spatial) { proxy.scrollTo(id, anchor: target) }
        } else {
            proxy.scrollTo(id, anchor: target)
        }
    }

    /// Android `NoteLine` at Glance emphasis.
    private func line(_ note: SongNote, distance: Int?) -> some View {
        let isActive = note.id == activeId
        return HStack(alignment: .firstTextBaseline, spacing: 0) {
            Group {
                if let ms = note.positionMs {
                    // labelSmall (11/16), tabular; SemiBold when current.
                    Text(NotesStore.formatPosition(ms))
                        .font(.gsf(11, isActive ? .semibold : .medium))
                        .tracking(0.1)
                        .monospacedDigit()
                        .foregroundStyle(isActive ? primary : onSurfaceVariant)
                } else {
                    // No moment: a quiet hollow dot.
                    Circle().strokeBorder(onSurfaceVariant, lineWidth: 1.5)
                        .frame(width: 6, height: 6)
                        .padding(.leading, 2)
                }
            }
            .frame(width: Self.stampColumn, alignment: .leading)
            // Glance: bodyMedium Medium, the current line bodyLarge Bold.
            Text(note.content)
                .font(isActive ? .gsf(16, .bold) : .gsf(14, .medium))
                .tracking(0)
                .foregroundStyle(isActive ? primary : onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .opacity(Self.alpha(isActive: isActive, distance: distance))
        .scaleEffect(isActive ? 1 : 0.94, anchor: .leading)
        .animation(Yoin.effects, value: isActive)
    }

    /// Android `noteLineAlpha` (Glance).
    static func alpha(isActive: Bool, distance: Int?) -> Double {
        if isActive { return 1 }
        guard let distance else { return 0.28 }
        return distance <= 1 ? 0.55 : distance == 2 ? 0.40 : 0.28
    }
}

/// Android `NoteWriteBar`, open: the moment the note is filed at in a stamp,
/// the words in a plain field (no box around them), Save underneath. Tapping
/// the stamp moves the moment to now.
struct NoteComposer: View {
    var scheme: CoverScheme
    @Binding var text: String
    var anchorMs: Int64?
    var focus: FocusState<Bool>.Binding
    var onRealign: () -> Void
    var onSave: () -> Void
    /// Editing a saved note: Cancel beside Save leaves it as it was.
    var onCancel: (() -> Void)? = nil

    private var canSave: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Button(action: onRealign) {
                    Text(anchorMs.map(NotesStore.formatPosition) ?? "0:00")
                        .yoinRole(Tokens.type.labelLarge)
                        .monospacedDigit()
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .frame(height: 36)
                        .background(scheme.primary.opacity(0.12), in: Capsule())
                        .contentShape(.capsule)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Moment")
                TextField(text: $text, prompt: Text("Write a note")
                    .foregroundStyle(scheme.onSurfaceVariant.opacity(0.8)), axis: .vertical) {
                    Text("Note")
                }
                .font(.gsf(16))
                .tracking(0)
                .foregroundStyle(scheme.onSurface)
                .tint(scheme.primary)
                .lineLimit(2...6)
                .focused(focus)
                // First line centered on the 36 pt stamp (bodyLarge 24 pt line).
                .padding(.top, 6)
            }
            HStack(spacing: 4) {
                Spacer(minLength: 0)
                if let onCancel {
                    Button(action: onCancel) {
                        Text("Cancel")
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(scheme.primary)
                            .padding(.horizontal, 12)
                            .frame(height: 36)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressScale())
                    .transition(.opacity)
                }
                Button(action: onSave) {
                    Text("Save")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(canSave ? scheme.onPrimary : scheme.onSurfaceVariant.opacity(0.5))
                        .padding(.horizontal, 16)
                        .frame(height: 36)
                        .background(canSave ? scheme.primary : scheme.surfaceContainerHighest, in: Capsule())
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .disabled(!canSave)
                .animation(Yoin.effects, value: canSave)
            }
            .padding(.leading, 4)
        }
        .padding(.leading, 10)
        .padding(.trailing, 14)
        .padding(.vertical, 10)
        .background(scheme.surfaceContainerHigh.opacity(0.92),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// The lyric stream's direction on a song change (Android
/// LyricsTrackTransition's `forward`): decided when the song key changes,
/// from the queue index the outgoing song had. A reference, so the leaving
/// song's transition (built on its last render) reads the new direction.
final class LyricsDirection {
    private(set) var forward = true
    private var key: String?
    private var index: Int?

    func update(key: String, index: Int?) {
        guard key != self.key else { return }
        if let index, let last = self.index { forward = index >= last }
        self.key = key
        self.index = index
    }
}

/// The song-change slide: in from a quarter below, out a quarter above
/// (mirrored stepping back), fading on the same spring.
struct LyricsTrackSlide: Transition {
    let direction: LyricsDirection
    let distance: CGFloat

    func body(content: Content, phase: TransitionPhase) -> some View {
        let sign: CGFloat = direction.forward ? 1 : -1
        content
            .offset(y: phase == .willAppear ? sign * distance : phase == .didDisappear ? -sign * distance : 0)
            .opacity(phase.isIdentity ? 1 : 0)
    }
}
