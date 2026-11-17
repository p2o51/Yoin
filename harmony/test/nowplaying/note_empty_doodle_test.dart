import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/note_empty_doodle.dart';
import 'package:yoin/theme/tokens.dart';

// The empty Note page's doodle (NoteEmptyDoodle.kt, NoteFullscreenPane
// NoteEmptyState): one of three drawings per song, drawing itself in, then resting.

Future<void> _pump(
  WidgetTester tester, {
  required double width,
  required double height,
  NoteDoodle doodle = NoteDoodle.paper,
}) => tester.pumpWidget(
  MaterialApp(
    home: Scaffold(
      body: Align(
        alignment: Alignment.topLeft,
        child: SizedBox(
          width: width,
          height: height,
          child: NoteEmptyState(doodle: doodle, label: 'No notes yet'),
        ),
      ),
    ),
  ),
);

void main() {
  setUp(resetNoteDoodlePicks);

  test('should_keepTheSameDoodle_when_theSameSongComesBack', () {
    final song = MediaId.subsonic('a');
    final first = noteDoodleFor(song, random: math.Random(1));
    for (var seed = 0; seed < 20; seed++) {
      expect(noteDoodleFor(song, random: math.Random(seed)), first);
    }
  });

  test('should_pickEveryDoodle_when_manySongsPlay', () {
    final random = math.Random(7);
    final picks = {for (var i = 0; i < 60; i++) noteDoodleFor(MediaId.subsonic('s$i'), random: random)};
    expect(picks, NoteDoodle.values.toSet());
  });

  test('should_blinkThreeTimesThenStayOn_when_drawn', () {
    expect(noteCaretBlink(0), 1);
    expect(noteCaretBlink(600), 0);
    expect(noteCaretBlink(1200), 1);
    expect(noteCaretBlink(1600), 0);
    expect(noteCaretBlink(2600), 0);
    expect(noteCaretBlink(3000), 1);
    expect(noteCaretBlink(99999), 1);
  });

  test('should_readArcsSmoothCurvesAndRelativeLines_when_parsingThePrototypePaths', () {
    final dot = doodlePath('M25 104 a5 5 0 1 0 10 0 a5 5 0 1 0 -10 0 Z').getBounds();
    expect(dot.left, closeTo(25, 0.01));
    expect(dot.right, closeTo(35, 0.01));
    expect(dot.top, closeTo(99, 0.01));
    expect(dot.bottom, closeTo(109, 0.01));
    final wave = doodlePath('M30 104 C44 78 58 128 74 104 S102 78 116 104').getBounds();
    expect(wave.left, closeTo(30, 0.01));
    expect(wave.right, closeTo(116, 0.01));
    final tick = doodlePath('M232 66 l8 -8').getBounds();
    expect(tick, const Rect.fromLTRB(232, 58, 240, 66));
    expect(() => doodlePath('M0 0 Q1 1 2 2'), throwsFormatException);
  });

  testWidgets('should_showTheDoodleOverTheLabel_when_thereIsRoom', (tester) async {
    await _pump(tester, width: 400, height: 600);
    expect(find.byType(NoteEmptyDoodle), findsOneWidget);
    expect(find.text('No notes yet'), findsOneWidget);
    // At most 240 wide, at the drawing's aspect ratio.
    final size = tester.getSize(find.byType(NoteEmptyDoodle));
    expect(size.width, NowPlayingNoteTokens.doodleMaxWidth);
    expect(size.height, closeTo(240 * 170 / 260, 0.01));
    await tester.pumpAndSettle();
  });

  testWidgets('should_shrinkTheDoodle_when_theColumnIsNarrow', (tester) async {
    await _pump(tester, width: 130, height: 600, doodle: NoteDoodle.mark);
    expect(tester.getSize(find.byType(NoteEmptyDoodle)).width, 130);
    await tester.pumpAndSettle();
  });

  testWidgets('should_dropTheDoodle_when_theRegionIsTooShort', (tester) async {
    await _pump(tester, width: 400, height: 120, doodle: NoteDoodle.stroke);
    expect(find.byType(NoteEmptyDoodle), findsNothing);
    expect(find.text('No notes yet'), findsOneWidget);
  });

  testWidgets('should_restAndAskForNoFrames_when_drawn', (tester) async {
    for (final doodle in NoteDoodle.values) {
      await _pump(tester, width: 400, height: 600, doodle: doodle);
      await tester.pump(const Duration(milliseconds: 100));
      expect(tester.binding.hasScheduledFrame, isTrue);
      await tester.pumpAndSettle();
      expect(tester.binding.hasScheduledFrame, isFalse);
    }
  });
}
