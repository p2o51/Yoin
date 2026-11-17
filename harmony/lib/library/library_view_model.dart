import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../detail/detail_cache.dart';
import '../platform/i18n/name_transliterator.dart';
import '../settings/account_reconnect.dart';
import '../ui/component/error_diagnostics.dart';
import 'library_playlists_by_you.dart';
import 'library_sort.dart';
import 'library_stores.dart';
import 'newest_album_songs_pager.dart';
import 'playlist_deletions.dart';
import 'playlist_writes.dart';

export 'library_sort.dart' show LibraryTab, LibrarySort, LibraryItem, SortedView;

/// The Library root's state (Android LibraryUiState).
sealed class LibraryUiState {
  const LibraryUiState();
}

final class LibraryLoading extends LibraryUiState {
  const LibraryLoading();
}

/// No account yet: the page offers to connect one (Harmony only).
final class LibraryNoAccount extends LibraryUiState {
  const LibraryNoAccount();
}

final class LibraryError extends LibraryUiState {
  LibraryError(this.error, {String? diagnostics}) : diagnostics = diagnostics ?? diagnosticReport(error);
  final Object error;

  /// The scrubbed report Copy details copies, taken when the read failed.
  final String diagnostics;
}

/// Whether the Songs list can grow.
enum SongsMore { none, available, loading, failed }

/// A view whose read failed: the page shows a snackbar naming it, or the
/// failure's own line ([error], Android snackbarOr).
@immutable
final class LibraryNotice {
  const LibraryNotice(this.serial, this.tab, {this.error});
  final int serial;
  final LibraryTab tab;
  final Object? error;
}

/// Per-view payloads: null = not loaded yet, empty = loaded and empty.
final class LibraryContent extends LibraryUiState {
  const LibraryContent({
    this.selected = LibraryTab.all,
    this.availableTabs = const [],
    this.artists,
    this.albums,
    this.playlists,
    this.songs,
    this.favorites,
    this.sorted = SortedLibraryLists.empty,
    this.sorts = const {},
    this.songsMore = SongsMore.none,
    this.isRefreshing = false,
    this.allSettled = false,
    this.notice,
    this.byYouSelected = false,
  });

  final LibraryTab selected;
  final List<LibraryTab> availableTabs;
  final List<Artist>? artists;
  final List<Album>? albums;
  final List<Playlist>? playlists;
  final List<Track>? songs;
  final Starred? favorites;

  /// The sorted views (with fast-scroller sections); only these are drawn.
  final SortedLibraryLists sorted;
  final Map<LibraryTab, LibrarySort> sorts;
  final SongsMore songsMore;
  final bool isRefreshing;

  /// Every list All mixes has finished its first read (All may show "empty").
  final bool allSettled;
  final LibraryNotice? notice;

  /// The user turned Playlists' By You on (kept through chips and refreshes).
  final bool byYouSelected;

  /// Playlists' By You: offered while the loaded playlists are mixed.
  PlaylistsByYou get playlistsByYou =>
      PlaylistsByYou(available: sorted.playlistsByYou != null, selected: byYouSelected);

  /// What the Playlists view lists: under By You only the user's own.
  SortedView? get shownPlaylists => playlistsByYou.filtering ? sorted.playlistsByYou : sorted.playlists;

  LibrarySort sortOf(LibraryTab tab) => sorts[tab] ?? librarySortOptions(tab).first;

