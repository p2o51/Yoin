/// The album page's track sheet, the pure part (Android
/// AlbumScrapbookSheetModel.kt and the sheet's edits from AlbumScrapbook.kt):
/// one song's timed lyrics for the Note tab, the edits the sheet makes, the
/// AI's work in flight and what it says when done, and the rule that puts
/// three lyric lines either side of each note.
library;

import 'package:flutter/foundation.dart';

import '../data/about/album_about_store.dart';
import '../data/about/song_about_store.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../nowplaying/lyrics_logic.dart';
import 'album_scrapbook.dart';

/// One timed lyric line.
@immutable
final class ScrapLyricLine {
  const ScrapLyricLine(this.startMs, this.text);
  final int startMs;
  final String text;

  @override
  bool operator ==(Object other) => other is ScrapLyricLine && other.startMs == startMs && other.text == text;

  @override
  int get hashCode => Object.hash(startMs, text);
}

/// One song's lyrics as the Note tab reads them: timed lines only (an untimed text has no moments).
@immutable
final class ScrapLyrics {
  const ScrapLyrics(this.lines, {this.loading = false});

  static const none = ScrapLyrics([]);
  static const pending = ScrapLyrics([], loading: true);

  final List<ScrapLyricLine> lines;
  final bool loading;

  /// Blank timed lines (instrumental gaps) are dropped; untimed lyrics give no lines.
  static ScrapLyrics of(LyricSheet? sheet) {
    if (sheet == null) return none;
    final lines = [
      for (final r in sheet.rows)
        if (r.startMs != null && r.text.trim().isNotEmpty) ScrapLyricLine(r.startMs!, r.text.trim()),
    ]..sort((a, b) => a.startMs.compareTo(b.startMs));
    return ScrapLyrics(lines);
  }

  @override
  bool operator ==(Object other) => other is ScrapLyrics && other.loading == loading && listEquals(other.lines, lines);

  @override
  int get hashCode => Object.hash(loading, Object.hashAll(lines));
}

/// One row of the Note tab, top to bottom.
@immutable
sealed class ScrapNoteRow {
  const ScrapNoteRow();
  String get key;
}

/// A lyric line; [noted] when a note was taken on it.
final class ScrapLyricRow extends ScrapNoteRow {
  const ScrapLyricRow(this.index, this.line, {required this.noted});
  final int index;
  final ScrapLyricLine line;
  final bool noted;

  @override
  String get key => 'line:$index';

  @override
  bool operator ==(Object other) =>
      other is ScrapLyricRow && other.index == index && other.line == line && other.noted == noted;

  @override
  int get hashCode => Object.hash(index, line, noted);
}

final class ScrapNoteCardRow extends ScrapNoteRow {
  const ScrapNoteCardRow(this.note);
  final SongNote note;

  @override
  String get key => 'note:${note.id}';

  @override
  bool operator ==(Object other) => other is ScrapNoteCardRow && other.note == note;

  @override
  int get hashCode => note.hashCode;
}

/// The note being written from a lyric line (or, without timed lyrics, from nothing).
final class ScrapDraftRow extends ScrapNoteRow {
  const ScrapDraftRow(this.positionMs);
  final int? positionMs;

  @override
  String get key => 'draft';

  @override
  bool operator ==(Object other) => other is ScrapDraftRow && other.positionMs == positionMs;

  @override
  int get hashCode => positionMs.hashCode;
}

/// Lines left out between two passages ("· · ·").
final class ScrapGapRow extends ScrapNoteRow {
  const ScrapGapRow(this.after);
  final int after;

  @override
  String get key => 'gap:$after';

  @override
  bool operator ==(Object other) => other is ScrapGapRow && other.after == after;

  @override
  int get hashCode => after.hashCode;
}

/// Lyric lines shown above and below a note (owner 2026-11-02: three each way).
const int scrapNoteContextLines = 3;

