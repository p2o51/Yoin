import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/action_glow.dart';
import 'package:yoin/nowplaying/now_playing_background.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_spine.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/nowplaying/transport_pulse.dart';
import 'package:yoin/nowplaying/transport_signal.dart';
import 'package:yoin/theme/playback_theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

// The reactive room (NowPlayingAuroraBackground.kt): still at rest, it runs
// only while something it answers is moving.

const _field = CoverField(
  palette: [Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFDD835)],
  accent: Color(0xFFE53935),
);

const _playing = TransportPulseKey(songId: 'a', isPlaying: true, queueIndex: 0);
const _paused = TransportPulseKey(songId: 'a', isPlaying: false, queueIndex: 0);

class _Host extends StatelessWidget {
  const _Host({required this.signal, this.focus, this.pulseKey, this.field, this.reduced = false});

  final NowPlayingTransportSignal signal;
  final Animation<double>? focus;
  final TransportPulseKey? pulseKey;
  final CoverField? field;
  final bool reduced;

  @override
  Widget build(BuildContext context) => MaterialApp(
    theme: ThemeData(brightness: Brightness.dark),
    home: MediaQuery(
      data: MediaQuery.of(context).copyWith(disableAnimations: reduced),
      child: NowPlayingBackground(
        field: field,
        focus: focus,
        signal: signal,
        pulseKey: pulseKey,
        isPlaying: pulseKey?.isPlaying ?? false,
        songProgress: () => 0.3,
      ),
    ),
  );
}

