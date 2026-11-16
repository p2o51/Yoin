import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/neodb/neodb_api.dart';
import 'package:yoin/data/neodb/neodb_config.dart';
import 'package:yoin/data/neodb/neodb_voices.dart';
import 'package:yoin/data/neodb/neodb_voices_service.dart';
import 'package:yoin/neodb/neodb_providers.dart';
import 'package:yoin/neodb/neodb_voices_screen.dart';
import 'package:yoin/neodb/neodb_voices_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

const _album = NeoDbAlbumVoices(
  itemUuid: 'u1',
  itemUrl: 'https://neodb.social/album/u1',
  title: 'Blue',
  rating: 8.6,
  ratingCount: 120,
  distribution: [2, 3, 10, 35, 50],
  doubanUrl: 'https://music.douban.com/subject/1/',
);

NeoDbVoice _voice(String id, {String text = 'Short words.', bool folded = false, String? title}) => NeoDbVoice(
  id: id,
  author: 'Listener $id',
  handle: 'l$id@neodb.social',
  text: text,
  rating: 8,
  folded: folded,
  reviewTitle: title,
  url: 'https://neodb.social/p/$id',
);

class _FakeVoices extends NeoDbVoicesService {
  _FakeVoices()
    : super(
        api: NeoDbApi(MockClient((_) async => http.Response('', 500))),
        config: NeoDbConfigStore(InMemoryStore(), null),
        client: MockClient((_) async => http.Response('', 500)),
      );

  NeoDbAlbumVoices? album = _album;
  bool failFind = false;
  bool failPage = false;
  final pages = <int, NeoDbVoicePage>{};
  final asked = <int>[];
  List<NeoDbReferenceScore> references = const [];

  @override
  Future<NeoDbAlbumVoices?> findAlbum(String albumName, String? artist) async {
    if (failFind) throw Exception('offline');
    return album;
  }

  @override
  Future<NeoDbVoicePage> voices(String itemUuid, int page) async {
    asked.add(page);
    if (failPage && page > 1) throw Exception('offline');
    return pages[page] ?? NeoDbVoicePage(voices: const [], page: page, pages: page, count: 0);
  }

  @override
  Future<String> searchUrl(String albumName, String? artist) async => 'https://neodb.social/search?q=$albumName';

  @override
  Future<List<NeoDbReferenceScore>> referenceScores(NeoDbAlbumVoices album) async => references;
}

const _query = (albumName: 'Blue', artistName: 'Joni Mitchell');

