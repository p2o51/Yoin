import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/up_next_timing.dart';
import 'package:yoin/theme/tokens.dart';

void main() {
  const rows = [LyricRow('a', startMs: 1000), LyricRow('b', startMs: 150000), LyricRow('c', startMs: 170000)];
  const next = UpNextLyrics(
    songKey: 'next',
    title: 'Next',
    artist: 'Artist',
    sheet: LyricSheet([LyricRow('one', startMs: 500)]),
  );

  test('should_revealAtLastLineAndHandOverBeforeEnd_when_longTail', () {
    final t = lastLineOutroFor(rows, 200000)!;
    expect(t.revealStartMs, 200000 - NowPlayingFocusTokens.upNextRevealLeadMs);
    expect(t.handoffAtMs, 200000 - NowPlayingFocusTokens.upNextHandoffLeadMs);
  });

  test('should_startWithLastLine_when_lastLineInsideRevealLead', () {
    final t = lastLineOutroFor(rows, 175000)!;
    expect(t.revealStartMs, 170000);
    expect(t.handoffAtMs, 175000 - NowPlayingFocusTokens.upNextHandoffLeadMs);
  });

  test('should_slideHandoverLater_when_songEndsOnLastWord', () {
    final t = lastLineOutroFor(rows, 171500)!;
    expect(t.handoffAtMs, 171500 - NowPlayingFocusTokens.upNextHandoffMinLeadMs);
    expect(t.revealStartMs, lessThanOrEqualTo(t.handoffAtMs));
  });

  test('should_haveNoOutro_when_untimedOrUnknownOrTooLate', () {
    expect(lastLineOutroFor(const [LyricRow('x')], 200000), isNull);
    expect(lastLineOutroFor(rows, null), isNull);
    expect(lastLineOutroFor(rows, 170500), isNull);
  });

  test('should_stageNextSong_when_itsLyricsAreTimed', () {
    expect(upNextTimingFor(rows, next, 200000), isNotNull);
    const untimed = UpNextLyrics(songKey: 'n', title: 'N', artist: '', sheet: LyricSheet([LyricRow('x')]));
    expect(upNextTimingFor(rows, untimed, 200000), isNull);
    expect(upNextTimingFor(rows, null, 200000), isNull);
  });

  test('should_quantizeOutroProgress_when_ticking', () {
    const t = UpNextTiming(revealStartMs: 0, handoffAtMs: 2400);
    expect(t.outroProgress(-5), 0);
    expect(t.outroProgress(1200), 0.5);
    expect(t.outroProgress(1210), 0.5, reason: 'steps of 1/24');
    expect(t.outroProgress(9999), 1);
  });

  test('should_reportHandedOver_when_stoppedPastHandoffLessSlack', () {
    final handoff = upNextTimingFor(rows, next, 200000)!.handoffAtMs;
    bool at(int ms, {bool following = true, String to = 'next'}) => upNextHandedOver(
      rows: rows,
      upNext: next,
      durationMs: 200000,
      followedPlayhead: following,
      toKey: to,
      atPositionMs: ms,
    );
    expect(at(handoff - NowPlayingFocusTokens.upNextHandoffTickSlackMs), isTrue);
    expect(at(handoff - 1000), isFalse);
    expect(at(handoff, following: false), isFalse);
    expect(at(handoff, to: 'other'), isFalse);
  });

  test('should_stretchLastLine_when_outroPeaks', () {
    expect(lastLineStretchScale(0), 1);
    expect(lastLineStretchScale(1), 1 + NowPlayingFocusTokens.lastLineStretchMax);
  });
}
