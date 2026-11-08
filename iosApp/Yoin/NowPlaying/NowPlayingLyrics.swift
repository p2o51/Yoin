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
    /// The song's outro (UpNextTiming), quantized: nil before its window.
    var outro: Outro? = nil

    /// Android LyricsDisplay's outro: the last line held wide (`stretch`,
    /// focus only), the next song's title and first line rising faintly under
    /// it (`reveal`), and at the hand-over that line taking the stage.
    struct Outro: Equatable {
        var stretch: CGFloat
        var reveal: CGFloat
        var handingOver: Bool
        var next: UpNextTiming.Next?
    }

    var body: some View {
        LyricLinesBody(lines: lines, active: active, anchor: anchor, primary: primary,
                       onSurfaceVariant: onSurfaceVariant, wraps: wraps, solo: solo, outro: outro)
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
    var outro: LyricLinesView.Outro?

    @State private var heights: [Int: CGFloat] = [:]
    @State private var labelHeight: CGFloat = 0
    /// The list's scroll, driven on its own (see the offset below); nil until first laid out.
    @State private var scrollY: CGFloat?
    /// Android UpNextRise / UpNextLabelGap.
    private static let rise: CGFloat = 24
    private static let labelGap: CGFloat = 20

    /// Before measuring: a one-line inactive row (bodyMedium line + pads).
    private static let nominalRow = Tokens.type.bodyMedium.lineHeight.cg + 8
    private static let spacing: CGFloat = 2

    var body: some View {
        let shown = max(active, 0)
        let next = outro?.next
        let handing = outro?.handingOver == true && next != nil
        // The hand-over parks the next song's line where its own list opens
        // (its first line at the anchor), so the song change swaps in place.
        let aboveCount = handing ? lines.count : min(shown, lines.count)
        let above = (0..<aboveCount).reduce(CGFloat(0)) { $0 + (heights[$1] ?? Self.nominalRow) + Self.spacing }
            + (handing ? labelHeight + Self.spacing : 0)
        // The next song waits out the first half of the window, then surfaces
        // faintly (FastOutSlowIn); at the hand-over it comes to full strength.
        let ease = Self.fastOutSlowIn(min(max(((outro?.reveal ?? 0) - 0.5) / 0.5, 0), 1))
        let nextAlpha = next == nil ? 0 : handing ? 1 : UpNextTiming.revealAlpha * ease
        let nextRise = handing ? 0 : (1 - ease) * Self.rise
        let block: Double = handing ? 0 : 1
        VStack(alignment: .leading, spacing: Self.spacing) {
            ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                let d = abs(i - shown)
                let isActive = i == active && !handing
                // I-2: the last line held wide from its left edge, at draw
                // time (it never re-wraps), in focus only.
                let held = solo && isActive && i == lines.count - 1 ? (outro?.stretch ?? 0) : 0
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
                    .animation(held > 0 ? Yoin.slowSpatial : Yoin.spatial) {
                        $0.scaleEffect(x: 1 + UpNextTiming.lastLineStretchMax * held, y: 1, anchor: .leading)
                    }
                    .animation(Yoin.slowEffects) { $0.opacity(block) }
            }
            if let next {
                // "Up next · title", then the next song's first line as its own
                // list shows it before it starts (an inactive line).
                Text("Up next \u{00B7} \(next.title)")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(onSurfaceVariant)
                    .lineLimit(1)
                    .padding(.top, Self.labelGap)
                    .padding(.bottom, 2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { labelHeight = $0 }
                    .animation(Yoin.slowEffects) { $0.opacity(nextAlpha * block) }
                    .animation(Yoin.slowSpatial) { $0.offset(y: nextRise) }
                Text(next.firstLine)
                    .font(.gsf(14))
                    .tracking(0)
                    .lineLimit(wraps ? nil : 1)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(minHeight: Tokens.type.bodyMedium.lineHeight.cg)
                    .padding(.vertical, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(onSurfaceVariant)
                    .scaleEffect(0.94, anchor: .leading)
                    // Where the next list opens it: the first line at 0.55.
                    .animation(Yoin.slowEffects) { $0.opacity(nextAlpha * 0.55) }
                    .animation(Yoin.slowSpatial) { $0.offset(y: nextRise) }
            }
        }
        // Every row at its full height: the window offers the list less height
        // than it needs, and the stack squeezed each row's min-height frame
        // below its wrapped text, which then drew over the next row.
        .fixedSize(horizontal: false, vertical: true)
        // Scroll so the active line's box (with its 4 pt pad) sits at
        // `anchor`. Only the scroll animates (Android: the list reflows at
        // once, the scroll springs): a value-keyed animation here also sprang
        // the rows themselves, so a line that wraps as it lights up overlapped
        // the next one until the spring settled. A row measured a frame late
        // (a wrap) still glides, as the offset follows it.
        // No overshoot (Android animateScrollToItem): a bouncing list swung the
        // lines past their places and back on every advance.
        //
        // The offset is its own state, moved in its own transaction: the
        // body-scoped `.animation(_:body:)` never animated it (measured on the
        // simulator: the list jumped ~34 pt in one frame), and a value-keyed
        // animation on the list also sprang the rows (ghosted letters).
        .offset(y: scrollY ?? anchor - 4 - above)
        .onAppear { scrollY = anchor - 4 - above }
        .onChange(of: anchor - 4 - above) { _, target in
            guard scrollY != nil else { scrollY = target; return }
            withAnimation(handing ? Yoin.slowSpatial : Self.scroll) { scrollY = target }
        }
    }

    /// The list's glide to the next line: the spatial spring's pace, critically damped.
    @MainActor static var scroll: Animation {
        .spring(response: 0.42, dampingFraction: 1).speed(LaunchConfig.animationSpeed)
    }

    /// Material FastOutSlowIn (cubic 0.4, 0, 0.2, 1).
    static func fastOutSlowIn(_ x: CGFloat) -> CGFloat {
        var lo: CGFloat = 0, hi: CGFloat = 1
        for _ in 0..<20 {
            let t = (lo + hi) / 2
            let bx = 3 * (1 - t) * (1 - t) * t * 0.4 + 3 * (1 - t) * t * t * 0.2 + t * t * t
            if bx < x { lo = t } else { hi = t }
        }
        let t = (lo + hi) / 2
        return 3 * (1 - t) * t * t * 1 + t * t * t
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
    /// The song change swaps in place (no slide): the outro already parked
    /// the next song's first line where its list opens, or the previous
    /// change's slide is still running (two Next taps in quick succession: a
    /// second slide drew the middle song's lines over the other two).
    private(set) var inPlace = false
    /// The in-place swap is a rapid second change (not the outro's): the
    /// leaving list goes at once instead of riding out the skip's animation.
    private(set) var rapid = false
    /// Set by the outro once it has handed over to this song.
    var inPlaceNext: String?
    private var key: String?
    private var index: Int?
    private var changedAt: CFTimeInterval = -.infinity

    /// About the spatial spring's settle: a change within it lands as a cut.
    nonisolated static let rapidWindow: CFTimeInterval = 0.45

    func update(key: String, index: Int?, now: CFTimeInterval = CACurrentMediaTime()) {
        guard key != self.key else { return }
        if let index, let last = self.index { forward = index >= last }
        let changed = self.key != nil
        let handedOver = changed && key == inPlaceNext
        rapid = changed && !handedOver && now - changedAt < Self.rapidWindow
        inPlace = handedOver || rapid
        inPlaceNext = nil
        if changed { changedAt = now }
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
        let moves = !direction.inPlace
        // The outro's swap keeps both lists opaque (two identical frames); a rapid
        // second change drops the leaving list at once.
        let leaves = moves || direction.rapid
        content
            .offset(y: !moves ? 0 : phase == .willAppear ? sign * distance : phase == .didDisappear ? -sign * distance : 0)
            .opacity(phase.isIdentity ? 1 : phase == .didDisappear ? (leaves ? 0 : 1) : (moves ? 0 : 1))
    }
}
