import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';

/// Search type chips (Android LibrarySearchFilter). Playlists only with
/// Capability.searchPlaylists, which Subsonic lacks.
enum LibrarySearchFilter { all, artists, albums, songs }

/// Library search state, separate from the Library lists so typing never
/// rebuilds them (Android search* fields of LibraryUiState).
final class LibrarySearchState {
  const LibrarySearchState({
    this.query = '',
    this.results,
    this.isSearching = false,
    this.error,
    this.filter = LibrarySearchFilter.all,
  });

  final String query;

  /// null = nothing searched yet (or cleared).
  final SearchResults? results;
  final bool isSearching;
  final Object? error;
  final LibrarySearchFilter filter;

  static const empty = LibrarySearchState();

  LibrarySearchState copyWith({
    String? query,
    SearchResults? results,
    bool clearResults = false,
    bool? isSearching,
    Object? error,
    bool clearError = false,
    LibrarySearchFilter? filter,
  }) => LibrarySearchState(
    query: query ?? this.query,
    results: clearResults ? null : (results ?? this.results),
    isSearching: isSearching ?? this.isSearching,
    error: clearError ? null : (error ?? this.error),
    filter: filter ?? this.filter,
  );
}

/// search3 after a 300 ms debounce, distinct, latest wins (Android
/// observeSearch, SEARCH_DEBOUNCE_MS).
class LibrarySearchNotifier extends Notifier<LibrarySearchState> {
  static const debounce = Duration(milliseconds: 300);

  Timer? _timer;
  int _token = 0;
  String _lastSearched = '';

  @override
  LibrarySearchState build() {
    ref.watch(activeSourceProvider);
    ref.onDispose(() => _timer?.cancel());
    return LibrarySearchState.empty;
  }

  /// The field changed. A blank query clears the results at once.
  void search(String query) {
    _timer?.cancel();
    final trimmed = query.trim();
    if (trimmed.isEmpty) {
      _token++;
      _lastSearched = '';
      state = state.copyWith(query: query, clearResults: true, isSearching: false, clearError: true);
      return;
    }
    state = state.copyWith(query: query);
    if (trimmed == _lastSearched && state.error == null) return;
    _timer = Timer(debounce, () => _run(trimmed));
  }

  /// The error state's Retry.
  Future<void> retry() => _run(state.query.trim());

  void setFilter(LibrarySearchFilter filter) => state = state.copyWith(filter: filter);

  /// Collapsing the surface clears the query and the filter.
  void clear() {
    _timer?.cancel();
    _token++;
    _lastSearched = '';
    state = LibrarySearchState.empty;
  }

  Future<void> _run(String query) async {
    if (query.isEmpty) return;
    final token = ++_token;
    _lastSearched = query;
    state = state.copyWith(isSearching: true, clearError: true);
    try {
      final source = await ref.read(activeSourceProvider.future);
      if (source == null) throw const SourceNetworkException('no account');
      final results = await source.library.search(query);
      if (!ref.mounted || token != _token) return;
      state = state.copyWith(results: results, isSearching: false);
    } catch (error) {
      if (!ref.mounted || token != _token) return;
      _lastSearched = '';
      state = state.copyWith(isSearching: false, error: error, clearResults: true);
    }
  }
}

final librarySearchProvider = NotifierProvider<LibrarySearchNotifier, LibrarySearchState>(LibrarySearchNotifier.new);
