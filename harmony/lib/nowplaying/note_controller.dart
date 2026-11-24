import 'dart:async';

import 'package:flutter/widgets.dart';

import '../data/memory/memory_store.dart';
import '../data/notes/note_markup.dart';
import '../notes/editor/note_editor_controller.dart';
import 'haptics.dart';
import 'note_logic.dart';

/// Now Playing's notes for the song on stage (Android NowPlayingScreen's
/// notes state + NoteDraftState + NoteDeletionState + NoteWriteBarState),
/// hoisted to the screen so the draft survives a page swipe, the Expanded →
/// Compact collapse and a skip. Reads and writes the account's [MemoryStore]
/// (the same `SongNote` rows Memories' diary reads).
class NowPlayingNotes extends ChangeNotifier {
  NowPlayingNotes({required this.loadStore, required this.positionMs, required this.nowMs, required this.seekTo}) {
    text.addListener(_onText);
    focus.addListener(_onFocus);
  }

  /// The active account's store (null: no account).
  final Future<MemoryStore?> Function() loadStore;

  /// The drawn playhead now (ms into the song).
  final int Function() positionMs;

  /// Epoch ms (a note's createdAt).
  final int Function() nowMs;
  final void Function(int ms) seekTo;

  /// A note was saved / one was deleted: the screen lights the background (ActionGlow.kt).
  VoidCallback? onSaved;
  VoidCallback? onDeleted;

  /// The write bar's one field: its words (stored text, [NoteEditorController.storedText]) are the draft's words.
  final NoteEditorController text = NoteEditorController();
  final FocusNode focus = FocusNode(debugLabel: 'noteWriteBar');

  final NoteDeletion _deletion = NoteDeletion();
  NoteTarget? _current;
  List<SongNote> _stored = const [];
  NoteSortMode _sortMode = NoteSortMode.timeline;
  NoteDraft _draft = const NoteDraft();
  bool _pendingFocus = false;

  /// A save just emptied the draft: no "Writing…" row until the reader
  /// types again or takes the field afresh (the keyboard may well be down —
  /// a hardware keyboard, a floating one — with the field still focused).
  bool _justSaved = false;
  bool _listHeld = false;
  bool _commitWhenLifted = false;
  bool _disposed = false;
  Timer? _undoTimer;
  Timer? _focusExpiry;
  MemoryStore? _store;
  StreamSubscription<MemoryStoreChange>? _changes;
  int _generation = 0;

  NoteTarget? get current => _current;

  /// The song's notes, a pending deletion already hidden (store order).
  List<SongNote> get notes => _deletion.visible(_stored);

  /// The note just deleted, offered back on the Note page.
  SongNote? get pendingDeletion => _deletion.pending;
  NoteSortMode get sortMode => _sortMode;
  NoteDraft get draft => _draft;
  bool get focused => focus.hasFocus;

  /// Writing: focused, or words waiting in the draft.
  bool get open => noteWriteBarOpen(focused: focused, draftText: _draft.text);

  /// The "Writing…" row on the Note page: words waiting, or the field just
  /// taken for a new note — never the empty draft a save leaves behind, nor
  /// an edit (its own line stays, dimmed).
  bool get showDraftRow =>
      draftHere && !_draft.isEditing && (_draft.text.isNotEmpty || (focused && !_justSaved));

  /// The note open in the write bar for rewriting (its line dims on the Note page).
  String? get editingId => _draft.editingNoteId;

  /// The Write pill asked for the keyboard; the stage hasn't landed yet.
  bool get pendingFocus => _pendingFocus;

  /// The draft's moment, shown as the "Writing…" row, when it belongs to the song on stage.
  bool get draftHere {
    final c = _current;
    return c != null && _draft.belongsTo(c.trackId);
  }

  // ---- the song ----

  /// The song on stage changed (or its title arrived). An empty draft follows it.
  void setCurrent(NoteTarget? target) {
    final changed = target?.trackId != _current?.trackId;
    _current = target;
    if (target != null) _draft = _draft.followIfEmpty(target, positionMs());
    if (changed) {
      _stored = const [];
      _reload();
    }
    _notify();
  }

  /// The account changed: profiles are separate data sources — a draft (and
  /// its song id) must never be filed under another one.
  void accountChanged() {
    _deletion.commit(_commitDelete);
    _undoTimer?.cancel();
    _changes?.cancel();
    _changes = null;
    _store = null;
    _draft = const NoteDraft();
    if (text.text.isNotEmpty) text.clear();
    _stored = const [];
    _reload();
    _notify();
  }

