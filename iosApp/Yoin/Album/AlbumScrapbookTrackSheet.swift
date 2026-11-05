import SwiftUI

/// Which track's sheet is open, and the About row it opened editing (Android ScrapSheetOpen).
struct ScrapSheetOpen: Identifiable {
    let id = UUID()
    var track: Track
    var editing: SongAboutStore.Entry.Ref? = nil
}

/// Page 2's track sheet (Android AlbumScrapbookTrackSheet, owner-approved
/// prototype v3, 2026-11-02), About part: the number, title and score on top
/// (ScrapScoreSheet), then every fact and question, each with a pencil (a
/// long press edits too). The editor has Delete, Regenerate with Gemini (not
/// on the listener's own entries) and Done; a delete or a regenerate can be
/// undone from the toast. Under them, a new entry: a title first, then Ask
/// Gemini, or Write it yourself.
///
/// Rows are `SongAboutStore` rows filed under the track's title and artist and
/// this album's name, so what is written here shows in Now Playing's About too.
/// Opened by tapping a ticket, or from Now Playing's About ("Manage in
/// Scrapbook", at that row's editor, scrolled to without the keyboard).
struct ScrapTrackSheet: View {
    var track: Track
    var album: Album
    var scheme: CoverScheme
    var store: LibraryStore
    /// The mock's hand-set scores are not editable.
    var scoreEditable = true
    var initialEditing: SongAboutStore.Entry.Ref? = nil

    @Environment(\.colorScheme) private var colorScheme
    /// One row at a time is open.
    @State private var editing: SongAboutStore.Entry.Ref?
    /// The editor takes the keyboard (not for one opened from Now Playing).
    @State private var focusEditor = true
    @State private var detent: PresentationDetent = .medium
    @State private var started = false

    private var about: SongAboutStore { .shared }
    private var loader: SongAboutLoader { .shared }
    private var toast: PlaylistActions { .shared }

    private var keys: (title: String, artist: String, album: String) {
        (track.title, track.artist ?? album.artist, album.title)
    }
    private var songKey: SongAboutStore.Entry.SongKey {
        SongAboutStore.key(title: keys.title, artist: keys.artist, album: keys.album)
    }

