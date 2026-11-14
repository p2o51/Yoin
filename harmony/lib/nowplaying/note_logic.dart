/// Now Playing's notes, pinned to the second (Android ui/component/NoteContent.kt,
/// NoteDraft.kt and ui/nowplaying/NoteWriting.kt): the ordering, the lit-note
/// rule, the page's rows, the draft and delete-with-undo. Pure Dart; the
/// widgets live in note_line.dart, note_compact_pane.dart, note_page.dart and
/// note_write_bar.dart.
library;

import 'package:flutter/foundation.dart';

import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import 'playhead_clock.dart';

/// Which of the two detail pages the stage shows. Android has Lyrics | About |
/// Note; About needs Gemini, which HarmonyOS lacks, so it is left out.
enum NowPlayingDetailPage { lyrics, note }

/// How a track's notes are ordered on the Note page.
/// - [timeline]: by the song-position anchor, un-anchored notes last.
/// - [created]: by when they were written (oldest first) — a journal.
enum NoteSortMode { timeline, created }

/// [notes] in [mode] order (Android sortNotes): timeline = by positionMs
/// (nulls last), ties by createdAt; created = by createdAt.
List<SongNote> sortNotes(List<SongNote> notes, NoteSortMode mode) {
  final sorted = [...notes];
  switch (mode) {
    case NoteSortMode.timeline:
      sorted.sort((a, b) {
        final pa = a.positionMs;
        final pb = b.positionMs;
        if (pa != pb) {
          if (pa == null) return 1;
          if (pb == null) return -1;
          return pa.compareTo(pb);
        }
        return a.createdAt.compareTo(b.createdAt);
      });
    case NoteSortMode.created:
      sorted.sort((a, b) => a.createdAt.compareTo(b.createdAt));
  }
  return sorted;
}

/// The note the playhead is "inside": the latest anchored note at or before
/// [positionMs] — the lyrics' current-line rule. Null before the first anchor
/// or when nothing is anchored. Order of [notes] doesn't matter; of two notes
/// on the same second the later in [notes] wins (Android currentAnchoredNoteId).
String? currentAnchoredNoteId(Iterable<SongNote> notes, int positionMs) {
  SongNote? best;
  for (final note in notes) {
    final anchor = note.positionMs;
    if (anchor == null) continue;
    if (anchor <= positionMs && (best?.positionMs ?? -1) <= anchor) best = note;
  }
  return best?.id;
}

/// m:ss of a note's moment (formatNotePosition).
String formatNotePosition(int ms) => formatPlaybackTime(ms);

/// How loudly a note line is drawn.
enum NoteLineEmphasis {
  /// The Compact window: strong distance falloff and the lyric shrink.
  glance,

  /// The Expanded Note page: every line stays legible; the current one lights up.
  page,
}

/// Row opacity (Android noteLineAlpha). [distance] is how many rows away the
/// current note is; null when the playhead is inside no note at all.
double noteLineAlpha({required bool active, required int? distance, required NoteLineEmphasis emphasis}) {
  if (active) return 1;
  if (emphasis == NoteLineEmphasis.page) return distance == null ? 0.86 : 0.62;
  if (distance == null) return 0.28;
  if (distance <= 1) return 0.55;
  if (distance == 2) return 0.40;
  return 0.28;
}

/// The song a note is written FOR, captured with its moment when writing
/// begins: a skip between the first word and Save must not move the note to
/// the next song.
@immutable
final class NoteTarget {
  const NoteTarget({required this.trackId, this.albumId, this.title = '', this.artist = ''});

  factory NoteTarget.of(Track track) =>
      NoteTarget(trackId: track.id, albumId: track.albumId, title: track.title ?? '', artist: track.artist ?? '');

  final MediaId trackId;
  final MediaId? albumId;
  final String title;
  final String artist;

  @override
  bool operator ==(Object other) =>
      other is NoteTarget &&
      other.trackId == trackId &&
      other.albumId == albumId &&
      other.title == title &&
      other.artist == artist;

  @override
  int get hashCode => Object.hash(trackId, albumId, title, artist);
}