  LibraryContent copyWith({
    LibraryTab? selected,
    List<LibraryTab>? availableTabs,
    List<Artist>? artists,
    List<Album>? albums,
    List<Playlist>? playlists,
    List<Track>? songs,
    bool clearSongs = false,
    Starred? favorites,
    SortedLibraryLists? sorted,
    Map<LibraryTab, LibrarySort>? sorts,
    SongsMore? songsMore,
    bool? isRefreshing,
    bool? allSettled,
    LibraryNotice? notice,
    bool? byYouSelected,
  }) => LibraryContent(
    selected: selected ?? this.selected,
    availableTabs: availableTabs ?? this.availableTabs,
    artists: artists ?? this.artists,
    albums: albums ?? this.albums,
    playlists: playlists ?? this.playlists,
    songs: clearSongs ? null : (songs ?? this.songs),
    favorites: favorites ?? this.favorites,
    sorted: sorted ?? this.sorted,
    sorts: sorts ?? this.sorts,
    songsMore: songsMore ?? this.songsMore,
    isRefreshing: isRefreshing ?? this.isRefreshing,
    allSettled: allSettled ?? this.allSettled,
    notice: notice ?? this.notice,
    byYouSelected: byYouSelected ?? this.byYouSelected,
  );
}

/// Sorts on a background isolate once the collections are big enough to
/// cost a frame; small libraries sort inline.
Future<SortedLibraryLists> runLibrarySort(LibraryListInputs inputs) {
  final count = (inputs.artists?.length ?? 0) + (inputs.albums?.length ?? 0) + (inputs.playlists?.length ?? 0);
  if (count < LibraryViewModel.isolateSortThreshold) return Future.value(sortLibrary(inputs));
  return compute(sortLibrary, inputs);
}

/// The Library root (Android LibraryViewModel), Subsonic semantics.
class LibraryViewModel extends Notifier<LibraryUiState> {
  /// getAlbumList ceiling per request, and the read's hard stop.
  static const albumsBatch = 500;
  static const albumsReadCeiling = 100000;
  static const isolateSortThreshold = 1500;
  static const recentsDebounce = Duration(milliseconds: 150);

  int _generation = 0;
  MusicSource? _source;
  String? _profileId;

  // Live album read (L10).
  final _albumIds = <MediaId>{};
  List<Album> _albums = const [];
  final _held = <Album>[];
  int _albumsOffset = 0;
  bool _albumsDone = false;
  bool _albumsReading = false;
  bool _playlistsLoading = false;
  bool _favoritesLoading = false;
  bool _artistsLoading = false;

  LibrarySongsPager? _pager;
  Map<String, int> _lastSeen = const {};
  bool _shown = false;
  Timer? _recentsTimer;
  int _sortToken = 0;
  int _noticeSerial = 0;

  /// What the last failed view read threw (its snackbar's line).
  Object? _lastLoadError;

  /// The last deletion the lists have taken in ([PlaylistDeletionState.lastSeq]).
  int _deletionsSeen = 0;

  @override
  LibraryUiState build() {
    ref.onDispose(() => _recentsTimer?.cancel());
    // A playlist made, renamed or deleted anywhere: the listed playlists are re-read.
    ref.listen(playlistsRevisionProvider, (_, _) => _rereadPlaylists());
    // A playlist deleted on its page leaves Playlists and All at once, and comes back if the service refuses.
    _deletionsSeen = ref.read(playlistDeletionsProvider).lastSeq;
    ref.listen(playlistDeletionsProvider, (_, next) => _onDeletions(next));
    final source = ref.watch(activeSourceProvider);
    _generation++;
    _reset();
    return switch (source) {
      AsyncData(value: null) => _sourceless(null),
      AsyncData(:final value?) => _coldStart(value),
      AsyncError(:final error) => _sourceless(error),
      // A failed read Riverpod is retrying: no source meanwhile either.
      AsyncLoading(:final error?) when !source.hasValue => _sourceless(error),
      _ => const LibraryLoading(),
    };
  }

