import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/lyrics_view.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/nowplaying/up_next_timing.dart';
import 'package:yoin/playback/playhead.dart';

// Once nothing scrolls in from below, the expanded list stops at its natural
// end and the sung line walks on down the screen (Android dd9ac2fa3,
// LyricsEndClampTest).

const _lines = 30;
const _lineMs = 4000;
const _durationMs = _lines * _lineMs + 100000;

LyricSheet _sheet(String prefix) =>
    LyricSheet([for (var i = 0; i < _lines; i++) LyricRow('$prefix line $i', startMs: i * _lineMs)]);

Future<void> _pump(WidgetTester tester, {required int positionMs, bool staged = true}) async {
  tester.view.physicalSize = const Size(400, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final clock = PlayheadClock(now: () => 0)
    ..update(Playhead(entryId: 'a', positionMs: positionMs, durationMs: _durationMs));
  addTearDown(clock.dispose);
  await tester.pumpWidget(
    MaterialApp(
      home: Material(
        child: LyricsView(
          mode: LyricsListMode.full,
          lyrics: LyricsReady(_sheet('this')),
          songKey: 'a',
          clock: clock,
          onSeek: (_) {},
          durationMs: _durationMs,
          upNext: staged
              ? UpNextLyrics(songKey: 'b', title: 'Next song', artist: 'Someone', sheet: _sheet('next'))
              : null,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  test('should_restTheLastRowTheTailAboveTheWindowBottom_when_clamped', () {
    const viewport = 1600.0, pad = 96.0, tail = 300.0, lastBottom = 5000.0;
    final offset = lyricsEndClampOffset(
      viewport: viewport,
      contentPadTop: pad,
      endRowBottom: lastBottom,
      endTail: tail,
    );
    // The last row's bottom in the window = padding + its content bottom - the scroll offset.
    expect(pad + lastBottom - offset, viewport - tail);
  });

  testWidgets('should_stopAtTheSongsNaturalEnd_when_theLastLineIsSungWithUpNextStaged', (tester) async {
    await _pump(tester, positionMs: (_lines - 1) * _lineMs + 500);
    final last = tester.getRect(find.text('this line ${_lines - 1}'));
    const naturalEnd = 800 - LyricsPaneMetrics.contentPad - LyricsPaneMetrics.tail;
    // The highlight walked down to where the list ends, not up to the raised seat.
    expect(last.bottom, lessThanOrEqualTo(naturalEnd + 1));
    expect(last.bottom, greaterThan(naturalEnd - 2 * LyricsPaneMetrics.rowPadV - 1));
  });

  testWidgets('should_restWhereTheListEnds_when_nothingIsStaged', (tester) async {
    await _pump(tester, positionMs: (_lines - 1) * _lineMs + 500, staged: false);
    final last = tester.getRect(find.text('this line ${_lines - 1}'));
    const naturalEnd = 800 - LyricsPaneMetrics.contentPad - LyricsPaneMetrics.tail;
    expect(last.bottom, lessThanOrEqualTo(naturalEnd + 1));
    expect(last.bottom, greaterThan(naturalEnd - 2 * LyricsPaneMetrics.rowPadV - 1));
  });

  testWidgets('should_keepTheRaisedSeat_when_farFromTheSongsEnd', (tester) async {
    await _pump(tester, positionMs: 10 * _lineMs + 500);
    final line = tester.getRect(find.text('this line 10'));
    // The row's centre on the seat: the clean band between the 64 fades, at
    // 40 % of the 800 screen rounded to a 2 % step (0.38), measured from the
    // window's top, not from under the list's 48 top padding.
    const fade = LyricsPaneMetrics.fade;
    const seat = fade + (800 - 2 * fade) * 0.38;
    expect(line.center.dy, closeTo(seat, 1));
  });

  testWidgets('should_walkTheHighlightDown_when_theSongsLastLinesAreSung', (tester) async {
    await _pump(tester, positionMs: (_lines - 3) * _lineMs + 500);
    final earlier = tester.getRect(find.text('this line ${_lines - 3}')).center.dy;
    await _pump(tester, positionMs: (_lines - 1) * _lineMs + 500);
    final last = tester.getRect(find.text('this line ${_lines - 1}')).center.dy;
    expect(last, greaterThan(earlier + 2 * LyricsPaneMetrics.rowPadV));
  });
}