    var body: some View {
        let entries = about.entries(songKey)
        let facts = entries.filter { $0.kind == SongAboutStore.Kind.canonical && !$0.isBlank }
        let short = facts.filter { $0.entryKey != SongAboutStore.Canon.review }
        let paragraphs = facts.filter { $0.entryKey == SongAboutStore.Canon.review }
        // Oldest first here, so a new answer lands just above the entry it came from.
        let asks = Array(entries.filter { $0.kind != SongAboutStore.Kind.canonical }.reversed())
        let shown = Set(asks.filter { $0.kind == SongAboutStore.Kind.ask }.map(\.entryKey))
        let pending = loader.asking.filter { $0.song == songKey && !shown.contains(SongAboutStore.normalize($0.question)) }

        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if scoreEditable {
                        ScrapScoreSheet(track: track, scheme: scheme, store: store, embedded: true)
                            .padding(.horizontal, 8)
                            .padding(.bottom, 20)
                    } else {
                        plainHeader.padding(.bottom, 20)
                    }
                    Text("About")
                        .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .padding(.horizontal, 8)
                        .padding(.bottom, 4)
                    ForEach(Array(short.enumerated()), id: \.element.ref) { i, fact in
                        let open = fact.ref == editing
                        if i > 0, !open, short[i - 1].ref != editing {
                            Rectangle().fill(scheme.outlineVariant.opacity(0.6)).frame(height: 1)
                                .padding(.horizontal, 8)
                        }
                        factEntry(fact, paragraph: false).id(fact.ref)
                    }
                    ForEach(paragraphs, id: \.ref) { fact in
                        factEntry(fact, paragraph: true).id(fact.ref).padding(.top, 12)
                    }
                    ForEach(asks, id: \.ref) { ask in
                        askEntry(ask).id(ask.ref).padding(.top, 14)
                    }
                    ForEach(pending, id: \.self) { asking in
                        pendingAsk(asking.question).padding(.top, 14)
                            .transition(.opacity)
                    }
                    ScrapNewEntry(scheme: scheme) { question in
                        loader.askFromScrapbook(title: keys.title, artist: keys.artist, album: keys.album,
                                                question: question)
                    } onWrite: { heading, text in
                        withAnimation(Yoin.spatial) {
                            about.writeOwn(title: keys.title, artist: keys.artist, album: keys.album,
                                           heading: heading, text: text)
                        }
                    }
                    .padding(.top, 22)
                }
                .padding(.horizontal, 16)
                .padding(.top, 28)
                .padding(.bottom, 32)
                // A removed or opened row moves the rest on the spatial spring, never in one frame.
                .animation(Yoin.spatial, value: editing)
                .animation(Yoin.spatial, value: entries)
                .animation(Yoin.spatial, value: pending)
            }
            .scrollDismissesKeyboard(.interactively)
            .onAppear {
                guard !started else { return }
                started = true
                guard let ref = initialEditing, about.entry(ref) != nil else { return }
                // One opened from Now Playing is brought into view without the keyboard.
                focusEditor = false
                editing = ref
                detent = .large
                Task {
                    try? await Task.sleep(for: .milliseconds(350))
                    withAnimation(Yoin.spatial) { proxy.scrollTo(ref, anchor: .center) }
                }
            }
        }
        // The notice takes its own room at the sheet's foot while it is open
        // (the shell's toast sits under the sheet).
        .overlay(alignment: .bottom) {
            if let t = toast.toast {
                ToastPill(toast: t)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .id(t.id)
            }
        }
        .animation(Yoin.spatial, value: toast.toast)
        .presentationDetents([.medium, .large], selection: $detent)
        .presentationDragIndicator(.visible)
    }

    /// The mock's head: the number and title, no score bar.
    private var plainHeader: some View {
        HStack(spacing: 12) {
            Text(String(format: "%02d", track.number))
                .font(.gsf(14, .medium)).monospacedDigit()
                .foregroundStyle(scheme.onSurfaceVariant)
            Text(track.title)
                .font(.gsf(22, .semibold)).lineLimit(1)
                .foregroundStyle(scheme.onSurface)
        }
        .padding(.horizontal, 8)
    }

    // MARK: Actions

    private func startEdit(_ ref: SongAboutStore.Entry.Ref) {
        focusEditor = true
        editing = ref
    }

    /// The row goes at once; Undo writes it back ("Deleted · Undo", owner 2026-10-29: like Google Photos).
    private func remove(_ ref: SongAboutStore.Entry.Ref, wasTitle: String?, wasAnswer: String, name: String?) {
        editing = nil
        about.edit(ref, title: nil, answer: "")
        toast.show(name.map { "Deleted “\($0)”" } ?? "Deleted", action: .init(label: "Undo") {
            SongAboutStore.shared.edit(ref, title: wasTitle, answer: wasAnswer)
        })
    }

    /// Done keeps what is in the fields; an emptied text is a delete.
    private func done(_ ref: SongAboutStore.Entry.Ref, wasTitle: String?, wasAnswer: String, title: String?,
                      answer: String, name: String?) {
        if answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return remove(ref, wasTitle: wasTitle, wasAnswer: wasAnswer, name: name)
        }
        editing = nil
        let titleChanged = title.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) != (wasTitle ?? "") } ?? false
        if answer.trimmingCharacters(in: .whitespacesAndNewlines) != wasAnswer || titleChanged {
            about.edit(ref, title: title, answer: answer)
        }
    }

    private func regenerate(_ ref: SongAboutStore.Entry.Ref) {
        editing = nil
        loader.regenerateAndReport(ref)
    }

    // MARK: Rows

    /// One About fact: label and value with a pencil (a long press edits too), or its editor.
    @ViewBuilder
    private func factEntry(_ fact: SongAboutStore.Entry, paragraph: Bool) -> some View {
        let label = Self.factLabel(fact.entryKey)
        let thinking = loader.regenerating.contains(fact.ref)
        if fact.ref == editing {
            VStack(alignment: .leading, spacing: 0) {
                Text(label).yoinRole(Tokens.type.labelLarge, weight: .semibold).foregroundStyle(scheme.primary)
                ScrapAboutEditor(title: nil, text: fact.answerText, canRegenerate: true, autoFocus: focusEditor,
                                 scheme: scheme,
                                 onDelete: { remove(fact.ref, wasTitle: nil, wasAnswer: fact.answerText, name: label) },
                                 onRegenerate: { regenerate(fact.ref) },
                                 onDone: { _, answer in
                                     done(fact.ref, wasTitle: nil, wasAnswer: fact.answerText, title: nil,
                                          answer: answer, name: label)
                                 })
            }
            .padding(12)
            .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 20, style: .continuous))
            .transition(.opacity)
        } else {
            let value = Group {
                if thinking {
                    ThinkingDots(color: scheme.primary).padding(.vertical, 8)
                } else {
                    Text(NowPlayingAboutView.markdown(fact.answerText))
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(scheme.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Group {
                if paragraph {
                    HStack(alignment: .top, spacing: 0) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(label).yoinRole(Tokens.type.labelLarge, weight: .semibold)
                                .foregroundStyle(scheme.primary)
                            value
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        editButton(visible: !thinking, tint: scheme.onSurfaceVariant) { startEdit(fact.ref) }
                    }
                } else {
                    HStack(spacing: 10) {
                        Text(label).yoinRole(Tokens.type.labelLarge, weight: .semibold)
                            .foregroundStyle(scheme.primary)
                            .frame(width: 76, alignment: .leading)
                        value.frame(maxWidth: .infinity, alignment: .leading)
                        editButton(visible: !thinking, tint: scheme.onSurfaceVariant) { startEdit(fact.ref) }
                    }
                }
            }
            .padding(.leading, 8)
            .padding(.vertical, 8)
            .contentShape(.rect)
            .onLongPressGesture(minimumDuration: 0.4) { if !thinking { startEdit(fact.ref) } }
            .animation(Yoin.effects, value: thinking)
            .transition(.opacity)
        }
    }

    /// A question in full (the listener's words, Gemini's title and answer), or
    /// one of the listener's own entries.
    private func askEntry(_ ask: SongAboutStore.Entry) -> some View {
        let own = ask.kind == SongAboutStore.Kind.own
        let container = own ? scheme.primaryContainer : scheme.tertiaryContainer
        let content = own ? scheme.onPrimaryContainer : scheme.onTertiaryContainer
        let question = ask.promptText ?? ask.entryKey
        // An own entry's heading is its "question"; Gemini's title on an asked one.
        let wasTitle = ask.titleText
        let thinking = loader.regenerating.contains(ask.ref)
        return Group {
            if ask.ref == editing {
                VStack(alignment: .leading, spacing: 0) {
                    if !own {
                        Text("Asked: \(question)").font(.gsf(13.5)).foregroundStyle(content.opacity(0.75))
                    }
                    ScrapAboutEditor(title: wasTitle ?? "", text: ask.answerText, canRegenerate: !own,
                                     autoFocus: focusEditor, scheme: scheme,
                                     onDelete: {
                                         remove(ask.ref, wasTitle: wasTitle, wasAnswer: ask.answerText,
                                                name: wasTitle ?? question)
                                     },
                                     onRegenerate: { regenerate(ask.ref) },
                                     onDone: { title, answer in
                                         done(ask.ref, wasTitle: wasTitle, wasAnswer: ask.answerText, title: title,
                                              answer: answer, name: nil)
                                     })
                }
                .padding(16)
                .transition(.opacity)
            } else {
                ZStack(alignment: .topTrailing) {
                    VStack(alignment: .leading, spacing: 0) {
                        if !own {
                            Text(question).font(.gsf(13.5)).foregroundStyle(content.opacity(0.75))
                                .padding(.trailing, 32)
                        }
                        if let heading = ask.titleText, !heading.isEmpty {
                            Text(heading)
                                .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                                .foregroundStyle(content)
                                .padding(.top, own ? 0 : 6)
                                .padding(.trailing, own ? 32 : 0)
                        }
                        if thinking {
                            ThinkingDots(color: content).padding(.top, 10).padding(.bottom, 4)
                        } else {
                            Text(NowPlayingAboutView.markdown(ask.answerText))
                                .yoinRole(Tokens.type.bodyLarge)
                                .foregroundStyle(content)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.top, 4)
                        }
                        byline(own: own, color: content).padding(.top, 8)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    editButton(visible: !thinking, tint: content) { startEdit(ask.ref) }
                        .padding(4)
                }
                .contentShape(.rect)
                .onLongPressGesture(minimumDuration: 0.4) { if !thinking { startEdit(ask.ref) } }
                .animation(Yoin.effects, value: thinking)
                .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(container, in: .rect(cornerRadius: 20, style: .continuous))
    }

    /// A question asked from the sheet that Gemini has not answered yet.
    private func pendingAsk(_ question: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(question).font(.gsf(13.5)).foregroundStyle(scheme.onTertiaryContainer.opacity(0.75))
            ThinkingDots(color: scheme.onTertiaryContainer).padding(.top, 12).padding(.bottom, 4)
            byline(own: false, color: scheme.onTertiaryContainer).padding(.top, 8)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.tertiaryContainer, in: .rect(cornerRadius: 20, style: .continuous))
    }

    private func byline(own: Bool, color: Color) -> some View {
        HStack(spacing: 4) {
            if !own { YoinIcon("sparkle", size: 14) }
            Text(own ? "Written by you" : "Gemini")
                .yoinRole(Tokens.type.labelMedium, weight: .semibold)
        }
        .foregroundStyle(color)
    }

    /// Keeps its slot while hidden, so the row beside it never jumps.
    private func editButton(visible: Bool, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            YoinIcon("edit", size: 20)
                .foregroundStyle(tint)
                .frame(width: 40, height: 40)
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .opacity(visible ? 1 : 0)
        .disabled(!visible)
        .accessibilityLabel("Edit")
    }

    /// Android scrapFactLabel (detail_scrap_fact_*).
    static func factLabel(_ key: String) -> String {
        switch key {
        case SongAboutStore.Canon.creationTime: "Created"
        case SongAboutStore.Canon.creationLocation: "Recorded in"
        case SongAboutStore.Canon.lyricist: "Lyricist"
        case SongAboutStore.Canon.composer: "Composer"
        case SongAboutStore.Canon.producer: "Producer"
        case SongAboutStore.Canon.review: "About"
        default: key
        }
    }
}

/// The editor for one row (Android AboutEditor): `title` (nil: no title field)
/// and the text. Delete removes the row; Regenerate asks Gemini again (both
/// undoable from the toast); Done keeps what is in the fields.
private struct ScrapAboutEditor: View {
    var title: String?
    var text: String
    var canRegenerate: Bool
    var autoFocus: Bool
    var scheme: CoverScheme
    var onDelete: () -> Void
    var onRegenerate: () -> Void
    var onDone: (String?, String) -> Void

    @State private var titleDraft = ""
    @State private var textDraft = ""
    @FocusState private var textFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if title != nil {
                ScrapField(text: $titleDraft, hint: "Title", scheme: scheme, heading: true, singleLine: true)
            }
            ScrapField(text: $textDraft, hint: "Empty removes it", scheme: scheme)
                .focused($textFocused)
            HStack(spacing: 8) {
                editorButton("Delete", icon: "delete", content: scheme.error, action: onDelete)
                if canRegenerate {
                    editorButton("Regenerate", icon: "sparkle", content: scheme.tertiary, action: onRegenerate)
                }
                Spacer(minLength: 0)
                Button { onDone(title == nil ? nil : titleDraft, textDraft) } label: {
                    Text("Done")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.onPrimary)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .background(scheme.primary, in: .capsule)
                }
                .buttonStyle(PressScale())
            }
        }
        .padding(.top, 8)
        .onAppear {
            titleDraft = title ?? ""
            textDraft = text
            if autoFocus { textFocused = true }
        }
    }

    private func editorButton(_ label: String, icon: String, content: Color,
                              action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                YoinIcon(icon, size: 16)
                Text(label).yoinRole(Tokens.type.labelLarge).lineLimit(1)
            }
            .foregroundStyle(content)
            .padding(.horizontal, 14)
            .frame(minHeight: 40)
            .background(scheme.surface, in: .capsule)
        }
        .buttonStyle(PressScale())
    }
}

