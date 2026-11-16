import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/about_logic.dart';
import 'package:yoin/platform/storage/stores.dart';

SongAboutEntry _row(String kind, String key, String answer, {String? title, String? prompt, int at = 1}) =>
    SongAboutEntry(
      titleKey: 't',
      artistKey: 'a',
      albumKey: 'l',
      titleDisplay: 'T',
      artistDisplay: 'A',
      albumDisplay: 'L',
      kind: kind,
      entryKey: key,
      promptText: prompt,
      titleText: title,
      answerText: answer,
      createdAt: at,
      updatedAt: at,
    );

final class _Labels implements AboutLabels {
  @override
  String about(String entryKey) => 'about:$entryKey';
  @override
  String get quality => 'Quality';
  @override
  String get serverRating => 'Server rating';
  @override
  String get genre => 'Genre';
  @override
  String get year => 'Year';
  @override
  String get track => 'Track';
  @override
  String discTrack(int disc, int track) => 'Disc $disc, track $track';
  @override
  String kbps(int value) => '$value kbps';
  @override
  String khz(String value) => '$value kHz';
  @override
  String bit(int value) => '$value-bit';
  @override
  String get duration => 'Length';
  @override
  String get channels => 'Channels';
  @override
  String channelCount(int count) => count == 2 ? 'Stereo' : '$count channels';
  @override
  String get added => 'Added';
}

/// Android AboutReceipt's rows / paragraphs / carousel pages, the quick
/// prompts and the key hint's cooldown.
void main() {
  test('should_listCreditsThenWhenThenServerFacts_when_receiptBuilt', () {
    final rows = aboutReceiptRows(
      [
        _row(SongAboutKinds.canonical, AboutFields.creationTime, '1971'),
        _row(SongAboutKinds.canonical, AboutFields.lyricist, 'Joni\n  Mitchell'),
        _row(SongAboutKinds.canonical, AboutFields.composer, ''),
        _row(SongAboutKinds.canonical, AboutFields.review, 'Long review.'),
      ],
      const AboutSourceFacts(
        format: 'FLAC',
        bitDepth: 24,
        samplingRateHz: 96000,
        bitRateKbps: 2304,
        genre: 'Folk',
        year: 1971,
        trackNumber: 3,
        discNumber: 2,
        durationSec: 245,
        channels: 2,
        serverRating: 4,
        added: '2024-03-14T10:00:00Z',
      ),
      _Labels(),
    );
    expect(
      [for (final r in rows) '${r.label}=${r.value}'],
      [
        'about:lyricist=Joni Mitchell',
        'about:creation_time=1971',
        'Genre=Folk',
        'Year=1971',
        'Track=Disc 2, track 3',
        'Length=4:05',
        'Quality=FLAC  24-bit  96 kHz  2304 kbps',
        'Channels=Stereo',
        'Server rating=★★★★☆',
        'Added=2024-03-14',
      ],
    );
    expect(rows.first.entry?.entryKey, AboutFields.lyricist);
    expect(rows.last.entry, isNull);
  });

  test('should_leaveFactsOut_when_trackIsNotSubsonic', () {
    final track = Track(id: MediaId('applemusic', 'x'), title: 'T', genre: 'Pop');
    expect(AboutSourceFacts.of(track), isNull);
    final subsonic = Track(id: MediaId.subsonic('1'), title: 'T', genre: ' ', userRating: 9, year: 0);
    final facts = AboutSourceFacts.of(subsonic)!;
    expect(facts.genre, isNull);
    expect(facts.serverRating, isNull);
    expect(facts.year, isNull);
  });

  test('should_putReviewFirstThenAnswersByHeading_when_paragraphsBuilt', () {
    final paragraphs = aboutParagraphs([
      _row(SongAboutKinds.canonical, AboutFields.review, ' A record. '),
      _row(SongAboutKinds.ask, 'why', 'Because.', title: 'Why it matters', prompt: 'Why?'),
      _row(SongAboutKinds.ask, 'old', 'Old answer.', prompt: 'An old question'),
      _row(SongAboutKinds.own, 'mine', 'Mine.', title: 'My story'),
      _row(SongAboutKinds.ask, 'gone', ' '),
    ], 'About');
    expect(
      [for (final p in paragraphs) '${p.label}: ${p.text}'],
      ['About: A record.', 'Why it matters: Because.', 'An old question: Old answer.', 'My story: Mine.'],
    );
  });

  test('should_pageThreeLinesThenEachParagraph_when_carouselBuilt', () {
    final rows = [for (var i = 0; i < 7; i++) ReceiptRow('L$i', 'V$i')];
    final p = AboutParagraph('About', 'x', _row(SongAboutKinds.canonical, AboutFields.review, 'x'));
    final pages = aboutCarouselPages(rows, [p]);
    expect(pages, hasLength(4));
    expect((pages[0] as AboutCarouselLines).rows, hasLength(3));
    expect((pages[2] as AboutCarouselLines).rows, hasLength(1));
    expect(pages[3], isA<AboutCarouselParagraph>());
  });

  test('should_dropAskedPrompts_when_alreadyAskedOrWritten', () {
    final state = AboutReady([
      _row(SongAboutKinds.ask, 'k', 'a', prompt: 'Who sampled  this song?'),
      _row(SongAboutKinds.own, 'm', 'b', title: 'How was it written?'),
      _row(SongAboutKinds.canonical, AboutFields.review, 'r'),
    ]);
    final asked = askedQuestions(state);
    expect(asked, ['Who sampled  this song?', 'How was it written?']);
    expect(
      remainingQuickPrompts(['What does this song sample?', 'Who sampled this song?', 'How was it written?'], asked),
      ['What does this song sample?'],
    );
    expect(askedQuestions(const AboutLoading()), isEmpty);
  });

  test('should_formatLongDurations_when_overAnHour', () {
    expect(formatAboutDuration(3725), '1:02:05');
    expect(formatAboutDuration(59), '0:59');
  });

  group('key hint', () {
    test('should_beDue_when_neverShownOrCooldownPassed', () {
      expect(AiKeyHint.isDue(null, 100), isTrue);
      expect(AiKeyHint.isDue(100, 100 + AiKeyHint.cooldownMs - 1), isFalse);
      expect(AiKeyHint.isDue(100, 100 + AiKeyHint.cooldownMs), isTrue);
      // A clock set back counts as due.
      expect(AiKeyHint.isDue(1000, 10), isTrue);
    });

    test('should_claimOnlyOncePerCooldown_when_claimedTwice', () async {
      final store = InMemoryStore();
      expect(await AiKeyHint.claim(store, 5000), isTrue);
      expect(await AiKeyHint.claim(store, 6000), isFalse);
      expect(await AiKeyHint.claim(store, 5000 + AiKeyHint.cooldownMs), isTrue);
    });
  });
}
