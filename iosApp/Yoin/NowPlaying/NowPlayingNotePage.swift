import SwiftUI

/// Android `NoteFullscreenPane`: the expanded Note page. The song's notes
/// read like its lyrics: a stamp, then the words; the line the playhead is
/// inside lights up and the list glides to keep it ~22 % down (Timeline).
/// Above them, the note count and the Timeline / Created switch. Tap an
/// anchored line to play from its moment; long-press it to edit its words,
/// align it to now or delete it (a deleted line offers Undo in its place for
/// a few seconds). While the composer is open the list shows where the draft
/// will land; while it edits a note, that note's line stands as "Editing…".
struct NowPlayingNotePage: View {
    var notes: [SongNote]
    @Binding var sort: NotesStore.SortMode
    var scheme: CoverScheme
    /// The draft's moment while writing on this song; nil = not writing.
    var draftAnchorMs: Int64?
    var writing: Bool
    var playheadMs: () -> Int64
    var onSeek: (Int64) -> Void
    var onDelete: (String) -> SongNote?
    /// False: the note couldn't be written back (Undo stays).
    var onRestore: (SongNote) -> Bool
    var onRealign: (String, Int64) -> Void
    /// The note the composer is editing (its line reads "Editing…").
    var editingId: String? = nil
    var onEdit: (SongNote) -> Void = { _ in }

    @State private var activeId: String?
    @State private var deleted: SongNote?
    @State private var undoTask: Task<Void, Never>?
    @State private var userScrolled = false
    @State private var seeks = 0
    @State private var deletes = 0

    /// Android NoteFollowAnchorFraction.
    private static let followAnchor: CGFloat = 0.22

    private enum Row: Identifiable, Equatable {
        case line(SongNote)
        case deleted(SongNote)
        case draft(Int64?)

        var id: String {
            switch self {
            case .line(let n): n.id
            case .deleted(let n): "deleted:\(n.id)"
            case .draft: "draft"
            }
        }
    }

    private var rows: [Row] {
        // An edited note gives its place to the draft line (at its moment,
        // or where it stood in the Created order).
        let editedAt = editingId.flatMap { id in notes.firstIndex { $0.id == id } }
        var all = notes.filter { $0.id != editingId }.map(Row.line)
        if let d = deleted, !notes.contains(where: { $0.id == d.id }) {
            // The undo row stands where the note stood in this order.
            let placed = NotesStore.sorted(notes + [d], sort)
            let at = placed.firstIndex { $0.id == d.id } ?? all.count
            all.insert(.deleted(d), at: min(at, all.count))
        }
        if writing {
            let draft = Row.draft(draftAnchorMs)
            switch sort {
            case .created where editedAt != nil:
                all.insert(draft, at: min(editedAt ?? 0, all.count))
            case .created:
                all.append(draft)
            case .timeline:
                let at = draftAnchorMs.flatMap { ms in
                    all.firstIndex { if case .line(let n) = $0 { return (n.positionMs ?? .max) > ms } else { return false } }
                } ?? all.count
                all.insert(draft, at: at)
            }
        }
        return all
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if notes.isEmpty && deleted == nil && !writing {
                // Android NoteEmptyState (the label; the doodle stays on Android).
                Text("No notes yet")
                    .yoinRole(Tokens.type.titleSmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .transition(.opacity)
            } else {
                header
                list
            }
        }
        .background {
            // The playhead's note, read off the position ticks without
            // re-rendering the list on every one of them.
            PlaybackPosition { position, _ in
                Color.clear.onChange(of: NotesStore.currentAnchoredNoteId(notes, positionMs: Int64(position * 1000)),
                                     initial: true) { _, id in
                    if id != activeId { withAnimation(Yoin.effects) { activeId = id } }
                }
            }
        }
        .sensoryFeedback(.selection, trigger: seeks)
        .sensoryFeedback(.impact(weight: .medium), trigger: deletes)
        .onChange(of: sort) { userScrolled = false }
        .onDisappear { commitDelete() }
    }

    /// "3 notes" … [Timeline | Created].
    private var header: some View {
        HStack {
            Text(notes.count == 1 ? String(localized: "1 note") : String(localized: "\(notes.count) notes"))
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .contentTransition(.numericText())
            Spacer(minLength: 8)
            NoteSortToggle(mode: $sort, scheme: scheme)
        }
        .padding(.bottom, 6)
    }

