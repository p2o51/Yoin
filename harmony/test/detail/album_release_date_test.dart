import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/album_release_date.dart';
import 'package:yoin/detail/album_scrapbook.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

Album _album({String? releaseDate, int? year, int? durationSec}) => Album(
  id: MediaId.subsonic('al1'),
  name: 'Blue',
  artist: 'Joni',
  year: year,
  releaseDate: releaseDate,
  durationSec: durationSec,
  tracks: [for (var i = 1; i <= 3; i++) LibraryFixtures.track('t$i', artist: 'Joni', number: i, duration: 600)],
);

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _openAlbum(WidgetTester tester, Album album, {String lang = 'en'}) async {
  final c = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {album.id: album})),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
    ],
  );
  addTearDown(c.dispose);
  c.listen(playbackControllerProvider, (_, _) {});
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        locale: Locale(lang),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(onPressed: () => DetailRoutes.openAlbum(context, album.id), child: const Text('open')),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await _settle(tester);
}

/// Pulls the hero up into the track list (the list, and the date closing it, are built then).
Future<void> _pullUp(WidgetTester tester) async {
  await tester.drag(find.textContaining('Song\u00A0t2', findRichText: true).first, const Offset(0, -500));
  await _settle(tester);
}

Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}

void main() {
  group('ReleaseDate', () {
    test('should_keepEachServicesPrecision_when_isoParsed', () {
      expect(ReleaseDate.parseIso('2023-10-27'), ReleaseDate.of(2023, 10, 27));
      expect(ReleaseDate.parseIso('2023-10'), ReleaseDate.of(2023, 10));
      expect(ReleaseDate.parseIso('2023'), ReleaseDate.of(2023));
      expect(ReleaseDate.parseIso(' 2023-10-27T00:00:00Z '), ReleaseDate.of(2023, 10, 27), reason: 'a time is ignored');
      expect(ReleaseDate.parseIso('2023-10-27')!.iso, '2023-10-27');
      expect(ReleaseDate.parseIso('2023-10')!.iso, '2023-10');
    });

    test('should_dropInvalidParts_when_theyMakeNoRealDate', () {
      expect(ReleaseDate.parseIso('2023-13-01'), ReleaseDate.of(2023), reason: 'a bad month drops month and day');
      expect(ReleaseDate.parseIso('2023-02-30'), ReleaseDate.of(2023, 2), reason: 'a bad day drops the day');
      expect(ReleaseDate.parseIso('2024-02-29'), ReleaseDate.of(2024, 2, 29));
      expect(ReleaseDate.parseIso('0000'), isNull);
      expect(ReleaseDate.parseIso('71'), isNull);
      expect(ReleaseDate.parseIso(''), isNull);
      expect(ReleaseDate.parseIso(null), isNull);
    });
  });

  group('formatting', () {
    test('should_readCompactAndNumeric_when_inTheHeader', () {
      expect(albumHeaderDate(ReleaseDate.of(2022, 6, 12), 2022), '22.6.12');
      expect(albumHeaderDate(ReleaseDate.of(2022, 6), 2022), '22.6');
      expect(albumHeaderDate(ReleaseDate.of(2022), 2022), '2022');
      expect(albumHeaderDate(ReleaseDate.of(2005, 1, 3), null), '05.1.3');
      expect(albumHeaderDate(null, 1971), '1971', reason: 'no date: the year');
      expect(albumHeaderDate(null, null), isNull);
      expect(albumHeaderDate(null, 0), isNull);
    });

    test('should_writeTheReceiptsEnglish_when_releasedAndLength', () {
      expect(receiptReleaseDate(ReleaseDate.of(2022, 6, 12)!), 'Jun 12, 2022');
      expect(receiptReleaseDate(ReleaseDate.of(2022, 6)!), 'Jun 2022');
      expect(receiptReleaseDate(ReleaseDate.of(2022)!), '2022');
      expect(receiptAlbumLength(47 * 60 + 30), '47 min');
      expect(receiptAlbumLength(72 * 60), '1 hr 12 min');
      expect(receiptAlbumLength(120 * 60 + 5), '2 hr');
      expect(receiptAlbumLength(20), '1 min');
      expect(receiptAlbumLength(0), isNull);
      expect(receiptAlbumLength(null), isNull);
    });
  });

  group('receipt', () {
    AlbumScrapbookData played() => AlbumScrapbookData(
      plays: AlbumPlays(albumId: MediaId.subsonic('al1'), playCount: 2, firstPlayedAt: 1, lastPlayedAt: 1),
    );

    test('should_openWithReleasedAndLength_when_known', () {
      final receipt = buildAlbumScrapbook(_album(releaseDate: '2022-06-12'), played(), nowMs: 1).last as ScrapReceipt;
      expect(receipt.lines.take(3), [('Released', 'Jun 12, 2022'), ('Length', '30 min'), ('Plays in Yoin', '2')]);
    });

    test('should_takeTheServicesLength_when_itGivesOne', () {
      final receipt = buildAlbumScrapbook(_album(durationSec: 72 * 60), played(), nowMs: 1).last as ScrapReceipt;
      expect(receipt.lines.first, ('Length', '1 hr 12 min'), reason: 'no date: no Released line');
    });
  });

  group('page', () {
    testWidgets('should_showCompactDateInHeader_andFullDateUnderLastTrack_when_dated', (tester) async {
      await _openAlbum(tester, _album(releaseDate: '1971-06-22', year: 1971));
      expect(find.text('71.6.22', findRichText: true), findsWidgets);
      expect(find.text('1971', findRichText: true), findsNothing, reason: 'the date replaces the year');
      await _pullUp(tester);
      expect(find.byKey(const ValueKey('album-release-date')), findsOneWidget);
      expect(
        find.descendant(of: find.byKey(const ValueKey('album-release-date')), matching: find.text('June 22, 1971')),
        findsOneWidget,
      );
      await _close(tester);
    });

    testWidgets('should_writeTheDateInChinese_when_zh', (tester) async {
      await _openAlbum(tester, _album(releaseDate: '1971-06', year: 1971), lang: 'zh');
      expect(find.text('71.6', findRichText: true), findsWidgets);
      await _pullUp(tester);
      expect(
        find.descendant(of: find.byKey(const ValueKey('album-release-date')), matching: find.text('1971年6月')),
        findsOneWidget,
      );
      await _close(tester);
    });

    testWidgets('should_showTheYearAndNoDateLine_when_undated', (tester) async {
      await _openAlbum(tester, _album(year: 1971));
      expect(find.text('1971', findRichText: true), findsWidgets);
      await _pullUp(tester);
      expect(find.text('Song t3'), findsOneWidget);
      expect(find.byKey(const ValueKey('album-release-date')), findsNothing);
      await _close(tester);
    });
  });
}
