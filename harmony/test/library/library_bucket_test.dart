import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/library/library_sort.dart';

import '../support/library_fake_source.dart';

/// Pinyin as LocalizationKit's Han-Latin returns it (tone marks, spaced syllables).
const _pinyin = {
  '林间': 'lín jiān',
  '周杰伦': 'zhōu jié lún',
  '午後のソーダ': 'wǔ hòu のソーダ',
  '坂本龍一': 'bǎn běn lóng yī',
  '陈奕迅': 'chén yì xùn',
};

List<String> _letters(SortedView? v) => [for (final s in v!.sections) (s.label as LetterLabel).letter];

void main() {
  group('bucketLabelOf', () {
    test('should_fileHanUnderPinyinInitial_when_latinFormKnown', () {
      expect(bucketLabelOf('林间', _pinyin), 'L');
      expect(bucketLabelOf('周杰伦', _pinyin), 'Z');
      expect(bucketLabelOf('坂本龍一', _pinyin), 'B');
      expect(bucketLabelOf('午後のソーダ', _pinyin), 'W');
    });

    test('should_fileHanInOverflow_when_noLatinForm', () {
      expect(bucketLabelOf('林间'), '…');
    });

    test('should_fileKanaUnderGojuonRow_when_hiraganaOrKatakana', () {
      expect(bucketLabelOf('あいみょん'), 'あ');
      expect(bucketLabelOf('ずっと真夜中でいいのに。'), 'さ');
      expect(bucketLabelOf('ソーダ'), 'さ');
      expect(bucketLabelOf('ヨルシカ'), 'や');
      expect(bucketLabelOf('ゲスの極み乙女'), 'か');
      expect(bucketLabelOf('ぱすぴえ'), 'は');
      expect(bucketLabelOf('ヴィレッジ'), 'あ');
      expect(bucketLabelOf('ワンオク'), 'わ');
      expect(bucketLabelOf('ンダホ'), 'わ');
    });

    test('should_fileHangulUnderInitialConsonant_when_syllableOrJamo', () {
      expect(bucketLabelOf('가수'), 'ㄱ');
      expect(bucketLabelOf('까치'), 'ㄱ');
      expect(bucketLabelOf('뉴진스'), 'ㄴ');
      expect(bucketLabelOf('방탄소년단'), 'ㅂ');
      expect(bucketLabelOf('아이유'), 'ㅇ');
      expect(bucketLabelOf('혁오'), 'ㅎ');
      expect(bucketLabelOf('ㅋㅋ'), 'ㅋ');
    });

    test('should_fileOtherScriptsInInflow_when_notLatinKanaHangulOrHan', () {
      expect(bucketLabelOf('Кино'), '…');
      expect(bucketLabelOf('Ωmega'), '…');
    });

    test('should_foldPinyinTones_when_bucketing', () {
      expect(collationKey('lǚ'), 'lu');
      expect(collationKey('bǎn běn'), 'ban ben');
    });
  });

  test('should_orderLatinHangulKanaOverflowNumber_when_alphabetical', () {
    final sorted = sortLibrary(
      LibraryListInputs(
        artists: [
          LibraryFixtures.artist('1', name: '2Pac'),
          LibraryFixtures.artist('2', name: '王菲'),
          LibraryFixtures.artist('3', name: 'ヨルシカ'),
          LibraryFixtures.artist('4', name: '아이유'),
          LibraryFixtures.artist('5', name: 'Кино'),
          LibraryFixtures.artist('6', name: 'Beck'),
        ],
        sorts: const {LibraryTab.artists: LibrarySort.alphabetical},
        nowMs: 0,
      ),
    );
    expect([for (final i in sorted.artists!.items) i.name], ['Beck', 'Кино', '아이유', 'ヨルシカ', '王菲', '2Pac']);
    expect(_letters(sorted.artists), ['B', '…', 'ㅇ', 'や', '…', '#']);
  });

  test('should_interleaveHanWithLatinByPinyin_when_latinNamesGiven', () {
    final sorted = sortLibrary(
      LibraryListInputs(
        artists: [
          LibraryFixtures.artist('1', name: '周杰伦'),
          LibraryFixtures.artist('2', name: 'Lorde'),
          LibraryFixtures.artist('3', name: '林间'),
          LibraryFixtures.artist('4', name: 'Coldplay'),
          LibraryFixtures.artist('5', name: '陈奕迅'),
          LibraryFixtures.artist('6', name: '午後のソーダ'),
        ],
        sorts: const {LibraryTab.artists: LibrarySort.alphabetical},
        latinNames: _pinyin,
        nowMs: 0,
      ),
    );
    expect([for (final i in sorted.artists!.items) i.name], ['陈奕迅', 'Coldplay', '林间', 'Lorde', '午後のソーダ', '周杰伦']);
    expect(_letters(sorted.artists), ['C', 'L', 'W', 'Z']);
  });

  test('should_bucketCreatorByPinyin_when_creatorSort', () {
    final sorted = sortLibrary(
      LibraryListInputs(
        albums: [
          LibraryFixtures.album('1', name: 'Fantasy', artist: '周杰伦'),
          LibraryFixtures.album('2', name: 'U87', artist: '陈奕迅'),
        ],
        sorts: const {LibraryTab.albums: LibrarySort.creator},
        latinNames: _pinyin,
        nowMs: 0,
      ),
    );
    expect([for (final i in sorted.albums!.items) i.name], ['U87', 'Fantasy']);
    expect(_letters(sorted.albums), ['C', 'Z']);
  });
}