/// A new entry (Android NewEntry): its title first, then Ask Gemini, or Write
/// to type it yourself (the button becomes Save).
private struct ScrapNewEntry: View {
    var scheme: CoverScheme
    var onAsk: (String) -> Void
    var onWrite: (String, String) -> Void

    @State private var title = ""
    @State private var body_ = ""
    @State private var typing = false
    @FocusState private var focus: Field?

    private enum Field { case title, body }

    private var hasTitle: Bool { !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var hasBody: Bool { !body_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrapField(text: $title, hint: "A title, like: what is this song about", scheme: scheme,
                       heading: true, singleLine: true)
                .focused($focus, equals: .title)
            if typing {
                ScrapField(text: $body_, hint: "Write what you know…", scheme: scheme, minHeight: 84)
                    .focused($focus, equals: .body)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: 8) {
                Button {
                    onAsk(title.trimmingCharacters(in: .whitespacesAndNewlines))
                    reset()
                } label: {
                    label("Ask Gemini", icon: "sparkle", fill: scheme.tertiaryContainer,
                          content: scheme.onTertiaryContainer)
                }
                .buttonStyle(PressScale())
                .disabled(!hasTitle)
                .opacity(hasTitle ? 1 : 0.38)
                Button {
                    if !typing {
                        withAnimation(Yoin.spatial) { typing = true }
                        focus = .body
                    } else {
                        onWrite(title.trimmingCharacters(in: .whitespacesAndNewlines),
                                body_.trimmingCharacters(in: .whitespacesAndNewlines))
                        reset()
                    }
                } label: {
                    label(typing ? "Save" : "Write", icon: typing ? nil : "edit", fill: scheme.primary,
                          content: scheme.onPrimary)
                }
                .buttonStyle(PressScale())
                .disabled(!hasTitle || (typing && !hasBody))
                .opacity(hasTitle && (!typing || hasBody) ? 1 : 0.38)
            }
            .animation(Yoin.effects, value: hasTitle)
            .animation(Yoin.effects, value: hasBody)
        }
        .padding(14)
        .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 24, style: .continuous))
    }

    private func reset() {
        title = ""
        body_ = ""
        withAnimation(Yoin.spatial) { typing = false }
        focus = nil
    }

    private func label(_ text: String, icon: String?, fill: Color, content: Color) -> some View {
        HStack(spacing: 6) {
            if let icon { YoinIcon(icon, size: 18) }
            Text(text).yoinRole(Tokens.type.labelLarge).lineLimit(1)
        }
        .foregroundStyle(content)
        .frame(maxWidth: .infinity, minHeight: 44)
        .background(fill, in: .capsule)
        .contentShape(.capsule)
    }
}

/// The sheet's text field (Android EditField): a 16-corner fill, 14 × 12 in,
/// the hint in onSurfaceVariant.
private struct ScrapField: View {
    @Binding var text: String
    var hint: String
    var scheme: CoverScheme
    var heading = false
    var singleLine = false
    var minHeight: CGFloat = 0

    var body: some View {
        let role = heading ? Tokens.type.titleMedium : Tokens.type.bodyLarge
        TextField(text: $text, prompt: Text(hint).foregroundStyle(scheme.onSurfaceVariant),
                  axis: singleLine ? .horizontal : .vertical) { Text(hint) }
            .yoinRole(role, weight: heading ? .semibold : nil)
            .foregroundStyle(scheme.onSurface)
            .tint(scheme.primary)
            .textInputAutocapitalization(.sentences)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .topLeading)
            .background(scheme.surface, in: .rect(cornerRadius: 16, style: .continuous))
    }
}
