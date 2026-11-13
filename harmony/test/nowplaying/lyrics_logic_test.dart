import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';

void main() {
  group('LyricCredits', () {
    test('should_dropHeadAndTailCredits_when_neteaseStyleLrc', () {
      final texts = ['作词 : 唐恬', '作曲 : 钱雷', '编曲 : 钱雷', '都是勇敢的', '你额头的伤口', '她说：别走', '制作人 : 钱雷', '混音 : 赵靖'];
      expect(LyricCredits.scan(texts), {0, 1, 2, 6, 7});
    });

    test('should_keepSungLineWithColon_when_inTheMiddle', () {
      final texts = ['作词 : 唐恬', '第一句', '她说：别走', '最后一句'];
      expect(LyricCredits.scan(texts), {0});
    });

    test('should_takeNothing_when_noHeadlineRoleAndFewRoles', () {
      expect(LyricCredits.scan(['Chorus: la la', 'line one', 'line two']), isEmpty);
    });

    test('should_takeNothing_when_lessThanTwoLinesWouldRemain', () {
      expect(LyricCredits.scan(['作词 : 唐恬', '作曲 : 钱雷', '唯一一句']), isEmpty);
    });

    test('should_dropQqTitleLine_when_inHeadBlock', () {
      final texts = ['孤勇者 - 陈奕迅', '词：唐恬', '曲：钱雷', '都是勇敢的', '你额头的伤口'];
      expect(LyricCredits.scan(texts), {0, 1, 2});
    });

    test('should_recogniseEnglishRoles_when_threeOrMore', () {
      final texts = ['Bass Guitar: David', 'Drums: Ann', 'Mixed by: Bob', 'first line', 'second line'];
      expect(LyricCredits.scan(texts), {0, 1, 2});
    });
  });

  group('lyricSheetOf', () {
    test('should_sortAndStripCredits_when_synced', () {
      const lyrics = SyncedLyrics([
        LyricLine(5000, 'second'),
        LyricLine(0, '作词 : 唐恬'),
        LyricLine(1000, 'first'),
        LyricLine(0, '作曲 : 钱雷'),
      ]);
      final sheet = lyricSheetOf(lyrics);
      expect(sheet.rows.map((r) => r.text), ['first', 'second']);
      expect(sheet.isSynced, isTrue);
    });

    test('should_splitIntoUntimedRows_when_unsynced', () {
      final sheet = lyricSheetOf(const UnsyncedLyrics('a\n\nb\nc'));
      expect(sheet.rows.map((r) => r.text), ['a', 'b', 'c']);
      expect(sheet.isSynced, isFalse);
    });

    test('should_beEmpty_when_noLyrics', () {
      expect(lyricSheetOf(null).isEmpty, isTrue);
    });
  });

  group('activeLyricIndex', () {
    const rows = [LyricRow('a', startMs: 1000), LyricRow('b', startMs: 2000), LyricRow('c', startMs: 3000)];

    test('should_beMinusOne_when_beforeFirstLine', () => expect(activeLyricIndex(rows, 500), -1));
    test('should_pickLastStarted_when_between', () => expect(activeLyricIndex(rows, 2500), 1));
    test('should_pickLast_when_pastEnd', () => expect(activeLyricIndex(rows, 99999), 2));
    test('should_skipUntimed_when_mixed', () {
      expect(activeLyricIndex(const [LyricRow('x'), LyricRow('y', startMs: 0)], 10), 1);
    });
  });
}
