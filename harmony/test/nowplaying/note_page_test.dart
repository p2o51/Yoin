import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/note_compact_pane.dart';
import 'package:yoin/nowplaying/note_controller.dart';
import 'package:yoin/notes/note_markup_text.dart';
import 'package:yoin/nowplaying/note_logic.dart';
import 'package:yoin/nowplaying/note_page.dart';
import 'package:yoin/nowplaying/note_write_bar.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playhead.dart';

const int _now = 1700000000000;

final _track = NoteTarget(trackId: MediaId.subsonic('song'), albumId: MediaId.subsonic('album'), title: 'Song');

/// A paused playhead at [positionMs].
PlayheadClock _clock(int positionMs) =>
    PlayheadClock(now: () => _now)
      ..update(Playhead(entryId: 'e', positionMs: positionMs, sampledAtMs: _now, durationMs: 240000));

void main() {
  late MemoryStore store;
  late NowPlayingNotes notes;
  late PlayheadClock clock;

  // Built inside each test's fake-async zone (the store's futures must run there).
  void setUpNotes() {
    store = MemoryStore(InMemoryStore(), profileId: 'p');
    clock = _clock(83000);
    notes = NowPlayingNotes(loadStore: () async => store, positionMs: () => 83000, nowMs: () => _now, seekTo: (_) {})
      ..setCurrent(_track);
  }

  Future<void> pumpPage(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              Expanded(
                child: NotePage(notes: notes, clock: clock),
              ),
              SizedBox(
                height: 160,
                child: NoteWriteBar(notes: notes, clock: clock),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('should_pinNoteToPlayhead_when_writtenAndSaved', (tester) async {
    setUpNotes();
    await pumpPage(tester);
    expect(find.text('No notes yet'), findsOneWidget);

    await tester.tap(find.byType(NoteWriteBar));
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'Chorus comes in');
    await tester.pumpAndSettle();
    // While writing, the draft's spot shows where the note lands.
    expect(find.text('Writing…'), findsOneWidget);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(find.text('Chorus comes in'), findsOneWidget);
    expect(find.text('1:23'), findsWidgets);
    expect(find.text('1 note'), findsOneWidget);
    final saved = await store.notesForTracks([_track.trackId]);
    expect(saved.single.positionMs, 83000);
    expect(saved.single.albumId, _track.albumId);
    // The field is clear for the next line.
    expect(notes.text.storedText, isEmpty);
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_dropWritingRow_when_savedOrTappedAway', (tester) async {
    setUpNotes();
    await pumpPage(tester);
    await tester.tap(find.byType(NoteWriteBar));
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'Bridge');
    await tester.pumpAndSettle();
    expect(find.text('Writing…'), findsOneWidget);

    // Saved: the field keeps focus (the next line) but no empty ghost row stays.
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(notes.focused, isTrue);
    expect(find.text('Writing…'), findsNothing);

    // Typing again brings the row back where the words will land.
    await tester.enterText(find.byType(TextField), 'Outro');
    await tester.pumpAndSettle();
    expect(find.text('Writing…'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '');
    await tester.pumpAndSettle();

    // A click away from the bar over an empty draft puts the field down.
    await tester.tapAt(const Offset(400, 40));
    await tester.pumpAndSettle();
    expect(notes.focused, isFalse);
    expect(find.text('Writing…'), findsNothing);
    notes.dispose();
    store.dispose();
  });

  Future<void> putNote(String id, String content, {int positionMs = 48000}) => store.putSongNote(
    SongNote(id: id, trackId: _track.trackId, content: content, createdAt: _now - 1000, updatedAt: _now - 1000, positionMs: positionMs),
  );

  testWidgets('should_bringNoteBack_when_anEditIsEmptiedSavedThenUndone', (tester) async {
    setUpNotes();
    await putNote('n1', 'Everyone shouts here');
    await pumpPage(tester);
    expect(find.text('Everyone shouts here'), findsOneWidget);

    // A long press opens the note in the write bar; emptied and saved, it is deleted.
    await tester.longPress(find.text('Everyone shouts here'));
    await tester.pumpAndSettle();
    expect(notes.editingId, 'n1');
    expect(notes.text.storedText, 'Everyone shouts here');
    expect(notes.focused, isTrue);
    await tester.enterText(find.byType(TextField), '');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(find.text('Everyone shouts here'), findsNothing);
    expect(find.text('Deleted'), findsOneWidget);

    await tester.tap(find.text('Undo'));
    await tester.pumpAndSettle();
    expect(find.text('Everyone shouts here'), findsOneWidget);
    expect(find.text('Deleted'), findsNothing);

    // The undo window passing changes nothing: the note was never deleted.
    await tester.pump(const Duration(seconds: 6));
    await tester.pumpAndSettle();
    expect(await store.notesForTracks([_track.trackId]), hasLength(1));
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_rewriteTheSameNote_when_editedFromALongPress', (tester) async {
    setUpNotes();
    await putNote('n1', 'Clap', positionMs: 1000);
    await pumpPage(tester);

    await tester.longPress(find.text('Clap'));
    await tester.pumpAndSettle();
    // The stamp shows the note's own moment; the line recedes while it is in the bar.
    expect(find.text('0:01'), findsWidgets);
    final line = tester.widget<Opacity>(
      find.ancestor(of: find.text('Clap').first, matching: find.byType(Opacity)).first,
    );
    expect(line.opacity, lessThan(0.5));
    // Untouched, Save does nothing.
    expect(notes.draft.canSave, isFalse);

    await tester.enterText(find.byType(TextField), 'Clap along');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final saved = (await store.notesForTracks([_track.trackId])).single;
    expect(saved.id, 'n1');
    expect(saved.content, 'Clap along');
    expect(saved.createdAt, _now - 1000);
    expect(saved.positionMs, 1000);
    expect(notes.editingId, isNull);
    expect(notes.text.storedText, isEmpty);
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_closeTheEditUnchanged_when_putAway', (tester) async {
    setUpNotes();
    await putNote('n1', 'Clap', positionMs: 1000);
    await pumpPage(tester);
    await tester.longPress(find.text('Clap'));
    await tester.pumpAndSettle();
    expect(notes.editingId, 'n1');

    notes.focus.unfocus();
    await tester.pumpAndSettle();
    expect(notes.editingId, isNull);
    expect(notes.text.storedText, isEmpty);
    expect((await store.notesForTracks([_track.trackId])).single.content, 'Clap');
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_keepUnsavedWords_when_anotherNoteIsLongPressed', (tester) async {
    setUpNotes();
    await putNote('n1', 'Clap', positionMs: 1000);
    await pumpPage(tester);
    await tester.tap(find.byType(NoteWriteBar));
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'New words');
    await tester.pumpAndSettle();

    await tester.longPress(find.text('Clap'));
    await tester.pumpAndSettle();
    expect(notes.editingId, isNull);
    expect(notes.text.storedText, 'New words');
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_deleteFromStore_when_undoWindowCloses', (tester) async {
    setUpNotes();
    await putNote('n1', 'Clap', positionMs: 1000);
    await pumpPage(tester);

    await tester.longPress(find.text('Clap'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Undo'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: noteUndoWindowMs + 100));
    await tester.pumpAndSettle();
    expect(find.text('Undo'), findsNothing);
    expect(await store.notesForTracks([_track.trackId]), isEmpty);
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_tickATodo_when_itsBoxIsTapped', (tester) async {
    setUpNotes();
    await putNote('n1', '- [ ] hum the intro', positionMs: 1000);
    await pumpPage(tester);
    final box = find.descendant(of: find.byType(NoteMarkupText), matching: find.byType(RichText)).first;
    await tester.tapAt(tester.getTopLeft(box) + const Offset(4, 8));
    await tester.pumpAndSettle();
    expect((await store.notesForTracks([_track.trackId])).single.content, '- [x] hum the intro');
    notes.dispose();
    store.dispose();
  });

  testWidgets('should_lightNoteThePlayheadIsIn_when_compact', (tester) async {
    setUpNotes();
    final rows = [
      SongNote(id: 'a', trackId: _track.trackId, content: 'Intro', createdAt: 0, updatedAt: 0, positionMs: 12000),
      SongNote(id: 'b', trackId: _track.trackId, content: 'Shout', createdAt: 0, updatedAt: 0, positionMs: 48000),
      SongNote(id: 'c', trackId: _track.trackId, content: 'Chorus', createdAt: 0, updatedAt: 0, positionMs: 90000),
    ];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            height: 200,
            child: NoteCompactPane(notes: rows, clock: _clock(50000)),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final colors = Theme.of(tester.element(find.text('Shout'))).colorScheme;
    expect(tester.widget<Text>(find.text('Shout')).style!.color, colors.primary);
    expect(tester.widget<Text>(find.text('Chorus')).style!.color, isNot(colors.primary));
    notes.dispose();
    store.dispose();
  });
}