  /// No source came ([error]: why, when reading the account failed). An
  /// account whose source isn't coming — its credentials couldn't be read —
  /// is said as Home's dot says it ("Account needs reconnecting",
  /// [activeNeedsReconnectProvider]), not as "no account". That answer is
  /// worked out a beat after the source settled, so the page follows it
  /// rather than reading it once.
  LibraryUiState _sourceless(Object? error) {
    final gen = _generation;
    bool? shown;
    LibraryUiState? answer(AsyncValue<bool> needs) {
      if (needs.isLoading || !needs.hasValue) return null;
      final value = needs.requireValue;
      if (value == shown) return null;
      shown = value;
      return switch ((value, error)) {
        (true, _) => LibraryError(
          const AccountNeedsReconnectError(),
          diagnostics: error == null ? null : diagnosticReport(error),
        ),
        (false, null) => const LibraryNoAccount(),
        (false, final Object error) => LibraryError(error),
      };
    }

    ref.listen<AsyncValue<bool>>(activeNeedsReconnectProvider, (_, next) {
      if (!_alive(gen)) return;
      final page = answer(next);
      if (page != null) state = page;
    });
    return answer(ref.read(activeNeedsReconnectProvider)) ?? const LibraryLoading();
  }

  void _reset() {
    _albumIds.clear();
    _albums = const [];
    _held.clear();
    _albumsOffset = 0;
    _albumsDone = false;
    _albumsReading = false;
    _playlistsLoading = false;
    _favoritesLoading = false;
    _artistsLoading = false;
    _pager = null;
    _lastSeen = const {};
  }

  LibraryContent? get _content => state is LibraryContent ? state as LibraryContent : null;

  LibraryUiState _coldStart(MusicSource source) {
    _source = source;
    final gen = _generation;
    Future.microtask(() => _loadInitial(source, gen));
    return const LibraryLoading();
  }

  /// The tabs this source offers, in chip order.
  static List<LibraryTab> availableTabsFor(MusicSource source) {
    final caps = source.capabilities;
    final subsonic = source.provider == MediaId.providerSubsonic;
    return [
      for (final tab in libraryChipOrder)
        if (switch (tab) {
          LibraryTab.playlists => caps.contains(Capability.playlistsRead),
          // Subsonic: songsFromNewestAlbums; Apple Music: the library's own songs.
          LibraryTab.songs => subsonic || caps.contains(Capability.librarySongs),
          LibraryTab.favorites => subsonic && caps.contains(Capability.favorites),
          _ => true,
        })
          tab,
    ];
  }

  Future<void> _loadInitial(MusicSource source, int gen) async {
    try {
      final profileId = await _readProfileId();
      final sorts = profileId == null ? const <LibraryTab, LibrarySort>{} : await _readSorts(profileId);
      final artists = await _readArtists(source);
      if (!_alive(gen)) return;
      _profileId = profileId;
      state = LibraryContent(availableTabs: availableTabsFor(source), artists: artists, sorts: sorts);
      await _loadRecents(gen);
      await _resort(gen);
      if (_shown) await ensureSelectedTabLoaded();
    } catch (error) {
      if (!_alive(gen)) return;
      state = LibraryError(error);
    }
  }

  Future<String?> _readProfileId() async {
    try {
      return (await ref.read(profileRepositoryProvider).activeProfile())?.id;
    } catch (_) {
      return null;
    }
  }

  Future<Map<LibraryTab, LibrarySort>> _readSorts(String profileId) async {
    try {
      return await ref.read(librarySortStoreProvider).read(profileId);
    } catch (_) {
      return const {};
    }
  }

  static Future<List<Artist>> _readArtists(MusicSource source) async => [
    for (final group in await source.library.getArtists()) ...group.artists,
  ];

  bool _alive(int gen) => ref.mounted && gen == _generation;

  // ------------------------------------------------------------ visibility

  /// Library came on screen: re-read Recents (debounced), let held albums
  /// join, and load what the selected view needs.
  void onShown() {
    _shown = true;
    _recentsTimer?.cancel();
    final gen = _generation;
    _recentsTimer = Timer(recentsDebounce, () async {
      if (!_alive(gen) || _content == null) return;
      await _loadRecents(gen);
      _releaseHeld();
      await _resort(gen);
    });
    _releaseHeld();
    unawaited(ensureSelectedTabLoaded());
  }

  void onHidden() => _shown = false;

  /// Reads the selected view's payload if it is still null (Android
  /// ensureSelectedTabLoaded). All needs albums and playlists.
  Future<void> ensureSelectedTabLoaded() async {
    final content = _content;
    if (content == null) return;
    await _ensureLoaded(content.selected);
  }

