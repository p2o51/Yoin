import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../platform/storage/stores.dart';
import 'library_sort.dart';

/// The sort each view uses, per profile (Android LibrarySortStore): key
/// `library.sort.v1/<profileId>/<View>` → enum name. An unknown or
/// no-longer-offered value reads as "none chosen".
final class LibrarySortStore {
  LibrarySortStore(this._store);

  final KeyValueStore _store;

  static const _views = [LibraryTab.all, LibraryTab.artists, LibraryTab.albums, LibraryTab.playlists];

  static String _key(String profileId, LibraryTab tab) => 'library.sort.v1/$profileId/${_viewName(tab)}';
  static String _viewName(LibraryTab tab) => switch (tab) {
    LibraryTab.all => 'All',
    LibraryTab.artists => 'Artists',
    LibraryTab.albums => 'Albums',
    LibraryTab.playlists => 'Playlists',
    LibraryTab.songs => 'Songs',
    LibraryTab.favorites => 'Favorites',
  };

  Future<Map<LibraryTab, LibrarySort>> read(String profileId) async {
    final sorts = <LibraryTab, LibrarySort>{};
    for (final tab in _views) {
      try {
        final raw = await _store.read(_key(profileId, tab));
        final sort = LibrarySort.values.where((s) => s.name == raw).firstOrNull;
        if (sort != null && librarySortOptions(tab).contains(sort)) sorts[tab] = sort;
      } catch (_) {
        // Unreadable = none chosen.
      }
    }
    return sorts;
  }

  Future<void> write(String profileId, LibraryTab tab, LibrarySort sort) async {
    try {
      await _store.write(_key(profileId, tab), sort.name);
    } catch (_) {
      // Best effort: the choice still applies for this session.
    }
  }
}

/// Library opens (Android LibraryOpenStore): `<Kind>/<rawId>` → epoch ms,
/// newest 300 per profile + provider, kept on device only. Album and artist
/// visits also reach the activity log from their pages; this store adds the
/// playlists (which have no visit event) and opens from search.
final class LibraryOpenStore {
  LibraryOpenStore(this._store);

  final KeyValueStore _store;
  Future<void> _tail = Future.value();

  static const cap = 300;

  static String _key(String profileId, String provider) => 'library.opens.v1/$profileId/$provider';

  /// Every record, [LibraryItem.recentsKey] → ms.
  Future<Map<String, int>> read(String profileId, String provider) async {
    await _tail;
    return _load(profileId, provider);
  }

  Future<Map<String, int>> _load(String profileId, String provider) async {
    try {
      final raw = await _store.read(_key(profileId, provider));
      if (raw == null) return {};
      return (jsonDecode(raw) as Map).map((k, v) => MapEntry(k as String, (v as num).toInt()));
    } catch (_) {
      return {};
    }
  }

  /// Records an open now; one read-trim-write at a time.
  Future<void> record(String profileId, String provider, String recentsKey, int nowMs) {
    final done = _tail.then((_) async {
      final rows = await _load(profileId, provider);
      rows[recentsKey] = nowMs;
      if (rows.length > cap) {
        final keep = (rows.entries.toList()..sort((a, b) => b.value.compareTo(a.value))).take(cap);
        rows
          ..clear()
          ..addEntries(keep);
      }
      try {
        await _store.write(_key(profileId, provider), jsonEncode(rows));
      } catch (_) {
        // Best effort.
      }
    });
    _tail = done;
    return done;
  }
}

/// Merges the activity log (album/artist/playlist plays and visits) with the
/// Library's own opens into one lastSeen map (newest wins).
Map<String, int> mergeRecents(List<ActivityEvent> activity, Map<String, int> opens) {
  final merged = Map<String, int>.of(opens);
  for (final event in activity) {
    final kind = switch (event.entityType) {
      ActivityEntityType.album => 'album',
      ActivityEntityType.artist => 'artist',
      ActivityEntityType.playlist => 'playlist',
      ActivityEntityType.song => null,
    };
    if (kind == null) continue;
    final key = '$kind:${event.entityId}';
    final old = merged[key];
    if (old == null || event.timestampMs > old) merged[key] = event.timestampMs;
  }
  return merged;
}

final librarySortStoreProvider = Provider<LibrarySortStore>(
  (ref) => LibrarySortStore(ref.watch(keyValueStoreProvider)),
);
final libraryOpenStoreProvider = Provider<LibraryOpenStore>(
  (ref) => LibraryOpenStore(ref.watch(keyValueStoreProvider)),
);

/// The active profile's id (sort and open records are per profile).
final libraryProfileIdProvider = FutureProvider<String?>((ref) async {
  await ref.watch(activeSourceProvider.future);
  return (await ref.watch(profileRepositoryProvider).activeProfile())?.id;
});
