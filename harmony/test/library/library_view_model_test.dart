import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/library/library_view_model.dart';

import '../support/fake_music_source.dart';

ProviderContainer _container(MusicSource? source) => ProviderContainer(
      overrides: [activeSourceProvider.overrideWith((ref) async => source)],
    );

Future<LibraryUiState> _settled(ProviderContainer c) async {
  c.listen(libraryViewModelProvider, (_, _) {});
  for (var i = 0; i < 20 && c.read(libraryViewModelProvider) is LibraryLoading; i++) {
    await Future<void>.delayed(Duration.zero);
  }
  return c.read(libraryViewModelProvider);
}

void main() {
  test('should_offerConnect_when_noAccount', () async {
    final c = _container(null);
    addTearDown(c.dispose);
    expect(await _settled(c), isA<LibraryNoAccount>());
  });

  test('should_loadFirstPageAlphabetically_when_accountActive', () async {
    final source = FakeMusicSource(albums: FakeMusicSource.numberedAlbums(70));
    final c = _container(source);
    addTearDown(c.dispose);

    final state = await _settled(c);

    expect(state, isA<LibraryContent>().having((s) => s.albums.length, 'albums', LibraryViewModel.pageSize));
    expect((state as LibraryContent).canLoadMore, isTrue);
    expect(source.albumListCalls.single.type, AlbumListType.alphabeticalByName);
  });

  test('should_appendNextPage_when_loadMore', () async {
    final source = FakeMusicSource(albums: FakeMusicSource.numberedAlbums(70));
    final c = _container(source);
    addTearDown(c.dispose);
    await _settled(c);

    await c.read(libraryViewModelProvider.notifier).loadMore();

    final state = c.read(libraryViewModelProvider) as LibraryContent;
    expect(state.albums.length, 70);
    expect(state.canLoadMore, isFalse);
    expect(source.albumListCalls.last.offset, LibraryViewModel.pageSize);
  });

  test('should_showError_when_sourceThrows', () async {
    final source = FakeMusicSource(failWith: const SourceNetworkException('offline'));
    final c = _container(source);
    addTearDown(c.dispose);
    expect(await _settled(c), isA<LibraryError>());
  });
}