  Future<bool> _ensureLoaded(LibraryTab tab) async {
    final content = _content;
    final source = _source;
    if (content == null || source == null) return true;
    switch (tab) {
      case LibraryTab.all:
        final results = await Future.wait([
          if (!_albumsDone) _readAlbums(),
          if (content.playlists == null && content.availableTabs.contains(LibraryTab.playlists)) _loadPlaylists(),
        ]);
        _settleAll();
        return results.every((ok) => ok);
      case LibraryTab.albums:
        return _readAlbums();
      case LibraryTab.artists:
        return content.artists != null || await _loadArtists();
      case LibraryTab.playlists:
        return content.playlists != null || await _loadPlaylists();
      case LibraryTab.favorites:
        return content.favorites != null || await _loadFavorites();
      case LibraryTab.songs:
        return content.songs != null || await _loadFirstSongs();
    }
  }

  void _settleAll() {
    final content = _content;
    if (content == null) return;
    final playlistsDone = content.playlists != null || !content.availableTabs.contains(LibraryTab.playlists);
    final settled = content.artists != null && (_albumsDone || content.albums != null) && playlistsDone;
    if (settled != content.allSettled) state = content.copyWith(allSettled: settled);
  }

  // ------------------------------------------------------------- selection

  /// Playlists' By You on or off (Android selectPlaylistsByYou); it can only
  /// turn on while the playlists are mixed.
  void selectPlaylistsByYou(bool selected) {
    final content = _content;
    if (content == null) return;
    if (selected && !content.playlistsByYou.available) return;
    state = content.copyWith(byYouSelected: selected);
  }

  /// Turns [tab] on (or, for the tab already on, back to All); a fetch that
  /// fails steps back to the previous view and names it in a snackbar.
  Future<void> selectTab(LibraryTab tab) async {
    final content = _content;
    if (content == null) return;
    final target = tab == content.selected ? LibraryTab.all : tab;
    final previous = content.selected;
    state = content.copyWith(selected: target);
    _releaseHeld();
    final gen = _generation;
    _lastLoadError = null;
    final ok = await _ensureLoaded(target);
    if (!_alive(gen)) return;
    final after = _content;
    if (!ok && after != null && after.selected == target && target != LibraryTab.all) {
      state = after.copyWith(selected: previous, notice: LibraryNotice(++_noticeSerial, target, error: _lastLoadError));
    }
  }

  Future<void> selectSort(LibraryTab tab, LibrarySort sort) async {
    final content = _content;
    if (content == null || content.sortOf(tab) == sort) return;
    state = content.copyWith(sorts: {...content.sorts, tab: sort});
    if (_profileId case final id?) unawaited(ref.read(librarySortStoreProvider).write(id, tab, sort));
    _releaseHeld();
    await _resort(_generation);
  }

  /// Records an open from Library (Recents, L7). The list does not re-sort
  /// now: Recents is re-read the next time Library comes into view.
  Future<void> recordOpened(LibraryItem item) async {
    final profileId = _profileId;
    final source = _source;
    if (profileId == null || source == null) return;
    await ref
        .read(libraryOpenStoreProvider)
        .record(profileId, source.provider, item.recentsKey, DateTime.now().millisecondsSinceEpoch);
  }

  /// The error page's Retry: reload everything.
  void retry() => ref.invalidateSelf();

  // ---------------------------------------------------------------- reads

  Future<bool> _loadArtists() async {
    final source = _source;
    if (source == null || _artistsLoading) return true;
    _artistsLoading = true;
    final gen = _generation;
    try {
      final artists = await _readArtists(source);
      if (!_alive(gen)) return true;
      state = _content!.copyWith(artists: artists);
      await _resort(gen);
      return true;
    } catch (e) {
      _lastLoadError = e;
      return false;
    } finally {
      _artistsLoading = false;
    }
  }