  Future<MemoryStore?> _resolveStore() async {
    final store = await loadStore();
    if (store != _store) {
      await _changes?.cancel();
      _store = store;
      _changes = store?.changes.listen((c) {
        if (c == MemoryStoreChange.songNotes) _reload();
      });
    }
    return store;
  }

  Future<void> _reload() async {
    final generation = ++_generation;
    final target = _current;
    if (target == null) return;
    final MemoryStore? store;
    try {
      store = await _resolveStore();
    } on Object {
      return;
    }
    if (store == null || _disposed || generation != _generation) return;
    final rows = await store.notesForTracks([target.trackId]);
    if (_disposed || generation != _generation) return;
    _stored = rows;
    _deletion.prune(rows);
    _notify();
  }

  void setSortMode(NoteSortMode mode) {
    if (mode == _sortMode) return;
    _sortMode = mode;
    _notify();
  }

  // ---- the draft ----

  void _onText() {
    final current = _current;
    final words = text.storedText;
    if (current == null || words == _draft.text) return;
    if (words.isNotEmpty) _justSaved = false;
    _draft = _draft.edit(words, current, positionMs());
    _notify();
  }

  void _onFocus() {
    final current = _current;
    // The moment writing begins is the moment the note means.
    if (focus.hasFocus && current != null) _draft = _draft.followIfEmpty(current, positionMs());
    if (focus.hasFocus) _pendingFocus = false;
    if (!focus.hasFocus) {
      _justSaved = false;
      // An edit put away unchanged closes: the bar returns to its capsule, the note as it was.
      if (_draft.isUntouchedEdit) _cancelEdit();
    }
    _notify();
  }

  /// A long press on a note line (Now Playing expanded, owner 2026-11-24): its
  /// words go into the write bar, which takes focus; saving rewrites that note
  /// (an emptied one is deleted, with Undo). Words not yet saved (a new note,
  /// or a changed edit of another one) are never dropped for it: the bar opens
  /// on them instead (false).
  bool beginEdit(SongNote note) {
    final current = _current;
    if (current == null) return false;
    YoinHaptics.longPress();
    if (_draft.text.trim().isNotEmpty && _draft.editingNoteId != note.id && !_draft.isUntouchedEdit) {
      focus.requestFocus();
      return false;
    }
    _draft = _draft.beginEdit(note.id, note.content, note.positionMs, current);
    _justSaved = false;
    text.setStoredText(note.content);
    focus.requestFocus();
    _notify();
    return true;
  }

  void _cancelEdit() {
    final current = _current;
    if (current == null || !_draft.isEditing) return;
    _draft = _draft.cleared(current, positionMs());
    if (text.text.isNotEmpty) text.clear();
  }

  /// The stamp chip while writing: the draft's moment becomes "now".
  void realignDraft() {
    final current = _current;
    if (current == null) return;
    YoinHaptics.tick();
    _draft = _draft.realign(current, positionMs());
    _notify();
  }

  /// Save: files the words under the song and moment the draft was started
  /// on, clears the draft for what's playing and keeps the keyboard up.
  Future<void> save() async {
    if (_draft.isEditing) return _saveEdit();
    final words = _draft.text.trim();
    final target = _draft.target ?? _current;
    final current = _current;
    if (words.isEmpty || target == null || current == null) return;
    final now = nowMs();
    final note = SongNote(
      id: '${now.toRadixString(36)}-${target.trackId.rawId.hashCode.toRadixString(36)}',
      trackId: target.trackId,
      albumId: target.albumId,
      content: words,
      createdAt: now,
      updatedAt: now,
      title: target.title,
      artist: target.artist,
      positionMs: _draft.anchorMs,
    );
    YoinHaptics.confirm();
    onSaved?.call();
    _draft = _draft.cleared(current, positionMs());
    _justSaved = true;
    text.clear();
    // Shown at once; the store's change event confirms it.
    if (note.trackId == current.trackId) _stored = [..._stored, note];
    _notify();
    final store = await _resolveStore();
    await store?.putSongNote(note);
  }

