import SwiftUI

/// The scrapbook track sheet's Notes tab (Android AlbumScrapbookTrackSheet NotesTab, owner-approved
/// prototype v3, 2026-11-02): each timed note under the lyric line it was taken on, three lines either
/// side (ScrapNoteRows), "All lyrics" for every line. A tap on a line opens New note and Play from; a tap
/// on a note opens Edit and Play from. The editor sits where the note goes; an emptied note is deleted,
/// with Undo. Without timed lyrics the notes are listed alone, with New note under them.
///
/// The lyrics come through Now Playing's loader (NowPlayingData), only once this tab is open.
struct ScrapNotesTab: View {
    var track: Track
    var album: Album
    var scheme: CoverScheme
    /// The song as the engine would play it; nil for a song no signed-in service can read (no lyrics).
    var playable: PlayableTrack?
    var onPlayFrom: (Int64?) -> Void

    @State private var expanded = false
    @State private var editingNote: String?
    @State private var draft: ScrapNoteRows.Draft?
    @State private var started = false

    private var toast: PlaylistActions { .shared }

    private var lyrics: ScrapLyrics {
        guard let playable else { return .none }
        return ScrapLyrics.from(NowPlayingData.shared.lyricsState(playable))
    }

    var body: some View {
        let notes = NotesStore.shared.notes(provider: track.id.provider, trackId: track.id.rawId)
        let lyrics = lyrics
        let timed = !lyrics.lines.isEmpty
        let rows = ScrapNoteRows.rows(lines: lyrics.lines, notes: notes, expanded: expanded, draft: draft)
        ScrollViewReader { proxy in
        VStack(alignment: .leading, spacing: 0) {
            if timed {
                tonalButton(expanded ? String(localized: "Notes only") : String(localized: "All lyrics"),
                            icon: expanded ? "chevron_up" : "chevron_down", leading: false) {
                    withAnimation(Yoin.spatial) {
                        draft = nil
                        expanded.toggle()
                    }
                }
                .frame(maxWidth: .infinity)
                .transition(.opacity)
            } else if lyrics.loading && notes.isEmpty {
                hint(String(localized: "Loading lyrics…")).padding(.vertical, 8)
                    .transition(.opacity)
            }
            ForEach(rows) { row in
                rowView(row)
                    .transition(.opacity)
            }
            if notes.isEmpty && draft == nil && !lyrics.loading {
                // Only the label: no how-to line (owner 2026-10-08, the same as Now Playing's Note page).
                hint(String(localized: "No notes yet.")).padding(.top, 12)
                    .transition(.opacity)
            }
            // Without timed lyrics there is no line to start from: a note without a moment.
            if !timed && !lyrics.loading && draft == nil && editingNote == nil {
                tonalButton(String(localized: "New note"), icon: "add", leading: true) {
                    withAnimation(Yoin.spatial) { draft = .init(positionMs: nil) }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 12)
                .transition(.opacity)
            }
        }
        // A row that comes or goes moves the rest on the spatial spring, never in one frame.
        .animation(Yoin.spatial, value: rows.map(\.id))
        .animation(Yoin.spatial, value: lyrics)
        // The editor's buttons stay above the keyboard (Android KeepAboveKeyboard).
        .onChange(of: draft) { _, d in if d != nil { keepEditorInView(proxy) } }
        .onChange(of: editingNote) { _, id in if id != nil { keepEditorInView(proxy) } }
        }
        .task {
            #if DEBUG
            applyDemo(notes: notes)
            if ScrapNotesDemo.mode == "loading" { return }
            #endif
            if let playable { await NowPlayingData.shared.prefetchLyrics(playable, client: AppModel.shared.session.client) }
        }
    }

    // MARK: Rows

