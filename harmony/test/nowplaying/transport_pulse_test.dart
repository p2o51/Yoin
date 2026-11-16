import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/action_glow.dart';
import 'package:yoin/nowplaying/transport_pulse.dart';
import 'package:yoin/nowplaying/transport_signal.dart';

// TransportPulseTest.kt (D4 §1: option B + the P0 corrections), ported.

const int _now = 100000;
const _playing = TransportPulseKey(songId: 'a', isPlaying: true, queueIndex: 3);
const _paused = TransportPulseKey(songId: 'a', isPlaying: false, queueIndex: 3);
const _nextSong = TransportPulseKey(songId: 'b', isPlaying: true, queueIndex: 4);
const _nextBuffering = TransportPulseKey(songId: 'b', isPlaying: false, queueIndex: 4);
const _previousSong = TransportPulseKey(songId: 'z', isPlaying: true, queueIndex: 2);

const _playButton = Offset(120, 900);
const _nextButton = Offset(260, 900);
const _prevButton = Offset(40, 1000);

TransportTap _tap(TransportTapKind kind, Offset center, {int agoMs = 400}) =>
    TransportTap(centerRoot: center, uptimeMs: _now - agoMs, kind: kind);

TransportPulseDecision _resolve(
  TransportPulseKey? previous,
  TransportPulseKey? current, {
  TransportTap? tap,
  bool lyricsPrimary = false,
  LyricsHandover? handover,
  PlaySettle? settle,
  int now = _now,
}) => resolveTransportPulse(
  previous: previous,
  current: current,
  tap: tap,
  nowUptimeMs: now,
  lyricsPrimary: lyricsPrimary,
  handover: handover,
  settle: settle,
);

TransportTap _prevTap({
  Object? songId = 'a',
  int positionMs = 42000,
  TransportTapKind kind = TransportTapKind.skipPrevious,
}) => TransportTap(centerRoot: _prevButton, uptimeMs: _now - 400, kind: kind, songId: songId, positionMs: positionMs);

/// A signal on song "a" holding a PREVIOUS tapped 42 s in.
NowPlayingTransportSignal _signalAfterPreviousTap({int Function()? clock}) =>
    NowPlayingTransportSignal(clock: clock ?? () => _now)
      ..committedSongId = 'a'
      ..recordTap(_prevButton, TransportTapKind.skipPrevious, positionMs: 42000);

/// A signal on song "a" that just saw the user seek.
NowPlayingTransportSignal _signalAfterSeek({bool playing = true}) => NowPlayingTransportSignal(clock: () => _now)
  ..committedSongId = 'a'
  ..committedPlaying = playing
  ..recordSeek();

