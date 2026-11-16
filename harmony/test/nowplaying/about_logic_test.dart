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
  @override
  String get arranger => 'Arranger';
  @override
  String get size => 'Size';
  @override
  String megabytes(String value) => '$value MB';
  @override
  String get bpm => 'BPM';
  @override
  String get playCount => 'Plays';
  @override
  String get comment => 'Comment';
  @override
  String get file => 'File';
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
      null,
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

  // Android AboutReceiptTest.
  test('should_putCreditsThenAiThenEveryServerFact_when_allArePresent', () {
    final lyricist = _row(SongAboutKinds.canonical, AboutFields.lyricist, 'AI guess');
    final time = _row(SongAboutKinds.canonical, AboutFields.creationTime, '2019');
    final rows = aboutReceiptRows(
      [
        lyricist,
        time,
        _row(SongAboutKinds.canonical, AboutFields.producer, 'P\n'),
        _row(SongAboutKinds.canonical, AboutFields.review, 'a long review'),
      ],
      const LyricCredits([
        LyricCredit('作词', '唐恬', headline: LyricCreditRole.lyricist),
        LyricCredit('编曲', '钱雷', headline: LyricCreditRole.arranger),
        LyricCredit('吉他', '高飞'),
      ]),
      const AboutSourceFacts(
        format: 'FLAC',
        bitRateKbps: 1000,
        samplingRateHz: 44100,
        bitDepth: 16,
        serverRating: 4,
        genre: 'Pop',
        year: 2019,
        trackNumber: 3,
        discNumber: 1,
        durationSec: 245,
        channels: 2,
        sizeBytes: 9437184,
        playCount: 12,
        bpm: 120,
        composer: 'Server composer',
        comment: 'Remaster',
        added: '2024-01-02T03:04:05Z',
        path: 'Pop/song.flac',
      ),
      _Labels(),
    );
    expect(
      [for (final r in rows) '${r.label}=${r.value}'],
      [
        'about:lyricist=唐恬',
        'about:composer=Server composer',
        'Arranger=钱雷',
        'about:producer=P',
        'about:creation_time=2019',
        'Genre=Pop',
        'Year=2019',
        'Track=3',
        'Length=4:05',
        'Quality=FLAC  16-bit  44.1 kHz  1000 kbps',
        'Channels=Stereo',
        'Size=9.0 MB',
        'BPM=120',
        'Plays=12',
        'Server rating=★★★★☆',
        'Added=2024-01-02',
        'Comment=Remaster',
        'File=Pop/song.flac',
      ],
    );
    // Rows from the lyrics or the server have no AI row behind them; the AI's keep theirs.
    expect(rows.first.entry, isNull);
    expect(rows.firstWhere((r) => r.label == 'about:creation_time').entry, same(time));
    expect(rows.firstWhere((r) => r.label == 'about:producer').entry?.entryKey, AboutFields.producer);
  });

  test('should_fallBackToAi_when_noSourceNamesTheRole', () {
    final composer = _row(SongAboutKinds.canonical, AboutFields.composer, 'AI composer');
    final rows = aboutReceiptRows([composer], null, null, _Labels());
    expect([for (final r in rows) '${r.label}=${r.value}'], ['about:composer=AI composer']);
    expect(rows.single.entry, same(composer));
  });

  test('should_showTheLyricsCredits_when_theAiHasNothing', () {
    final rows = aboutReceiptRows(
      const [],
      const LyricCredits([LyricCredit('Lyrics by', 'Alice', headline: LyricCreditRole.lyricist)]),
      null,
      _Labels(),
    );
    expect([for (final r in rows) '${r.label}=${r.value}'], ['about:lyricist=Alice']);
  });

  test('should_previewTheFirstThreeRoles_when_moreCreditsFold', () {
    const others = [
      LyricCredit('吉他', 'A'),
      LyricCredit('', 'Recorded at X'),
      LyricCredit('混音', 'B'),
      LyricCredit('母带', 'C'),
    ];
    expect(lyricCreditsMorePreview(others, 'Note'), '吉他、Note、混音…');
  });

  test('should_formatMegabytes_when_givenBytes', () {
    expect(formatAboutMegabytes(9437184), '9.0');
    expect(formatAboutMegabytes(5000000), '4.8');
  });

  test('should_readEveryServerFact_when_theSongIsSubsonic', () {
    final facts = AboutSourceFacts.of(
      Track(
        id: MediaId.subsonic('1'),
        sizeBytes: 1024,
        playCount: 3,
        bpm: 98,
        composer: ' C ',
        comment: ' ',
        path: 'a/b.flac',
        addedAt: '2024-01-01',
      ),
    )!;
    expect(facts.sizeBytes, 1024);
    expect(facts.playCount, 3);
    expect(facts.bpm, 98);
    expect(facts.composer, 'C');
    expect(facts.comment, isNull);
    expect(facts.path, 'a/b.flac');
    expect(facts.added, '2024-01-01');
  });

  test('should_keepOnlyTheComposer_when_appleMusicNamesOne', () {
    final facts = AboutSourceFacts.of(
      Track(id: MediaId(MediaId.providerAppleMusic, 'x'), genre: 'Pop', year: 2020, composer: 'Max Martin'),
    );
    expect(facts, const AboutSourceFacts(composer: 'Max Martin'));
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