/// A note being written (Android NoteDraft). While it has no words it
/// follows what's playing; the moment writing begins binds it to that song
/// and playhead until it's saved or explicitly re-aligned.
@immutable
final class NoteDraft {
  const NoteDraft({this.text = '', this.anchorMs, this.target});

  final String text;
  final int? anchorMs;
  final NoteTarget? target;

  /// Words that belong to a song other than [current].
  bool isForOtherTrack(NoteTarget current) => text.isNotEmpty && target != null && target!.trackId != current.trackId;

  /// No words yet: (re)bind to [current] at [positionMs].
  NoteDraft followIfEmpty(NoteTarget current, int positionMs) =>
      text.isEmpty ? NoteDraft(anchorMs: positionMs, target: current) : this;

  /// Typing. An unbound draft — or an emptied one still bound to a song that
  /// has since ended — binds to [current] at [positionMs] with its first word.
  NoteDraft edit(String newText, NoteTarget current, int positionMs) {
    final t = target;
    if (t == null || (text.isEmpty && t.trackId != current.trackId)) {
      return NoteDraft(text: newText, anchorMs: positionMs, target: current);
    }
    return NoteDraft(text: newText, anchorMs: anchorMs, target: t);
  }

  /// "Align to now": the moment moves to [positionMs] on [current], words kept.
  NoteDraft realign(NoteTarget current, int positionMs) => NoteDraft(text: text, anchorMs: positionMs, target: current);

  /// After a save: an empty draft for what's playing now.
  NoteDraft cleared(NoteTarget current, int positionMs) => NoteDraft(anchorMs: positionMs, target: current);

  /// A draft without a song yet, or one started on [trackId], lands in that song's list.
  bool belongsTo(MediaId trackId) => target == null || target!.trackId == trackId;
}

/// The stamp's realign glyph appears once the playhead has moved this far from the draft's moment.
const int noteRealignHintDriftMs = 2000;

bool showNoteRealignHint(int? anchorMs, int positionMs) =>
    anchorMs != null && (positionMs - anchorMs).abs() >= noteRealignHintDriftMs;

/// The bar is open while it has focus or holds unsaved words.
bool noteWriteBarOpen({required bool focused, required String draftText}) => focused || draftText.isNotEmpty;

/// The keyboard went down after it had come up for the bar, over an empty draft: back to the capsule.
bool shouldCollapseNoteWriteBar({required bool imeWasShown, required bool imeVisible, required bool draftEmpty}) =>
    imeWasShown && !imeVisible && draftEmpty;

/// Timeline follow for the Note page: glide to the note the playhead is in,
/// unless the reader dragged the list or is writing.
bool shouldFollowCurrentNote({
  required NoteSortMode sortMode,
  required int currentIndex,
  required bool userScrolled,
  required bool writing,
}) => sortMode == NoteSortMode.timeline && currentIndex >= 0 && !userScrolled && !writing;

/// A row of the Expanded Note page.
sealed class NoteRow {
  const NoteRow();
  Object get key;
}

/// A written note.
final class NoteLineRow extends NoteRow {
  const NoteLineRow(this.note);
  final SongNote note;
  @override
  Object get key => note.id;
}

/// A just-deleted note: "Deleted · Undo" in its place until the undo window closes.
final class NoteDeletedRow extends NoteRow {
  const NoteDeletedRow(this.note);
  final SongNote note;
  @override
  Object get key => note.id;
}

/// The note being written, at the moment it will be filed at.
final class NoteDraftRow extends NoteRow {
  const NoteDraftRow(this.anchorMs);
  final int? anchorMs;
  @override
  Object get key => _draftRowKey;
}

const String _draftRowKey = 'note-draft';