void main() {
  testWidgets('should_stayStill_when_nothingHappens', (tester) async {
    final signal = NowPlayingTransportSignal();
    await tester.pumpWidget(_Host(signal: signal, field: _field, pulseKey: _playing));
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
    expect(signal.committedSongId, 'a');
    expect(signal.committedPlaying, isTrue);
  });

  testWidgets('should_runOnlyForTheLight_when_anActionFires', (tester) async {
    final signal = NowPlayingTransportSignal();
    await tester.pumpWidget(_Host(signal: signal, field: _field));
    signal.glow(ActionGlowKind.favoriteOn, const Rect.fromLTWH(300, 500, 48, 48));
    await tester.pump();
    expect(signal.pendingGlows, isEmpty, reason: 'the background took it');
    await tester.pump(const Duration(milliseconds: 500));
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pump(const Duration(milliseconds: 1000));
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.binding.hasScheduledFrame, isFalse, reason: 'FavoriteOn lasts 1.4 s');
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_lightNothing_when_motionIsReduced', (tester) async {
    final signal = NowPlayingTransportSignal();
    await tester.pumpWidget(_Host(signal: signal, field: _field, reduced: true));
    signal
      ..glow(ActionGlowKind.favoriteOn, const Rect.fromLTWH(300, 500, 48, 48))
      ..recordTapDots(const Rect.fromLTWH(10, 10, 56, 56), lean: 1);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('should_spendThePlayTap_when_playPauseCommits', (tester) async {
    var now = 1000;
    final signal = NowPlayingTransportSignal(clock: () => now);
    await tester.pumpWidget(_Host(signal: signal, field: _field, pulseKey: _playing));
    signal.recordTap(const Offset(100, 700), TransportTapKind.playPause);
    now += 300;
    await tester.pumpWidget(_Host(signal: signal, field: _field, pulseKey: _paused));
    expect(signal.lastTap, isNull, reason: 'the burst rang from the tapped key');
    expect(tester.binding.hasScheduledFrame, isTrue);
    await tester.pump(const Duration(milliseconds: RoomMetrics.pauseBurstMs + 100));
    await tester.pump(const Duration(milliseconds: 16));
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets('should_flowAnyway_when_theNewCoversColoursNeverCome', (tester) async {
    var now = 1000;
    final signal = NowPlayingTransportSignal(clock: () => now);
    await tester.pumpWidget(_Host(signal: signal, field: _field, pulseKey: _playing));
    signal.recordTap(const Offset(300, 700), TransportTapKind.skipNext);
    now += 200;
    const next = TransportPulseKey(songId: 'b', isPlaying: true, queueIndex: 1);
    await tester.pumpWidget(_Host(signal: signal, field: _field, pulseKey: next));
    expect(signal.lastTap, isNull, reason: 'the skip tap is spent on its flow');
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse, reason: 'it waits for the new colours first');
    await tester.pump(const Duration(milliseconds: RoomMetrics.flowWaitMs));
    expect(tester.binding.hasScheduledFrame, isTrue, reason: 'same album: it flows anyway');
    await tester.pumpAndSettle();
  });

  testWidgets('should_keepTheFieldDrifting_when_focused', (tester) async {
    final signal = NowPlayingTransportSignal();
    final focus = ValueNotifier<double>(0);
    final anim = _ValueAnimation(focus);
    await tester.pumpWidget(_Host(signal: signal, field: _field, focus: anim));
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
    focus.value = 1;
    await tester.pump();
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 100));
      expect(tester.binding.hasScheduledFrame, isTrue);
    }
    // A new line swells the edge band; a new cover crosses the field over.
    signal.lyricLineChanged();
    await tester.pumpWidget(
      _Host(signal: signal, field: _field.withPalette([0xFF000000, 0xFF222222, 0xFF444444, 0xFF666666]), focus: anim),
    );
    await tester.pump(const Duration(milliseconds: 500));
    expect(tester.takeException(), isNull);
    focus.value = 0;
    await tester.pump();
    // The cover's cross-over finishes its 900 ms, then the field stops.
    await tester.pump(const Duration(milliseconds: RoomMetrics.fieldCrossMs));
    await tester.pump(const Duration(milliseconds: 16));
    expect(tester.binding.hasScheduledFrame, isFalse, reason: 'out of focus the field stops');
  });

  group('screen', () {
    Future<NowPlayingOverlayController> pumpPhone(WidgetTester tester, Size size) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final tracks = [
        Track(
          id: MediaId.subsonic('t0'),
          title: 'Song',
          artist: 'Artist',
          album: 'Album',
          durationSec: 200,
          genre: 'Jazz',
          year: 2019,
        ),
      ];
      final playback = NowPlayingFakeController(playingState(tracks));
      final controller = NowPlayingOverlayController(vsync: tester);
      addTearDown(controller.dispose);
      controller.window = YoinWindowInfo(size.width, size.height);
      controller.height = size.height;
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: playback, source: NowPlayingFakeSource()),
            upNextPrefetchDelayProvider.overrideWithValue(Duration.zero),
          ],
          child: MaterialApp(
            home: Material(
              child: NowPlayingOverlayScope(
                controller: controller,
                child: NowPlayingScreen(controller: controller),
              ),
            ),
          ),
        ),
      );
      controller.openPlayer();
      await tester.pumpAndSettle();
      return controller;
    }

    testWidgets('should_stampThePlayTapOnTheSignal_when_playIsTapped', (tester) async {
      await pumpPhone(tester, const Size(412, 892));
      final signal = tester.widget<NowPlayingBackground>(find.byType(NowPlayingBackground)).signal!;
      expect(signal.committedSongId, isNotNull);
      expect(signal.cover.rect, isNotNull, reason: 'the cover reports where it sits');
      await tester.tap(find.bySemanticsLabel('Pause').first);
      await tester.pump();
      expect(signal.tapDots.value?.lean, 0, reason: 'PLAY rings its own dots');
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_readTheSongInTheSpine_when_theCoverIsHeightCapped', (tester) async {
      await pumpPhone(tester, const Size(412, 892));
      expect(find.byType(NowPlayingSpine), findsNothing, reason: 'a full-width cover leaves no band');
      await pumpPhone(tester, const Size(412, 560));
      expect(find.byType(NowPlayingSpine), findsOneWidget);
      expect(find.textContaining('Jazz', findRichText: true), findsOneWidget);
    });
  });
}

/// A plain value as an animation (the overlay's focus spring stand-in).
class _ValueAnimation extends Animation<double> with AnimationLazyListenerMixin, AnimationLocalListenersMixin {
  _ValueAnimation(this._value);

  final ValueNotifier<double> _value;

  @override
  double get value => _value.value;

  @override
  AnimationStatus get status => AnimationStatus.forward;

  @override
  void addStatusListener(AnimationStatusListener listener) {}

  @override
  void removeStatusListener(AnimationStatusListener listener) {}

  @override
  void didStartListening() => _value.addListener(notifyListeners);

  @override
  void didStopListening() => _value.removeListener(notifyListeners);
}
