import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';

/// Search type chips (Android LibrarySearchFilter). Playlists only with
/// Capability.searchPlaylists, which Subsonic lacks.
enum LibrarySearchFilter { all, artists, albums, songs, playlists }

/// Where a search looks (Android LibrarySearchScope): the user's library, or
/// the service's whole catalogue (Apple Music) where it has one.
enum LibrarySearchScope { library, catalog }

/// The type chips the search offers in [scope] (Android searchFiltersFor).
/// Capability alone decides, never whether the current results hold that
/// type, so the row doesn't reshuffle as results land. Playlists need
/// Capability.searchPlaylists, and inside the library the library must hold
/// playlists (Capability.playlistsRead).
List<LibrarySearchFilter> searchFiltersFor(Set<Capability> capabilities, LibrarySearchScope scope) => [
  for (final f in LibrarySearchFilter.values)
    if (f != LibrarySearchFilter.playlists ||
        (capabilities.contains(Capability.searchPlaylists) &&
            (scope != LibrarySearchScope.library || capabilities.contains(Capability.playlistsRead))))
      f,
];

/// What a song row says after Add to library (Android LibraryActionFeedback).
enum LibraryAddFeedback { added, pending, failed }

/// Library search state, separate from the Library lists so typing never
/// rebuilds them (Android search* fields of LibraryUiState).
final class LibrarySearchState {
  const LibrarySearchState({
    this.query = '',
    this.results,
    this.isSearching = false,
    this.error,
    this.filter = LibrarySearchFilter.all,
    this.scope = LibrarySearchScope.library,
    this.capabilities = const {},
    this.membership = const {},
    this.adding = const {},
    this.feedback = const {},
    this.addFailures = const {},
  });

  final String query;

  /// null = nothing searched yet (or cleared).
  final SearchResults? results;
  final bool isSearching;
  final Object? error;
  final LibrarySearchFilter filter;
  final LibrarySearchScope scope;

  /// The account in use's capabilities: which scopes, chips and buttons show.
  final Set<Capability> capabilities;

  /// Catalogue songs' place in the library, as far as it has been asked.
  final Map<MediaId, LibraryMembership> membership;

  /// Songs whose Add to library is on its way.
  final Set<MediaId> adding;

  /// The line under a song after its Add to library.
  final Map<MediaId, LibraryAddFeedback> feedback;

  /// What a failed Add to library threw: its line says why (Android toUserMessage).
  final Map<MediaId, Object> addFailures;

  static const empty = LibrarySearchState();

  /// The catalogue scope's chip is offered (Android canSearchAppleMusicCatalog).
  bool get canSearchCatalog => capabilities.contains(Capability.catalogSearch);

  /// Catalogue song rows carry Add to library (Android canAddToLibrary).
  bool get canAddToLibrary => capabilities.contains(Capability.libraryAdd);

  List<LibrarySearchFilter> get filters => searchFiltersFor(capabilities, scope);

  LibrarySearchState copyWith({
    String? query,
    SearchResults? results,
    bool clearResults = false,
    bool? isSearching,
    Object? error,
    bool clearError = false,
    LibrarySearchFilter? filter,
    LibrarySearchScope? scope,
    Set<Capability>? capabilities,
    Map<MediaId, LibraryMembership>? membership,
    Set<MediaId>? adding,
    Map<MediaId, LibraryAddFeedback>? feedback,
    Map<MediaId, Object>? addFailures,
  }) => LibrarySearchState(
    query: query ?? this.query,
    results: clearResults ? null : (results ?? this.results),
    isSearching: isSearching ?? this.isSearching,
    error: clearError ? null : (error ?? this.error),
    filter: filter ?? this.filter,
    scope: scope ?? this.scope,
    capabilities: capabilities ?? this.capabilities,
    membership: membership ?? this.membership,
    adding: adding ?? this.adding,
    feedback: feedback ?? this.feedback,
    addFailures: addFailures ?? this.addFailures,
  );
}

/// search3 after a 300 ms debounce, distinct, latest wins (Android
/// observeSearch, SEARCH_DEBOUNCE_MS).
class LibrarySearchNotifier extends Notifier<LibrarySearchState> {
  static const debounce = Duration(milliseconds: 300);

  /// Catalogue songs whose library membership is asked at once (Android Semaphore(3)).
  static const membershipReads = 3;

  /// Songs whose membership is asked per search: the All list's songs.
  static const membershipRows = 40;

  Timer? _timer;
  int _token = 0;
  String _lastSearched = '';
  int _generation = 0;