void main() {
  late _FakeVoices fake;

  setUp(() => fake = _FakeVoices());

  ProviderContainer container() {
    final c = ProviderContainer(overrides: [neoDbVoicesServiceProvider.overrideWithValue(fake)]);
    addTearDown(c.dispose);
    return c;
  }

  Future<NeoDbVoicesUiState> settle(ProviderContainer c) async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    return c.read(neoDbVoicesViewModelProvider(_query));
  }

  test('should_showContentWithReferences_when_albumFound', () async {
    fake.pages[1] = NeoDbVoicePage(voices: [_voice('1'), _voice('2')], page: 1, pages: 2, count: 30);
    fake.references = const [NeoDbReferenceScore(source: 'Discogs', value: 4.31, scale: 5, url: 'https://d')];
    final c = container();
    final sub = c.listen(neoDbVoicesViewModelProvider(_query), (_, _) {});
    addTearDown(sub.close);

    final state = await settle(c) as NeoDbVoicesContent;

    expect(state.voices.map((v) => v.id), ['1', '2']);
    expect(state.count, 30);
    expect(state.endReached, isFalse);
    expect(state.references.single.source, 'Discogs');
  });

  test('should_appendNextPageWithoutRepeats_when_loadMore', () async {
    fake.pages[1] = NeoDbVoicePage(voices: [_voice('1'), _voice('2')], page: 1, pages: 2, count: 3);
    fake.pages[2] = NeoDbVoicePage(voices: [_voice('2'), _voice('3')], page: 2, pages: 2, count: 3);
    final c = container();
    final sub = c.listen(neoDbVoicesViewModelProvider(_query), (_, _) {});
    addTearDown(sub.close);
    await settle(c);

    await c.read(neoDbVoicesViewModelProvider(_query).notifier).loadMore();
    final state = c.read(neoDbVoicesViewModelProvider(_query)) as NeoDbVoicesContent;

    expect(state.voices.map((v) => v.id), ['1', '2', '3']);
    expect(state.endReached, isTrue);
    // At the end, no more pages are asked for.
    await c.read(neoDbVoicesViewModelProvider(_query).notifier).loadMore();
    expect(fake.asked, [1, 2]);
  });

  test('should_endInRetry_when_nextPageFails', () async {
    fake.failPage = true;
    fake.pages[1] = NeoDbVoicePage(voices: [_voice('1')], page: 1, pages: 3, count: 50);
    final c = container();
    final sub = c.listen(neoDbVoicesViewModelProvider(_query), (_, _) {});
    addTearDown(sub.close);
    await settle(c);

    await c.read(neoDbVoicesViewModelProvider(_query).notifier).loadMore();

    final state = c.read(neoDbVoicesViewModelProvider(_query)) as NeoDbVoicesContent;
    expect(state.moreFailed, isTrue);
    expect(state.loadingMore, isFalse);
    expect(state.voices, hasLength(1));
  });

  test('should_offerSearch_when_neoDbHasNoSuchAlbum', () async {
    fake.album = null;
    final c = container();
    final sub = c.listen(neoDbVoicesViewModelProvider(_query), (_, _) {});
    addTearDown(sub.close);
    final state = await settle(c);
    expect(state, isA<NeoDbVoicesNotFound>());
    expect((state as NeoDbVoicesNotFound).searchUrl, contains('Blue'));
  });

  test('should_fail_when_neoDbUnreachable', () async {
    fake.failFind = true;
    final c = container();
    final sub = c.listen(neoDbVoicesViewModelProvider(_query), (_, _) {});
    addTearDown(sub.close);
    expect(await settle(c), isA<NeoDbVoicesFailed>());
  });

  group('page', () {
    Future<List<String>> pump(WidgetTester tester, NeoDbVoicesUiState state) async {
      final opened = <String>[];
      await tester.pumpWidget(
        MaterialApp(
          home: NeoDbVoicesView(
            state: state,
            albumName: 'Blue',
            artistName: 'Joni Mitchell',
            onRetry: () {},
            onLoadMore: () {},
            onOpenUrl: opened.add,
          ),
        ),
      );
      await tester.pumpAndSettle();
      return opened;
    }

    testWidgets('should_showSpreadVoicesAndWaysOut_when_content', (tester) async {
      final opened = await pump(
        tester,
        NeoDbVoicesContent(
          album: _album,
          voices: [
            _voice('1'),
            _voice('2', title: 'A long review', text: 'Its opening.'),
          ],
          count: 2,
          endReached: true,
        ),
      );
      expect(find.text('What others say on NeoDB'), findsOneWidget);
      expect(find.text('8.6'), findsOneWidget);
      expect(find.text('120 ratings'), findsOneWidget);
      expect(find.text('50%'), findsOneWidget);
      expect(find.text('A long review'), findsOneWidget);
      expect(find.text('2 comments in all'), findsOneWidget);

      await tester.tap(find.text('See all on NeoDB'));
      await tester.tap(find.text('Douban'));
      await tester.tap(find.text('RYM'));
      await tester.tap(find.text('Short words.'));
      expect(opened, [
        'https://neodb.social/album/u1',
        'https://music.douban.com/subject/1/',
        'https://rateyourmusic.com/search?searchtype=l&searchterm=Blue+Joni+Mitchell',
        'https://neodb.social/p/1',
      ]);
    });

    testWidgets('should_unfoldNotOpen_when_foldedVoiceTapped', (tester) async {
      final opened = await pump(
        tester,
        NeoDbVoicesContent(
          album: _album,
          voices: [_voice('9', text: 'Hidden words', folded: true)],
          count: 1,
        ),
      );
      expect(find.text('Hidden words'), findsNothing);
      await tester.tap(find.text('Folded by the writer. Tap to read'));
      await tester.pumpAndSettle();
      expect(find.text('Hidden words'), findsOneWidget);
      expect(opened, isEmpty);
    });

    testWidgets('should_showEmptyLine_when_noOneWrote', (tester) async {
      await pump(tester, const NeoDbVoicesContent(album: _album, voices: [], count: 0, endReached: true));
      expect(find.text('No one on NeoDB has written about this album yet.'), findsOneWidget);
    });

    testWidgets('should_offerRetry_when_failed', (tester) async {
      await pump(tester, const NeoDbVoicesFailed());
      expect(find.text("Couldn't reach NeoDB."), findsOneWidget);
      expect(find.text('Retry'), findsOneWidget);
      // No item yet: NeoDB's own button stays folded; the other ways out are there.
      expect(find.text('See all on NeoDB'), findsNothing);
      expect(find.text('AOTY'), findsOneWidget);
    });
  });

  testWidgets('should_openPageOverEverything_when_voicesOpened', (tester) async {
    fake.pages[1] = NeoDbVoicePage(voices: [_voice('1')], page: 1, pages: 1, count: 1);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          neoDbVoicesServiceProvider.overrideWithValue(fake),
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () => openNeoDbVoices(context, albumName: 'Blue', artistName: 'Joni Mitchell'),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text('Short words.'), findsOneWidget);
    // The explicit back pops the page.
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.byType(NeoDbVoicesScreen), findsNothing);
  });
}