    private var list: some View {
        let rows = rows
        let activeIndex = rows.firstIndex { if case .line(let n) = $0 { n.id == activeId } else { false } }
        return ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                        rowView(row, distance: activeIndex.map { abs($0 - i) })
                            .id(row.id)
                            .transition(.opacity)
                    }
                }
                .padding(.top, 4)
                .padding(.bottom, 48)
                .animation(Yoin.spatial, value: rows)
            }
            .mask(NowPlayingAboutView.fade(top: 24, bottom: 64))
            .onScrollPhaseChange { _, phase in
                if phase == .interacting { userScrolled = true }
            }
            .onAppear { follow(proxy, animated: false) }
            .onChange(of: activeId) { follow(proxy, animated: true) }
            .onChange(of: writing) { follow(proxy, animated: true) }
            .onChange(of: notes) { userScrolled = false; follow(proxy, animated: true) }
        }
    }

    /// Lyrics-style follow: Timeline only, until the reader drags the list;
    /// writing keeps the draft's spot in view instead.
    private func follow(_ proxy: ScrollViewProxy, animated: Bool) {
        let target: String?
        if writing {
            target = "draft"
        } else if sort == .timeline && !userScrolled {
            target = activeId
        } else {
            target = nil
        }
        guard let target else { return }
        let anchor = UnitPoint(x: 0, y: Self.followAnchor)
        if animated {
            withAnimation(Yoin.spatial) { proxy.scrollTo(target, anchor: anchor) }
        } else {
            proxy.scrollTo(target, anchor: anchor)
        }
    }

    @ViewBuilder
    private func rowView(_ row: Row, distance: Int?) -> some View {
        switch row {
        case .line(let note):
            line(note, distance: distance)
        case .deleted:
            deletedLine
        case .draft(let anchor):
            draftLine(anchor)
        }
    }

    /// Android NoteLine at Page emphasis, tappable to seek, long-press for actions.
    private func line(_ note: SongNote, distance: Int?) -> some View {
        let isActive = note.id == activeId
        return Button {
            guard let ms = note.positionMs else { return }
            seeks += 1
            onSeek(ms)
        } label: {
            NotePageLine(note: note, isActive: isActive, distance: distance,
                         meta: sort == .created ? NotesStore.formatDate(note.createdAt, nowMs: Self.nowMs) : nil,
                         scheme: scheme)
        }
        .buttonStyle(.plain).pointerHighlight()
        .contextMenu {
            Button {
                onEdit(note)
            } label: {
                Label { Text("Edit") } icon: { Image("yoin.edit") }
            }
            Button {
                onRealign(note.id, playheadMs())
            } label: {
                Label { Text("Align to now") } icon: { Image("yoin.music_note") }
            }
            Button(role: .destructive) {
                delete(note)
            } label: {
                Label { Text("Delete") } icon: { Image("yoin.close") }
            }
        }
        .accessibilityHint(note.positionMs.map { String(localized: "Plays from \(NotesStore.formatPosition($0))") } ?? "")
    }

    private static var nowMs: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    /// Android NoteDeletedLine: "Deleted" and Undo, until the window closes.
    private var deletedLine: some View {
        HStack(spacing: 0) {
            Spacer().frame(width: NoteLinesView.stampColumn)
            Text("Deleted")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
            Spacer(minLength: 8)
            Button {
                undoTask?.cancel()
                guard let d = deleted else { return }
                seeks += 1
                // Written back first: a restore that didn't reach the disk keeps Undo on offer.
                guard onRestore(d) else { return }
                withAnimation(Yoin.spatial) { deleted = nil }
            } label: {
                Text("Undo")
                    .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                    .foregroundStyle(scheme.primary)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain).pointerHighlight()
        }
        .frame(minHeight: 48)
    }

    /// Android NoteDraftLine: the draft's moment, "Writing…" on a dotted line.
    private func draftLine(_ anchor: Int64?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(anchor.map(NotesStore.formatPosition) ?? "")
                .yoinRole(Tokens.type.labelMedium, weight: .semibold)
                .monospacedDigit()
                .foregroundStyle(scheme.primary.opacity(0.8))
                .frame(width: NoteLinesView.stampColumn, alignment: .leading)
            Text(editingId != nil ? String(localized: "Editing…") : String(localized: "Writing…"))
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.onSurfaceVariant)
                .padding(.bottom, 3)
                .overlay(alignment: .bottom) {
                    Line().stroke(scheme.primary, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                        .frame(height: 1)
                }
        }
        .padding(.vertical, 6)
    }

    private func delete(_ note: SongNote) {
        commitDelete()
        deletes += 1
        withAnimation(Yoin.spatial) {
            deleted = onDelete(note.id)
        }
        undoTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            withAnimation(Yoin.spatial) { deleted = nil }
        }
    }

    /// The undo window closes: the note stays deleted.
    private func commitDelete() {
        undoTask?.cancel()
        deleted = nil
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            Path { p in
                p.move(to: CGPoint(x: rect.minX, y: rect.midY))
                p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            }
        }
    }
}