  @override
  LibrarySearchState build() {
    final source = ref.watch(activeSourceProvider).value;
    _generation++;
    ref.onDispose(() => _timer?.cancel());
    return LibrarySearchState(capabilities: source?.capabilities ?? const {});
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

  /// Library ⇄ the catalogue (Android selectSearchScope): the results go, the
  /// query is searched again where asked, and a type chip the new scope
  /// doesn't offer falls back to All.
  void setScope(LibrarySearchScope scope) {
    if (scope == state.scope) return;
    if (scope == LibrarySearchScope.catalog && !state.canSearchCatalog) return;
    _timer?.cancel();
    _token++;
    _lastSearched = '';
    final next = state.copyWith(scope: scope, clearResults: true, isSearching: false, clearError: true);
    state = next.copyWith(filter: next.filters.contains(next.filter) ? next.filter : LibrarySearchFilter.all);
    final query = state.query.trim();
    if (query.isNotEmpty) unawaited(_run(query));
  }

  /// Collapsing the surface clears the query, the filter and the scope.
  void clear() {
    _timer?.cancel();
    _token++;
    _lastSearched = '';
    state = LibrarySearchState(capabilities: state.capabilities);
  }

  Future<void> _run(String query) async {
    if (query.isEmpty) return;
    final token = ++_token;
    _lastSearched = query;
    final scope = state.scope;
    state = state.copyWith(isSearching: true, clearError: true);
    try {
      final source = await ref.read(activeSourceProvider.future);
      // No account: Search failed, not a connectivity line.
      if (source == null) throw StateError('No account');
      final collection = source.libraryCollection;
      // A service with a catalogue searches it only when asked; its library has a search of its own.
      final results =
          scope == LibrarySearchScope.library &&
              source.capabilities.contains(Capability.catalogSearch) &&
              collection != null
          ? await collection.searchLibrary(query)
          : await source.library.search(query);
      if (!ref.mounted || token != _token) return;
      state = state.copyWith(results: results, isSearching: false);
      if (scope == LibrarySearchScope.catalog) unawaited(_readMembership(source, results.tracks, token));
    } catch (error) {
      if (!ref.mounted || token != _token) return;
      _lastSearched = '';
      state = state.copyWith(isSearching: false, error: error, clearResults: true);
    }
  }

  /// Asks the library about the shown catalogue songs, a few at a time
  /// (Android observeSearchLibraryMembership). An answer that fails says nothing.
  Future<void> _readMembership(MusicSource source, List<Track> tracks, int token) async {
    final adds = source.libraryAdds;
    if (adds == null) return;
    final ids = {
      for (final t in tracks.take(membershipRows))
        if (!state.membership.containsKey(t.id)) t.id,
    }.toList();
    var next = 0;
    Future<void> worker() async {
      while (next < ids.length) {
        final id = ids[next++];
        LibraryMembership answer;
        try {
          answer = await adds.libraryMembership(id);
        } catch (_) {
          continue;
        }
        if (!ref.mounted || token != _token) return;
        state = state.copyWith(membership: {...state.membership, id: answer});
      }
    }

    await Future.wait([for (var i = 0; i < membershipReads; i++) worker()]);
  }

  /// Add to library on a catalogue song (Android addSongToLibrary): the row
  /// works while it runs, then says Added, Waiting for Apple Music (a later
  /// tap asks again without posting twice), or that it couldn't. Once added,
  /// it stays added.
  Future<void> addToLibrary(Track track) async {
    final id = track.id;
    if (!state.canAddToLibrary || state.adding.contains(id)) return;
    if (state.membership[id] == LibraryMembership.added) return;
    final generation = _generation;
    final source = await ref.read(activeSourceProvider.future);
    final adds = source?.libraryAdds;
    if (adds == null || !ref.mounted || generation != _generation) return;
    state = state.copyWith(adding: {...state.adding, id}, feedback: {...state.feedback}..remove(id));
    LibraryMembership? result;
    Object? failure;
    try {
      result = await adds.addToLibrary(id);
    } catch (e) {
      result = null;
      failure = e;
    }
    // Another account in use meanwhile: its search starts over, nothing to say here.
    if (!ref.mounted || generation != _generation) return;
    state = state.copyWith(
      adding: {...state.adding}..remove(id),
      membership: result == null ? state.membership : {...state.membership, id: result},
      addFailures: failure == null ? ({...state.addFailures}..remove(id)) : {...state.addFailures, id: failure},
      feedback: {
        ...state.feedback,
        id: switch (result) {
          LibraryMembership.added => LibraryAddFeedback.added,
          LibraryMembership.pending => LibraryAddFeedback.pending,
          _ => LibraryAddFeedback.failed,
        },
      },
    );
  }
}

final librarySearchProvider = NotifierProvider<LibrarySearchNotifier, LibrarySearchState>(LibrarySearchNotifier.new);