  Future<bool> _loadPlaylists() async {
    final source = _source;
    if (source == null || _playlistsLoading) return true;
    _playlistsLoading = true;
    final gen = _generation;
    try {
      final playlists = await source.library.getPlaylists();
      if (!_alive(gen)) return true;
      state = _content!.copyWith(playlists: playlists);
      _settleAll();
      await _resort(gen);
      return true;
    } catch (e) {
      _lastLoadError = e;
      return false;
    } finally {
      _playlistsLoading = false;
    }
  }

  /// After a playlist write: re-reads the playlists if they were listed.
  Future<void> _rereadPlaylists() async {
    final source = _source;
    if (source == null || _content?.playlists == null) return;
    final gen = _generation;
    try {
      final playlists = await source.library.getPlaylists();
      if (!_alive(gen)) return;
      state = _content!.copyWith(playlists: playlists);
      await _resort(gen);
    } catch (_) {
      // The old list stays; pull to refresh retries.
    }
  }

  /// Deletions moved on (Android observePlaylistDeletions): the ones the
  /// service took leave the listed playlists for good, in the same pass as
  /// they leave [PlaylistDeletionState.pending], so they never show again
  /// before the read that follows; the lists are sorted again either way (a
  /// refused one comes back where it was).
  void _onDeletions(PlaylistDeletionState next) {
    final account = ref.read(playlistAccountProvider).value;
    final gone = {
      for (final key in next.doneAfter(_deletionsSeen))
        if (key.account == account) key.id,
    };
    _deletionsSeen = next.lastSeq;
    final content = _content;
    if (content?.playlists == null) return;
    if (gone.isNotEmpty) {
      state = content!.copyWith(
        playlists: [
          for (final p in content.playlists!)
            if (!gone.contains(p.id)) p,
        ],
      );
    }
    unawaited(_resort(_generation));
  }

  /// [playlists] as the lists show them ([PlaylistDeletionState.visible]).
  List<Playlist>? _visiblePlaylists(List<Playlist>? playlists) => playlists == null
      ? null
      : ref.read(playlistDeletionsProvider).visible(playlists, ref.read(playlistAccountProvider).value);

  Future<bool> _loadFavorites() async {
    final source = _source;
    if (source == null || _favoritesLoading) return true;
    _favoritesLoading = true;
    final gen = _generation;
    try {
      final starred = await source.library.getStarred();
      if (!_alive(gen)) return true;
      state = _content!.copyWith(favorites: starred);
      return true;
    } catch (e) {
      _lastLoadError = e;
      return false;
    } finally {
      _favoritesLoading = false;
    }
  }

  /// The library's own songs where the service lists them (Apple Music), else
  /// the newest albums' songs (Subsonic's songsFromNewestAlbums).
  LibrarySongsPager _newPager(MusicSource source) {
    final collection = source.libraryCollection;
    if (source.capabilities.contains(Capability.librarySongs) && collection != null) {
      final transliterator = ref.read(nameTransliteratorProvider);
      return ServiceLibrarySongsPager(collection, latinNames: (names) => latinKeyMap(transliterator, names));
    }
    return NewestAlbumSongsPager(source.library, openAlbum: ref.read(detailCacheProvider).album);
  }

  Future<bool> _loadFirstSongs() async {
    final source = _source;
    if (source == null) return true;
    final pager = _pager ??= _newPager(source);
    final gen = _generation;
    try {
      final songs = await pager.next();
      if (!_alive(gen) || !identical(pager, _pager)) return true;
      state = _content!.copyWith(songs: songs, songsMore: pager.ended ? SongsMore.none : SongsMore.available);
      return true;
    } catch (e) {
      _lastLoadError = e;
      return false;
    }
  }

