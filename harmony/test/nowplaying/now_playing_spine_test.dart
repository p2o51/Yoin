import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/now_playing_spine.dart';
import 'package:yoin/theme/tokens.dart';

// The spine (书脊, NowPlayingSpine.kt): what it reads and how it steps.

SongAboutEntry _row(String kind, String key, String answer) => SongAboutEntry(
  titleKey: 't',
  artistKey: 'a',
  albumKey: 'l',
  titleDisplay: 'T',
  artistDisplay: 'A',
  albumDisplay: 'L',
  kind: kind,
  entryKey: key,
  answerText: answer,
  createdAt: 0,
  updatedAt: 0,
);

const _en = SpineLabels(zh: false);
const _zh = SpineLabels(zh: true);

Track _track({String provider = MediaId.providerSubsonic}) => Track(
  id: MediaId(provider, 't1'),
  title: 'Song',
  artist: 'Artist',
  album: 'Album',
  year: 2019,
  genre: 'Jazz',
  trackNumber: 3,
  discNumber: 2,
  userRating: 4,
  audioFormat: const AudioFormat(suffix: 'flac', bitDepth: 24, sampleRateHz: 96000, bitRateKbps: 2304),
);

Future<void> _pumpSpine(WidgetTester tester, List<SpineItem> items, {String songKey = 'a'}) => tester.pumpWidget(
  MaterialApp(
    home: Scaffold(
      body: Center(
        child: SizedBox(
          width: 40,
          height: 200,
          child: NowPlayingSpine(items: items, songKey: songKey, length: 200),
        ),
      ),
    ),
  ),
);

String? _shownLabel(WidgetTester tester) {
  final texts = tester.widgetList<Text>(find.byType(Text)).toList();
  return texts.isEmpty ? null : texts.last.textSpan!.toPlainText();
}

