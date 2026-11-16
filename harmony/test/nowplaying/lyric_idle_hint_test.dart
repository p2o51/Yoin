import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/nowplaying/lyric_idle_hint.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playhead.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

const _synced = LyricsReady(
  LyricSheet([LyricRow('one', startMs: 0), LyricRow('two', startMs: 20000), LyricRow('three', startMs: 40000)]),
);
const _plain = LyricsReady(LyricSheet([LyricRow('one'), LyricRow('two')]));

void main() {
  group('claims', () {
    test('should_countLocalDays_when_convertingToEpochDay', () {
      expect(localEpochDay(DateTime(1970, 1, 1, 23, 59)), 0);
      expect(localEpochDay(DateTime(1970, 1, 2, 0, 1)), 1);
      expect(localEpochDay(DateTime(2026, 11, 17, 8)), localEpochDay(DateTime(2026, 11, 17, 22)));
    });

    test('should_allowOncePerDay_when_lastShownDayDiffers', () {
      final today = DateTime(2026, 11, 17, 9);
      expect(lyricIdleHintAllowed(null, today), isTrue);
      expect(lyricIdleHintAllowed(localEpochDay(today), today), isFalse);
      expect(lyricIdleHintAllowed(localEpochDay(today) - 1, today), isTrue);
    });

    test('should_claimOnlyOnce_when_sameLocalDay', () async {
      final store = InMemoryStore();
      final claims = LyricIdleHintClaims(store);
      expect(await claims.claim(DateTime(2026, 11, 17, 9)), isTrue);
      expect(await claims.claim(DateTime(2026, 11, 17, 23)), isFalse);
      expect(store.values[LyricIdleHintClaims.key], '${localEpochDay(DateTime(2026, 11, 17))}');
      // Another window reads the stored day.
      expect(await LyricIdleHintClaims(store).claim(DateTime(2026, 11, 17, 12)), isFalse);
      expect(await claims.claim(DateTime(2026, 11, 18, 0, 5)), isTrue);
    });

    test('should_honourAnotherWindowsClaim_when_bothWindowsAreOpen', () async {
      final store = InMemoryStore();
      final first = LyricIdleHintClaims(store);
      final second = LyricIdleHintClaims(store);
      expect(await second.claim(DateTime(2026, 11, 16, 20)), isTrue);
      // The next day the first window claims it; the second, already open, must not show it again.
      expect(await first.claim(DateTime(2026, 11, 17, 9)), isTrue);
      expect(await second.claim(DateTime(2026, 11, 17, 10)), isFalse);
    });

    test('should_rememberForTheSession_when_noStore', () async {
      final claims = LyricIdleHintClaims(null);
      expect(await claims.claim(DateTime(2026, 11, 17)), isTrue);
      expect(await claims.claim(DateTime(2026, 11, 17)), isFalse);
    });
  });

  test('should_pickLastStartedLine_when_findingActiveLine', () {
    final sheet = _synced.sheet;
    expect(activeLyricLine(sheet, -1), -1);
    expect(activeLyricLine(sheet, 0), 0);
    expect(activeLyricLine(sheet, 19999), 0);
    expect(activeLyricLine(sheet, 20000), 1);
    expect(activeLyricLine(sheet, 99999), 2);
    expect(activeLyricLine(_plain.sheet, 5000), -1);
  });

  group('row', () {
    late PlayheadClock clock;
    late InMemoryStore store;

    void play(int positionMs, {bool playing = true}) => clock.update(
      Playhead(entryId: 'e', positionMs: positionMs, sampledAtMs: 0, isPlaying: playing, durationMs: 200000),
    );

    Future<void> pump(WidgetTester tester, {bool enabled = true, LyricsState lyrics = _synced, String song = 's'}) =>
        tester.pumpWidget(
          ProviderScope(
            overrides: [keyValueStoreProvider.overrideWithValue(store)],
            child: MaterialApp(
              home: Scaffold(
                body: SizedBox(
                  height: 44,
                  child: LyricIdleHint(
                    enabled: enabled,
                    lyrics: lyrics,
                    songKey: song,
                    clock: clock,
                    child: const Text('lyric line'),
                  ),
                ),
              ),
            ),
          ),
        );

    // The playing clock ticks on, so springs are settled by time, not pumpAndSettle.
    Future<void> settle(WidgetTester tester) async {
      for (var i = 0; i < 60; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
    }

    double lyricOpacity(WidgetTester tester) => tester
        .widget<Opacity>(find.ancestor(of: find.text('lyric line'), matching: find.byType(Opacity)).first)
        .opacity;

    setUp(() {
      // The clock's own "now" stays put: the line only moves when a sample says so.
      clock = PlayheadClock(now: () => 0);
      store = InMemoryStore();
    });
    tearDown(() => clock.dispose());

    testWidgets('should_crossFadeToUnfoldSymbol_when_lineHoldsDuringPlayback', (tester) async {
      play(5000);
      await pump(tester);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs - 100));
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pump(const Duration(milliseconds: 300));
      // The real symbol, cross-faded in over the line.
      expect(find.byType(UnfoldHintSymbol), findsOneWidget);
      expect(find.byIcon(YoinSymbols.unfoldMore), findsNWidgets(2));
      expect(lyricOpacity(tester), lessThan(0.5));
      // Shown only while the symbol moves, then the lyric returns.
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.showMs));
      await settle(tester);
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      expect(lyricOpacity(tester), 1);
    });

    testWidgets('should_showOnlyOncePerDay_when_lineHoldsAgain', (tester) async {
      play(5000);
      await pump(tester);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs + 100));
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.showMs));
      await settle(tester);
      // The next line holds just as long: today's hint is spent.
      play(25000);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs + 500));
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      expect(store.values[LyricIdleHintClaims.key], isNotNull);
    });

    testWidgets('should_restartTheWait_when_lineMovesOn', (tester) async {
      play(15000);
      await pump(tester);
      await tester.pump(const Duration(milliseconds: 6000));
      play(21000); // line 2 starts: the wait starts over
      await tester.pump(const Duration(milliseconds: 6000));
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      expect(store.values, isEmpty);
      await tester.pump(const Duration(milliseconds: 2100));
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.byType(UnfoldHintSymbol), findsOneWidget);
    });

    testWidgets('should_stayQuiet_when_pausedUnsyncedOrDisabled', (tester) async {
      play(5000, playing: false);
      await pump(tester);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs * 2));
      expect(find.byType(UnfoldHintSymbol), findsNothing);

      play(5000);
      await pump(tester, lyrics: _plain);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs * 2));
      expect(find.byType(UnfoldHintSymbol), findsNothing);

      await pump(tester, enabled: false);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs * 2));
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      expect(store.values, isEmpty);
    });

    testWidgets('should_startTheWait_when_enabledLater', (tester) async {
      play(5000);
      await pump(tester, enabled: false);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs * 2));
      expect(store.values, isEmpty);
      // The row turns into the one-line lyric row: the wait starts from now.
      await pump(tester);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs - 500));
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.byType(UnfoldHintSymbol), findsOneWidget);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.showMs));
      await settle(tester);
    });

    testWidgets('should_takeHintBack_when_playbackPauses', (tester) async {
      play(5000);
      await pump(tester);
      await tester.pump(const Duration(milliseconds: LyricIdleHintTokens.delayMs + 100));
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.byType(UnfoldHintSymbol), findsOneWidget);
      play(5000, playing: false);
      await settle(tester);
      expect(find.byType(UnfoldHintSymbol), findsNothing);
      expect(lyricOpacity(tester), 1);
    });
  });

  testWidgets('should_pressThenSpringOut_when_symbolPlays', (tester) async {
    Widget symbol(int play) => MaterialApp(
      home: Center(
        child: UnfoldHintSymbol(play: play, color: Colors.black),
      ),
    );
    double upperShift() => tester
        .widget<Transform>(find.descendant(of: find.byType(UnfoldHintSymbol), matching: find.byType(Transform)).first)
        .transform
        .getTranslation()
        .y;
    await tester.pumpWidget(symbol(0));
    expect(upperShift(), 0);
    await tester.pumpWidget(symbol(1));
    var mostIn = 0.0, mostOut = 0.0;
    for (var i = 0; i < 90; i++) {
      await tester.pump(const Duration(milliseconds: 16));
      final y = upperShift();
      if (y > mostIn) mostIn = y;
      if (y < mostOut) mostOut = y;
    }
    // Pressed toward the centre line (down for the upper chevron), then sprung out past rest.
    expect(mostIn, closeTo(LyricIdleHintTokens.press, 0.3));
    expect(mostOut, lessThan(-LyricIdleHintTokens.bounce * 0.8));
    await tester.pumpAndSettle();
    expect(upperShift(), closeTo(0, 0.01));
  });
}
