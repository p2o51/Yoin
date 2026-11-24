import Foundation
import Observation
import YoinShared

/**
 * One note editor's state (Android ui/notes/editor/NoteEditorState.kt): the stored text (light Markdown,
 * mentions as `[@Title](yoin:…)` links), the selection and the keyboard's marked text, all in UTF-16 offsets
 * into the stored text; the `@` being typed and the popup's rows and highlighted row. The text rules are
 * :shared's (YoinNoteEditor, the same ones Android runs): a keyboard edit passes [intercept] (Return at an
 * open popup picks its top row; a mention stays whole; Enter continues a list; `[] ` / `＃` shortcuts), the
 * editor's own actions (a row picked, a tool) write canonical text directly. The text view only shows it.
 */
@MainActor
@Observable
final class NoteEditorModel {
    /// The stored text as it stands.
    private(set) var text: String
    /// The selection in the stored text (UTF-16).
    private(set) var selection: NSRange
    /// The keyboard's marked (still composing) text in the stored text, if any.
    private(set) var composing: NSRange?
    /// Bumps when the editor itself rewrote the text: the text view takes [text] and [selection] again.
    private(set) var revision = 0
    /// The text view holds the keyboard.
    var focused = false
    /// The popup's highlighted row (↑ ↓ move it; Return picks it).
    var highlighted = 0
    private(set) var rows = MentionRows.empty
    private(set) var loading = false

    /// The `@` the reader dismissed (Esc, a tap away): the popup stays shut until another `@`.
    @ObservationIgnored private var dismissedAt: Int?
    /// Every row this editor has been offered: each letter typed after the @ filters it at once.
    @ObservationIgnored private var pool = MentionRows.empty
    @ObservationIgnored private var queryTask: Task<Void, Never>?
    @ObservationIgnored private var askedQuery: String?
    @ObservationIgnored let source: MentionRowSource
    @ObservationIgnored let romanizer: YoinMentionRomanizer
    @ObservationIgnored let debounce: Duration
    /// Told every new text (the host's binding).
    @ObservationIgnored var onChange: ((String) -> Void)?

    /// Android PopupRowsPerKind.
    static let rowsPerKind = 5

    init(text: String = "", source: MentionRowSource, romanizer: YoinMentionRomanizer = PinyinRomanizer.shared,
         debounce: Duration = .milliseconds(160)) {
        self.text = text
        self.selection = NSRange(location: text.utf16.count, length: 0)
        self.source = source
        self.romanizer = romanizer
        self.debounce = debounce
    }

    // MARK: Reading

    /// The `@` being typed at the caret, dismissed or not (letters the keyboard is still composing count).
    var typedTrigger: MentionTrigger? {
        guard selection.length == 0 else { return nil }
        let c = composing.flatMap { $0.length > 0 ? $0 : nil }
        return YoinNoteEditor.shared.trigger(text: text, caret: Int32(selection.location),
                                             composingStart: Int32(c?.location ?? -1),
                                             composingEnd: Int32(c.map { $0.location + $0.length } ?? -1))
    }

    /// The `@` being typed, unless dismissed.
    var trigger: MentionTrigger? {
        guard let t = typedTrigger, Int(t.start) != dismissedAt else { return nil }
        return t
    }

    /// The popup shows: an `@` is being typed in the focused field.
    var popupOpen: Bool { focused && trigger != nil }

    /// What Return picks while the popup is open (its highlighted row, the top one unless moved).
    var enterPick: YoinNoteMention? {
        guard popupOpen else { return nil }
        let all = rows.all
        guard all.indices.contains(highlighted) else { return all.first?.mention }
        return all[highlighted].mention
    }

    /// The tools that read as pressed at the caret.
    var activeTools: Set<NoteTool> {
        Set(YoinNoteEditor.shared.activeTools(text: text, selectionStart: Int32(selection.location),
                                              selectionEnd: Int32(selection.location + selection.length)))
    }

    // MARK: The text view's events

    /// A keyboard edit about to land: replacing [range] (stored-text offsets) with [replacement]. Returns the
    /// corrected edit, already applied here (the text view then shows it instead), or nil: the edit stands as
    /// typed and arrives through [textChanged].
    func intercept(range: NSRange, replacement: String) -> NoteEdit? {
        let original = text as NSString
        guard range.location >= 0, range.location + range.length <= original.length else { return nil }
        let edited = original.replacingCharacters(in: range, with: replacement)
        let caret = range.location + (replacement as NSString).length
        let editor = YoinNoteEditor.shared
        if replacement.contains("\n"), let pick = enterPick, let at = trigger,
           let fix = editor.enterPicksMention(original: text, edited: edited, trigger: at, pick: pick) {
            commit(fix)
            highlighted = 0
            return fix
        }
        if let fix = editor.reconcile(original: text, edited: edited, editedSelectionStart: Int32(caret),
                                      editedSelectionEnd: Int32(caret)) {
            commit(fix)
            return fix
        }
        return nil
    }