/// The lyric line playing at [positionMs]: the last one started by then; -1 before the first.
int lyricIndexAt(List<ScrapLyricLine> lines, int positionMs) {
  var low = 0;
  var high = lines.length - 1;
  var found = -1;
  while (low <= high) {
    final mid = (low + high) >> 1;
    if (lines[mid].startMs <= positionMs) {
      found = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return found;
}

/// The Note tab's rows (scrapNoteRows). Each timed note sits under the line it
/// was taken on, with [context] lines above and below; passages that touch
/// merge, so no line shows twice, and a [ScrapGapRow] separates the rest.
/// [expanded] shows every line instead. Notes without a moment, or a song
/// without timed lyrics, list the notes alone. [draft] is placed like a note.
List<ScrapNoteRow> scrapNoteRows(
  List<ScrapLyricLine> lines,
  List<SongNote> notes, {
  required bool expanded,
  ScrapDraftRow? draft,
  int context = scrapNoteContextLines,
}) {
  if (lines.isEmpty) return [for (final n in notes) ScrapNoteCardRow(n), ?draft];
  // Anchored items by the line they follow; -1 = before the first line.
  final atLine = <int, List<ScrapNoteRow>>{};
  final loose = <ScrapNoteRow>[];
  void place(int? positionMs, ScrapNoteRow row) {
    if (positionMs == null) {
      loose.add(row);
    } else {
      (atLine[lyricIndexAt(lines, positionMs)] ??= []).add(row);
    }
  }

  for (final n in notes) {
    place(n.positionMs, ScrapNoteCardRow(n));
  }
  if (draft != null) place(draft.positionMs, draft);
  final noted = {
    for (final n in notes)
      if (n.positionMs != null) lyricIndexAt(lines, n.positionMs!),
  };
  final last = lines.length - 1;
  final ranges = <(int, int)>[];
  if (expanded) {
    ranges.add((0, last));
  } else {
    for (final at in atLine.keys.toList()..sort()) {
      final from = (at - context).clamp(0, last);
      final to = (at + context).clamp(0, last);
      if (ranges.isNotEmpty && from <= ranges.last.$2 + 1) {
        final prev = ranges.removeLast();
        ranges.add((prev.$1, prev.$2 > to ? prev.$2 : to));
      } else {
        ranges.add((from, to));
      }
    }
  }
  final rows = <ScrapNoteRow>[];
  for (final (i, (from, to)) in ranges.indexed) {
    if (i > 0) rows.add(ScrapGapRow(ranges[i - 1].$2));
    if (from == 0) rows.addAll(atLine[-1] ?? const []);
    for (var index = from; index <= to; index++) {
      rows.add(ScrapLyricRow(index, lines[index], noted: noted.contains(index)));
      rows.addAll(atLine[index] ?? const []);
    }
  }
  if (loose.isNotEmpty && ranges.isNotEmpty) rows.add(ScrapGapRow(last));
  rows.addAll(loose);
  return rows;
}

/// What the track sheet changes (ScrapEdit). Written straight to the stores;
/// the page re-reads its data, so nothing here is optimistic.
@immutable
sealed class ScrapEdit {
  const ScrapEdit();
}

/// A track's score, 0–10 (0.0 is a score); null clears it back to "not rated".
final class ScrapScoreEdit extends ScrapEdit {
  const ScrapScoreEdit(this.trackId, this.score);
  final MediaId trackId;
  final double? score;
}

/// New text for one Ask / About row ([title] only applies to a question or
/// own entry). A blank [answer] removes the row from the page and from Now Playing.
final class ScrapAboutEdit extends ScrapEdit {
  const ScrapAboutEdit(this.ref, {this.title, required this.answer});
  final AboutRef ref;
  final String? title;
  final String answer;
}

/// Ask the AI about a track; the answer joins the track's About like one asked in Now Playing.
final class ScrapAskEdit extends ScrapEdit {
  const ScrapAskEdit(this.trackId, this.question);
  final MediaId trackId;
  final String question;
}

/// The listener's own About entry: a heading and their text (no AI).
final class ScrapWriteEdit extends ScrapEdit {
  const ScrapWriteEdit(this.trackId, this.heading, this.text);
  final MediaId trackId;
  final String heading;
  final String text;
}

/// The AI writes a track's About from nothing (an empty sheet offers it).
final class ScrapGenerateEdit extends ScrapEdit {
  const ScrapGenerateEdit(this.trackId);
  final MediaId trackId;
}

/// The AI writes one row again.
final class ScrapRegenerateEdit extends ScrapEdit {
  const ScrapRegenerateEdit(this.ref);
  final AboutRef ref;
}

/// New text for a note; blank deletes it ([ScrapRestoreNoteEdit] brings it back).
final class ScrapNoteTextEdit extends ScrapEdit {
  const ScrapNoteTextEdit(this.noteId, this.text);
  final String noteId;
  final String text;
}

/// A note written from the sheet, at [positionMs] in the song (a lyric line's moment), or none.
final class ScrapNewNoteEdit extends ScrapEdit {
  const ScrapNewNoteEdit(this.trackId, this.positionMs, this.text);
  final MediaId trackId;
  final int? positionMs;
  final String text;
}

/// Undo of a deleted note: the same note, back at its moment.
final class ScrapRestoreNoteEdit extends ScrapEdit {
  const ScrapRestoreNoteEdit(this.noteId);
  final String noteId;
}

/// A new album note (an album holds any number); blank writes nothing.
final class ScrapNewAlbumNoteEdit extends ScrapEdit {
  const ScrapNewAlbumNoteEdit(this.text);
  final String text;
}

/// New text for an album note; blank deletes it ([ScrapRestoreAlbumNoteEdit] brings it back).
final class ScrapAlbumNoteTextEdit extends ScrapEdit {
  const ScrapAlbumNoteTextEdit(this.noteId, this.text);
  final String noteId;
  final String text;
}

/// Undo of a deleted album note.
final class ScrapRestoreAlbumNoteEdit extends ScrapEdit {
  const ScrapRestoreAlbumNoteEdit(this.noteId);
  final String noteId;
}

/// The page came to rest: write the album's About if it was never written (one AI call per album, then
/// cached). Quiet: no key, or a failure, simply leaves the card off.
final class ScrapEnsureAlbumAboutEdit extends ScrapEdit {
  const ScrapEnsureAlbumAboutEdit();
}

/// "Regenerate" (or "Generate with …") in the album's About sheet (undoable).
final class ScrapRegenerateAlbumAboutEdit extends ScrapEdit {
  const ScrapRegenerateAlbumAboutEdit();
}

/// Undo of [ScrapRegenerateAlbumAboutEdit]: the album's About rows as they were.
final class ScrapRestoreAlbumAboutEdit extends ScrapEdit {
  const ScrapRestoreAlbumAboutEdit(this.rows);
  final List<AlbumAboutEntry> rows;
}

/// Ask the AI about the album from its About sheet.
final class ScrapAskAlbumEdit extends ScrapEdit {
  const ScrapAskAlbumEdit(this.question);
  final String question;
}

/// What the album's stickies are doing (Android StickyEditing): shown, or
/// one being written in place. [open] is null with nothing open, [newSticky]
/// while the dashed sticky (or bare paper) became an editor, else the id of
/// the note being edited; [text] is what the editor opened with.
class StickyEditing extends ChangeNotifier {
  static const newSticky = '\u0000new';

  String? _open;
  String _text = '';

  String? get open => _open;
  String get text => _text;

  void startNew() => _set(newSticky, '');

  void startEdit(ScrapSticky sticky) => _set(sticky.noteId, sticky.text);

  void close() => _set(null, '');

  void _set(String? open, String text) {
    if (_open == open && _text == text) return;
    _open = open;
    _text = text;
    notifyListeners();
  }
}

/// Saving an open sticky, as the page's edit: a new note, new words, or a
/// delete (blank words on an existing one, with Undo); a blank new sticky
/// writes nothing (Android stickySave).
ScrapEdit? stickySave(String open, String text) {
  final words = text.trim();
  if (open == StickyEditing.newSticky) return words.isEmpty ? null : ScrapNewAlbumNoteEdit(words);
  return ScrapAlbumNoteTextEdit(open, words);
}

/// A question asked from the sheet that the AI has not answered yet.
@immutable
final class ScrapAsking {
  const ScrapAsking(this.trackId, this.question);
  final MediaId trackId;
  final String question;

  @override
  bool operator ==(Object other) => other is ScrapAsking && other.trackId == trackId && other.question == question;

  @override
  int get hashCode => Object.hash(trackId, question);
}

/// The AI's work in flight from page 2.
@immutable
final class ScrapGemini {
  const ScrapGemini({
    this.regenerating = const {},
    this.asking = const [],
    this.generating = const {},
    this.albumAbout = false,
    this.albumAsking = const [],
  });

  final Set<AboutRef> regenerating;
  final List<ScrapAsking> asking;

  /// Tracks whose About the AI is writing from nothing.
  final Set<MediaId> generating;

  /// The album's own About is being written (the first time, or Regenerate).
  final bool albumAbout;

  /// Questions asked about the album that the AI has not answered yet.
  final List<String> albumAsking;

  ScrapGemini copyWith({
    Set<AboutRef>? regenerating,
    List<ScrapAsking>? asking,
    Set<MediaId>? generating,
    bool? albumAbout,
    List<String>? albumAsking,
  }) => ScrapGemini(
    regenerating: regenerating ?? this.regenerating,
    asking: asking ?? this.asking,
    generating: generating ?? this.generating,
    albumAbout: albumAbout ?? this.albumAbout,
    albumAsking: albumAsking ?? this.albumAsking,
  );

  @override
  bool operator ==(Object other) =>
      other is ScrapGemini &&
      setEquals(other.regenerating, regenerating) &&
      listEquals(other.asking, asking) &&
      setEquals(other.generating, generating) &&
      other.albumAbout == albumAbout &&
      listEquals(other.albumAsking, albumAsking);

  @override
  int get hashCode => Object.hash(
    Object.hashAll(regenerating),
    Object.hashAll(asking),
    Object.hashAll(generating),
    albumAbout,
    Object.hashAll(albumAsking),
  );
}

/// What the AI's work from page 2 came to (ScrapEvent).
sealed class ScrapEvent {
  const ScrapEvent();
}

/// A row (or the album's About) came back new; [restore] puts the old text back (Undo).
final class ScrapRegenerated extends ScrapEvent {
  const ScrapRegenerated(this.restore);
  final ScrapEdit restore;
}

enum ScrapFailure { keyMissing, failed, nothingNew }

final class ScrapFailed extends ScrapEvent {
  const ScrapFailed(this.failure);
  final ScrapFailure failure;
}

/// Writing the album's About again came back with nothing, or failed: said in the About sheet when it is
/// open (under the paragraph), on the page otherwise.
final class ScrapAlbumAboutFailed extends ScrapEvent {
  const ScrapAlbumAboutFailed(this.failure);
  final ScrapFailure failure;
}

enum ScrapSheetTab { about, notes }

/// The track a sheet is about: its Ask / About (null: none yet) and its notes in timeline order.
@immutable
final class ScrapTrackSheetTarget {
  const ScrapTrackSheetTarget(this.track, this.about, this.notes);
  final ScrapTrack track;
  final ScrapAboutSheet? about;
  final List<SongNote> notes;
}

/// The first piece on the page holding [trackId]'s ticket; null once it has left the page.
ScrapTrackSheetTarget? trackSheetFor(List<ScrapPiece> pieces, MediaId trackId) {
  for (final piece in pieces) {
    switch (piece) {
      case ScrapTrackCluster(:final track, :final sheet, :final notes) when track.track.id == trackId:
        return ScrapTrackSheetTarget(track, sheet, notes);
      case ScrapContactSheet(:final tracks):
        for (final t in tracks) {
          if (t.track.id == trackId) return ScrapTrackSheetTarget(t, null, const []);
        }
      default:
        break;
    }
  }
  return null;
}
