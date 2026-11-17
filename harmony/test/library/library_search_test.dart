import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/library/library_search.dart';

import '../support/library_fake_source.dart';

void main() {
  late LibraryFakeSource source;
  late ProviderContainer c;

  setUp(() {
    source = LibraryFakeSource(
      searchResults: SearchResults(artists: [LibraryFixtures.artist('a', name: 'Beck')]),
    );
    c = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => source)]);
    c.listen(librarySearchProvider, (_, _) {});
  });
  tearDown(() => c.dispose());

  Future<void> wait(int ms) => Future<void>.delayed(Duration(milliseconds: ms));

  test('should_searchOnceWithLatestQuery_when_typingWithinDebounce', () async {
    await c.read(activeSourceProvider.future);
    final search = c.read(librarySearchProvider.notifier);
    search.search('b');
    await wait(100);
    search.search('be');
    await wait(100);
    search.search('beck');
    await wait(200);
    expect(source.searches, isEmpty);
    await wait(200);

    expect(source.searches, ['beck']);
    final state = c.read(librarySearchProvider);
    expect(state.results!.artists.single.name, 'Beck');
    expect(state.isSearching, isFalse);
  });

  test('should_clearResultsAtOnce_when_queryBlank', () async {
    final search = c.read(librarySearchProvider.notifier);
    search.search('beck');
    await wait(400);
    expect(c.read(librarySearchProvider).results, isNotNull);
    search.search('  ');

    expect(c.read(librarySearchProvider).results, isNull);
  });

  test('should_showErrorThenRetry_when_searchFails', () async {
    source.failures['search'] = const SourceNetworkException('down');
    final search = c.read(librarySearchProvider.notifier);
    search.search('beck');
    await wait(400);
    expect(c.read(librarySearchProvider).error, isA<SourceNetworkException>());

    source.failures.clear();
    await search.retry();
    expect(c.read(librarySearchProvider).results, isNotNull);
    expect(c.read(librarySearchProvider).error, isNull);
  });

  test('should_resetFilterAndQuery_when_cleared', () {
    final search = c.read(librarySearchProvider.notifier);
    search.setFilter(LibrarySearchFilter.songs);
    search.search('x');
    search.clear();
    final state = c.read(librarySearchProvider);
    expect(state.filter, LibrarySearchFilter.all);
    expect(state.query, isEmpty);
  });
}
