import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/scrobble.dart';

import '../support/playback_fakes.dart';

ScrobbleSnapshot _snap({String key = 'e1#0', bool playing = true, int position = 0, int duration = 200000, String profile = 'p1'}) =>
    ScrobbleSnapshot(
      profileId: profile,
      provider: MediaId.providerSubsonic,
      songId: 's1',
      playKey: key,
      isPlaying: playing,
      positionMs: position,
      durationMs: duration,
    );

void main() {
  group('ScrobbleRule', () {
    const rule = ScrobbleRule();
    test('should_needHalfCappedAt240s_when_durationKnown', () {
      expect(rule.thresholdMs(200000), 100000);
      expect(rule.thresholdMs(600000), 240000);
    });
    test('should_neverCount_when_trackIs30sOrShorter', () {
      expect(rule.thresholdMs(30000), isNull);
    });
    test('should_wait240s_when_durationUnknown', () {
      expect(rule.thresholdMs(0), 240000);
    });
  });

  group('ScrobbleTracker', () {
    late int mono;
    late ScrobbleTracker tracker;
    setUp(() {
      mono = 0;
      tracker = ScrobbleTracker(monotonicMs: () => mono, wallMs: () => 1700000000000 + mono);
    });

    test('should_announceOnce_when_firstPlaying', () {
      expect(tracker.update(_snap(playing: false)), isEmpty);
      final started = tracker.update(_snap());
      expect(started, [
        const ScrobbleStarted('p1', MediaId.providerSubsonic, 's1'),
        const ScrobbleStateChanged('p1', MediaId.providerSubsonic, 's1', PlaybackReportState.playing, 0),
      ]);
      mono += 1000;
      expect(tracker.update(_snap(position: 1000)), isEmpty);
    });

    test('should_submitOnce_when_listenedHalf', () {
      tracker.update(_snap());
      mono += 99000;
      expect(tracker.update(_snap(position: 99000)), isEmpty);
      expect(tracker.msUntilSubmit(), 1000);
      mono += 1000;
      final events = tracker.update(_snap(position: 100000));
      final submit = events.single as ScrobbleSubmit;
      expect(submit.startedAtMs, 1700000000000);
      mono += 50000;
      expect(tracker.update(_snap(position: 150000)), isEmpty);
    });

    test('should_notCountPausedTime_when_paused', () {
      tracker.update(_snap());
      mono += 50000;
      tracker.update(_snap(playing: false, position: 50000));
      mono += 500000; // Paused a long time.
      expect(tracker.update(_snap(position: 50000)).whereType<ScrobbleSubmit>(), isEmpty);
      expect(tracker.msUntilSubmit(), 50000);
    });

    test('should_earnNothing_when_seekingForward', () {
      tracker.update(_snap());
      mono += 1000;
      expect(tracker.update(_snap(position: 190000)).whereType<ScrobbleSubmit>(), isEmpty);
      expect(tracker.msUntilSubmit(), 99000);
    });

    test('should_countAgain_when_repeatChangesPlayKey', () {
      tracker.update(_snap());
      mono += 100000;
      expect(tracker.update(_snap(position: 100000)).whereType<ScrobbleSubmit>(), hasLength(1));
      final again = tracker.update(_snap(key: 'e1#1'));
      expect(again.whereType<ScrobbleStarted>(), hasLength(1));
    });

    test('should_submitWhenClosed_when_dueAtSessionEnd', () {
      tracker.update(_snap());
      mono += 100000;
      final events = tracker.update(null);
      expect(events.first, isA<ScrobbleSubmit>());
      expect(events.last, isA<ScrobbleStateChanged>());
    });

    test('should_reportPositionOnly_when_seekingWhilePlaying', () {
      tracker.update(_snap());
      mono = 10000;
      // Playing on: no report.
      expect(tracker.update(_snap(position: 10000)), isEmpty);
      mono = 11000;
      expect(tracker.update(_snap(position: 150000)), [
        const ScrobbleStateChanged('p1', MediaId.providerSubsonic, 's1', PlaybackReportState.playing, 150000),
      ]);
      expect(tracker.msUntilSubmit(), 89000);
    });

    test('should_notCallItASeek_when_driftIsWithinTolerance', () {
      tracker.update(_snap());
      mono = 10000;
      expect(tracker.update(_snap(position: 10000 + ScrobbleTracker.seekToleranceMs)), isEmpty);
    });

    test('should_reportPauseAndResume_when_afterTheStart', () {
      tracker.update(_snap());
      mono = 5000;
      expect(tracker.update(_snap(playing: false, position: 5000)), [
        const ScrobbleStateChanged('p1', MediaId.providerSubsonic, 's1', PlaybackReportState.paused, 5000),
      ]);
      mono = 6000;
      expect(tracker.update(_snap(position: 5000)), [
        const ScrobbleStateChanged('p1', MediaId.providerSubsonic, 's1', PlaybackReportState.playing, 5000),
      ]);
    });

    test('should_reportSeek_when_pausedAndMoved', () {
      tracker.update(_snap());
      mono = 5000;
      tracker.update(_snap(playing: false, position: 5000));
      mono = 60000;
      expect(tracker.update(_snap(playing: false, position: 120000)), [
        const ScrobbleStateChanged('p1', MediaId.providerSubsonic, 's1', PlaybackReportState.paused, 120000),
      ]);
    });

    test('should_stopTheOldSongAtItsLastPosition_when_skippedEarly', () {
      tracker.update(_snap());
      mono = 20000;
      tracker.update(_snap(position: 20000));
      final events = tracker.update(
        const ScrobbleSnapshot(
          profileId: 'p1',
          provider: MediaId.providerSubsonic,
          songId: 's2',
          playKey: 'e2#0',
          isPlaying: true,
          positionMs: 0,
          durationMs: 200000,
        ),
      );
      expect(events.whereType<ScrobbleSubmit>(), isEmpty);
      expect(
        events.first,
        const ScrobbleStateChanged('p1', MediaId.providerSubsonic, 's1', PlaybackReportState.stopped, 20000),
      );
      expect(events.whereType<ScrobbleStarted>().single.songId, 's2');
    });

    test('should_reportNothing_when_aSongNeverPlayed', () {
      expect(tracker.update(_snap(playing: false)), isEmpty);
      expect(tracker.update(null), isEmpty);
    });
  });

  group('PendingScrobbles', () {
    const a = PendingScrobble(profileId: 'p1', provider: 'subsonic', songId: 's', timeMs: 1);
    test('should_dedupeAndCap_when_adding', () {
      var q = PendingScrobbles.add(const [], a);
      q = PendingScrobbles.add(q, a);
      expect(q, [a]);
      for (var i = 0; i < PendingScrobbles.maxPending + 5; i++) {
        q = PendingScrobbles.add(q, PendingScrobble(profileId: 'p1', provider: 'subsonic', songId: 's', timeMs: 10 + i));
      }
      expect(q, hasLength(PendingScrobbles.maxPending));
      expect(q.first.timeMs, 15, reason: 'oldest dropped');
    });

    test('should_batchOnlyThatAccount_when_flushing', () {
      const b = PendingScrobble(profileId: 'p2', provider: 'subsonic', songId: 's', timeMs: 2);
      expect(PendingScrobbles.batchFor([a, b], 'p2'), [b]);
    });

    test('should_readEmpty_when_valueUnreadable', () {
      expect(PendingScrobbles.decode('{nope'), isEmpty);
      expect(PendingScrobbles.decode(PendingScrobbles.encode([a])), [a]);
    });

    test('should_rejectOnlyCodes10And70_when_classifyingErrors', () {
      expect(PendingScrobbles.isRejected(const SourceServerException('x', code: 70)), isTrue);
      expect(PendingScrobbles.isRejected(const SourceServerException('x', code: 10)), isTrue);
      expect(PendingScrobbles.isRejected(const SourceServerException('x', code: 0)), isFalse);
      expect(PendingScrobbles.isRejected(const SourceNetworkException('offline')), isFalse);
    });
  });

  group('ScrobbleReporter', () {
    late int mono;
    late PlaybackFakeSource source;
    late InMemoryStore store;
    late ScrobbleReporter reporter;
    setUp(() {
      mono = 0;
      source = PlaybackFakeSource();
      store = InMemoryStore();
      reporter = ScrobbleReporter(
        tracker: ScrobbleTracker(monotonicMs: () => mono, wallMs: () => 1700000000000),
        targetFor: (profile) => profile == 'p1' ? source : null,
        store: store,
      );
    });
    tearDown(() => reporter.dispose());

    test('should_reportNowPlayingAndSubmit_when_playCounts', () async {
      reporter.update(_snap());
      mono += 100000;
      reporter.update(_snap(position: 100000));
      await reporter.liveReported;
      await reporter.pending();
      expect(source.nowPlaying, [MediaId.subsonic('s1')]);
      expect(source.submitted.single.$2.millisecondsSinceEpoch, 1700000000000);
      expect(await reporter.pending(), isEmpty);
    });

    test('should_keepPlayOnDisk_when_offline', () async {
      source.submitFailure = const SourceNetworkException('offline');
      reporter.update(_snap());
      mono += 100000;
      reporter.update(_snap(position: 100000));
      expect(await reporter.pending(), hasLength(1));
      expect(store.values[PendingScrobbles.storeKey], isNotNull);

      source.submitFailure = null;
      await reporter.flush('p1');
      expect(source.submitted, hasLength(1));
      expect(await reporter.pending(), isEmpty);
      expect(store.values.containsKey(PendingScrobbles.storeKey), isFalse);
    });

    test('should_dropPlay_when_serverRejectsIt', () async {
      source.submitFailure = const SourceServerException('not found', code: 70);
      reporter.update(_snap());
      mono += 100000;
      reporter.update(_snap(position: 100000));
      expect(await reporter.pending(), isEmpty);
    });

    test('should_resumeQueueFromStore_when_newReporter', () async {
      store.values[PendingScrobbles.storeKey] = PendingScrobbles.encode(
          const [PendingScrobble(profileId: 'p1', provider: 'subsonic', songId: 'old', timeMs: 5)]);
      await reporter.flush('p1');
      expect(source.submitted.single.$1, MediaId.subsonic('old'));
    });

    test('should_notSend_when_profileNotActive', () async {
      reporter.update(_snap(profile: 'p2'));
      mono += 100000;
      reporter.update(_snap(profile: 'p2', position: 100000));
      expect(source.nowPlaying, isEmpty);
      expect(await reporter.pending(), hasLength(1), reason: 'kept for when that account is active again');
    });
  });

  group('ScrobbleReporter with playbackReport', () {
    late int mono;
    late _StateReportingSource source;
    late ScrobbleReporter reporter;
    setUp(() {
      mono = 0;
      source = _StateReportingSource();
      reporter = ScrobbleReporter(
        tracker: ScrobbleTracker(monotonicMs: () => mono, wallMs: () => 1700000000000),
        targetFor: (profile) => profile == 'p1' ? source : null,
        store: InMemoryStore(),
      );
    });
    tearDown(() => reporter.dispose());

    test('should_sendLiveStatesInsteadOfNowPlaying_when_serverHasPlaybackReport', () async {
      reporter.update(_snap());
      mono = 5000;
      reporter.update(_snap(playing: false, position: 5000));
      await reporter.liveReported;
      expect(source.nowPlaying, isEmpty);
      expect(source.reports, [
        (MediaId.subsonic('s1'), PlaybackReportState.starting, 0),
        (MediaId.subsonic('s1'), PlaybackReportState.playing, 0),
        (MediaId.subsonic('s1'), PlaybackReportState.paused, 5000),
      ]);
    });

    test('should_stillCountPlaysThroughScrobble_when_serverHasPlaybackReport', () async {
      reporter.update(_snap());
      mono = 100000;
      reporter.update(_snap(position: 100000));
      await reporter.liveReported;
      await reporter.pending();
      expect(source.submitted.single.$1, MediaId.subsonic('s1'));
    });

    test('should_reportStopped_when_playbackEnds', () async {
      reporter.update(_snap());
      mono = 8000;
      reporter.update(_snap(position: 8000));
      reporter.update(null);
      await reporter.liveReported;
      expect(source.reports.last, (MediaId.subsonic('s1'), PlaybackReportState.stopped, 8000));
    });

    test('should_fallBackToNowPlaying_when_serverLacksPlaybackReport', () async {
      source.supports = false;
      reporter.update(_snap());
      mono = 5000;
      reporter.update(_snap(playing: false, position: 5000));
      await reporter.liveReported;
      expect(source.nowPlaying, [MediaId.subsonic('s1')]);
      expect(source.reports, isEmpty);
    });

    test('should_dropReportAndKeepGoing_when_serverUnreachable', () async {
      source.reportFailure = const SourceNetworkException('offline');
      reporter.update(_snap());
      await reporter.liveReported;
      source.reportFailure = null;
      mono = 5000;
      reporter.update(_snap(playing: false, position: 5000));
      await reporter.liveReported;
      expect(source.reports, [(MediaId.subsonic('s1'), PlaybackReportState.paused, 5000)]);
    });

    test('should_notReport_when_profileNotActive', () async {
      reporter.update(_snap(profile: 'p2'));
      await reporter.liveReported;
      expect(source.reports, isEmpty);
      expect(source.asked, 0);
    });
  });
}

/// A server with OpenSubsonic `playbackReport` (unless [supports] is false).
class _StateReportingSource extends PlaybackFakeSource implements MusicPlaybackStateReports {
  bool supports = true;
  int asked = 0;
  Object? reportFailure;
  final List<(MediaId, PlaybackReportState, int)> reports = [];

  @override
  Future<bool> supportsPlaybackStateReport() async {
    asked++;
    return supports;
  }

  @override
  Future<void> reportPlaybackState(MediaId trackId, PlaybackReportState state, int positionMs) async {
    final failure = reportFailure;
    if (failure != null) throw failure;
    reports.add((trackId, state, positionMs));
  }
}
