import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/lyrics_view.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/playback/playhead.dart';

void main() {
  group('cjkLocaleForLines', () {
    test('should_returnJapaneseLocale_when_kanaPresent', () {
      expect(cjkLocaleForLines(['夢ならば', 'どれほどよかったでしょう']), const Locale('ja'));
      expect(cjkLocaleForLines(['レモン']), const Locale('ja'));
    });

    test('should_returnKoreanLocale_when_hangulPresent', () {
      expect(cjkLocaleForLines(['사랑해', '오늘도']), const Locale('ko'));
    });

    test('should_returnNull_when_onlyHanOrLatin', () {
      expect(cjkLocaleForLines(['孤勇者', 'Hello world']), isNull);
    });
  });

  group('LyricsView', () {
    testWidgets('should_reSeatActiveLine_when_translationToggledOn', (tester) async {
      final rows = [
        for (var i = 0; i < 30; i++)
          LyricRow(
            'Original lyric line number $i with enough words to wrap nicely',
            startMs: i * 4000,
            translation: '这是第 $i 行歌词的中文翻译文本，开启翻译后高度会明显增加',
          ),
      ];
      final sheet = LyricSheet(rows);
      final autoFollow = ValueNotifier<bool>(true);
      addTearDown(autoFollow.dispose);
      final clock = PlayheadClock(now: () => 0);
      addTearDown(clock.dispose);
      // Place active line well down the list (line 20 at 80_500 ms).
      clock.update(
        const Playhead(
          entryId: 'e0',
          positionMs: 80500,
          sampledAtMs: 0,
          isPlaying: false,
          durationMs: 120000,
        ),
      );

      Widget buildView({required bool showTranslation}) => MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 390,
            height: 520,
            child: LyricsView(
              mode: LyricsListMode.full,
              lyrics: LyricsReady(sheet),
              songKey: 'track:0',
              clock: clock,
              autoFollow: autoFollow,
              showTranslation: showTranslation,
              onSeek: (_) {},
            ),
          ),
        ),
      );

      await tester.pumpWidget(buildView(showTranslation: false));
      await tester.pumpAndSettle();
      final beforeCenter = tester.getCenter(find.textContaining('Original lyric line number 20'));
      expect(beforeCenter.dy, inInclusiveRange(0, 520));

      // Toggling translation on expands all 20 lines above; LyricsView must re-seat line 20 inside the viewport.
      await tester.pumpWidget(buildView(showTranslation: true));
      await tester.pumpAndSettle();
      expect(autoFollow.value, isTrue);
      final activeFinder = find.textContaining('Original lyric line number 20');
      expect(activeFinder, findsOneWidget);
      final afterCenter = tester.getCenter(activeFinder);
      expect(afterCenter.dy, inInclusiveRange(40, 480));
    });
  });
}