void main() {
  test('should_readTheServersFacts_when_theSongIsSubsonic', () {
    final items = spineItems(
      facts: SpineSourceFacts.of(_track()),
      albumName: 'Album',
      artist: 'Artist',
      title: 'Song',
      labels: _en,
    );
    expect(items, const [
      SpineItem('Quality', 'FLAC  24-bit  96 kHz  2304 kbps'),
      SpineItem('Server rating', '★★★★☆'),
      SpineItem('Genre', 'Jazz'),
      SpineItem('Year', '2019'),
      SpineItem('Track', 'Disc 2, track 3'),
    ]);
  });

  test('should_readAlbumArtistAndTitle_when_thereAreNoFacts', () {
    expect(SpineSourceFacts.of(_track(provider: MediaId.providerSpotify)), isNull);
    final items = spineItems(facts: null, albumName: 'Album', artist: 'Artist', title: 'Song', labels: _en);
    expect(items, const [SpineItem('', 'Album   Artist   Song')]);
    expect(spineItems(facts: null, albumName: '', artist: ' ', title: '', labels: _en), isEmpty);
  });

  test('should_putTheLyricsCreditsFirst_when_theLyricsCarryThem', () {
    final items = spineItems(
      facts: const SpineSourceFacts(genre: 'Pop'),
      albumName: 'Album',
      artist: 'Artist',
      title: 'Song',
      labels: _zh,
      credits: const LyricCredits([
        LyricCredit('曲', 'A  B', headline: LyricCreditRole.composer),
        LyricCredit('词', ' C ', headline: LyricCreditRole.lyricist),
      ]),
    );
    expect(items, const [SpineItem('作词', 'C'), SpineItem('作曲', 'A B'), SpineItem('流派', 'Pop')]);
  });

  // Android SpineCreditsTest.
  test('should_listCanonicalCreditsInAboutOrder_when_cached', () {
    final items = spineItems(
      entries: [
        _row(SongAboutKinds.canonical, AboutFields.producer, 'P'),
        _row(SongAboutKinds.canonical, AboutFields.lyricist, 'L\nL2'),
        _row(SongAboutKinds.canonical, AboutFields.review, 'a long review'),
        _row(SongAboutKinds.canonical, AboutFields.creationTime, '1971'),
        _row(SongAboutKinds.ask, AboutFields.composer, 'not a credit'),
      ],
      facts: null,
      albumName: 'Album',
      artist: 'Artist',
      title: 'Title',
      labels: _en,
    );
    expect(items, const [
      SpineItem('Creation Time', '1971'),
      SpineItem('Lyricist', 'L L2'),
      SpineItem('Producer', 'P'),
    ]);
  });

  test('should_preferTheLyricsSourceCredits_when_itNamesTheRole', () {
    final items = spineItems(
      entries: [
        _row(SongAboutKinds.canonical, AboutFields.lyricist, 'AI guess'),
        _row(SongAboutKinds.canonical, AboutFields.producer, 'P'),
      ],
      credits: const LyricCredits([
        LyricCredit('作词', '唐恬', headline: LyricCreditRole.lyricist),
        LyricCredit('作曲', '钱雷', headline: LyricCreditRole.composer),
      ]),
      facts: null,
      albumName: 'Album',
      artist: 'Artist',
      title: 'Title',
      labels: _en,
    );
    expect(items, const [SpineItem('Lyricist', '唐恬'), SpineItem('Composer', '钱雷'), SpineItem('Producer', 'P')]);
  });

  test('should_fallBackToAlbumArtistTitle_when_theCachedCreditIsBlank', () {
    final items = spineItems(
      entries: [_row(SongAboutKinds.canonical, AboutFields.composer, '  ')],
      facts: const SpineSourceFacts(),
      albumName: 'Album',
      artist: 'Artist',
      title: 'Title',
      labels: _en,
    );
    expect(items, const [SpineItem('', 'Album   Artist   Title')]);
  });

  test('should_showTheTrackAlone_when_theSongIsOnTheFirstDisc', () {
    final items = spineItems(
      facts: const SpineSourceFacts(trackNumber: 5, discNumber: 1),
      albumName: '',
      artist: '',
      title: '',
      labels: _zh,
    );
    expect(items, const [SpineItem('曲目', '5')]);
    expect(_zh.discTrack(2, 3), '第 2 碟 第 3 首');
  });

  test('should_writeKilohertzLikeAndroid_when_formatting', () {
    expect(formatKhz(44100), '44.1');
    expect(formatKhz(96000), '96');
    expect(formatKhz(48000), '48');
    expect(formatKhz(88200), '88.2');
  });

  test('should_stepOnlyPastTheStep_when_swipedAcrossTheBand', () {
    const step = NowPlayingEffectTokens.spineSwipeStep;
    expect(spineIndexAfterSwipe(0, -(step - 1), 3), 0);
    expect(spineIndexAfterSwipe(0, -step, 3), 1);
    expect(spineIndexAfterSwipe(2, step, 3), 1);
    expect(spineIndexAfterSwipe(0, -100, 1), 0, reason: 'one fact never steps');
    expect(spineDragOffset(40), closeTo(6, 1e-9));
    expect(spineDragOffset(1000), NowPlayingEffectTokens.spineSwipeReach);
    expect(spineDragOffset(-1000), -NowPlayingEffectTokens.spineSwipeReach);
  });

  testWidgets('should_moveOnToTheNextFact_when_itHasRested', (tester) async {
    await _pumpSpine(tester, const [SpineItem('Genre', 'Jazz'), SpineItem('Year', '2019')]);
    await tester.pump();
    expect(_shownLabel(tester), contains('Jazz'));
    await tester.pump(const Duration(milliseconds: NowPlayingEffectTokens.spineDwellMs + 100));
    await tester.pumpAndSettle();
    expect(_shownLabel(tester), contains('2019'));
  });

  testWidgets('should_stepToTheNextFact_when_swipedTowardTheStart', (tester) async {
    await _pumpSpine(tester, const [SpineItem('Genre', 'Jazz'), SpineItem('Year', '2019'), SpineItem('Track', '3')]);
    await tester.pump();
    await tester.drag(find.byType(NowPlayingSpine), const Offset(-60, 0));
    await tester.pumpAndSettle();
    expect(_shownLabel(tester), contains('2019'));
    await tester.drag(find.byType(NowPlayingSpine), const Offset(10, 0));
    await tester.pumpAndSettle();
    expect(_shownLabel(tester), contains('2019'), reason: 'a short swipe only nudges it');
  });

  testWidgets('should_startAtTheFirstFact_when_theSongChanges', (tester) async {
    const items = [SpineItem('Genre', 'Jazz'), SpineItem('Year', '2019')];
    await _pumpSpine(tester, items);
    await tester.drag(find.byType(NowPlayingSpine), const Offset(-60, 0));
    await tester.pumpAndSettle();
    expect(_shownLabel(tester), contains('2019'));
    await _pumpSpine(tester, items, songKey: 'b');
    await tester.pumpAndSettle();
    expect(_shownLabel(tester), contains('Jazz'));
  });

  testWidgets('should_readBottomToTop_when_laidInTheBand', (tester) async {
    await _pumpSpine(tester, const [SpineItem('Genre', 'Jazz')]);
    await tester.pumpAndSettle();
    final rotated = tester.widget<RotatedBox>(find.byType(RotatedBox));
    expect(rotated.quarterTurns, 3);
  });
}