  /// Save on an edit: the same note (id, creation time) with the new words at
  /// the stamp's moment; emptied, it is deleted with the usual Undo.
  Future<void> _saveEdit() async {
    final current = _current;
    final id = _draft.editingNoteId;
    if (current == null || id == null || !_draft.canSave) return;
    final words = _draft.text.trim();
    final anchor = _draft.anchorMs;
    SongNote? original;
    for (final n in _stored) {
      if (n.id == id) original = n;
    }
    _draft = _draft.cleared(current, positionMs());
    _justSaved = true;
    text.clear();
    if (original == null) {
      _notify();
      return;
    }
    if (words.isEmpty) {
      YoinHaptics.reject();
      delete(original);
      return;
    }
    final rewritten = SongNote(
      id: original.id,
      trackId: original.trackId,
      albumId: original.albumId,
      content: words,
      createdAt: original.createdAt,
      updatedAt: nowMs(),
      title: original.title,
      artist: original.artist,
      positionMs: anchor,
    );
    YoinHaptics.confirm();
    onSaved?.call();
    _stored = [for (final n in _stored) n.id == id ? rewritten : n];
    _notify();
    final store = await _resolveStore();
    await store?.putSongNote(rewritten);
  }

  /// A to-do's box ticked on a drawn note: the note saved with it flipped.
  Future<void> toggleChecklist(SongNote note, int lineIndex) async {
    final content = NoteMarkup.toggleChecklist(note.content, lineIndex);
    if (content == note.content) return;
    final ticked = SongNote(
      id: note.id,
      trackId: note.trackId,
      albumId: note.albumId,
      content: content,
      createdAt: note.createdAt,
      updatedAt: nowMs(),
      title: note.title,
      artist: note.artist,
      positionMs: note.positionMs,
    );
    _stored = [for (final n in _stored) n.id == note.id ? ticked : n];
    _notify();
    final store = await _resolveStore();
    await store?.putSongNote(ticked);
  }

  /// The Write pill: take focus (and the keyboard) once the Note page has landed.
  void requestWriting() {
    _pendingFocus = true;
    _focusExpiry?.cancel();
    _focusExpiry = Timer(const Duration(milliseconds: noteWriteRequestTimeoutMs), cancelPendingFocus);
    _notify();
  }

  /// The page landed: the pending request takes the focus.
  void takePendingFocus() {
    if (!_pendingFocus) return;
    _pendingFocus = false;
    _focusExpiry?.cancel();
    focus.requestFocus();
    _notify();
  }

  void cancelPendingFocus() {
    if (!_pendingFocus) return;
    _pendingFocus = false;
    _notify();
  }

  // ---- the rows ----

  /// Tap a line: play from its moment.
  void playFrom(SongNote note) {
    final at = note.positionMs;
    if (at == null) return;
    YoinHaptics.tick();
    seekTo(at);
  }

  /// "Align to now" from a line's menu: the note moves to [atMs].
  Future<void> realignNote(SongNote note, int atMs) async {
    final moved = SongNote(
      id: note.id,
      trackId: note.trackId,
      albumId: note.albumId,
      content: note.content,
      createdAt: note.createdAt,
      updatedAt: nowMs(),
      title: note.title,
      artist: note.artist,
      positionMs: atMs,
    );
    _stored = [for (final n in _stored) n.id == note.id ? moved : n];
    _notify();
    final store = await _resolveStore();
    await store?.putSongNote(moved);
  }

  /// Hides [note] and offers it back for [noteUndoWindowMs]; the store deletes it after.
  void delete(SongNote note) {
    onDeleted?.call();
    _deletion.delete(note, _commitDelete);
    _commitWhenLifted = false;
    _undoTimer?.cancel();
    _undoTimer = Timer(const Duration(milliseconds: noteUndoWindowMs), _closeUndoWindow);
    _notify();
  }

  void undo() {
    _undoTimer?.cancel();
    _commitWhenLifted = false;
    if (_deletion.undo() != null) _notify();
  }

  /// A finger on the Note page's list: the undo window waits for it to lift,
  /// so a tap on its way to Undo doesn't land on the note sliding up.
  void setListHeld(bool held) {
    _listHeld = held;
    if (!held && _commitWhenLifted) {
      _commitWhenLifted = false;
      _commitPending();
    }
  }

  void _closeUndoWindow() {
    if (_listHeld) {
      _commitWhenLifted = true;
    } else {
      _commitPending();
    }
  }

  void _commitPending() {
    if (_deletion.pending == null) return;
    _deletion.commit(_commitDelete);
    _notify();
  }

  void _commitDelete(String id) {
    final store = _store;
    if (store != null) {
      unawaited(store.deleteSongNote(id).catchError((Object _) {}));
    } else {
      unawaited(_resolveStore().then((s) => s?.deleteSongNote(id)).catchError((Object _) {}));
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    // Now Playing left: a pending deletion is final.
    _deletion.commit(_commitDelete);
    _disposed = true;
    _undoTimer?.cancel();
    _focusExpiry?.cancel();
    _changes?.cancel();
    text.dispose();
    focus.dispose();
    super.dispose();
  }
}
