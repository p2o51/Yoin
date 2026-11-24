import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/notes/mention_ranking.dart';
import 'package:yoin/data/notes/note_editor_text.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/notes/editor/mention_candidates_source.dart';
import 'package:yoin/notes/editor/mention_popup.dart';
import 'package:yoin/notes/editor/note_editor_controller.dart';
import 'package:yoin/notes/editor/note_editor_display.dart';
import 'package:yoin/notes/editor/note_format_toolbar.dart';
import 'package:yoin/notes/editor/note_markup_field.dart';

NoteMention _track(String title, {String? artist}) =>
    NoteMention(kind: MentionKind.track, provider: 'subsonic', id: 't-$title', title: title, artist: artist);

/// Recent plays when nothing is typed; a "search" for anything else.
class _FakeCandidates implements MentionCandidateSource {
  _FakeCandidates({this.recent = const [], this.search = const []});

  final List<String> recent;
  final List<String> search;
  final queries = <String>[];

  @override
  Future<MentionCandidates> candidates(String query, int limit) async {
    queries.add(query);
    if (query.trim().isEmpty) {
      return MentionCandidates(
        tracks: [for (final t in recent) MentionCandidate(mention: _track(t), source: MentionSource.recent)],
      );
    }
    return MentionCandidates(tracks: [for (final t in search) MentionCandidate(mention: _track(t))]);
  }
}

Future<(NoteEditorController, FocusNode)> _pump(WidgetTester tester, MentionCandidateSource candidates, {String stored = ''}) async {
  final controller = NoteEditorController(stored: stored);
  final focus = FocusNode();
  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              const Spacer(),
              NoteMarkupField(
                controller: controller,
                focusNode: focus,
                candidates: candidates,
                romanizer: noMentionRomanizer,
                maxLines: 4,
              ),
              NoteFormatToolbar(controller: controller),
            ],
          ),
        ),
      ),
    ),
  );
  return (controller, focus);
}

void main() {
  testWidgets('should_putNewYorkFirst_onAtN_andPickItOnEnter', (tester) async {
    final candidates = _FakeCandidates(recent: ['Animal', 'Sunny', 'New York', 'Nights']);
    final (controller, _) = await _pump(tester, candidates);
    await tester.showKeyboard(find.byType(TextField));
    await tester.enterText(find.byType(TextField), '@');
    await tester.pumpAndSettle();
    expect(find.byType(MentionPopup), findsOneWidget);
    expect(find.text('Animal'), findsOneWidget);

    await tester.enterText(find.byType(TextField), '@N');
    await tester.pump();
    // filtered at once, before the search answers
    final rows = tester.widgetList<MentionRow>(find.byType(MentionRow)).map((r) => r.row.mention.title).toList();
    expect(rows.first, 'New York');
    await tester.pumpAndSettle();

    // a soft keyboard's Enter: the newline becomes the top row
    await tester.enterText(find.byType(TextField), '@N\n');
    await tester.pumpAndSettle();
    expect(controller.storedText, '${NoteMarkup.serializeMention(_track('New York'))} ');
    expect(find.byType(MentionPopup), findsNothing);
    expect(find.byType(NoteEditorChip), findsOneWidget);
  });

  testWidgets('should_moveTheHighlightWithArrows_andPickWithAHardwareEnter', (tester) async {
    final candidates = _FakeCandidates(recent: ['Animal', 'Sunny']);
    final (controller, _) = await _pump(tester, candidates);
    await tester.showKeyboard(find.byType(TextField));
    await tester.enterText(find.byType(TextField), 'hi @');
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(controller.storedText, 'hi ${NoteMarkup.serializeMention(_track('Sunny'))} ');
  });

  testWidgets('should_closeThePopup_onEscape_untilAnotherAt', (tester) async {
    final (controller, _) = await _pump(tester, _FakeCandidates(recent: ['Animal']));
    await tester.showKeyboard(find.byType(TextField));
    await tester.enterText(find.byType(TextField), '@a');
    await tester.pumpAndSettle();
    expect(find.byType(MentionPopup), findsOneWidget);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.byType(MentionPopup), findsNothing);
    expect(controller.trigger, isNull);
    await tester.enterText(find.byType(TextField), '@a @');
    await tester.pumpAndSettle();
    expect(find.byType(MentionPopup), findsOneWidget);
  });

  testWidgets('should_endMentionMode_when_theFieldLosesFocus', (tester) async {
    final (_, focus) = await _pump(tester, _FakeCandidates(recent: ['Animal']));
    await tester.showKeyboard(find.byType(TextField));
    await tester.enterText(find.byType(TextField), '@');
    await tester.pumpAndSettle();
    expect(find.byType(MentionPopup), findsOneWidget);
    focus.unfocus();
    await tester.pumpAndSettle();
    expect(find.byType(MentionPopup), findsNothing);
  });

  testWidgets('should_openOnAFullWidthAt_andPickByTap', (tester) async {
    final (controller, _) = await _pump(tester, _FakeCandidates(search: ['灯塔']));
    await tester.showKeyboard(find.byType(TextField));
    await tester.enterText(find.byType(TextField), '这首像＠灯');
    await tester.pumpAndSettle();
    await tester.tap(find.byType(MentionRow).first);
    await tester.pumpAndSettle();
    expect(controller.storedText, '这首像${NoteMarkup.serializeMention(_track('灯塔'))} ');
  });

  testWidgets('should_drawAStoredMentionAsOneChip_andTakeItWholeOnBackspace', (tester) async {
    final link = NoteMarkup.serializeMention(_track('Andromeda'));
    final (controller, _) = await _pump(tester, _FakeCandidates(), stored: 'a $link');
    expect(find.byType(NoteEditorChip), findsOneWidget);
    expect(find.text('@Andromeda'), findsOneWidget);
    expect(controller.text, 'a $objectReplacement');
    await tester.showKeyboard(find.byType(TextField));
    // Backspace at the end: the chip's one character goes, its mention with it
    tester.testTextInput.updateEditingValue(
      const TextEditingValue(text: 'a ', selection: TextSelection.collapsed(offset: 2)),
    );
    await tester.pump();
    expect(controller.storedText, 'a ');
    expect(controller.atoms, isEmpty);
  });

  testWidgets('should_continueAListOnEnter_andFormatFromTheRow', (tester) async {
    final (controller, _) = await _pump(tester, _FakeCandidates());
    await tester.showKeyboard(find.byType(TextField));
    await tester.enterText(find.byType(TextField), '- one');
    await tester.enterText(find.byType(TextField), '- one\n');
    await tester.pump();
    expect(controller.storedText, '- one\n- ');
    await tester.enterText(find.byType(TextField), 'hook');
    controller.selection = const TextSelection(baseOffset: 0, extentOffset: 4);
    await tester.tap(find.bySemanticsLabel('Bold'));
    await tester.pump();
    expect(controller.storedText, '**hook**');
    expect(controller.activeTools, contains(NoteTool.bold));
  });
}
