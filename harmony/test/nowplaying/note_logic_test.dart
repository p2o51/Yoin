import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/note_logic.dart';

SongNote _note(String id, {int? at, int created = 0}) => SongNote(
  id: id,
  trackId: MediaId.subsonic('t'),
  content: 'note $id',
  createdAt: created,
  updatedAt: created,
  positionMs: at,
);

void main() {
  group('sortNotes', () {
    test('should_orderByMomentWithUnanchoredLast_when_timeline', () {
      final notes = [
        _note('late', at: 90000, created: 1),
        _note('legacy', created: 0),
        _note('early', at: 12000, created: 5),
        _note('tieB', at: 48000, created: 9),
        _note('tieA', at: 48000, created: 3),
      ];
      expect(sortNotes(notes, NoteSortMode.timeline).map((n) => n.id), ['early', 'tieA', 'tieB', 'late', 'legacy']);
    });

    test('should_orderByCreation_when_created', () {
      final notes = [_note('b', at: 1000, created: 20), _note('a', at: 90000, created: 10), _note('c', created: 30)];
      expect(sortNotes(notes, NoteSortMode.created).map((n) => n.id), ['a', 'b', 'c']);
    });
  });

  group('currentAnchoredNoteId', () {
    final notes = [_note('a', at: 12000), _note('b', at: 48000), _note('legacy'), _note('c', at: 83000)];

    test('should_beNull_when_playheadBeforeFirstAnchor', () {
      expect(currentAnchoredNoteId(notes, 11999), isNull);
    });

    test('should_lightLatestNoteAtOrBeforePlayhead_when_inside', () {
      expect(currentAnchoredNoteId(notes, 12000), 'a');
      expect(currentAnchoredNoteId(notes, 50000), 'b');
      expect(currentAnchoredNoteId(notes, 600000), 'c');
    });

    test('should_ignoreListOrder_when_shuffled', () {
      expect(currentAnchoredNoteId(notes.reversed, 50000), 'b');
    });

    test('should_beNull_when_nothingAnchored', () {
      expect(currentAnchoredNoteId([_note('legacy')], 50000), isNull);
    });
  });

  group('noteLineAlpha', () {
    test('should_fadeByDistance_when_glance', () {
      expect(noteLineAlpha(active: true, distance: 0, emphasis: NoteLineEmphasis.glance), 1);
      expect(noteLineAlpha(active: false, distance: 1, emphasis: NoteLineEmphasis.glance), 0.55);
      expect(noteLineAlpha(active: false, distance: 2, emphasis: NoteLineEmphasis.glance), 0.40);
      expect(noteLineAlpha(active: false, distance: 5, emphasis: NoteLineEmphasis.glance), 0.28);
    });

    test('should_stayLegible_when_page', () {
      expect(noteLineAlpha(active: false, distance: 4, emphasis: NoteLineEmphasis.page), 0.62);
      expect(noteLineAlpha(active: false, distance: null, emphasis: NoteLineEmphasis.page), 0.86);
    });
  });

  group('noteRows', () {
    final notes = [_note('a', at: 12000, created: 1), _note('b', at: 48000, created: 2), _note('legacy', created: 0)];

    test('should_placeDraftAfterNotesAtOrBeforeItsMoment_when_timeline', () {
      final rows = noteRows(notes, NoteSortMode.timeline, draftAnchorMs: 30000, showDraft: true);
      expect(rows.map((r) => r.key), ['a', 'note-draft', 'b', 'legacy']);
    });

    test('should_placeDraftLast_when_created', () {
      final rows = noteRows(notes, NoteSortMode.created, draftAnchorMs: 30000, showDraft: true);
      expect(rows.last, isA<NoteDraftRow>());
    });

    test('should_keepDeletedNoteInItsPlace_when_pendingUndo', () {
      final deleted = notes[1];
      final rows = noteRows([notes[0], notes[2]], NoteSortMode.timeline, deleted: deleted);
      expect(rows.map((r) => r.runtimeType), [NoteLineRow, NoteDeletedRow, NoteLineRow]);
    });
  });

  group('NoteDraft', () {
    final song = NoteTarget(trackId: MediaId.subsonic('s1'), title: 'S1');
    final next = NoteTarget(trackId: MediaId.subsonic('s2'), title: 'S2');

    test('should_keepSongAndMoment_when_wordsWrittenThenSkipped', () {
      var draft = const NoteDraft().followIfEmpty(song, 1000).edit('hi', song, 2000);
      draft = draft.followIfEmpty(next, 5000);
      expect(draft.target, song);
      expect(draft.anchorMs, 1000);
      expect(draft.isForOtherTrack(next), isTrue);
    });

    test('should_followSong_when_empty', () {
      final draft = const NoteDraft().followIfEmpty(song, 1000).followIfEmpty(next, 5000);
      expect(draft.target, next);
      expect(draft.anchorMs, 5000);
    });

    test('should_moveMomentKeepWords_when_realigned', () {
      final draft = const NoteDraft().edit('hi', song, 1000).realign(song, 9000);
      expect(draft.text, 'hi');
      expect(draft.anchorMs, 9000);
    });
  });

  group('NoteDeletion', () {
    test('should_restoreSameNote_when_undone', () {
      final d = NoteDeletion();
      final n = _note('a', at: 1);
      d.delete(n, (_) => fail('no commit'));
      expect(d.visible([n]), isEmpty);
      expect(d.undo(), n);
      expect(d.visible([n]), [n]);
    });

    test('should_commitFirst_when_secondDeleted', () {
      final d = NoteDeletion();
      final committed = <String>[];
      d.delete(_note('a'), committed.add);
      d.delete(_note('b'), committed.add);
      expect(committed, ['a']);
      expect(d.pending?.id, 'b');
    });
  });

  test('should_showRealignGlyph_when_driftedTwoSeconds', () {
    expect(showNoteRealignHint(10000, 11999), isFalse);
    expect(showNoteRealignHint(10000, 12000), isTrue);
    expect(showNoteRealignHint(null, 50000), isFalse);
  });
}