/// The page's rows (Android noteRows): the notes in [mode] order, the
/// [deleted] note back in its place as an undo row, and — while writing — the
/// draft at [draftAnchorMs]: after every note at or before its moment on the
/// timeline (un-anchored notes stay last), at the end of the journal order.
List<NoteRow> noteRows(
  List<SongNote> notes,
  NoteSortMode mode, {
  SongNote? deleted,
  int? draftAnchorMs,
  bool showDraft = false,
}) {
  final all = deleted != null && notes.every((n) => n.id != deleted.id) ? [...notes, deleted] : notes;
  final rows = <NoteRow>[
    for (final note in sortNotes(all, mode)) note.id == deleted?.id ? NoteDeletedRow(note) : NoteLineRow(note),
  ];
  if (!showDraft) return rows;
  var at = rows.length;
  if (mode == NoteSortMode.timeline && draftAnchorMs != null) {
    final i = rows.indexWhere((row) {
      final anchor = switch (row) {
        NoteLineRow(:final note) || NoteDeletedRow(:final note) => note.positionMs,
        NoteDraftRow() => null,
      };
      return anchor == null || anchor > draftAnchorMs;
    });
    if (i >= 0) at = i;
  }
  rows.insert(at, NoteDraftRow(draftAnchorMs));
  return rows;
}

/// Where the followed line rests in a note list, as in the compact lyrics window.
const double noteFollowAnchorFraction = 0.22;

/// How long a deleted note can be taken back before it is gone.
const int noteUndoWindowMs = 5000;

/// The Write pill's keyboard waits for the stage to (nearly) finish expanding.
const double noteWriteFocusStageProgress = 0.9;

/// A Write-pill request the page never landed for expires.
const int noteWriteRequestTimeoutMs = 2000;

/// Delete with undo (Android NoteDeletionState): a deleted note is hidden at
/// once everywhere and offered back in its place on the Note page; the store
/// deletes it only when the undo window closes ([commit]), so an undo restores
/// the very same row. One pending deletion at a time: deleting another
/// commits the first.
final class NoteDeletion {
  SongNote? _pending;
  Set<String> _hidden = const {};

  SongNote? get pending => _pending;
  Set<String> get hiddenIds => _hidden;

  List<SongNote> visible(List<SongNote> notes) => _hidden.isEmpty
      ? notes
      : [
          for (final n in notes)
            if (!_hidden.contains(n.id)) n,
        ];

  /// Hides [note]; a different earlier pending deletion is handed to [commit].
  void delete(SongNote note, void Function(String id) commit) {
    final previous = _pending;
    if (previous != null && previous.id != note.id) commit(previous.id);
    _pending = note;
    _hidden = {..._hidden, note.id};
  }

  /// Takes the pending deletion back; null when there was none.
  SongNote? undo() {
    final note = _pending;
    if (note == null) return null;
    _pending = null;
    _hidden = {..._hidden}..remove(note.id);
    return note;
  }

  /// The undo window closed (or Now Playing left): delete for real.
  void commit(void Function(String id) commit) {
    final note = _pending;
    if (note == null) return;
    _pending = null;
    commit(note.id);
  }

  /// Forget hidden ids the store has dropped.
  void prune(List<SongNote> notes) {
    if (_hidden.isEmpty) return;
    final present = {for (final n in notes) n.id};
    final kept = {
      for (final id in _hidden)
        if (id == _pending?.id || present.contains(id)) id,
    };
    if (kept.length != _hidden.length) _hidden = kept;
  }
}

/// When a note was written for the journal order: relative while recent, then
/// the date (Android formatNoteDate; strings cmp_note_*).
String formatNoteDate(int epochMs, {required int nowMs, required bool zh}) {
  final delta = (nowMs - epochMs).clamp(0, 1 << 62);
  final minutes = delta ~/ 60000;
  final hours = delta ~/ 3600000;
  final days = delta ~/ 86400000;
  if (minutes < 1) return zh ? '刚刚' : 'Just now'; // cmp_note_just_now
  if (minutes < 60) {
    return zh ? '$minutes 分钟前' : '$minutes ${minutes == 1 ? 'minute' : 'minutes'} ago'; // cmp_note_minutes_ago
  }
  if (hours < 24) return zh ? '$hours 小时前' : '$hours ${hours == 1 ? 'hour' : 'hours'} ago'; // cmp_note_hours_ago
  if (days < 7) return zh ? '$days 天前' : '$days ${days == 1 ? 'day' : 'days'} ago'; // cmp_note_days_ago
  final d = DateTime.fromMillisecondsSinceEpoch(epochMs);
  if (zh) return '${d.year}年${d.month}月${d.day}日';
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return '${months[d.month - 1]} ${d.day}, ${d.year}';
}