    /// The text view's text after an edit it made itself (typing, marked text, paste, undo).
    func textChanged(_ value: String, selection: NSRange, composing: NSRange?) {
        let changed = value != text
        text = value
        self.selection = selection
        self.composing = composing
        syncDismissal()
        refreshQuery()
        if changed { onChange?(value) }
    }

    /// The caret or the marked text moved.
    func selectionChanged(_ selection: NSRange, composing: NSRange?) {
        guard selection != self.selection || composing != self.composing else { return }
        self.selection = selection
        self.composing = composing
        syncDismissal()
        refreshQuery()
    }

    func focusChanged(_ on: Bool) {
        guard on != focused else { return }
        focused = on
        refreshQuery()
    }

    // MARK: The editor's own actions

    /// [row] in place of the `@` being typed (or at the selection).
    func pick(_ row: MentionRow) {
        guard let mention = row.mention else { return }
        let edit = YoinNoteEditor.shared.insertMention(text: text, selectionStart: Int32(selection.location),
                                                       selectionEnd: Int32(selection.location + selection.length),
                                                       trigger: trigger, mention: mention)
        if let edit { commit(edit) }
        highlighted = 0
    }

    /// The formatting row's [tool] at the selection.
    func apply(_ tool: NoteTool) {
        let edit = YoinNoteEditor.shared.apply(tool: tool, text: text, selectionStart: Int32(selection.location),
                                               selectionEnd: Int32(selection.location + selection.length))
        commit(edit)
        if tool == .mention { dismissedAt = nil; refreshQuery() }
    }

    /// Ticks or unticks the to-do on [line].
    func toggleChecklist(line: Int) {
        let out = YoinNoteEditor.shared.toggleChecklist(text: text, lineIndex: Int32(line))
        guard out != text else { return }
        commit(NoteEdit(text: out, selectionStart: Int32(selection.location),
                        selectionEnd: Int32(selection.location + selection.length)))
    }

    /// Esc, a tap away: the popup shuts until another `@`.
    func dismissPopup() {
        dismissedAt = trigger.map { Int($0.start) }
        queryTask?.cancel()
        askedQuery = nil
    }

    /// ↑ ↓ in the popup.
    func moveHighlight(_ delta: Int) {
        let count = rows.all.count
        guard count > 0 else { return }
        highlighted = (highlighted + delta + count) % count
    }

    /// Replaces the words from outside (a draft cleared after saving, a note opened); caret at the end.
    func setText(_ value: String) {
        guard value != text else { return }
        text = value
        selection = NSRange(location: value.utf16.count, length: 0)
        composing = nil
        dismissedAt = nil
        highlighted = 0
        revision &+= 1
        refreshQuery()
    }

    // MARK: Internals

    private func commit(_ edit: NoteEdit) {
        let length = (edit.text as NSString).length
        let start = min(max(Int(edit.selectionStart), 0), length)
        let end = min(max(Int(edit.selectionEnd), start), length)
        text = edit.text
        selection = NSRange(location: start, length: end - start)
        composing = nil
        revision &+= 1
        syncDismissal()
        refreshQuery()
        onChange?(text)
    }

    /// A dismissal ends once its `@` is gone or the caret has left it.
    private func syncDismissal() {
        guard let at = dismissedAt else { return }
        if typedTrigger.map({ Int($0.start) }) != at { dismissedAt = nil }
    }

    /// Asks for rows when the words after the `@` change (Android NoteMarkupField's LaunchedEffect(query)).
    private func refreshQuery() {
        guard popupOpen, let query = trigger?.query else {
            if askedQuery != nil { queryTask?.cancel(); askedQuery = nil; loading = false }
            return
        }
        guard query != askedQuery else { return }
        askedQuery = query
        queryTask?.cancel()
        loading = true
        if !query.isEmpty {
            rows = MentionRanking.whileSearching(query, pool: pool, romanizer: romanizer, cap: Self.rowsPerKind)
            highlighted = 0
        }
        let source = source, romanizer = romanizer, debounce = debounce
        queryTask = Task { [weak self] in
            if !query.isEmpty {
                try? await Task.sleep(for: debounce)
                guard !Task.isCancelled else { return }
            }
            let found = await source.rows(query: query, limit: Self.rowsPerKind)
            guard !Task.isCancelled, let self else { return }
            self.pool = self.pool.merge(found)
            self.rows = MentionRanking.afterSearch(query, found: found, pool: self.pool, romanizer: romanizer,
                                                   cap: Self.rowsPerKind)
            self.highlighted = 0
            self.loading = false
        }
    }
}