void main() {
  test('should_notPulse_when_playbackStartsStopsOrNothingChanged', () {
    expect(_resolve(null, _playing).pulse, const PulseNone());
    expect(_resolve(_playing, null).pulse, const PulseNone());
    expect(_resolve(_playing, _playing).pulse, const PulseNone());
    // A queue edit that moves the current song's index is not a commit.
    expect(
      _resolve(_playing, const TransportPulseKey(songId: 'a', isPlaying: true, queueIndex: 7)).pulse,
      const PulseNone(),
    );
  });

  test('should_breatheFromThePlayButton_when_aFreshPlayTapPauses', () {
    final decision = _resolve(_playing, _paused, tap: _tap(TransportTapKind.playPause, _playButton));
    expect(decision.pulse, const PulseBurst(isPlay: false, focalRoot: _playButton));
    expect(decision.tapUsed, isTrue);
  });

  test('should_breatheFromTheNeutralPoint_when_playPauseCameFromOutside', () {
    expect(_resolve(_paused, _playing).pulse, const PulseBurst(isPlay: true));
    final stale = _resolve(_paused, _playing, tap: _tap(TransportTapKind.playPause, _playButton, agoMs: 3500));
    expect(stale.pulse, const PulseBurst(isPlay: true));
    expect(stale.tapUsed, isFalse);
  });

  test('should_ignoreThePlayStateFlicker_when_aSkipIsInFlight', () {
    final decision = _resolve(_playing, _paused, tap: _tap(TransportTapKind.skipNext, _nextButton));
    expect(decision.pulse, const PulseNone());
    expect(decision.tapUsed, isFalse);
  });

  test('should_flowTheNewColoursInFromTheTappedSide_when_theCoverLeadsAFreshSkip', () {
    final decision = _resolve(_playing, _nextSong, tap: _tap(TransportTapKind.skipNext, _nextButton));
    expect(decision.pulse, const PulseSideFlow(fromEnd: true));
    expect(decision.tapUsed, isTrue);
  });

  test('should_neverReuseAnOldButton_when_theSongChangesUntapped', () {
    final stale = _tap(TransportTapKind.skipNext, _nextButton, agoMs: 120000);
    expect(_resolve(_playing, _nextSong, tap: stale).pulse, const PulseNone());
    expect(_resolve(_playing, _nextSong).pulse, const PulseNone());
    expect(_resolve(_playing, _nextSong, tap: _tap(TransportTapKind.playPause, _playButton)).pulse, const PulseNone());
  });

  test('should_treatItAsASkip_when_songAndPlayStateChangeTogether', () {
    final decision = _resolve(_playing, _nextBuffering, tap: _tap(TransportTapKind.skipNext, _nextButton));
    expect(decision.pulse, const PulseSideFlow(fromEnd: true));
  });

  test('should_runTheLightWithTheLyricFlow_when_theLyricsLead', () {
    expect(
      _resolve(_playing, _nextSong, tap: _tap(TransportTapKind.skipNext, _nextButton), lyricsPrimary: true).pulse,
      const PulseFlowLight(forward: true),
    );
    expect(_resolve(_playing, _nextSong, lyricsPrimary: true).pulse, const PulseFlowLight(forward: true));
    expect(_resolve(_playing, _previousSong, lyricsPrimary: true).pulse, const PulseFlowLight(forward: false));
  });

  test('should_spendTheSkipTap_when_theLightAnswersIt', () {
    final decision = _resolve(
      _playing,
      _nextSong,
      tap: _tap(TransportTapKind.skipNext, _nextButton),
      lyricsPrimary: true,
    );
    expect(decision.tapUsed, isTrue);
  });

  test('should_stayQuiet_when_theOutroAlreadyHandedTheSongOverInPlace', () {
    const handover = LyricsHandover(fromSongId: 'a', toSongId: 'b');
    expect(_resolve(_playing, _nextSong, lyricsPrimary: true, handover: handover).pulse, const PulseNone());
    expect(
      _resolve(
        _playing,
        _nextSong,
        lyricsPrimary: true,
        handover: const LyricsHandover(fromSongId: 'a', toSongId: 'c'),
      ).pulse,
      const PulseFlowLight(forward: true),
    );
  });

  test('should_trustATapOnlyForThreeSeconds_when_thePlayerIsSlow', () {
    final justInTime = _resolve(_playing, _nextSong, tap: _tap(TransportTapKind.skipNext, _nextButton, agoMs: 3000));
    final tooLate = _resolve(_playing, _nextSong, tap: _tap(TransportTapKind.skipNext, _nextButton, agoMs: 3001));
    expect(justInTime.pulse, const PulseSideFlow(fromEnd: true));
    expect(tooLate.pulse, const PulseNone());
  });

  test('should_followTheLyricStreamDirection_when_theQueuePositionMoves', () {
    expect(lyricFlowForward(3, 4), isTrue);
    expect(lyricFlowForward(3, 3), isTrue);
    expect(lyricFlowForward(3, 2), isFalse);
  });

  test('should_readAsARestart_when_thePlayheadJumpsBackOnTheTappedSong', () {
    expect(_prevTap().restartedInPlace('a', 200), isTrue);
    expect(_prevTap().restartedInPlace('a', 41000), isTrue);
  });

  test('should_notReadAsARestart_when_theSongChangesOrThePlayheadOnlyDrifts', () {
    expect(_prevTap().restartedInPlace('z', 0), isFalse);
    expect(_prevTap().restartedInPlace('a', 42250), isFalse);
    expect(_prevTap().restartedInPlace('a', 41500), isFalse);
    expect(_prevTap(songId: null).restartedInPlace(null, 0), isFalse);
    expect(_prevTap(positionMs: unknownTapPositionMs).restartedInPlace('a', 0), isFalse);
    expect(_prevTap(kind: TransportTapKind.skipNext).restartedInPlace('a', 0), isFalse);
    expect(_prevTap(kind: TransportTapKind.playPause).restartedInPlace('a', 0), isFalse);
  });

  test('should_spendThePreviousTapAtOnce_when_thePlayerOnlyRestartsTheSong', () {
    final signal = _signalAfterPreviousTap();
    expect(signal.lastTap, isNotNull);
    signal.observePlayhead(42250);
    expect(signal.lastTap, isNotNull, reason: 'playback moving on keeps the tap');
    signal.observePlayhead(150);
    expect(signal.lastTap, isNull);
  });

  test('should_breatheFromTheNeutralPoint_when_aHeadsetPauseFollowsARestartedPrevious', () {
    final signal = _signalAfterPreviousTap()..observePlayhead(150);
    expect(_resolve(_playing, _paused, tap: signal.lastTap).pulse, const PulseBurst(isPlay: false));
  });

  test('should_notRingFromPrevious_when_anUntappedChangeFollowsARestartedPrevious', () {
    final signal = _signalAfterPreviousTap()..observePlayhead(150);
    expect(_resolve(_playing, _nextSong, tap: signal.lastTap).pulse, const PulseNone());
  });

  test('should_keepThePreviousTapForItsFlow_when_thePreviousSongArrives', () {
    final signal = _signalAfterPreviousTap();
    final tapped = signal.lastTap!;
    signal
      ..committedSongId = 'z'
      ..observePlayhead(0);
    expect(signal.lastTap, same(tapped));
    final decision = _resolve(_playing, _previousSong, tap: signal.lastTap, now: tapped.uptimeMs + 400);
    expect(decision.pulse, const PulseSideFlow(fromEnd: false));
    expect(decision.tapUsed, isTrue);
  });

  test('should_notBreatheAfterTheLight_when_theSkippedToSongFinishesBuffering', () {
    final skip = _resolve(
      _playing,
      _nextBuffering,
      tap: _tap(TransportTapKind.skipNext, _nextButton),
      lyricsPrimary: true,
    );
    expect(skip.pulse, const PulseFlowLight(forward: true));
    expect(skip.tapUsed, isTrue);
    expect(skip.settle, isNotNull);
    final resume = _resolve(_nextBuffering, _nextSong, lyricsPrimary: true, settle: skip.settle, now: _now + 300);
    expect(resume.pulse, const PulseNone());
    expect(resume.settle, isNull, reason: 'absorbed once');
  });

  test('should_settleTheResume_when_anUntappedChangeLandsBuffering', () {
    final skip = _resolve(_playing, _nextBuffering);
    expect(skip.pulse, const PulseNone());
    expect(_resolve(_nextBuffering, _nextSong, settle: skip.settle, now: _now + 500).pulse, const PulseNone());
  });

  test('should_settleADipThatCommitsLate_when_theSkipLandedPlaying', () {
    final skip = _resolve(_playing, _nextSong, lyricsPrimary: true);
    final dip = _resolve(_nextSong, _nextBuffering, lyricsPrimary: true, settle: skip.settle, now: _now + 200);
    expect(dip.pulse, const PulseNone());
    expect(dip.settle, isNotNull, reason: 'the resume is still owed');
    final resume = _resolve(_nextBuffering, _nextSong, lyricsPrimary: true, settle: dip.settle, now: _now + 500);
    expect(resume.pulse, const PulseNone());
  });

  test('should_breatheAgain_when_theSettleIsSpentExpiredOrForAnotherSong', () {
    final settle = PlaySettle.after('b', _now);
    final pause = _resolve(_nextSong, _nextBuffering, settle: settle, now: _now + playSettleDipWindowMs + 1);
    expect(pause.pulse, const PulseBurst(isPlay: false));
    expect(pause.settle, isNull);
    expect(_resolve(_nextBuffering, _nextSong, settle: settle, now: _now + 3001).pulse, const PulseBurst(isPlay: true));
    expect(_resolve(_paused, _playing, settle: settle, now: _now + 300).pulse, const PulseBurst(isPlay: true));
    const playTap = TransportTap(centerRoot: _playButton, uptimeMs: _now + 100, kind: TransportTapKind.playPause);
    expect(
      _resolve(_nextBuffering, _nextSong, tap: playTap, settle: settle, now: _now + 300).pulse,
      const PulseBurst(isPlay: true, focalRoot: _playButton),
    );
  });

  test('should_notSettle_when_theSkipStartedPaused', () {
    expect(_resolve(_paused, _nextBuffering).settle, isNull);
  });

  test('should_carryTheSettle_when_nothingCommits', () {
    final settle = PlaySettle.after('b', _now);
    expect(_resolve(_nextBuffering, _nextBuffering, settle: settle).settle, settle);
    const queueEdit = TransportPulseKey(songId: 'b', isPlaying: false, queueIndex: 9);
    expect(_resolve(_nextBuffering, queueEdit, settle: settle).settle, settle);
  });

  test('should_settleTheDipAndResume_when_aRestartedPreviousBuffers', () {
    final signal = _signalAfterPreviousTap()..observePlayhead(150);
    expect(signal.lastTap, isNull);
    final settle = signal.playSettle!;
    expect(settle.songId, 'a');
    final seenAt = settle.dipUntilUptimeMs - playSettleDipWindowMs;
    final dip = _resolve(
      _playing,
      _paused,
      tap: signal.lastTap,
      lyricsPrimary: true,
      settle: settle,
      now: seenAt + 100,
    );
    expect(dip.pulse, const PulseNone());
    final resume = _resolve(_paused, _playing, lyricsPrimary: true, settle: dip.settle, now: seenAt + 400);
    expect(resume.pulse, const PulseNone());
    expect(resume.settle, isNull);
  });

  test('should_settleTheDipAndResume_when_theUserSeeksWhilePlaying', () {
    final settle = _signalAfterSeek().playSettle!;
    expect(settle.songId, 'a');
    final seekAt = settle.dipUntilUptimeMs - playSettleDipWindowMs;
    final dip = _resolve(_playing, _paused, lyricsPrimary: true, settle: settle, now: seekAt + 80);
    expect(dip.pulse, const PulseNone());
    final resume = _resolve(_paused, _playing, lyricsPrimary: true, settle: dip.settle, now: seekAt + 450);
    expect(resume.pulse, const PulseNone());
    expect(resume.settle, isNull);
  });

  test('should_breatheFromTheNeutralPoint_when_aHeadsetPauseFollowsASeekPastTheDipWindow', () {
    final settle = _signalAfterSeek().playSettle!;
    final seekAt = settle.dipUntilUptimeMs - playSettleDipWindowMs;
    final pause = _resolve(
      _playing,
      _paused,
      lyricsPrimary: true,
      settle: settle,
      now: seekAt + playSettleDipWindowMs + 1,
    );
    expect(pause.pulse, const PulseBurst(isPlay: false));
    expect(pause.settle, isNull);
  });

  test('should_owePlayNothing_when_theUserSeeksWhilePausedOrBeforeASongIsShown', () {
    expect(seekPlaySettle('a', playing: false, nowUptimeMs: _now), isNull);
    expect(seekPlaySettle(null, playing: true, nowUptimeMs: _now), isNull);
    expect(_signalAfterSeek(playing: false).playSettle, isNull);
  });

  test('should_keepASkipsSettle_when_aSeekWhilePausedHasNothingToAdd', () {
    final owed = PlaySettle.after('a', _now);
    final signal = NowPlayingTransportSignal(clock: () => _now)
      ..committedSongId = 'a'
      ..committedPlaying = false
      ..playSettle = owed;
    signal.recordSeek();
    expect(signal.playSettle, owed);
  });

  group('signal', () {
    test('should_lightOnlyAnchorlessKinds_when_theControlHasNoBounds', () {
      final signal = NowPlayingTransportSignal(clock: () => _now);
      signal.glow(ActionGlowKind.favoriteOn, null);
      signal.glow(ActionGlowKind.favoriteOn, Rect.zero);
      expect(signal.pendingGlows, isEmpty);
      expect(signal.glowFires.value, 0);
      signal.glow(ActionGlowKind.roomLift, null);
      signal.glow(ActionGlowKind.lyricsReveal, null);
      expect([for (final g in signal.pendingGlows) g.kind], [ActionGlowKind.roomLift, ActionGlowKind.lyricsReveal]);
      expect(signal.glowFires.value, 2);
    });

    test('should_dropTheOldestLight_when_tooManyWait', () {
      var t = 0;
      final signal = NowPlayingTransportSignal(clock: () => t++);
      for (var i = 0; i < 8; i++) {
        signal.glow(ActionGlowKind.seekRelease, const Rect.fromLTWH(0, 0, 10, 10));
      }
      expect(signal.pendingGlows, hasLength(6));
      expect(signal.pendingGlows.first.bornUptimeMs, 2);
    });

    test('should_lightTheTappedLine_when_aSeekFollowsAFreshFinger', () {
      var t = 0;
      final signal = NowPlayingTransportSignal(clock: () => t);
      signal.recordLineTap();
      expect(signal.lineTaps.value, 0, reason: 'no finger: a keyboard seek lights nothing');
      signal.recordPointerDown(const Offset(20, 340));
      t = 400;
      signal.recordLineTap();
      expect(signal.lineTaps.value, 1);
      expect(signal.lineTapY, 340);
      t = 2400;
      signal.recordLineTap();
      expect(signal.lineTaps.value, 1, reason: 'the finger went down too long ago');
    });

    test('should_stampTapsWithTheSongOnScreen_when_recorded', () {
      final signal = NowPlayingTransportSignal(clock: () => _now)..committedSongId = 'a';
      signal.recordTap(_playButton, TransportTapKind.playPause);
      expect(signal.lastTap!.songId, 'a');
      expect(signal.lastTap!.uptimeMs, _now);
      signal.spendTap();
      expect(signal.lastTap, isNull);
    });

    testWidgets('should_keepTheLeadingList_when_aFadingListClaimsAfterIt', (tester) async {
      final signal = NowPlayingTransportSignal(clock: () => _now);
      Widget host({required bool lead, required bool fading}) => Directionality(
        textDirection: TextDirection.ltr,
        child: NowPlayingSignalScope(
          signal: signal,
          child: Column(
            children: [
              if (lead)
                const LyricsSurfaceReporter(
                  key: ValueKey('window'),
                  primary: true,
                  landingFromTop: 28,
                  child: SizedBox(width: 100, height: 100),
                ),
              if (fading)
                const LyricsSurfaceReporter(
                  key: ValueKey('focus'),
                  primary: false,
                  landingFromTop: 28,
                  child: SizedBox(width: 100, height: 40),
                ),
            ],
          ),
        ),
      );
      // The focus list, still fading out, builds after the window: the window keeps the lead.
      await tester.pumpWidget(host(lead: true, fading: true));
      expect(signal.lyricsSurface?.primary, isTrue);
      expect(signal.lyricsSurface?.boundsRoot.height, 100);
      expect(signal.currentLineRootY, 28);
      // The leader leaves: the list still on screen takes over at once, no rebuild needed.
      await tester.pumpWidget(host(lead: false, fading: true));
      expect(signal.lyricsSurface?.primary, isFalse);
      expect(signal.lyricsSurface?.boundsRoot.height, 40);
      await tester.pumpWidget(host(lead: false, fading: false));
      expect(signal.lyricsSurface, isNull);
    });

    test('should_keepOnlyItsOwnHandover_when_aPageClearsIt', () {
      final signal = NowPlayingTransportSignal(clock: () => _now);
      signal.publishLyricsHandover('a', 'b', staged: true);
      signal.publishLyricsHandover('b', null, staged: false);
      expect(signal.lyricsHandover?.toSongId, 'b', reason: "the incoming page must not wipe the outgoing page's word");
      signal.publishLyricsHandover('a', null, staged: false);
      expect(signal.lyricsHandover, isNull);
    });
  });
}
