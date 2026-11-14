import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/copy/memory_copy.dart';
import 'package:yoin/memories/copy/memory_voice.dart';

MemoryDay d(int y, int m, int day) => DateTime.utc(y, m, day);

void main() {
  group('MemoryScores', () {
    test('should_roundHalfUp_when_scoreEndsInFive', () {
      expect(MemoryScores.text(9.95), '10.0');
      expect(MemoryScores.text(8.25), '8.3');
      expect(MemoryScores.text(8.24), '8.2');
      expect(MemoryScores.text(7), '7.0');
      expect(MemoryScores.text(0), '0.0');
      expect(MemoryScores.tenths(9.95), 100);
    });
  });

  group('MemoryDates', () {
    test('should_dropYear_when_dateIsThisYear', () {
      expect(MemoryDates.day(d(2026, 7, 26), d(2026, 11, 14)), 'Jul 26');
      expect(MemoryDates.day(d(2025, 11, 8), d(2026, 11, 14)), 'Nov 8, 2025');
      expect(MemoryDates.dayZh(d(2025, 11, 8), d(2026, 11, 14)), '2025年11月8日');
      expect(MemoryDates.dayZh(d(2026, 7, 26), d(2026, 11, 14)), '7月26日');
    });

    test('should_countCalendarDays_when_acrossDst', () {
      expect(MemoryDates.daysBetween(d(2026, 3, 1), d(2026, 4, 1)), 31);
      expect(MemoryDates.daysBetween(d(2026, 4, 1), d(2026, 3, 1)), -31);
    });
  });

  group('MemorySentences', () {
    test('should_keepDecimals_when_splitting', () {
      expect(MemorySentences.split('I gave it 8.5. Then more!'), ['I gave it 8.5.', 'Then more!']);
      expect(MemorySentences.split('夜里听。很安静！'), ['夜里听。', '很安静！']);
      expect(MemorySentences.split('He said “stay.” Then left.'), ['He said “stay.”', 'Then left.']);
    });

    test('should_joinWithoutSpace_when_cjk', () {
      expect(MemorySentences.join(['夜里听。', '很安静！']), '夜里听。很安静！');
      expect(MemorySentences.join(['One.', 'Two.']), 'One. Two.');
    });

    test('should_weighHanAsOne_when_measuring', () {
      expect(MemorySentences.weightedLength('夜里ab'), 3.0);
    });
  });

  group('MemoryVoice', () {
    final today = d(2026, 11, 14);

    test('should_writeChinese_when_hanOutweighsLatin', () {
      expect(MemoryVoice.writesIn('这张专辑 ok', const []), MemoryProseLanguage.zh);
      expect(MemoryVoice.writesIn('a long english review', const ['短']), MemoryProseLanguage.en);
      expect(MemoryVoice.writesIn('ひらがな', const [], appLanguage: MemoryProseLanguage.zh), MemoryProseLanguage.zh);
      expect(MemoryVoice.writesIn(null, const []), MemoryProseLanguage.en);
    });

    test('should_useAlbumName_when_noAiTitle', () {
      final v = MemoryVoice.compose(const MemoryCopyInput(albumName: 'Blue'), today);
      expect(v.title, 'Blue');
      expect(v.titleKind, MemoryTitleKind.album);
      expect(v.narration, isNull);
      expect(v.question, 'What stays with you from this one?');
    });

    test('should_narrateNotes_when_notesButNoRatings', () {
      final v = MemoryVoice.composeIn(
        MemoryCopyInput(
          albumName: 'Blue',
          notes: [
            MemoryCopyNote(text: 'a', writtenOn: d(2026, 11, 1)),
            MemoryCopyNote(
              text: 'b',
              writtenOn: d(2026, 11, 4),
              track: const MemoryCopyTrack(number: 2, title: 'River'),
            ),
          ],
        ),
        today,
        MemoryProseLanguage.en,
      );
      expect(v.narration, 'Two notes in four days; the latest was on River.');
      expect(v.question, 'Put together, what would you say about the album?');
    });

    test('should_nameTopTrack_when_mostTracksRated', () {
      final v = MemoryVoice.composeIn(
        MemoryCopyInput(
          albumName: 'Blue',
          tracks: const [
            MemoryCopyTrack(number: 1, title: 'One', rating: 8.5),
            MemoryCopyTrack(number: 2, title: 'Two', rating: 9.5),
            MemoryCopyTrack(number: 3, title: 'Three', rating: 9.5),
          ],
          ratedTracks: 3,
          totalTracks: 4,
          listening: MemoryListening(plays: 12, firstHeard: d(2026, 10, 1), lastHeard: d(2026, 11, 12)),
        ),
        today,
        MemoryProseLanguage.zh,
      );
      expect(v.narration, '听了 12 遍，最高分是《Two》的 9.5。');
      expect(v.question, '那整张专辑呢？');
    });

    test('should_askAboutScore_when_onlyAlbumScore', () {
      final v = MemoryVoice.composeIn(
        MemoryCopyInput(
          albumName: 'Blue',
          albumScore: 8,
          listening: MemoryListening(plays: 1, firstHeard: d(2025, 2, 3), lastHeard: d(2026, 11, 12)),
        ),
        today,
        MemoryProseLanguage.en,
      );
      expect(v.narration, '1 play since February 2025, the last one two days ago.');
      expect(v.question, 'You gave it an 8.0. What earned it?');
    });

    test('should_stayQuiet_when_reviewWritten', () {
      final v = MemoryVoice.compose(const MemoryCopyInput(albumName: 'Blue', review: 'Mine.', albumScore: 9), today);
      expect(v.narration, isNull);
      expect(v.question, isNull);
    });
  });

  group('MemoryExcerpt', () {
    final today = d(2026, 11, 14);

    test('should_offerSentencesLongestFirst_when_review', () {
      final c = MemoryExcerpt.candidates(
        MemoryCopyInput(albumName: 'B', review: 'One. Two. Three.\nSecond paragraph.', reviewWrittenOn: d(2026, 9, 2)),
        capped: false,
        today: today,
      );
      expect(c.map((e) => e.text), ['One. Two. Three.', 'One. Two.', 'One.', null]);
      expect(c.last.attribution, const MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Sep 2'));
    });

    test('should_capAtTwoSentences_when_medium', () {
      final c = MemoryExcerpt.candidates(
        const MemoryCopyInput(albumName: 'B', review: 'One. Two. Three.'),
        capped: true,
        today: today,
      );
      expect(c.map((e) => e.text), ['One. Two.', 'One.', null]);
    });

    test('should_quoteFirstSongNoteThenShortest_when_noReview', () {
      const t1 = MemoryCopyTrack(number: 1, title: 'Opener');
      const t3 = MemoryCopyTrack(number: 3, title: 'Third');
      final c = MemoryExcerpt.candidates(
        MemoryCopyInput(
          albumName: 'B',
          notes: [
            MemoryCopyNote(text: 'a long note on three', writtenOn: d(2026, 1, 1), track: t3),
            MemoryCopyNote(text: 'the opener at the drop', writtenOn: d(2026, 1, 2), track: t1, positionMs: 141000),
          ],
        ),
        capped: false,
        today: today,
      );
      expect(c.map((e) => e.text), ['the opener at the drop', 'a long note on three', null]);
      expect(c.first.attribution.detail, 'Opener 2:21');
    });

    test('should_offerNothing_when_nothingWritten', () {
      expect(MemoryExcerpt.candidates(const MemoryCopyInput(albumName: 'B'), capped: false, today: today), isEmpty);
    });
  });
}
