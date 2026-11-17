import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/lyrics/huawei_lrc.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/model/model.dart';

/// Android HuaweiLrcTest: synthetic LRC bodies shaped like Huawei's files (no real lyrics).
void main() {
  const translated = {'translate', 'scrolling'};

  List<int> starts(String lrc) => [for (final l in (LrcParser.parse(lrc) as SyncedLyrics).lines) l.startMs];

  test('should_splitAtCaret_when_spacesPresentOrAbsent', () {
    final payload = huaweiLrcSplit(
      '[00:10.00]First line ^第一行\n[00:12.50]Second line^第二行\n[00:15.00]Third line  ^  第三行',
      translated,
    )!;
    expect(payload.lyric, '[00:10.00]First line\n[00:12.50]Second line\n[00:15.00]Third line');
    expect(payload.translatedLyric, '[00:10.00]第一行\n[00:12.50]第二行\n[00:15.00]第三行');
  });

  test('should_splitAtLastCaret_when_originalContainsCaret', () {
    final split = huaweiLrcSplit('[00:10.00]Smile ^_^ for me^为我微笑', translated)!;
    expect(split.lyric, '[00:10.00]Smile ^_^ for me');
    expect(split.translatedLyric, '[00:10.00]为我微笑');
  });

  test('should_keepCaretAsText_when_nothingReadableFollowsIt', () {
    final payload = huaweiLrcSplit('[00:10.00]Smile ^_^\n[00:12.00]Next ^下一句', translated)!;
    expect(payload.lyric, '[00:10.00]Smile ^_^\n[00:12.00]Next');
    expect(payload.translatedLyric, '[00:10.00]//\n[00:12.00]下一句');
  });

  test('should_emitPlaceholder_when_lineHasNoTranslation', () {
    final payload = huaweiLrcSplit(
      '[00:00.00]Song - Artist\n'
      '[00:00.00]Lyrics by : Someone\n'
      '[00:00.00]Opening line ^开场白\n'
      '[00:14.07]Yeah\n'
      '[00:20.00]Dangling caret^',
      translated,
    )!;
    expect(payload.translatedLyric!.split('\n'), [
      '[00:00.00]//',
      '[00:00.00]//',
      '[00:00.00]开场白',
      '[00:14.07]//',
      '[00:20.00]//',
    ]);
    expect(payload.lyric.split('\n'), contains('[00:20.00]Dangling caret'));
    // Both sides parse to the same number of lines per timestamp → positional pairing works.
    expect(starts(payload.translatedLyric!), starts(payload.lyric));
  });

  test('should_keepTagsAndUntimedLinesInOriginalOnly_when_splitting', () {
    final payload = huaweiLrcSplit('[ti:Song]\n[ar:Artist]\n[offset:0]\n[00:01.00]Line ^行', translated)!;
    expect(payload.lyric, '[ti:Song]\n[ar:Artist]\n[offset:0]\n[00:01.00]Line');
    expect(payload.translatedLyric, '[00:01.00]行');
  });

  test('should_notSplit_when_subTypeLacksTranslate', () {
    const raw = '[00:01.00]Up ^ and down^上下';
    final payload = huaweiLrcSplit(raw, {'scrolling'})!;
    expect(payload.lyric, raw);
    expect(payload.translatedLyric, isNull);
  });

  test('should_sniffInlineTranslation_when_subTypeUnknown', () {
    final withTranslation = huaweiLrcSplit('[00:01.00]Hello ^你好', const {})!;
    final withoutTranslation = huaweiLrcSplit('[00:01.00]Up ^ and down^ up', const {})!;
    expect(withTranslation.lyric, '[00:01.00]Hello');
    expect(withTranslation.translatedLyric, '[00:01.00]你好');
    expect(withoutTranslation.lyric, '[00:01.00]Up ^ and down^ up');
    expect(withoutTranslation.translatedLyric, isNull);
  });

  test('should_returnNullTranslation_when_noLineTranslated', () {
    final payload = huaweiLrcSplit('[00:00.00]Song - Artist\n[00:01.00]Ooh', translated)!;
    expect(payload.lyric, '[00:00.00]Song - Artist\n[00:01.00]Ooh');
    expect(payload.translatedLyric, isNull);
  });

  test('should_keepAllTimestamps_when_lineHasMultipleStamps', () {
    final payload = huaweiLrcSplit('[00:10.00][01:10.00]Chorus ^副歌', translated)!;
    expect(payload.lyric, '[00:10.00][01:10.00]Chorus');
    expect(payload.translatedLyric, '[00:10.00][01:10.00]副歌');
  });

  test('should_dropLineFromBothSides_when_originalTextIsEmpty', () {
    final payload = huaweiLrcSplit('[00:01.00]Line ^行\n[00:02.00]^孤立译文\n[00:03.00]', translated)!;
    expect(payload.lyric, '[00:01.00]Line');
    expect(payload.translatedLyric, '[00:01.00]行');
  });

  test('should_produceOriginalWithoutCaretTails_when_translated', () {
    final payload = huaweiLrcSplit('[00:01.00]A ^甲\n[00:02.00]B^乙\n[00:03.00]C\n[00:04.00]D ^丁', translated)!;
    expect(payload.lyric, isNot(contains('^')));
    expect([for (final l in (LrcParser.parse(payload.lyric) as SyncedLyrics).lines) l.text], ['A', 'B', 'C', 'D']);
  });

  test('should_returnNull_when_instrumentalPlaceholder', () {
    expect(huaweiLrcSplit('[00:00:00]此歌曲为没有填词的纯音乐', {'abs'}), isNull);
    expect(huaweiLrcSplit('[00:00.00]此歌曲为没有填词的纯音乐\n', const {}), isNull);
    expect(huaweiLrcSplit('﻿\r\n  \r\n', translated), isNull);
  });

  test('should_stripBomAndCrlf_when_present', () {
    final payload = huaweiLrcSplit('﻿[ti:Song]\r\n[00:01.00]Line ^行\r\n', translated)!;
    expect(payload.lyric.trim(), '[ti:Song]\n[00:01.00]Line');
    expect(payload.translatedLyric, '[00:01.00]行');
  });

  test('should_decodeNumericEntities_when_present', () {
    final payload = huaweiLrcSplit('[00:01.00]&#45684;&#51652;&#49828; ma&#xF1;ana ^新牛仔', translated)!;
    expect(payload.lyric, '[00:01.00]뉴진스 mañana');
    expect(payload.translatedLyric, '[00:01.00]新牛仔');
  });
}