/// Android `NoteLine` at Page emphasis: every line legible, the current one
/// lit in primary; a stamp column, the words, the date under them in the
/// journal order.
struct NotePageLine: View {
    var note: SongNote
    var isActive: Bool
    var distance: Int?
    var meta: String?
    var scheme: CoverScheme

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Group {
                if let ms = note.positionMs {
                    Text(NotesStore.formatPosition(ms))
                        .yoinRole(Tokens.type.labelMedium, weight: isActive ? .semibold : .medium)
                        .monospacedDigit()
                        .foregroundStyle(isActive ? scheme.primary : scheme.onSurfaceVariant)
                } else {
                    Circle().strokeBorder(scheme.onSurfaceVariant, lineWidth: 1.5)
                        .frame(width: 6, height: 6)
                        .padding(.leading, 2)
                        .frame(height: Tokens.type.bodyLarge.lineHeight.cg)
                }
            }
            .frame(width: NoteLinesView.stampColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(note.content)
                    .yoinRole(Tokens.type.bodyLarge, weight: isActive ? .semibold : .regular)
                    .foregroundStyle(isActive ? scheme.primary : scheme.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                if let meta {
                    Text(meta)
                        .yoinRole(Tokens.type.labelSmall)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 6)
        .contentShape(.rect)
        .opacity(isActive ? 1 : (distance == nil ? 0.86 : 0.62))
        .scaleEffect(isActive ? 1 : 0.97, anchor: .leading)
        .animation(Yoin.effects, value: isActive)
    }
}

/// Android `NoteSortToggle`: a micro segmented capsule, Timeline | Created.
struct NoteSortToggle: View {
    @Binding var mode: NotesStore.SortMode
    var scheme: CoverScheme
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 0) {
            option(String(localized: "Timeline"), .timeline)
            option(String(localized: "Created"), .created)
        }
        .padding(3)
        .background(scheme.surfaceContainerHigh, in: Capsule())
        .sensoryFeedback(.selection, trigger: mode)
    }

    private func option(_ label: String, _ value: NotesStore.SortMode) -> some View {
        let on = mode == value
        return Button {
            guard !on else { return }
            withAnimation(Yoin.spatialFast) { mode = value }
        } label: {
            Text(label)
                .yoinRole(Tokens.type.labelSmall)
                .foregroundStyle(on ? scheme.onPrimary : scheme.onSurfaceVariant)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background {
                    if on {
                        Capsule().fill(scheme.primary)
                            .matchedGeometryEffect(id: "sort", in: pill)
                    }
                }
                .contentShape(.capsule)
        }
        .buttonStyle(.plain).pointerHighlight()
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Android `NoteWriteBar` at rest: a 56 pt capsule docked under the Note
/// page, the live playhead in its stamp and the field's placeholder. A tap
/// opens the composer.
struct NoteWriteBarCapsule: View {
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                PlaybackPosition { position, _ in
                    Text(NotesStore.formatPosition(Int64(position * 1000)))
                        .yoinRole(Tokens.type.labelLarge)
                        .monospacedDigit()
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .frame(height: 36)
                        .background(scheme.primary.opacity(0.12), in: Capsule())
                }
                Text("Write a note")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(scheme.onSurfaceVariant.opacity(0.8))
                Spacer(minLength: 0)
            }
            .padding(.leading, 10)
            .padding(.trailing, 14)
            .frame(height: NowPlayingExpanded.writeBar)
            .background(scheme.surfaceContainerHigh.opacity(0.92), in: Capsule())
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("Write a note")
    }
}