  /// The next Songs page (near-end trigger, or the foot's retry).
  Future<void> loadMoreSongs() async {
    final content = _content;
    final pager = _pager;
    if (content == null || pager == null) return;
    if (content.songsMore != SongsMore.available && content.songsMore != SongsMore.failed) return;
    state = content.copyWith(songsMore: SongsMore.loading);
    final gen = _generation;
    try {
      final more = await pager.next();
      if (!_alive(gen) || !identical(pager, _pager)) return;
      final now = _content!;
      state = now.copyWith(
        songs: [...?now.songs, ...more],
        songsMore: more.isEmpty || pager.ended ? SongsMore.none : SongsMore.available,
      );
    } catch (_) {
      if (!_alive(gen)) return;
      state = _content!.copyWith(songsMore: SongsMore.failed);
    }
  }

  /// Reads the album collection in batches from where it stopped (L10).
  /// Returns false when the read failed before any album was known.
  Future<bool> _readAlbums() async {
    final source = _source;
    if (source == null || _albumsDone) return true;
    if (_albumsReading) return true;
    _albumsReading = true;
    final gen = _generation;
    try {
      while (true) {
        final batch = await source.library.getAlbumList(AlbumListType.newest, size: albumsBatch, offset: _albumsOffset);
        if (!_alive(gen)) return true;
        final fresh = [
          for (final a in batch)
            if (_albumIds.add(a.id)) a,
        ];
        _albumsOffset += batch.length;
        final first = _content!.albums == null;
        if (first || !_holdsBatches) {
          _albums = [..._albums, ...fresh];
          state = _content!.copyWith(albums: _albums);
          await _resort(gen);
        } else {
          _held.addAll(fresh);
        }
        if (batch.length < albumsBatch || fresh.isEmpty || _albumsOffset >= albumsReadCeiling) {
          _albumsDone = true;
          break;
        }
      }
      _releaseHeld();
      _settleAll();
      return true;
    } catch (e) {
      if (!_alive(gen)) return true;
      _lastLoadError = e;
      // Stops where it is; the next Library visit reads on from this offset.
      _settleAll();
      return _content?.albums != null;
    } finally {
      _albumsReading = false;
    }
  }

  /// While All/Albums shows a sort other than Recently added, a joining
  /// batch would land among rows on screen: it waits.
  bool get _holdsBatches {
    final content = _content;
    if (content == null || !_shown) return false;
    final tab = content.selected;
    if (tab != LibraryTab.all && tab != LibraryTab.albums) return false;
    return content.sortOf(tab) != LibrarySort.recentlyAdded;
  }

  void _releaseHeld() {
    if (_held.isEmpty || _content == null) return;
    _albums = [..._albums, ..._held];
    _held.clear();
    state = _content!.copyWith(albums: _albums);
    unawaited(_resort(_generation));
  }

  // ------------------------------------------------------------- refresh

  /// Pull to refresh: re-reads exactly what the view lists, rows replaced in
  /// place. One pull at a time; a failed list keeps its rows and is named.
  Future<void> refresh() async {
    final content = _content;
    final source = _source;
    if (content == null || source == null || content.isRefreshing) return;
    state = content.copyWith(isRefreshing: true);
    final gen = _generation;
    final view = content.selected;
    final failed = <LibraryTab>[];
    final errors = <LibraryTab, Object>{};
    Future<void> guard(LibraryTab tab, Future<void> Function() read) async {
      try {
        await read();
      } catch (e) {
        failed.add(tab);
        errors[tab] = e;
      }
    }

    Future<void> artists() async {
      final list = await _readArtists(source);
      if (_alive(gen)) state = _content!.copyWith(artists: list);
    }

    Future<void> playlists() async {
      final list = await source.library.getPlaylists();
      if (_alive(gen)) state = _content!.copyWith(playlists: list);
    }

    switch (view) {
      case LibraryTab.all:
        await Future.wait([
          guard(LibraryTab.artists, artists),
          guard(LibraryTab.albums, () => _rereadAlbums(source, gen)),
          if (content.availableTabs.contains(LibraryTab.playlists)) guard(LibraryTab.playlists, playlists),
        ]);
      case LibraryTab.albums:
        await guard(LibraryTab.albums, () => _rereadAlbums(source, gen));
      case LibraryTab.artists:
        await guard(LibraryTab.artists, artists);
      case LibraryTab.playlists:
        await guard(LibraryTab.playlists, playlists);
      case LibraryTab.favorites:
        await guard(LibraryTab.favorites, () async {
          final starred = await source.library.getStarred();
          if (_alive(gen)) state = _content!.copyWith(favorites: starred);
        });
      case LibraryTab.songs:
        await guard(LibraryTab.songs, () async {
          final pager = _newPager(source);
          final songs = await pager.next();
          if (!_alive(gen)) return;
          _pager = pager;
          state = _content!.copyWith(songs: songs, songsMore: pager.ended ? SongsMore.none : SongsMore.available);
        });
    }
    if (!_alive(gen)) return;
    await _resort(gen);
    if (!_alive(gen)) return;
    final now = _content!;
    state = now.copyWith(
      isRefreshing: false,
      notice: failed.isEmpty
          ? null
          : LibraryNotice(
              ++_noticeSerial,
              view == LibraryTab.all ? failed.first : view,
              error: errors[view == LibraryTab.all ? failed.first : view],
            ),
    );
  }

