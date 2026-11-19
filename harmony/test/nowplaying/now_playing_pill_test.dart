import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/marquee.dart';
import 'package:yoin/nowplaying/now_playing_pill.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_strings.dart';
import 'package:yoin/theme/theme.dart';

import '../support/nowplaying_fakes.dart';

void main() {
  testWidgets('should_showLocalizedError_when_engineFailsWithoutTrack', (tester) async {
    const raw = 'MEDIA_ERR_NETWORK 5400103 raw native text';
    final controller = NowPlayingFakeController(
      const NowPlayingState(phase: PlaybackPhase.error, errorMessage: raw, errorKind: PlaybackErrorKind.playFailed),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: nowPlayingOverrides(controller: controller),
        child: const MaterialApp(
          home: Scaffold(
            body: Center(child: SizedBox(width: 280, height: 56, child: NowPlayingPill())),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(find.textContaining(PlaybackStrings.en.playFailed), findsWidgets);
    expect(find.textContaining(raw), findsNothing);
  });

  testWidgets('should_animateTitleInkToOnPrimaryContainer_when_trackStartsInDarkMode', (tester) async {
    final theme = yoinTheme(Brightness.dark);
    final controller = NowPlayingFakeController(const NowPlayingState());
    await tester.pumpWidget(
      ProviderScope(
        overrides: nowPlayingOverrides(controller: controller),
        child: MaterialApp(
          theme: theme,
          home: const Scaffold(
            body: Center(child: SizedBox(width: 280, height: 56, child: NowPlayingPill())),
          ),
        ),
      ),
    );
    await tester.pump();
    final tracks = nowPlayingTracks(1);
    controller.emit(playingState(tracks));
    for (var i = 0; i < 60; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    final titleMarquee = tester.widget<Marquee>(find.byKey(ValueKey('pill-title-${tracks.first.id}')));
    expect(titleMarquee.style.color!.toARGB32(), theme.colorScheme.onPrimaryContainer.toARGB32());
  });
}
