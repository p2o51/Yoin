import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import 'detail_cache.dart';

/// Artist page state (Android ArtistDetailUiState).
sealed class ArtistDetailState {
  const ArtistDetailState();
}

final class ArtistDetailLoading extends ArtistDetailState {
  const ArtistDetailLoading();
}

final class ArtistDetailError extends ArtistDetailState {
  const ArtistDetailError({required this.notFound});
  final bool notFound;
}

final class ArtistDetailContent extends ArtistDetailState {
  const ArtistDetailContent({required this.artist, required this.releases, this.filter, this.showAll = false});

  final ArtistDetail artist;

  /// Newest first, undated last.
  final List<Album> releases;

  /// The discography filter chip (null = All).
  final ReleaseKind? filter;

  /// "Show all" pressed.
  final bool showAll;

  List<Album> get filteredReleases => filter == null
      ? releases
      : [
          for (final r in releases)
            if (releaseKindOf(r) == filter) r,
        ];

  ArtistDetailContent copyWith({ReleaseKind? filter, bool clearFilter = false, bool? showAll}) => ArtistDetailContent(
    artist: artist,
    releases: releases,
    filter: clearFilter ? null : (filter ?? this.filter),
    showAll: showAll ?? this.showAll,
  );

  /// The portrait: the artist's own image, else the first release's cover.
  CoverRef? get heroCover => artist.coverArt ?? releases.firstOrNull?.coverArt;

  /// "2016 – 2025", a single year, or null.
  String? get activeSpan => artistActiveSpan(releases);

  /// The distinct release kinds present (filter chips need two or more).
  Set<ReleaseKind> get kinds => {for (final r in releases) releaseKindOf(r)};
}

/// The discography filter buckets.
enum ReleaseKind { album, singlesAndEps, compilation }

ReleaseKind releaseKindOf(Album album) => switch (album.releaseType) {
  ReleaseType.single || ReleaseType.ep => ReleaseKind.singlesAndEps,
  ReleaseType.compilation => ReleaseKind.compilation,
  ReleaseType.album || null => ReleaseKind.album,
};

/// Releases newest first (Android newestReleasesFirst): a list that came
/// oldest-first (Navidrome) is reversed, then a stable sort by year
/// descending puts undated releases last.
List<Album> newestReleasesFirst(List<Album> albums) {
  final dated = albums.where((a) => a.year != null).toList();
  var list = albums;
  if (dated.length >= 2 && dated.first.year! < dated.last.year!) list = albums.reversed.toList();
  final indexed = list.indexed.toList()
    ..sort((a, b) {
      final ya = a.$2.year, yb = b.$2.year;
      if (ya == null || yb == null) {
        if (ya != yb) return ya == null ? 1 : -1;
        return a.$1.compareTo(b.$1);
      }
      final byYear = yb.compareTo(ya);
      return byYear != 0 ? byYear : a.$1.compareTo(b.$1);
    });
  return [for (final (_, a) in indexed) a];
}

/// "min – max" (en dash, spaces) or one year; null without years.
String? artistActiveSpan(List<Album> releases) {
  final years = releases.map((r) => r.year).whereType<int>().toList();
  if (years.isEmpty) return null;
  final min = years.reduce((a, b) => a < b ? a : b);
  final max = years.reduce((a, b) => a > b ? a : b);
  return min == max ? '$min' : '$min – $max';
}

/// Loads the artist and prefetches its first six releases.
class ArtistDetailNotifier extends Notifier<ArtistDetailState> {
  ArtistDetailNotifier(this.artistId);

  final MediaId artistId;
  int _generation = 0;

  static const prefetchReleases = 6;

  @override
  ArtistDetailState build() {
    _load(ref.watch(detailCacheProvider));
    return const ArtistDetailLoading();
  }

  /// A filter chip: the one on, or null for All.
  void setFilter(ReleaseKind? filter) {
    final s = state;
    if (s is ArtistDetailContent) state = s.copyWith(filter: filter, clearFilter: filter == null);
  }

  void toggleShowAll() {
    final s = state;
    if (s is ArtistDetailContent) state = s.copyWith(showAll: !s.showAll);
  }

  Future<void> retry() async {
    state = const ArtistDetailLoading();
    await _load(ref.read(detailCacheProvider));
  }

  Future<void> _load(DetailCache cache) async {
    final gen = ++_generation;
    try {
      final artist = await cache.artist(artistId);
      if (!ref.mounted || gen != _generation) return;
      if (artist == null) {
        state = const ArtistDetailError(notFound: true);
        return;
      }
      final releases = newestReleasesFirst(artist.albums);
      state = ArtistDetailContent(artist: artist, releases: releases);
      for (final r in releases.take(prefetchReleases)) {
        cache.prefetchAlbum(r.id);
      }
      _recordVisit(artist);
    } catch (_) {
      if (!ref.mounted || gen != _generation) return;
      state = const ArtistDetailError(notFound: false);
    }
  }

  /// ARTIST / VISITED (home spec item 18).
  void _recordVisit(ArtistDetail artist) {
    unawaited(() async {
      try {
        final log = await ref.read(activityLogProvider.future);
        await log?.record(
          ActivityEvent(
            entityType: ActivityEntityType.artist,
            actionType: ActivityActionType.visited,
            entityId: artist.id.rawId,
            title: artist.name,
            subtitle: 'Artist',
            coverArtKey: artist.coverArt?.storageKey,
            artistId: artist.id.rawId,
            timestampMs: DateTime.now().millisecondsSinceEpoch,
          ),
        );
      } catch (_) {
        // Logging never affects the page.
      }
    }());
  }
}

final artistDetailProvider = NotifierProvider.autoDispose.family<ArtistDetailNotifier, ArtistDetailState, MediaId>(
  ArtistDetailNotifier.new,
);