  /// The whole collection again from offset 0; albums gone from the server
  /// leave only when the whole read is in.
  Future<void> _rereadAlbums(MusicSource source, int gen) async {
    final fresh = <Album>[];
    final ids = <MediaId>{};
    var offset = 0;
    while (true) {
      final batch = await source.library.getAlbumList(AlbumListType.newest, size: albumsBatch, offset: offset);
      if (!_alive(gen)) return;
      final added = [
        for (final a in batch)
          if (ids.add(a.id)) a,
      ];
      fresh.addAll(added);
      offset += batch.length;
      if (batch.length < albumsBatch || added.isEmpty || offset >= albumsReadCeiling) break;
    }
    _albumIds
      ..clear()
      ..addAll(ids);
    _albums = fresh;
    _held.clear();
    _albumsOffset = offset;
    _albumsDone = true;
    state = _content!.copyWith(albums: fresh);
  }

  // --------------------------------------------------------------- sorting

  Future<void> _loadRecents(int gen) async {
    try {
      final log = await ref.read(activityLogProvider.future);
      final List<ActivityEvent> activity = log == null ? const [] : await log.recent();
      final source = _source;
      final opens = _profileId == null || source == null
          ? const <String, int>{}
          : await ref.read(libraryOpenStoreProvider).read(_profileId!, source.provider);
      if (!_alive(gen)) return;
      _lastSeen = mergeRecents(activity, opens);
    } catch (_) {
      // Recents falls back to Recently added.
    }
  }

  Future<void> _resort(int gen) async {
    final content = _content;
    if (content == null) return;
    final token = ++_sortToken;
    // Han names get their pinyin first (ArkTS yoin/i18n, cached), as Android's ICU index does.
    final latinNamesFetch = latinKeyMap(ref.read(nameTransliteratorProvider), [
      ...?content.artists?.map((a) => a.name),
      for (final a in content.albums ?? const <Album>[]) ...[a.name, a.artist],
      for (final p in content.playlists ?? const <Playlist>[]) ...[p.name, p.owner],
    ]);
    final latinNames = latinNamesFetch == null ? const <String, String>{} : await latinNamesFetch;
    if (!_alive(gen) || token != _sortToken) return;
    final inputs = LibraryListInputs(
      artists: content.artists,
      albums: content.albums,
      playlists: _visiblePlaylists(content.playlists),
      sorts: content.sorts,
      lastSeenMs: _lastSeen,
      latinNames: latinNames,
      nowMs: DateTime.now().millisecondsSinceEpoch,
    );
    final sorted = await runLibrarySort(inputs);
    if (!_alive(gen) || token != _sortToken) return;
    final now = _content;
    if (now != null) state = now.copyWith(sorted: sorted);
  }
}

final libraryViewModelProvider = NotifierProvider<LibraryViewModel, LibraryUiState>(LibraryViewModel.new);
