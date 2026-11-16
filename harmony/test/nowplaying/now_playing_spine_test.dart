import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/now_playing_spine.dart';
import 'package:yoin/theme/tokens.dart';

// The spine (书脊, NowPlayingSpine.kt): what it reads and how it steps.

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
      credits: const {SpineCreditRole.composer: 'A  B', SpineCreditRole.lyricist: ' C '},
    );
    expect(items, const [SpineItem('作词', 'C'), SpineItem('作曲', 'A B'), SpineItem('流派', 'Pop')]);
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