    @ViewBuilder
    private func rowView(_ row: ScrapNoteRow) -> some View {
        switch row {
        case .lyric(_, let line, let noted):
            lyricRow(line, noted: noted)
        case .note(let note):
            if editingNote == note.id {
                ScrapNoteEditor(positionMs: note.positionMs, text: note.content, scheme: scheme, canDelete: true,
                                onDelete: { delete(note) },
                                onDone: { finishEdit(note, $0) })
            } else {
                noteCard(note)
            }
        case .draft(let positionMs):
            ScrapNoteEditor(positionMs: positionMs, text: "", scheme: scheme, canDelete: false,
                            onDelete: { withAnimation(Yoin.spatial) { draft = nil } },
                            onDone: { finishDraft(positionMs, $0) })
        case .gap:
            Text(verbatim: "·  ·  ·") // the gap between two passages
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(scheme.outline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .accessibilityHidden(true)
        }
    }

    /// Android LyricRow: bodyMedium 13, line 1.75 em; a noted line in Medium onSurfaceVariant, the rest
    /// in outline.
    private func lyricRow(_ line: ScrapLyricLine, noted: Bool) -> some View {
        let at = NotesStore.formatPosition(line.startMs)
        return Menu {
            Button {
                withAnimation(Yoin.spatial) {
                    editingNote = nil
                    draft = .init(positionMs: line.startMs)
                }
            } label: {
                Label { Text("New note") } icon: { Image("yoin.add") }
            }
            Button { play(line.startMs) } label: {
                Label { Text("Play from \(at)") } icon: { Image("yoin.play_filled") }
            }
        } label: {
            Text(line.text)
                .font(.gsf(13, noted ? .medium : .regular))
                .lineSpacing(YoinType.lineSpacing(size: 13, lineHeight: 13 * 1.75))
                .padding(.vertical, (13 * 1.75 - 13 * YoinType.naturalLine) / 2)
                .foregroundStyle(noted ? scheme.onSurfaceVariant : scheme.outline)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 8)
                .contentShape(.rect(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .accessibilityHint(String(localized: "New note"))
    }

    /// Android NoteCard: the note on the clipping's paper with its rail, the moment stamp over the words.
    private func noteCard(_ note: SongNote) -> some View {
        let at = note.positionMs.map(NotesStore.formatPosition)
        return Menu {
            Button {
                withAnimation(Yoin.spatial) {
                    draft = nil
                    editingNote = note.id
                }
            } label: {
                Label { Text("Edit note", comment: "The scrapbook note menu's Edit") } icon: { Image("yoin.edit") }
            }
            Button { play(note.positionMs) } label: {
                Label { Text(at.map { String(localized: "Play from \($0)") } ?? String(localized: "Play")) } icon: {
                    Image("yoin.play_filled")
                }
            }
        } label: {
            ScrapNoteCard(scheme: scheme) {
                if let at { ScrapNoteStamp(text: at, scheme: scheme) }
                Text(note.content)
                    .font(.gsf(15))
                    .lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.5))
                    .foregroundStyle(scheme.onSurface)
                    .multilineTextAlignment(.leading)
                    .padding(.top, 2)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: Actions

    /// Once the keyboard is up, the open editor's buttons come into view on the spatial spring.
    private func keepEditorInView(_ proxy: ScrollViewProxy) {
        Task {
            // Once as the keyboard rises, once more when it has settled (the inset grows under the first).
            for wait in [450, 450] {
                try? await Task.sleep(for: .milliseconds(wait))
                withAnimation(Yoin.spatial) { proxy.scrollTo(ScrapNoteEditor.actionsID, anchor: .bottom) }
            }
        }
    }

    private func play(_ positionMs: Int64?) {
        onPlayFrom(positionMs)
        if let positionMs {
            toast.show(String(localized: "Playing from \(NotesStore.formatPosition(positionMs))"))
        }
    }

    private func delete(_ note: SongNote) {
        guard let gone = NotesStore.shared.delete(id: note.id) else {
            return toast.show(String(localized: "Couldn't delete the note. Try again"))
        }
        withAnimation(Yoin.spatial) { editingNote = nil }
        toast.show(String(localized: "Note deleted"), action: .init(label: String(localized: "Undo")) {
            if !NotesStore.shared.restore(gone) { toast.show(String(localized: "Couldn't restore the note. Try again")) }
        })
    }

    private func finishEdit(_ note: SongNote, _ text: String) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if words.isEmpty { return delete(note) }
        if words != note.content, NotesStore.shared.update(id: note.id, content: words) == nil {
            // Not written: the editor keeps the words.
            return toast.show(String(localized: "Couldn't save the note"))
        }
        withAnimation(Yoin.spatial) { editingNote = nil }
    }

    private func finishDraft(_ positionMs: Int64?, _ text: String) {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !words.isEmpty, NotesStore.shared.add(provider: track.id.provider, trackId: track.id.rawId, content: words,
                                                 positionMs: positionMs, title: track.title,
                                                 artist: track.artist ?? album.artist) == nil {
            return toast.show(String(localized: "Couldn't save the note"))
        }
        withAnimation(Yoin.spatial) { draft = nil }
    }

    // MARK: Pieces

    private func hint(_ text: String) -> some View {
        Text(text)
            .yoinRole(Tokens.type.bodySmall)
            .foregroundStyle(scheme.outline)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 8)
    }

    /// Android FilledTonalButton in surfaceContainerHigh / onSurface, an 18 icon 6 from the label.
    private func tonalButton(_ label: String, icon: String, leading: Bool,
                             action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if leading { YoinIcon(icon, size: 18) }
                Text(label).yoinRole(Tokens.type.labelLarge).lineLimit(1)
                if !leading { YoinIcon(icon, size: 18) }
            }
            .foregroundStyle(scheme.onSurface)
            .padding(.leading, leading ? 16 : 24)
            .padding(.trailing, leading ? 24 : 16)
            .frame(minHeight: 40)
            .background(scheme.surfaceContainerHigh, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
    }

    #if DEBUG
    private func applyDemo(notes: [SongNote]) {
        guard !started, let mode = ScrapNotesDemo.mode else { return }
        started = true
        switch mode {
        case "all": expanded = true
        case "edit": editingNote = notes.first?.id
        case "draft": draft = .init(positionMs: ScrapNotesDemo.draftMs)
        case "plain-draft": draft = .init(positionMs: nil)
        default: break
        }
    }
    #endif
}

/// The note's paper (Android NoteCard / NoteEditor): surfaceContainerHighest, Card 16, the rail 3 wide at
/// x 11 inset 14 (journalRail), padding 24 / 14 / 10 / 12, 6 above and below.
private struct ScrapNoteCard<Content: View>: View {
    var scheme: CoverScheme
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 24).padding(.trailing, 14).padding(.top, 10).padding(.bottom, 12)
            .background(alignment: .leading) {
                Capsule().fill(scheme.primary).frame(width: 3).padding(.vertical, 14).padding(.leading, 11)
            }
            .background(scheme.surfaceContainerHighest,
                        in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .contentShape(.rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .padding(.vertical, 6)
    }
}

/// Android NoteStamp: labelMedium 12 SemiBold, tabular, tracking 0.3, in the ink.
private struct ScrapNoteStamp: View {
    var text: String
    var scheme: CoverScheme

    var body: some View {
        Text(text)
            .font(.gsf(12, .semibold)).monospacedDigit()
            .tracking(0.3)
            .foregroundStyle(scheme.primary)
    }
}

/// A note being written or changed in place (Android NoteEditor): the stamp, the words over a 2 ink rule,
/// then Delete (an existing note) or Cancel (a new one), and Done.
private struct ScrapNoteEditor: View {
    var positionMs: Int64?
    var text: String
    var scheme: CoverScheme
    var canDelete: Bool
    var onDelete: () -> Void
    var onDone: (String) -> Void

    @State private var words = ""
    @FocusState private var focused: Bool

    /// The button row, scrolled above the keyboard while the editor is open (one editor at a time).
    static let actionsID = "scrap-note-editor-actions"

    var body: some View {
        let at = positionMs.map(NotesStore.formatPosition)
        ScrapNoteCard(scheme: scheme) {
            if let at { ScrapNoteStamp(text: at, scheme: scheme) }
            VStack(alignment: .leading, spacing: 4) {
                TextField(text: $words,
                          prompt: Text(at.map { String(localized: "A note at \($0)…") } ?? String(localized: "A note…"))
                              .foregroundStyle(scheme.onSurface.opacity(0.5)),
                          axis: .vertical) { Text(at.map { String(localized: "A note at \($0)…") } ?? String(localized: "A note…")) }
                    .font(.gsf(15))
                    .lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.5))
                    .foregroundStyle(scheme.onSurface)
                    .tint(scheme.primary)
                    .textInputAutocapitalization(.sentences)
                    .focused($focused)
                Rectangle().fill(scheme.primary.opacity(0.6)).frame(height: 2)
            }
            .padding(.top, 2)
            HStack(spacing: 8) {
                if canDelete {
                    Button(action: onDelete) {
                        HStack(spacing: 6) {
                            YoinIcon("delete", size: 16)
                            Text("Delete").yoinRole(Tokens.type.labelLarge).lineLimit(1)
                        }
                        .foregroundStyle(scheme.error)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 40)
                        .background(scheme.surface.opacity(0.7), in: .capsule)
                    }
                    .buttonStyle(PressScale())
                } else {
                    Button(action: onDelete) {
                        Text("Cancel").yoinRole(Tokens.type.labelLarge).lineLimit(1)
                            .foregroundStyle(scheme.primary)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 40)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressScale())
                }
                Spacer(minLength: 0)
                Button { onDone(words) } label: {
                    Text("Done").yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.onPrimary)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 40)
                        .background(scheme.primary, in: .capsule)
                }
                .buttonStyle(PressScale())
            }
            .padding(.top, 10)
            .padding(.bottom, 12)
            .id(Self.actionsID)
            .padding(.bottom, -12)
        }
        .onAppear {
            words = text
            focused = true
        }
    }
}

