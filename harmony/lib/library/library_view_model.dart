import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';

/// The Library root's state (Android LibraryViewModel, albums tab only for now).
sealed class LibraryUiState {
  const LibraryUiState();
}

final class LibraryLoading extends LibraryUiState {
  const LibraryLoading();
}

/// No account yet: the page offers to connect one.
final class LibraryNoAccount extends LibraryUiState {
  const LibraryNoAccount();
}

final class LibraryContent extends LibraryUiState {
  const LibraryContent({required this.albums, required this.canLoadMore, this.loadingMore = false});
  final List<Album> albums;
  final bool canLoadMore;
  final bool loadingMore;
}

final class LibraryError extends LibraryUiState {
  const LibraryError(this.error);
  final Object error;
}

/// Albums A–Z, paged.
class LibraryViewModel extends Notifier<LibraryUiState> {
  static const pageSize = 60;

  @override
  LibraryUiState build() {
    final source = ref.watch(activeSourceProvider);
    return switch (source) {
      AsyncData(value: null) => const LibraryNoAccount(),
      AsyncData(:final value?) => _startLoading(value),
      AsyncError(:final error) => LibraryError(error),
      _ => const LibraryLoading(),
    };
  }

  LibraryUiState _startLoading(MusicSource source) {
    Future.microtask(() => _loadPage(source, offset: 0));
    return const LibraryLoading();
  }

  Future<void> loadMore() async {
    final current = state;
    final source = ref.read(activeSourceProvider).value;
    if (current is! LibraryContent || !current.canLoadMore || current.loadingMore || source == null) return;
    state = LibraryContent(albums: current.albums, canLoadMore: true, loadingMore: true);
    await _loadPage(source, offset: current.albums.length);
  }

  Future<void> retry() async {
    final source = ref.read(activeSourceProvider).value;
    if (source == null) return;
    state = const LibraryLoading();
    await _loadPage(source, offset: 0);
  }

  Future<void> _loadPage(MusicSource source, {required int offset}) async {
    try {
      final page = await source.library.getAlbumList(AlbumListType.alphabeticalByName, size: pageSize, offset: offset);
      if (!ref.mounted) return;
      final before = state is LibraryContent && offset > 0 ? (state as LibraryContent).albums : const <Album>[];
      state = LibraryContent(albums: [...before, ...page], canLoadMore: page.length == pageSize);
    } catch (error) {
      if (!ref.mounted) return;
      state = LibraryError(error);
    }
  }
}

final libraryViewModelProvider = NotifierProvider<LibraryViewModel, LibraryUiState>(LibraryViewModel.new);