#if DEBUG
/// Screenshot aid for the Notes tab (`-scrapNotesDemo rows|all|edit|draft|plain|plain-draft|loading` with
/// the demo server and `-serverRoute album:<id>`): the album's first song gets three notes and, except for
/// `plain`, `plain-draft` and `loading`, timed lyrics chosen in the lyrics store; the scrapbook page opens
/// with that song's sheet on Notes. Debug builds only.
@MainActor
enum ScrapNotesDemo {
    static var mode: String? { UserDefaults.standard.string(forKey: "scrapNotesDemo") }
    static let draftMs: Int64 = 40_000

    private static let lrc = """
    [00:02.00]Paper moons over the station
    [00:06.50]Late buses humming in the rain
    [00:11.00]I kept your number on a receipt
    [00:15.50]Folded twice inside my coat
    [00:20.00]Every window shows a different city
    [00:24.50]Every city has your name
    [00:29.00]
    [00:31.00]We said nothing on the last train home
    [00:35.50]The piano rang in the empty hall
    [00:40.00]One take, the tape still running
    [00:44.50]Your laugh is somewhere on the track
    [00:49.00]Hold the note a little longer
    [00:53.50]Let the static fill the room
    [00:58.00]Paper moons, paper moons
    [01:02.50]Folding into morning
    [01:07.00]Late buses gone to sleep
    [01:11.50]And the receipt has faded white
    [01:16.00]But I still know the number
    [01:20.50]I still know the way
    """

    /// Seeds the first song once per launch; returns it.
    private static var opened = false

    static func seed(_ album: Album, store: LibraryStore) -> Track? {
        guard let mode, !opened, let t = album.tracks.first else { return nil }
        opened = true
        if mode != "plain", mode != "plain-draft", mode != "loading",
           let p = store.playable(PlaylistEntry(album: album, track: t)) {
            LyricsUserStore.shared.choose(lrc, for: NowPlayingData.key(p))
        }
        if NotesStore.shared.notes(provider: t.id.provider, trackId: t.id.rawId).isEmpty {
            let artist = t.artist ?? album.artist
            for (ms, words) in [(Int64(11_500), "The receipt line, every time"),
                                (Int64(36_000), "Piano comes in here. Recorded at night, one take"),
                                (Int64(78_000), "Ends on the number, not the name")] {
                NotesStore.shared.add(provider: t.id.provider, trackId: t.id.rawId, content: words, positionMs: ms,
                                      title: t.title, artist: artist)
            }
        }
        return t
    }
}
#endif
